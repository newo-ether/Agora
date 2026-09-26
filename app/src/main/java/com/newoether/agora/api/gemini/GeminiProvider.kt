package com.newoether.agora.api.gemini

import com.newoether.agora.api.*

import com.newoether.agora.util.DebugLog
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ThinkingProviderFamily
import com.newoether.agora.api.util.Base64FileRegistry
import com.newoether.agora.api.util.resolvedThinking
import com.newoether.agora.api.util.adaptToolRoundsForProvider
import com.newoether.agora.api.util.RequestFormatException
import com.newoether.agora.api.util.requireValidSerializedRequest
import com.newoether.agora.api.util.ProviderRetryPolicy
import com.newoether.agora.api.util.StreamTermination
import com.newoether.agora.api.util.asRetryableResponseBodyReadError
import com.newoether.agora.api.util.asRetryableTransportError
import com.newoether.agora.api.util.carriesModelOutput
import com.newoether.agora.api.util.safeWireToolName
import com.newoether.agora.api.util.safeWireToolCallId
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

// Gemini thought summaries carry their headline as **bold** or a markdown heading.
// Hoisted to file level: extraction runs on every streamed thought chunk, and Regex
// construction (Pattern.compile) is far too expensive to repeat per chunk.
private val THOUGHT_TITLE_BOLD = Regex("\\*\\*(.*?)\\*\\*")
private val THOUGHT_TITLE_HEADING = Regex("(?m)^#+\\s*(.*)$")

private fun extractThoughtTitle(content: String): String? =
    THOUGHT_TITLE_BOLD.find(content)?.groupValues?.get(1)
        ?: THOUGHT_TITLE_HEADING.find(content)?.groupValues?.get(1)

private fun ChatMessage.isGeminiToolRoundCompatible(
    targetModel: String,
    targetProviderName: String,
    signatureRequired: Boolean,
): Boolean {
    val calls = segments
        ?.filter { it.type == "tool" }
        .orEmpty()
        .ifEmpty {
            toolCall?.let { call ->
                listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = call.toolName,
                        toolArgs = call.arguments,
                        toolCallId = call.toolCallId,
                        signature = call.signature,
                    )
                )
            }.orEmpty()
        }
    if (calls.isEmpty()) return false
    return calls.all { call ->
        if (signatureRequired && call.signature.isNullOrBlank()) return@all false
        if (call.signature.isNullOrBlank()) return@all true
        call.signatureProvider?.let { provider ->
            return@all provider.equals(Constants.PROVIDER_GOOGLE, ignoreCase = true) ||
                provider == targetProviderName
        }
        modelName == null ||
            modelName.equals(targetModel, ignoreCase = true) ||
            modelName.contains("gemini", ignoreCase = true)
    }
}

private fun JsonObject.stringContent(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)

internal fun JsonObject.toGoogleSearchHostedUpdate(
    streamKey: String,
): StreamEvent.HostedToolCallUpdate {
    val queries = (this["webSearchQueries"] as? JsonArray)
        .orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
    val seenUrls = mutableSetOf<String>()
    val results = (this["groundingChunks"] as? JsonArray)
        .orEmpty()
        .mapNotNull { chunk ->
            val web = (chunk as? JsonObject)?.get("web") as? JsonObject
                ?: return@mapNotNull null
            val url = web.stringContent("uri") ?: return@mapNotNull null
            if (!seenUrls.add(url)) return@mapNotNull null
            JsonObject(
                linkedMapOf(
                    "title" to JsonPrimitive(web.stringContent("title") ?: url),
                    "url" to JsonPrimitive(url),
                ),
            )
        }
    val query = queries.joinToString(" · ")
    val arguments = JsonObject(
        if (query.isBlank()) emptyMap() else mapOf("query" to JsonPrimitive(query)),
    )
    val normalizedResult = linkedMapOf<String, JsonElement>(
        "type" to JsonPrimitive("web_search"),
        "provider" to JsonPrimitive("Google"),
    )
    if (query.isNotBlank()) normalizedResult["query"] = JsonPrimitive(query)
    normalizedResult["results"] = JsonArray(results)
    normalizedResult["grounding_metadata"] = this
    return StreamEvent.HostedToolCallUpdate(
        streamKey = streamKey,
        name = "google_search",
        arguments = arguments.toString(),
        result = JsonObject(normalizedResult).toString(),
    )
}

