package com.newoether.agora.api.ollama

import com.newoether.agora.api.*

import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.util.Base64FileRegistry
import com.newoether.agora.api.util.buildToolCallId
import com.newoether.agora.api.util.RequestFormatException
import com.newoether.agora.api.util.ProviderRetryPolicy
import com.newoether.agora.api.util.StreamTermination
import com.newoether.agora.api.util.asRetryableResponseBodyReadError
import com.newoether.agora.api.util.asRetryableTransportError
import com.newoether.agora.api.util.carriesModelOutput
import com.newoether.agora.api.util.requireValidSerializedRequest
import com.newoether.agora.api.util.safeWireToolCallId
import com.newoether.agora.api.util.safeWireToolName
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingProviderFamily
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.api.util.resolvedThinking
import com.newoether.agora.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = true,
    val options: JsonObject? = null,
    val tools: List<ToolDefinition>? = null,
    val think: JsonElement? = null,
)

@Serializable
internal data class OllamaMessage(
    val role: String,
    val content: String = "",
    val thinking: String? = null,
    val images: List<String>? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null
)

@Serializable
internal data class OllamaStreamResponse(
    val model: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean = false,
    @SerialName("done_reason") val doneReason: String? = null,
    val error: String? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null
)

internal fun ollamaStreamTermination(
    sawDone: Boolean,
    doneReason: String?,
    producedContent: Boolean,
    toolCallInFlight: Boolean = false,
    streamError: GenerationError? = null,
    timedOut: Boolean = false,
): StreamTermination = StreamTermination(
    sawTerminalMarker = sawDone,
    stopReason = doneReason?.trim()?.takeIf(String::isNotEmpty)?.lowercase(),
    producedContent = producedContent,
    toolCallInFlight = toolCallInFlight,
    streamError = streamError,
    timedOut = timedOut,
)

internal fun OllamaStreamResponse.toTokenUsage(): TokenUsage {
    val input = promptEvalCount?.coerceAtLeast(0)
    val output = evalCount?.coerceAtLeast(0)
    val total = when {
        input != null && output != null -> TokenUsage.addCounts(input, output)
        input != null -> input
        else -> output ?: 0
    }
    return TokenUsage(
        totalTokenCount = total,
        inputTokenCount = input,
        // Ollama reports prompt evaluation, but not a cache hit/miss split.
        cachedInputTokenCount = null,
        uncachedInputTokenCount = null,
        outputTokenCount = output,
    )
}

@Serializable
internal data class OllamaTagsResponse(
    val models: List<OllamaModelInfo>
)

@Serializable
internal data class OllamaModelInfo(
    val name: String
)

class OllamaProvider : LlmProvider {
    override val name: String = Constants.PROVIDER_OLLAMA
    override val defaultBaseUrl: String = ""
    override val baseUrlPlaceholder: String = "http://localhost:11434"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/')?.ifBlank { null }
            ?: return@flow emit(StreamEvent.Error(GenerationError.Configuration("Ollama base URL not configured")))
        val modelName = config.modelId

