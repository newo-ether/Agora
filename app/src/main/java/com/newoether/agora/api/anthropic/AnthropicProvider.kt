package com.newoether.agora.api.anthropic

import com.newoether.agora.api.*

import com.newoether.agora.util.DebugLog
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingProviderFamily
import com.newoether.agora.api.util.Base64FileRegistry
import com.newoether.agora.api.util.buildToolCallId
import com.newoether.agora.api.util.adaptToolRoundsForProvider
import com.newoether.agora.api.util.resolvedThinking
import com.newoether.agora.api.util.RequestFormatException
import com.newoether.agora.api.util.requireValidSerializedRequest
import com.newoether.agora.api.util.StreamTermination
import com.newoether.agora.api.util.asRetryableResponseBodyReadError
import com.newoether.agora.api.util.asRetryableTransportError
import com.newoether.agora.api.util.carriesModelOutput
import com.newoether.agora.api.util.ProviderRetryPolicy
import com.newoether.agora.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean = true,
    val thinking: AnthropicThinking? = null,
    @SerialName("output_config") val outputConfig: AnthropicOutputConfig? = null,
    val tools: List<AnthropicTool>? = null,
    @SerialName("cache_control") val cacheControl: AnthropicCacheControl? = null,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null
)

@Serializable
internal data class AnthropicCacheControl(
    val type: String = "ephemeral",
    val ttl: String = "1h",
)

@Serializable
internal data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject
)

@Serializable
internal data class AnthropicThinking(
    val type: String = "enabled",
    @SerialName("budget_tokens") val budgetTokens: Int? = null,
    val display: String? = null
)

@Serializable
internal data class AnthropicOutputConfig(
    val effort: String
)

@Serializable
internal data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContentPart>
)

@Serializable
internal data class AnthropicContentPart(
    val type: String,
    val text: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
    val source: AnthropicImageSource? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val content: String? = null
)

@Serializable
internal data class AnthropicImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String
)

@Serializable
internal data class AnthropicStreamEvent(
    val type: String,
    val delta: AnthropicDelta? = null,
    @SerialName("content_block") val contentBlock: AnthropicContentBlock? = null,
    val message: AnthropicMessageInfo? = null,
    val usage: AnthropicUsage? = null,
    val index: Int? = null,
    /** Non-standard relay outcome. `failed to generate` is a transient upstream failure. */
    val outcome: String? = null,
    // `event: error` is delivered INSIDE a 200 stream (overloaded_error, upstream 5xx, quota).
    // Without this field the event decoded to an all-null object, matched no `when` branch, and
    // the failure was silently discarded: the user only saw the generation "stop".
    val error: AnthropicStreamError? = null,
)

@Serializable
internal data class AnthropicStreamError(
    val type: String? = null,
    val message: String? = null,
)

@Serializable
internal data class AnthropicDelta(
    val text: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
    @SerialName("partial_json") val partialJson: String? = null,
    val citation: JsonElement? = null,
    val type: String? = null,
    // Protocol location of the terminal reason:
    //   {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{...}}
    // It is NOT on `message`, and `message_stop` carries no fields at all. Reading it off
    // `message` (what the previous code did) always yielded null, which is why the
    // premature-stop diagnostics never produced a single usable data point.
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_sequence") val stopSequence: String? = null,
)

@Serializable
internal data class AnthropicContentBlock(
    val type: String,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    val text: String? = null,
    val citations: List<JsonElement>? = null,
    val thinking: String? = null,
    val signature: String? = null
)

@Serializable
internal data class AnthropicMessageInfo(
    val usage: AnthropicUsage? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
internal data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int? = null,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int? = null,
)

/**
 * Routes Anthropic SSE deltas by protocol content-block identity. A block is exclusively text,
 * thinking, or tool-use, so a field carried by a tool block can never enter the answer channel.
 *
 * The router also owns terminal-state proof for the stream: whether a semantic end marker arrived,
 * what `stop_reason` the provider reported, whether a tool block was still open, and whether the
 * provider reported an in-band error. The transport layer cannot answer any of those from socket
 * state alone.
 */