internal fun ApiExecutableCode.toCodeExecutionStart(
    streamKey: String,
): StreamEvent.HostedToolCallUpdate = StreamEvent.HostedToolCallUpdate(
    streamKey = streamKey,
    name = "code_execution",
    arguments = JsonObject(
        linkedMapOf(
            "language" to JsonPrimitive(language),
            "code" to JsonPrimitive(code),
        ),
    ).toString(),
)

internal fun ApiCodeExecutionResult.toCodeExecutionCompletion(
    streamKey: String,
    arguments: String,
): StreamEvent.HostedToolCallUpdate = StreamEvent.HostedToolCallUpdate(
    streamKey = streamKey,
    name = "code_execution",
    arguments = arguments,
    result = JsonObject(
        linkedMapOf(
            "type" to JsonPrimitive("code_execution"),
            "outcome" to JsonPrimitive(outcome),
            "output" to JsonPrimitive(output),
        ),
    ).toString(),
    isError = outcome.uppercase() !in setOf("OUTCOME_OK", "OK", "SUCCESS"),
)

internal fun geminiModelMessageParts(message: ChatMessage): List<ApiRequestPart> {
    val segments = message.segments.orEmpty()
    if (segments.none { it.type == "tool" && it.toolName == "code_execution" }) {
        return message.text.takeIf(String::isNotEmpty)
            ?.let { listOf(ApiRequestPart(text = it)) }
            .orEmpty()
    }
    val parts = mutableListOf<ApiRequestPart>()
    var includedAnswerSegment = false
    segments.forEach { segment ->
        when {
            segment.type == "answer" && segment.content.isNotEmpty() -> {
                includedAnswerSegment = true
                parts += ApiRequestPart(text = segment.content)
            }
            segment.type == "tool" && segment.toolName == "code_execution" -> {
                val arguments = runCatching {
                    Json.parseToJsonElement(segment.toolArgs.orEmpty()) as? JsonObject
                }.getOrNull()
                val code = arguments?.stringContent("code")
                if (code != null) {
                    parts += ApiRequestPart(
                        executableCode = ApiExecutableCode(
                            language = arguments.stringContent("language") ?: "LANGUAGE_UNSPECIFIED",
                            code = code,
                        ),
                    )
                }
                val result = runCatching {
                    Json.parseToJsonElement(segment.toolResult.orEmpty()) as? JsonObject
                }.getOrNull()
                val outcome = result?.stringContent("outcome")
                val output = result?.get("output") as? JsonPrimitive
                if (outcome != null || output != null) {
                    parts += ApiRequestPart(
                        codeExecutionResult = ApiCodeExecutionResult(
                            outcome = outcome ?: "OUTCOME_UNSPECIFIED",
                            output = output?.content.orEmpty(),
                        ),
                    )
                }
            }
        }
    }
    if (!includedAnswerSegment && message.text.isNotEmpty()) {
        parts.add(0, ApiRequestPart(text = message.text))
    }
    return parts
}

internal fun normalizeGeminiFinishReason(raw: String): String = when (
    raw.trim().lowercase().replace('-', '_')
) {
    "max_tokens", "max_output_tokens" -> "max_output_tokens"
    else -> raw.trim().lowercase().replace('-', '_')
}