        fun buildApiMessages(
            resolvedRequest: ProviderRequestInput,
            base64Files: Base64FileRegistry,
        ): List<OllamaMessage> {
            val apiMessages = mutableListOf<OllamaMessage>()
            if (!resolvedRequest.systemPrompt.isNullOrBlank()) {
                apiMessages.add(OllamaMessage(role = "system", content = resolvedRequest.systemPrompt))
            }

            apiMessages.addAll(resolvedRequest.messages.flatMap { msg ->
            val entries = mutableListOf<OllamaMessage>()

            // tool_ messages: assistant turn with tool_calls (and thinking from segments)
            if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                val thinkingContent = msg.segments?.lastOrNull { it.type == "thought" }?.content
                if (!toolSegs.isNullOrEmpty()) {
                    val toolCalls = toolSegs.map { seg ->
                        val tid = seg.toolCallId ?: buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}")
                        val argsObj = try { json.parseToJsonElement(seg.toolArgs ?: "{}") as? JsonObject } catch (_: Exception) { JsonObject(emptyMap()) }
                        OpenAiToolCall(
                            id = tid,
                            type = "function",
                            function = OpenAiFunctionCall(name = seg.toolName ?: "", arguments = argsObj ?: JsonObject(emptyMap()))
                        )
                    }
                    entries.add(OllamaMessage(
                        role = "assistant",
                        content = "",
                        thinking = thinkingContent?.ifEmpty { null },
                        toolCalls = toolCalls
                    ))
                } else if (msg.toolCall != null) {
                    val toolId = msg.toolCall!!.toolCallId ?: buildToolCallId(msg.toolCall!!.toolName, msg.toolCall!!.arguments)
                    val argsObj = try { json.parseToJsonElement(msg.toolCall!!.arguments) as? JsonObject } catch (_: Exception) { JsonObject(emptyMap()) }
                    entries.add(OllamaMessage(
                        role = "assistant",
                        content = "",
                        thinking = thinkingContent?.ifEmpty { null },
                        toolCalls = listOf(OpenAiToolCall(
                            id = toolId,
                            type = "function",
                            function = OpenAiFunctionCall(name = msg.toolCall!!.toolName, arguments = argsObj ?: JsonObject(emptyMap()))
                        ))
                    ))
                }
                return@flatMap entries
            }

            // result_ messages carry the tool result(s)
            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    for (seg in toolSegs) {
                        entries.add(OllamaMessage(
                            role = "tool",
                            content = seg.toolResult ?: "",
                            toolName = seg.toolName,
                        ))
                    }
                } else if (msg.toolCall != null) {
                    entries.add(OllamaMessage(
                        role = "tool",
                        content = msg.toolCall!!.result,
                        toolName = msg.toolCall!!.toolName,
                    ))
                }
                return@flatMap entries
            }

            val images = if (config.includeImages && msg.participant == Participant.USER) {
                msg.images.mapNotNull(base64Files::register)
            } else null

            // Normal message: text + images only
            entries.add(OllamaMessage(
                role = if (msg.participant == Participant.USER) "user" else "assistant",
                content = msg.text,
                images = images?.takeIf { it.isNotEmpty() }
            ))
            entries
            })
            return apiMessages
        }

        // Generation settings previously never reached Ollama (the options field stayed null,
        // silently ignoring the user's temperature/top_p/max-tokens configuration).
        val options = buildMap<String, kotlinx.serialization.json.JsonElement> {
            config.temperature?.let { put("temperature", kotlinx.serialization.json.JsonPrimitive(it)) }
            config.topP?.let { put("top_p", kotlinx.serialization.json.JsonPrimitive(it)) }
            config.maxTokens?.let { put("num_predict", kotlinx.serialization.json.JsonPrimitive(it)) }
        }.takeIf { it.isNotEmpty() }?.let { JsonObject(it) }

        // think accepts a boolean or one of low/medium/high/max for every model the endpoint serves,
        // so the value follows the model's capability and no request is rejected locally.
        val resolvedThinking = config.resolvedThinking(ThinkingProviderFamily.OLLAMA)
        val think = resolvedThinking.effort
            ?.let { JsonPrimitive(it) }
            ?: JsonPrimitive(resolvedThinking.enabled)

        fun buildRequestBody(
            resolvedRequest: ProviderRequestInput,
            base64Files: Base64FileRegistry,
        ) = OllamaChatRequest(
            model = config.modelId,
            messages = buildApiMessages(resolvedRequest, base64Files),
            stream = true,
            options = options,
            tools = config.tools,
            think = think,
        )

        try {
            val url = "$baseUrl/api/chat"
            val headers = mutableMapOf("Content-Type" to "application/json")
            if (config.apiKey.isNotEmpty()) {
                headers["Authorization"] = "Bearer ${config.apiKey}"
            }
            val maxAttempts = ProviderRetryPolicy.MAX_ATTEMPTS
            val retryableCodes = setOf(401, 429, 502, 503, 504)
            var attempt = 0
            var completed = false

            while (attempt < maxAttempts && !completed) {
                attempt++
                val base64Files = Base64FileRegistry()
                val requestBody = buildRequestBody(
                    config.resolveRequest(messages),
                    base64Files,
                )
                requestBody.requireValidWireFormat()
                val requestBodyJson = json.encodeToString(OllamaChatRequest.serializer(), requestBody)
                val streamingRequest = base64Files.prepare(requestBodyJson)
                requireValidSerializedRequest(
                    provider = "Ollama",
                    body = requestBodyJson,
                    requiredStringFields = setOf("model"),
                    requiredArrayFields = setOf("messages"),
                )
                DebugLog.d(
                    "AgoraAPI",
                    "[Ollama] request model=${config.modelId} messages=${requestBody.messages.size} " +
                        "tools=${config.tools?.size ?: 0}",
                )
                val handle = try {
                    if (streamingRequest != null) {
                        HttpClient.streamPostBody(
                            url,
                            streamingRequest.body,
                            headers,
                            streamingRequest.diagnosticJson,
                        )
                    } else {
                        HttpClient.streamPost(url, requestBodyJson, headers)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val retryable = e.asRetryableTransportError()
                    if (retryable != null && attempt < maxAttempts) {
                        emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                        delay(ProviderRetryPolicy.delayMillis(attempt))
                        continue
                    }
                    throw e
                }
                try {
                    if (handle.code == 200) {
                        var producedContent = false
                        var sawDone = false
                        var doneReason: String? = null
                        var timedOut = false
                        var streamError: GenerationError? = null
                        var toolCallInFlight = false

                        suspend fun emitTracked(event: StreamEvent) {
                            if (event.carriesModelOutput()) producedContent = true
                            emit(event)
                        }

                        // Tolerate long thinking pauses, but not a silently-dead connection:
                        // 3 consecutive read timeouts (~15 min without a byte) → give up.
                        var consecutiveReadTimeouts = 0
                        while (currentCoroutineContext().isActive) {
                            val line = try {
                                handle.readLine()
                            } catch (e: java.net.SocketTimeoutException) {
                                if (!currentCoroutineContext().isActive) break
                                if (++consecutiveReadTimeouts >= 3) {
                                    timedOut = true
                                    break
                                }
                                continue
                            } catch (e: Exception) {
                                if (!currentCoroutineContext().isActive) break
                                streamError = e.asRetryableResponseBodyReadError() ?: throw e
                                break
                            } ?: break
                            consecutiveReadTimeouts = 0
                            try {
                                val response = json.decodeFromString<OllamaStreamResponse>(line)
                                response.doneReason?.takeIf(String::isNotBlank)?.let {
                                    doneReason = it
                                }
                                response.error?.takeIf(String::isNotBlank)?.let { message ->
                                    streamError = GenerationError.Api(
                                        code = null,
                                        type = "ollama_stream_error",
                                        message = message,
                                    )
                                }
                                response.message?.let { msg ->
                                    // 1. Handle explicit thinking field (Ollama 0.5.4+)
                                    msg.thinking?.let { thinking ->
                                        if (thinking.isNotEmpty()) {
                                            emitTracked(StreamEvent.ThoughtChunk(thinking, null))
                                        }
                                    }

                                    // 2. Ollama tool calls are complete snapshots in one NDJSON
                                    // message. Reject the whole batch if any call lacks a name or a
                                    // complete JSON-object argument payload.
                                    msg.toolCalls?.takeIf { it.isNotEmpty() }?.let { toolCalls ->
                                        val parsed = toolCalls.map { tc ->
                                            val streamKey = "call_stream_${java.util.UUID.randomUUID()}"
                                            val id = tc.id?.takeIf(String::isNotBlank)
                                                ?: "${Constants.TOOL_CALL_ID_PREFIX}${java.util.UUID.randomUUID()}"
                                            val name = tc.function?.name.orEmpty()
                                            val args = tc.function?.arguments?.let {
                                                if (it is kotlinx.serialization.json.JsonPrimitive && it.isString) {
                                                    it.content
                                                } else {
                                                    it.toString()
                                                }
                                            }.orEmpty().ifBlank { "{}" }
                                            Triple(
                                                StreamEvent.ToolCallUpdate(
                                                    streamKey = streamKey,
                                                    id = id,
                                                    name = name,
                                                    arguments = args,
                                                ),
                                                StreamEvent.ToolCallRequest(
                                                    id = id,
                                                    name = name,
                                                    arguments = args,
                                                    streamKey = streamKey,
                                                ),
                                                runCatching {
                                                    name.matches(safeWireToolName) &&
                                                        id.matches(safeWireToolCallId) &&
                                                        json.parseToJsonElement(args) is JsonObject
                                                }.getOrDefault(false),
                                            )
                                        }
                                        val callIds = parsed.map { it.second.id }
                                        if (
                                            parsed.any { !it.third } ||
                                            callIds.distinct().size != callIds.size
                                        ) {
                                            toolCallInFlight = true
                                            streamError = GenerationError.SseParse(
                                                rawLine = "tool_calls",
                                                cause = "Ollama returned incomplete tool metadata",
                                            )
                                        } else {
                                            parsed.forEach { emitTracked(it.first) }
                                            val calls = parsed.map { it.second }
                                            if (calls.size == 1) emitTracked(calls.single())
                                            else emitTracked(StreamEvent.ToolCallsRequest(calls))
                                        }
                                    }

                                    // 3. Compatibility parsing of inline thinking markers is
                                    // centralized after the native Ollama decoder.
                                    if (msg.content.isNotEmpty()) {
                                        emitTracked(StreamEvent.TextChunk(msg.content))
                                    }
                                }
                                if (response.done) {
                                    sawDone = true
                                    emit(StreamEvent.UsageUpdate(response.toTokenUsage()))
                                }
                                if (streamError != null || sawDone) break
                            } catch (e: Exception) {
                                DebugLog.e(
                                    "AgoraAPI",
                                    "[Ollama] malformed stream payload exception=${e.javaClass.simpleName}",
                                )
                                streamError = GenerationError.SseParse(
                                    rawLine = line.take(512),
                                    cause = e.localizedMessage ?: "Malformed Ollama stream payload",
                                )
                                break
                            }
                        }
                        if (!currentCoroutineContext().isActive) {
                            throw kotlinx.coroutines.CancellationException("Stream cancelled")
                        }
                        val termination = ollamaStreamTermination(
                            sawDone = sawDone,
                            doneReason = doneReason,
                            producedContent = producedContent,
                            toolCallInFlight = toolCallInFlight,
                            streamError = streamError,
                            timedOut = timedOut,
                        )
                        DebugLog.d("AgoraSSE", "[Ollama] ${termination.describe()}")
                        if (termination.isRetryable && attempt < maxAttempts) {
                            emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                            delay(ProviderRetryPolicy.delayMillis(attempt))
                        } else {
                            termination.toError("Ollama")?.let { emit(StreamEvent.Error(it)) }
                            completed = true
                        }
                    } else {
                        val errorRaw = handle.errorBody.orEmpty()
                        val responseBytes = errorRaw.toByteArray(Charsets.UTF_8).size
                        DebugLog.e(
                            "AgoraAPI",
                            "[Ollama] HTTP ${handle.code} responseBytes=$responseBytes",
                        )

                        if (
                            ProviderRetryPolicy.shouldRetryHttp(
                                handle.code,
                                errorRaw,
                                retryableCodes,
                            ) && attempt < maxAttempts
                        ) {
                            emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                            delay(ProviderRetryPolicy.delayMillis(attempt))
                        } else {
                            val genError = providerHttpError(handle.code, errorRaw)
                            emit(StreamEvent.Error(genError))
                            completed = true
                        }
                    }
                } finally { handle.close() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RequestFormatException) {
            DebugLog.e("AgoraAPI", "[Ollama] blocked invalid request: ${e.violations.joinToString()}")
            emit(StreamEvent.Error(GenerationError.RequestFormat("Ollama", e.violations.joinToString())))
        } catch (e: java.net.SocketTimeoutException) {
            emit(StreamEvent.Error(GenerationError.Timeout))
        } catch (e: java.net.ConnectException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Connection refused")))
        } catch (e: java.net.UnknownHostException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Unknown host")))
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error(GenerationError.Unknown(e)))
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val effectiveBaseUrl = requireNotNull(baseUrl?.trimEnd('/')?.ifBlank { null }) {
            "Ollama base URL not configured"
        }
        val responseText = HttpClient.fetchModelsResponse("$effectiveBaseUrl/api/tags")
            .requireModelFetchBody()
        val models = decodeModelFetchResponse {
            json.decodeFromString<OllamaTagsResponse>(responseText)
                .models.map { it.name }
        }
        if (models.isEmpty()) throw ModelFetchEmptyResultException()
        models
    }
}