private fun MessageSegment.signatureIsCompatibleWithAnthropic(
    sourceModel: String?,
    targetModel: String,
    targetProviderName: String,
): Boolean {
    signatureProvider?.let {
        return it.equals(Constants.PROVIDER_ANTHROPIC, ignoreCase = true) ||
            it == targetProviderName
    }
    return sourceModel == null ||
        sourceModel.equals(targetModel, ignoreCase = true) ||
        sourceModel.contains("claude", ignoreCase = true)
}

private fun ChatMessage.isAnthropicToolRoundCompatible(
    targetModel: String,
    targetProviderName: String,
    signedThinkingRequired: Boolean,
): Boolean {
    if (!signedThinkingRequired) return true
    val thoughts = segments
        ?.filter { it.type == "thought" && it.content.isNotBlank() }
        .orEmpty()
    return thoughts.isNotEmpty() && thoughts.all {
        !it.signature.isNullOrBlank() &&
            it.signatureIsCompatibleWithAnthropic(modelName, targetModel, targetProviderName)
    }
}

class AnthropicProvider(
    override val name: String = Constants.PROVIDER_ANTHROPIC,
    override val defaultBaseUrl: String = "https://api.anthropic.com/v1",
) : LlmProvider {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val modelName = config.modelId

        // Thinking parameters come from the selected model's documented capability, not from a
        // model-name guess, and never block the request. Messages API accepts
        // thinking=enabled{budget_tokens}/disabled/adaptive and output_config.effort in
        // low/medium/high/xhigh/max.
        val resolvedThinking = config.resolvedThinking(ThinkingProviderFamily.ANTHROPIC)
        val capability = resolvedThinking.capability
        val thinkingBudget = resolvedThinking.budgetTokens
        val hasThinkingControls = capability.supportsEffort || capability.supportsThinkingBudget
        val thinking = when {
            // Generations that predate extended thinking take no thinking parameter at all.
            !hasThinkingControls -> null
            resolvedThinking.disabled -> AnthropicThinking(type = "disabled")
            thinkingBudget != null ->
                AnthropicThinking(type = "enabled", budgetTokens = thinkingBudget, display = "summarized")
            // Only models with an effort selector accept the adaptive form; the budget generations
            // require an explicit budget instead.
            capability.supportsEffort -> AnthropicThinking(type = "adaptive", display = "summarized")
            else -> AnthropicThinking(
                type = "enabled",
                budgetTokens = capability.clampBudget(config.thinkingBudgetTokens),
                display = "summarized",
            )
        }
        // effort is an output control rather than a thinking switch, so it is sent whenever the
        // model accepts one, including with thinking off.
        // A model that cannot stop thinking reports its lowest level here, so a forced-thinking-off
        // caller does not inherit an unrelated high effort.
        val outputConfig = (resolvedThinking.effort ?: capability.nearestEffort(config.thinkingLevel))
            ?.let { AnthropicOutputConfig(effort = it) }

        // Convert ToolDefinition to Anthropic format
        val anthropicTools = config.tools?.map { td ->
            AnthropicTool(
                name = td.function.name,
                description = td.function.description,
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive(td.function.parameters.type),
                        "properties" to JsonObject(
                            td.function.parameters.properties.mapValues { (_, prop) ->
                                val propMap = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                                    "type" to JsonPrimitive(prop.type),
                                    "description" to JsonPrimitive(prop.description)
                                )
                                if (prop.items != null) {
                                    propMap["items"] = JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive(prop.items.type),
                                            "description" to JsonPrimitive(prop.items.description)
                                        )
                                    )
                                }
                                JsonObject(propMap)
                            }
                        ),
                        "required" to kotlinx.serialization.json.JsonArray(
                            td.function.parameters.required.map { JsonPrimitive(it) }
                        )
                    )
                )
            )
        }

        fun buildRequestBody(
            resolvedRequest: ProviderRequestInput,
            base64Files: Base64FileRegistry,
        ): AnthropicRequest {
            if (config.anthropicCacheEnabled && config.anthropicCacheTtl !in setOf("5m", "1h")) {
                throw RequestFormatException(name, listOf("Invalid Anthropic cache duration"))
            }
            val validatedPath = adaptToolRoundsForProvider(
                messages = resolvedRequest.messages,
                providerName = name,
            ) { toolMessage ->
                toolMessage.isAnthropicToolRoundCompatible(
                    targetModel = modelName,
                    targetProviderName = name,
                    signedThinkingRequired = thinking?.type in setOf("enabled", "adaptive"),
                )
            }
            val apiMessages = coalesceAnthropicMessages(buildList {
                var index = 0
                while (index < validatedPath.size) {
                    val message = validatedPath[index]
                    when {
                        message.id.startsWith(Constants.TOOL_MSG_PREFIX) -> {
                            add(buildAssistantToolUse(message, modelName))
                            index++
                            if (
                                index < validatedPath.size &&
                                validatedPath[index].id.startsWith(Constants.RESULT_MSG_PREFIX)
                            ) {
                                val resultBlocks = mutableListOf<AnthropicContentPart>()
                                while (
                                    index < validatedPath.size &&
                                    validatedPath[index].id.startsWith(Constants.RESULT_MSG_PREFIX)
                                ) {
                                    resultBlocks.addAll(buildToolResultBlocks(validatedPath[index]))
                                    index++
                                }
                                add(AnthropicMessage(role = "user", content = resultBlocks))
                            }
                        }
                        message.id.startsWith(Constants.RESULT_MSG_PREFIX) -> index++
                        else -> {
                            add(
                                buildNormalMessage(
                                    if (config.includeImages) message
                                    else message.copy(images = emptyList()),
                                    base64Files,
                                ),
                            )
                            index++
                        }
                    }
                }
            })
            return AnthropicRequest(
            model = modelName,
            messages = apiMessages,
            system = resolvedRequest.systemPrompt,
            cacheControl = if (config.anthropicCacheEnabled) {
                AnthropicCacheControl(ttl = config.anthropicCacheTtl)
            } else null,
            thinking = thinking,
            outputConfig = outputConfig,
            // On always-on/adaptive-thinking models max_tokens caps thinking + answer TOGETHER,
            // so the legacy 4096 default truncates mid-answer once the model thinks. Streaming is
            // always on here, so a generous default costs nothing (it is a cap, not a target).
            //
            // The answer headroom above the thinking budget must also leave room for a tool_use
            // block: with only ~1KB of slack, a thinking model routinely exhausts the cap exactly
            // where the tool call would begin, which surfaces as "the tool call vanished".
            maxTokens = config.maxTokens ?: when {
                thinking?.budgetTokens != null ->
                    maxOf(thinking.budgetTokens + ANSWER_HEADROOM_TOKENS, 16384)
                thinking?.type == "adaptive" -> 32768
                else -> 8192
            },
            tools = anthropicTools,
            // temperature/top_k/top_p are deprecated and rejected by models released after
            // Claude Opus 4.6, so the model's capability decides whether they are sent at all.
            // Sampling is accepted only by generations that document it, and only when no thinking
            // parameter is present at all; top_p additionally stays legal in 0.95..1 with thinking.
            temperature = config.temperature.takeIf {
                capability.supportsSamplingParams && thinking == null
            },
            topP = config.topP?.takeIf {
                capability.supportsSamplingParams && (thinking == null || it in 0.95f..1f)
            }
            )
        }

        try {
            val url = "$baseUrl/messages"
            val headers = mutableMapOf("Content-Type" to "application/json")
            headers["x-api-key"] = config.apiKey
            headers["anthropic-version"] = "2023-06-01"
            val maxAttempts = ProviderRetryPolicy.MAX_ATTEMPTS
            val retryableCodes = setOf(429, 502, 503, 504)
            var attempt = 0
            var done = false

            while (attempt < maxAttempts && !done) {
                attempt++
                val base64Files = Base64FileRegistry()
                val requestBody = buildRequestBody(
                    config.resolveRequest(messages),
                    base64Files,
                )
                requestBody.requireValidWireFormat()
                val requestBodyJson = json.encodeToString(AnthropicRequest.serializer(), requestBody)
                val streamingRequest = base64Files.prepare(requestBodyJson)
                requireValidSerializedRequest(
                    provider = name,
                    body = requestBodyJson,
                    requiredStringFields = setOf("model"),
                    requiredArrayFields = setOf("messages"),
                )
                DebugLog.d(
                    "AgoraAPI",
                    "[$name] request model=$modelName messages=${requestBody.messages.size} " +
                        "thinking=${thinking?.type} tools=${anthropicTools?.size ?: 0}",
                )
                // Opening the request can fail before any response headers exist (connect
                // timeout, TLS failure, reset). Those escaped the retry loop entirely before, so a
                // single flaky connection became a hard failure. Nothing has streamed at this
                // point, so replaying is always safe.
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
                        DebugLog.w(
                            "AgoraAPI",
                            "[$name] Transport failure opening the stream on attempt " +
                                "$attempt/$maxAttempts (${e.javaClass.simpleName}), retrying",
                        )
                        val retryDelayMs = ProviderRetryPolicy.delayMillis(attempt)
                        emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                        delay(retryDelayMs)
                        continue
                    }
                    throw e
                }
                try {
                if (handle.code == 200) {
                    var line: String? = null
                    val eventRouter = AnthropicStreamEventRouter()
                    // HTTP 200 only proves the request was accepted. Whether the MESSAGE completed
                    // is decided below from semantic markers, so a relay that cuts the stream at a
                    // block boundary can no longer masquerade as a finished answer.
                    var producedContent = false
                    var timedOut = false
                    var reportedError = false
                    var responseBodyReadError: GenerationError? = null

                    suspend fun emitTracked(event: StreamEvent) {
                        if (event.carriesModelOutput()) producedContent = true
                        if (event is StreamEvent.Error) reportedError = true
                        emit(event)
                    }

                    // Tolerate long thinking pauses, but not a silently-dead connection:
                    // 3 consecutive read timeouts (~15 min without a byte) → give up.
                    var consecutiveReadTimeouts = 0
                    while (currentCoroutineContext().isActive) {
                        try {
                            line = handle.readLine()
                            if (line == null) break
                            consecutiveReadTimeouts = 0
                        } catch (e: java.net.SocketTimeoutException) {
                            if (!currentCoroutineContext().isActive) break
                            if (++consecutiveReadTimeouts >= 3) {
                                timedOut = true
                                break
                            }
                            continue
                        } catch (e: Exception) {
                            if (!currentCoroutineContext().isActive) break
                            responseBodyReadError =
                                e.asRetryableResponseBodyReadError() ?: throw e
                            break
                        }
                        if (line.startsWith("data: ")) {
                            val jsonStr = line.substring(6).trim()
                            try {
                                val event = json.decodeFromString<AnthropicStreamEvent>(jsonStr)
                                eventRouter.route(event).forEach { routed ->
                                    emitTracked(routed)
                                }
                            } catch (e: Exception) {
                                DebugLog.e(
                                    "AgoraAPI",
                                    "[$name] malformed stream payload exception=${e.javaClass.simpleName}",
                                )
                                eventRouter.captureParseError(
                                    rawLine = jsonStr,
                                    cause = e.localizedMessage ?: "Malformed SSE payload",
                                )
                                break
                            }
                        }
                        // An in-band error ends the message; keeping the socket open only stalls
                        // until the read timeout.
                        if (
                            eventRouter.streamError != null ||
                            eventRouter.reportedError ||
                            reportedError
                        ) break
                        // message_stop is the semantic end. Some gateways then hold the connection
                        // open, so stop reading rather than waiting for a close that may never come.
                        if (eventRouter.messageStopReceived) break
                    }
                    eventRouter.reportIncompleteBlocks().forEach { emitTracked(it) }
                    if (!currentCoroutineContext().isActive) {
                        throw kotlinx.coroutines.CancellationException("Stream cancelled")
                    }

                    val termination = StreamTermination(
                        sawTerminalMarker = eventRouter.sawTerminalMarker,
                        stopReason = eventRouter.stopReason,
                        producedContent = producedContent,
                        toolCallInFlight = eventRouter.toolCallInFlight,
                        streamError = responseBodyReadError ?: eventRouter.streamError,
                        alreadyReportedError = eventRouter.reportedError || reportedError,
                        timedOut = timedOut,
                    )
                    DebugLog.d("AgoraSSE",
                        "[$name] stream_end ${termination.describe()} " +
                        "tool_use_blocks=${eventRouter.toolUseBlockStarts} " +
                        "attempt=$attempt/$maxAttempts"
                    )

                    if (termination.isRetryable && attempt < maxAttempts) {
                        // Nothing was surfaced yet, so replaying cannot duplicate visible output.
                        DebugLog.w("AgoraAPI",
                            "[$name] Incomplete stream on attempt $attempt/$maxAttempts, retrying")
                        val retryDelayMs = ProviderRetryPolicy.delayMillis(attempt)
                        emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                        delay(retryDelayMs)
                    } else {
                        done = true
                        termination.toError(name)?.let { emit(StreamEvent.Error(it)) }
                    }
                } else {
                    val errorRaw = handle.errorBody.orEmpty()
                    val responseBytes = errorRaw.toByteArray(Charsets.UTF_8).size
                    DebugLog.e(
                        "AgoraAPI",
                        "[$name] HTTP ${handle.code} responseBytes=$responseBytes",
                    )

                    if (
                        ProviderRetryPolicy.shouldRetryHttp(
                            handle.code,
                            errorRaw,
                            retryableCodes,
                        ) && attempt < maxAttempts
                    ) {
                        val retryDelayMs = ProviderRetryPolicy.delayMillis(attempt)
                        DebugLog.w("AgoraAPI", "[$name] Transient error ${handle.code} on attempt $attempt/$maxAttempts, retrying in ${retryDelayMs}ms...")
                        emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                        delay(retryDelayMs)
                    } else {
                        done = true
                        val genError = providerHttpError(handle.code, errorRaw)
                        emit(StreamEvent.Error(genError))
                    }
                }
                } finally { handle.close() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RequestFormatException) {
            DebugLog.e("AgoraAPI", "[$name] blocked invalid request: ${e.violations.joinToString()}")
            emit(StreamEvent.Error(GenerationError.RequestFormat(name, e.violations.joinToString())))
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

    // ── Message conversion helpers ──

    private fun buildAssistantToolUse(msg: ChatMessage, targetModel: String): AnthropicMessage {
        // With thinking enabled, Anthropic requires the assistant turn that carries tool_use to
        // replay its thinking block(s) unchanged (content + signature) — a bare tool_use turn is
        // rejected on the follow-up request. Unsigned thoughts cannot be replayed, so only signed
        // segments are included; when none exist (thinking off) the turn stays tool_use-only.
        val thinkingParts = msg.segments
            ?.filter {
                it.type == "thought" &&
                    it.content.isNotEmpty() &&
                    !it.signature.isNullOrBlank() &&
                    it.signatureIsCompatibleWithAnthropic(msg.modelName, targetModel, name)
            }
            ?.map { AnthropicContentPart(type = "thinking", thinking = it.content, signature = it.signature) }
            .orEmpty()
        val toolSegs = msg.segments?.filter { it.type == "tool" }
        if (!toolSegs.isNullOrEmpty()) {
            val blocks = toolSegs.map { seg -> buildToolUseBlock(seg.toolCallId, seg.toolName, seg.toolArgs) }
            return AnthropicMessage(role = "assistant", content = thinkingParts + blocks)
        }
        val tc = msg.toolCall ?: return AnthropicMessage(role = "assistant", content = listOf(
            AnthropicContentPart(type = "text", text = "Continue")
        ))
        val block = buildToolUseBlock(tc.toolCallId, tc.toolName, tc.arguments)
        return AnthropicMessage(role = "assistant", content = thinkingParts + listOf(block))
    }

    private fun buildToolUseBlock(id: String?, name: String?, args: String?): AnthropicContentPart {
        val toolId = id ?: buildToolCallId(name ?: "", args ?: "{}", "tool_")
        val input = try {
            json.parseToJsonElement(args ?: "{}") as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Exception) { JsonObject(emptyMap()) }
        return AnthropicContentPart(type = "tool_use", id = toolId, name = name ?: "", input = input)
    }

    private fun buildToolResultBlocks(msg: ChatMessage): List<AnthropicContentPart> {
        val toolSegs = msg.segments?.filter { it.type == "tool" }
        if (!toolSegs.isNullOrEmpty()) {
            return toolSegs.map { seg ->
                val toolId = seg.toolCallId ?: buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}", "tool_")
                AnthropicContentPart(type = "tool_result", toolUseId = toolId, content = seg.toolResult ?: "")
            }
        }
        val tc = msg.toolCall ?: return emptyList()
        val toolId = tc.toolCallId ?: buildToolCallId(tc.toolName, tc.arguments, "tool_")
        return listOf(AnthropicContentPart(type = "tool_result", toolUseId = toolId, content = tc.result))
    }

    private fun buildNormalMessage(
        msg: ChatMessage,
        base64Files: Base64FileRegistry,
    ): AnthropicMessage {
        val parts = mutableListOf<AnthropicContentPart>()
        val imagePaths = if (msg.participant == Participant.USER) msg.images else emptyList()
        for (imagePath in imagePaths) {
            val placeholder = base64Files.register(imagePath) ?: continue
            parts.add(
                AnthropicContentPart(
                    type = "image",
                    source = AnthropicImageSource(
                        mediaType = com.newoether.agora.api.util.imageMimeType(imagePath),
                        data = placeholder,
                    ),
                ),
            )
        }
        // isNotBlank, NOT isNotEmpty: Anthropic rejects a whitespace-only text block with
        // 400 "text content blocks must contain non-whitespace text". Whitespace-only turns
        // are real — a stopped generation that emitted one newline, a tool-only assistant
        // turn, or mergeConsecutiveSameRole joining two blank messages with "\n".
        if (msg.text.isNotBlank()) {
            parts.add(AnthropicContentPart(type = "text", text = msg.text))
        }
        if (parts.isEmpty()) parts.add(AnthropicContentPart(type = "text", text = "Continue"))
        val role = if (msg.participant == Participant.USER) "user" else "assistant"
        return AnthropicMessage(role = role, content = parts)
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val effectiveBaseUrl = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val headers = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
        // /v1/models is paginated (default page ~20); follow has_more/last_id so accounts
        // with long model lists aren't silently truncated to the first page.
        val all = mutableListOf<String>()
        var afterId: String? = null
        var pages = 0
        while (pages < 10) {
            val url = buildString {
                append(effectiveBaseUrl).append("/models?limit=100")
                afterId?.let { append("&after_id=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
            }
            val responseText = HttpClient.fetchModelsResponse(url, headers)
                .requireModelFetchBody()
            val page = decodeModelFetchResponse {
                json.decodeFromString<AnthropicModelsResponse>(responseText)
            }
            all += page.data.map { it.id }
            if (!page.hasMore || page.data.isEmpty()) break
            afterId = page.lastId ?: page.data.last().id
            pages++
        }
        if (all.isEmpty()) throw ModelFetchEmptyResultException()
        all
    }

    private companion object {
        /**
         * Headroom reserved above the thinking budget for the answer AND any tool_use block.
         * Anthropic's max_tokens covers thinking + output together, so this slack is what keeps a
         * tool call from being cut off at the block boundary.
         */
        const val ANSWER_HEADROOM_TOKENS = 8192
    }
}

@Serializable
internal data class AnthropicModelsResponse(
    val data: List<AnthropicModelInfo>,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("last_id") val lastId: String? = null
)

@Serializable
internal data class AnthropicModelInfo(
    val id: String,
    @SerialName("display_name") val displayName: String = ""
)