internal fun geminiStreamTermination(
    sawDone: Boolean,
    finishReason: String?,
    producedContent: Boolean,
    streamError: GenerationError? = null,
    timedOut: Boolean = false,
    toolCallInFlight: Boolean = false,
): StreamTermination = StreamTermination(
    sawTerminalMarker = sawDone || finishReason != null,
    stopReason = finishReason,
    producedContent = producedContent,
    toolCallInFlight = toolCallInFlight,
    streamError = streamError,
    timedOut = timedOut,
)

class GeminiProvider(
    override val name: String = Constants.PROVIDER_GOOGLE,
    override val defaultBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : LlmProvider {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val cleanModelName = config.modelId.removePrefix("models/")
        
        val requiresFunctionCallSignature =
            cleanModelName.contains("gemini-3", ignoreCase = true) ||
                cleanModelName.contains("gemini-3.5", ignoreCase = true)

        fun buildApiContents(
            resolvedMessages: List<ChatMessage>,
            base64Files: Base64FileRegistry,
        ): List<ApiRequestContent> {
            val validatedPath = adaptToolRoundsForProvider(
                messages = resolvedMessages,
                providerName = name,
            ) { toolMessage ->
                toolMessage.isGeminiToolRoundCompatible(
                    targetModel = cleanModelName,
                    targetProviderName = name,
                    signatureRequired = requiresFunctionCallSignature,
                )
            }

            return coalesceGeminiContents(validatedPath.flatMap { msg ->
            val entries = mutableListOf<ApiRequestContent>()

            // tool_ messages: model turn with functionCall(s)
            // Note: Gemini 3 requires thought to be boolean in requests, so we omit thought strings
            // and only include thoughtSignature on the functionCall part
            if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    val parts = toolSegs.map { seg ->
                        val args = try {
                            json.parseToJsonElement(seg.toolArgs ?: "{}") as? JsonObject
                        } catch (_: Exception) { JsonObject(emptyMap()) }
                        ApiRequestPart(
                            functionCall = GeminiFunctionCall(
                                id = seg.toolCallId,
                                name = seg.toolName ?: "",
                                args = args ?: JsonObject(emptyMap())
                            ),
                            thoughtSignature = seg.signature
                        )
                    }
                    entries.add(ApiRequestContent(role = "model", parts = parts))
                } else if (msg.toolCall != null) {
                    val args = try {
                        json.parseToJsonElement(msg.toolCall!!.arguments) as? JsonObject
                    } catch (_: Exception) { JsonObject(emptyMap()) }
                    entries.add(ApiRequestContent(
                        role = "model",
                        parts = listOf(ApiRequestPart(
                            functionCall = GeminiFunctionCall(
                                id = msg.toolCall!!.toolCallId,
                                name = msg.toolCall!!.toolName,
                                args = args ?: JsonObject(emptyMap())
                            ),
                            thoughtSignature = msg.toolCall!!.signature
                        ))
                    ))
                }
                return@flatMap entries
            }

            // result_ messages carry the function response(s)
            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    val parts = toolSegs.map { seg ->
                        val response = buildGeminiFunctionResponse(seg.toolResult ?: "{}")
                        ApiRequestPart(functionResponse = GeminiFunctionResponse(
                            id = seg.toolCallId,
                            name = seg.toolName ?: "",
                            response = response
                        ))
                    }
                    entries.add(ApiRequestContent(role = "user", parts = parts))
                } else if (msg.toolCall != null) {
                    val response = buildGeminiFunctionResponse(msg.toolCall!!.result)
                    entries.add(ApiRequestContent(
                        role = "user",
                        parts = listOf(ApiRequestPart(functionResponse = GeminiFunctionResponse(
                            id = msg.toolCall!!.toolCallId,
                            name = msg.toolCall!!.toolName,
                            response = response
                        )))
                    ))
                }
                return@flatMap entries
            }

            // Normal message: preserve Gemini code-execution parts for model history.
            val parts = if (msg.participant == Participant.MODEL) {
                geminiModelMessageParts(msg).toMutableList()
            } else {
                mutableListOf<ApiRequestPart>().apply {
                    if (msg.text.isNotEmpty()) add(ApiRequestPart(text = msg.text))
                }
            }
            if (config.includeImages && msg.participant == Participant.USER) {
                for (imagePath in msg.images) {
                    val placeholder = base64Files.register(imagePath) ?: continue
                    parts.add(
                        ApiRequestPart(
                            inlineData = ApiInlineData(
                                mimeType = com.newoether.agora.api.util.imageMimeType(imagePath),
                                data = placeholder,
                            ),
                        ),
                    )
                }
            }
            if (parts.isEmpty()) parts.add(ApiRequestPart(text = "[Attachment unavailable]"))
            entries.add(ApiRequestContent(
                role = if (msg.participant == Participant.USER) "user" else "model",
                parts = parts
            ))

            entries
            })
        }

        val tools = mutableListOf<ApiTool>()
        if (config.codeExecutionEnabled) tools.add(ApiTool(codeExecution = JsonObject(emptyMap())))
        if (config.googleSearchEnabled) tools.add(ApiTool(googleSearch = JsonObject(emptyMap())))

        // Add memory function declarations as a separate tool entry
        val functionDeclarations = config.tools?.map { td ->
            GeminiFunctionDeclaration(
                name = td.function.name,
                description = td.function.description,
                parameters = JsonObject(
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
        if (!functionDeclarations.isNullOrEmpty()) {
            tools.add(ApiTool(functionDeclarations = functionDeclarations))
        }

        // Thinking parameters come from the selected model's documented capability instead of a
        // model-name guess. thinkingConfig.thinkingLevel accepts MINIMAL/LOW/MEDIUM/HIGH only,
        // thinkingBudget is the token form, and thinkingBudget = 0 disables thinking.
        val resolvedThinking = config.resolvedThinking(ThinkingProviderFamily.GEMINI)
        val thinkingConfig = when {
            resolvedThinking.disabled -> ApiThinkingConfig(
                includeThoughts = false,
                thinkingBudget = 0.takeIf { resolvedThinking.capability.supportsThinkingBudget },
            )
            resolvedThinking.budgetTokens != null -> ApiThinkingConfig(
                includeThoughts = true,
                thinkingBudget = resolvedThinking.budgetTokens,
            )
            resolvedThinking.effort != null -> ApiThinkingConfig(
                includeThoughts = true,
                thinkingLevel = resolvedThinking.effort.uppercase(),
            )
            else -> ApiThinkingConfig(includeThoughts = true)
        }

        val hasBuiltInTools = tools.any { it.codeExecution != null || it.googleSearch != null }
        val hasFunctionDeclarations = tools.any { it.functionDeclarations != null }
        val toolConfig = if (hasBuiltInTools && hasFunctionDeclarations) {
            ApiToolConfig(includeServerSideToolInvocations = true)
        } else null

        // thinkingConfig is always present now, so generationConfig is always sent.
        val genConfig = ApiGenerationConfig(
            thinkingConfig = thinkingConfig,
            temperature = config.temperature,
            maxOutputTokens = config.maxTokens,
            topP = config.topP,
            frequencyPenalty = config.frequencyPenalty,
            presencePenalty = config.presencePenalty
        )

        fun buildRequestBody(
            resolvedRequest: ProviderRequestInput,
            base64Files: Base64FileRegistry,
        ) = ApiGenerateContentRequest(
            contents = buildApiContents(resolvedRequest.messages, base64Files),
            systemInstruction = resolvedRequest.systemPrompt
                ?.takeIf(String::isNotBlank)
                ?.let { ApiRequestContent(parts = listOf(ApiRequestPart(text = it))) },
            tools = if (tools.isNotEmpty()) tools else null,
            toolConfig = toolConfig,
            generationConfig = genConfig,
        )

        try {
            // Determine if baseUrl already includes versioning
            val finalUrlString = if (baseUrl.contains("/v1") || baseUrl.contains("/v1beta")) {
                "$baseUrl/models/$cleanModelName:streamGenerateContent?alt=sse"
            } else {
                "$baseUrl/v1beta/models/$cleanModelName:streamGenerateContent?alt=sse"
            }

            val headers = mapOf(
                "Content-Type" to "application/json",
                "x-goog-api-key" to config.apiKey
            )
            val maxAttempts = ProviderRetryPolicy.MAX_ATTEMPTS
            val retryableCodes = setOf(429, 500, 502, 503, 504)
            var attempt = 0
            var done = false

            while (attempt < maxAttempts && !done) {
                attempt++
                val base64Files = Base64FileRegistry()
                val requestBody = buildRequestBody(
                    config.resolveRequest(messages),
                    base64Files,
                )
                requestBody.requireValidWireFormat(cleanModelName)
                val requestJson = json.encodeToString(ApiGenerateContentRequest.serializer(), requestBody)
                val streamingRequest = base64Files.prepare(requestJson)
                requireValidSerializedRequest(
                    provider = name,
                    body = requestJson,
                    requiredArrayFields = setOf("contents"),
                )
                DebugLog.d(
                    "AgoraAPI",
                    "[$name] request model=$cleanModelName messages=${requestBody.contents.size} " +
                        "thinking=${config.thinkingEnabled} tools=${tools.size}",
                )
                val handle = try {
                    if (streamingRequest != null) {
                        HttpClient.streamPostBody(
                            finalUrlString,
                            streamingRequest.body,
                            headers,
                            streamingRequest.diagnosticJson,
                        )
                    } else {
                        HttpClient.streamPost(finalUrlString, requestJson, headers)
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
                        var currentThoughtSignature: String? = null
                        var inThoughtBlock = false
                        var consecutiveReadTimeouts = 0
                        var producedContent = false
                        var finishReason: String? = null
                        var sawDone = false
                        var timedOut = false
                        var streamError: GenerationError? = null
                        var toolCallInFlight = false
                        val completedToolCallIds = mutableSetOf<String>()
                        val pendingCodeExecutions = java.util.ArrayDeque<Pair<String, String>>()
                        val googleSearchStreamKey = "gemini_google_search_${UUID.randomUUID()}"
                        var lastGroundingMetadata: String? = null
                        var latestGroundingMetadata: JsonObject? = null
                        val answerText = StringBuilder()

                        suspend fun emitTracked(event: StreamEvent) {
                            if (event.carriesModelOutput()) producedContent = true
                            emit(event)
                        }

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
                            if (!line.startsWith("data: ")) continue
                            val jsonStr = line.substring(6).trim()
                            if (jsonStr == "[DONE]") {
                                sawDone = true
                                break
                            }
                            try {
                                val response = json.decodeFromString<ApiStreamResponse>(jsonStr)
                                response.error?.let { error ->
                                    streamError = GenerationError.Api(
                                        code = error.code?.toString(),
                                        type = error.status,
                                        message = error.message?.ifBlank {
                                            "$name reported an error in the response stream"
                                        } ?: "$name reported an error in the response stream",
                                    )
                                }
                                if (streamError == null && ProviderRetryPolicy.isFailedToGenerateOutcome(response.outcome)) {
                                    streamError = GenerationError.Api(null, "failed_to_generate", response.outcome.orEmpty())
                                }
                                val candidate = response.candidates?.firstOrNull()
                                candidate?.finishReason?.takeIf(String::isNotBlank)?.let {
                                    finishReason = normalizeGeminiFinishReason(it)
                                }
                                candidate?.groundingMetadata?.let { metadata ->
                                    latestGroundingMetadata = metadata
                                    val snapshot = metadata.toString()
                                    if (snapshot != lastGroundingMetadata) {
                                        lastGroundingMetadata = snapshot
                                        emitTracked(
                                            metadata.toGoogleSearchHostedUpdate(googleSearchStreamKey),
                                        )
                                    }
                                }
                                inThoughtBlock = false
                                candidate?.content?.parts?.forEach { part ->
                                    var isPartOfThought = false
                                    val partThoughtSignature =
                                        part.thoughtSignature?.takeIf(String::isNotBlank)
                                    partThoughtSignature?.let { signature ->
                                        currentThoughtSignature = signature
                                        isPartOfThought = true
                                        inThoughtBlock = true
                                    }
                                    part.thought?.let { thoughtElement ->
                                        if (thoughtElement is JsonPrimitive) {
                                            if (thoughtElement.isString) {
                                                val content = thoughtElement.content
                                                content.takeIf(String::isNotEmpty)?.let {
                                                    emitTracked(
                                                        StreamEvent.ThoughtChunk(
                                                            it,
                                                            extractThoughtTitle(it),
                                                            currentThoughtSignature,
                                                        ),
                                                    )
                                                    isPartOfThought = true
                                                    inThoughtBlock = true
                                                }
                                            } else if (thoughtElement.content == "true") {
                                                isPartOfThought = true
                                                inThoughtBlock = true
                                            }
                                        }
                                    }
                                    part.reasoningContent?.takeIf(String::isNotEmpty)?.let {
                                        emitTracked(StreamEvent.ThoughtChunk(it, extractThoughtTitle(it), currentThoughtSignature))
                                        isPartOfThought = true
                                        inThoughtBlock = true
                                    }
                                    part.text?.takeIf(String::isNotEmpty)?.let {
                                        if (isPartOfThought || inThoughtBlock) {
                                            emitTracked(
                                                StreamEvent.ThoughtChunk(
                                                    it,
                                                    extractThoughtTitle(it),
                                                    currentThoughtSignature,
                                                ),
                                            )
                                            inThoughtBlock = false
                                        } else {
                                            answerText.append(it)
                                            emitTracked(StreamEvent.TextChunk(it))
                                        }
                                    }
                                    part.executableCode?.let { code ->
                                        val active = code.toCodeExecutionStart(
                                            streamKey = "gemini_code_execution_${UUID.randomUUID()}",
                                        )
                                        pendingCodeExecutions.addLast(active.streamKey to active.arguments)
                                        emitTracked(active)
                                    }
                                    part.codeExecutionResult?.let { result ->
                                        val pending = pendingCodeExecutions.pollFirst()
                                        val streamKey = pending?.first
                                            ?: "gemini_code_execution_${UUID.randomUUID()}"
                                        emitTracked(
                                            result.toCodeExecutionCompletion(
                                                streamKey = streamKey,
                                                arguments = pending?.second ?: "{}",
                                            ),
                                        )
                                    }
                                    part.functionCall?.let { fc ->
                                        val callId = fc.id?.takeIf(String::isNotBlank)
                                            ?: "call_${UUID.randomUUID()}"
                                        if (
                                            !fc.name.matches(safeWireToolName) ||
                                            !callId.matches(safeWireToolCallId) ||
                                            !completedToolCallIds.add(callId)
                                        ) {
                                            toolCallInFlight = true
                                            streamError = GenerationError.SseParse(
                                                rawLine = "functionCall",
                                                cause = "Gemini returned invalid or duplicate tool metadata",
                                            )
                                        } else {
                                            val argsJson = fc.args?.let {
                                                Json.encodeToString(JsonObject.serializer(), it)
                                            } ?: "{}"
                                            val signature = partThoughtSignature
                                                ?: fc.thoughtSignature?.takeIf(String::isNotBlank)
                                                ?: currentThoughtSignature
                                            val streamKey = "call_stream_${UUID.randomUUID()}"
                                            emitTracked(StreamEvent.ToolCallUpdate(streamKey, callId, fc.name, argsJson, signature))
                                            emitTracked(StreamEvent.ToolCallRequest(callId, fc.name, argsJson, signature, streamKey))
                                            currentThoughtSignature = null
                                            inThoughtBlock = false
                                        }
                                    }
                                }
                                candidate?.groundingMetadata
                                    ?.toGeminiCitations(answerText.toString())
                                    ?.forEach { citation -> emit(StreamEvent.CitationUpdate(citation)) }
                                response.usageMetadata?.let { emit(StreamEvent.UsageUpdate(it.toTokenUsage())) }
                                if (streamError != null || finishReason != null) break
                            } catch (e: Exception) {
                                DebugLog.e(
                                    "AgoraAPI",
                                    "[$name] malformed stream payload exception=${e.javaClass.simpleName}",
                                )
                                streamError = GenerationError.SseParse(
                                    rawLine = jsonStr.take(512),
                                    cause = e.localizedMessage ?: "Malformed SSE payload",
                                )
                                break
                            }
                        }
                        if (!currentCoroutineContext().isActive) throw kotlinx.coroutines.CancellationException("Stream cancelled")
                        val termination = geminiStreamTermination(
                            sawDone,
                            finishReason,
                            producedContent,
                            streamError,
                            timedOut,
                            toolCallInFlight || pendingCodeExecutions.isNotEmpty(),
                        )
                        if (!(termination.isRetryable && attempt < maxAttempts)) {
                            latestGroundingMetadata
                                ?.toGeminiCitations(answerText.toString())
                                ?.forEach { citation -> emit(StreamEvent.CitationUpdate(citation)) }
                        }
                        DebugLog.d("AgoraSSE", "[$name] ${termination.describe()}")
                        if (termination.isRetryable && attempt < maxAttempts) {
                            emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                            delay(ProviderRetryPolicy.delayMillis(attempt))
                        } else {
                            termination.toError(name)?.let { emit(StreamEvent.Error(it)) }
                            done = true
                        }
                    } else {
                        val errorRaw = handle.errorBody.orEmpty()
                        val responseBytes = errorRaw.toByteArray(Charsets.UTF_8).size
                        DebugLog.e(
                            "AgoraAPI",
                            "[$name] HTTP ${handle.code} responseBytes=$responseBytes",
                        )
                        val retryable = ProviderRetryPolicy.shouldRetryHttp(
                            handle.code,
                            errorRaw,
                            retryableCodes,
                        )
                        if (retryable && attempt < maxAttempts) {
                            emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                            delay(ProviderRetryPolicy.delayMillis(attempt))
                        } else {
                            val genError = providerHttpError(handle.code, errorRaw)
                            emit(StreamEvent.Error(genError))
                            done = true
                        }
                    }
                } finally {
                    handle.close()
                }
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

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val effectiveBaseUrl = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val finalUrlString = if (effectiveBaseUrl.contains("/v1") || effectiveBaseUrl.contains("/v1beta")) {
            "$effectiveBaseUrl/models"
        } else {
            "$effectiveBaseUrl/v1beta/models"
        }

        val responseText = HttpClient.fetchModelsResponse(
            finalUrlString,
            mapOf("x-goog-api-key" to apiKey),
        ).requireModelFetchBody()
        val models = decodeModelFetchResponse {
            json.decodeFromString<ModelListResponse>(responseText)
                .models
                .filter { it.supportedGenerationMethods.contains("generateContent") }
                .map { it.name.removePrefix("models/") }
        }
        if (models.isEmpty()) throw ModelFetchEmptyResultException()
        models
    }

    private fun buildGeminiFunctionResponse(result: String): JsonObject {
        return try {
            val parsed = json.parseToJsonElement(result)
            when (parsed) {
                is JsonObject -> parsed
                else -> JsonObject(mapOf("result" to parsed))
            }
        } catch (_: Exception) {
            JsonObject(mapOf("result" to JsonPrimitive(result)))
        }
    }
}
