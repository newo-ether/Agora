package com.newoether.agora.api.openai

import com.newoether.agora.api.*

import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.util.Base64FileRegistry
import com.newoether.agora.api.util.StreamingJsonRequest
import com.newoether.agora.api.util.convertToOpenAiMessages
import com.newoether.agora.api.util.prepareMessages
import com.newoether.agora.api.util.RequestFormatException
import com.newoether.agora.api.util.requireValidSerializedRequest
import com.newoether.agora.api.util.StreamTermination
import com.newoether.agora.api.util.asRetryableResponseBodyReadError
import com.newoether.agora.api.util.asRetryableTransportError
import com.newoether.agora.api.util.carriesModelOutput
import com.newoether.agora.api.util.ProviderRetryPolicy
import com.newoether.agora.api.util.safeWireToolCallId
import com.newoether.agora.api.util.safeWireToolName
import com.newoether.agora.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

abstract class BaseOpenAiProvider : LlmProvider {

    protected val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    // -- Override points --

    /**
     * Modify the outgoing request before serialization (e.g. add reasoning_effort, plugins).
     * The default implementation returns the request unchanged.
     */
    protected open fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest = request

    /**
     * Extra HTTP headers to include in the POST to /chat/completions.
     */
    protected open fun getExtraHeaders(config: ProviderConfig): Map<String, String> = emptyMap()

    /**
     * Transform the system prompt before it is sent. Default: pass-through.
     */
    protected open fun transformSystemPrompt(prompt: String?): String? = prompt

    /**
     * Replay each assistant turn's stored chain of thought as `reasoning_content`. DeepSeek
     * thinking mode answers a tools request with 400 unless every earlier turn carries it; other
     * providers ignore the field, so the default is off.
     */
    protected open fun forwardsAssistantReasoningContent(config: ProviderConfig): Boolean = false

    /**
     * Parse one OpenAI-compatible delta into native thought and raw answer events.
     * Provider-neutral inline marker recovery is owned by ProviderStreamNormalizer.
     */
    protected open suspend fun parseDeltaContent(
        delta: OpenAiDelta,
        config: ProviderConfig,
        emit: suspend (StreamEvent) -> Unit
    ) {
        // reasoning_content is the vLLM/DeepSeek-compatible field; `reasoning` is the bare-string
        // form many relays emit instead. Take whichever the endpoint actually populated.
        val reasoning = delta.reasoningContent?.takeIf(String::isNotEmpty)
            ?: delta.reasoning?.takeIf(String::isNotEmpty)
        reasoning?.let {
            emit(StreamEvent.ThoughtChunk(it))
        }
        delta.content?.takeIf(String::isNotEmpty)?.let { content ->
            emit(StreamEvent.TextChunk(content))
        }
    }

    protected open val retryableStatusCodes: Set<Int> = setOf(429, 502, 503, 504)

    protected open val retryMissingV1BaseUrl: Boolean = false

    /**
     * OpenAI normally follows `finish_reason` with an optional usage-only event and `[DONE]`.
     * Compatible gateways sometimes omit `[DONE]` or keep the HTTP connection alive. Once the
     * semantic terminal event arrives, accept that tail only for this bounded window.
     */
    protected open val terminalSseGraceMillis: Long = 1_000L

    protected open fun retryDelayMillis(attempt: Int): Long =
        ProviderRetryPolicy.delayMillis(attempt)

    // -- Template method --

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val endpointUrls = openAiEndpointCandidates(
            baseUrl = baseUrl,
            path = if (config.responsesApiEnabled) "responses" else "chat/completions",
            retryMissingV1BaseUrl = retryMissingV1BaseUrl,
        )
        try {
            if (config.openAiWebSearchEnabled && !config.responsesApiEnabled) {
                throw RequestFormatException(
                    name,
                    listOf("hosted web search requires Responses API transport"),
                )
            }
            fun buildRequestBody(
                apiMessages: List<OpenAiMessage>,
                base64Files: Base64FileRegistry,
            ): Pair<String, StreamingJsonRequest?> {
                val requestBodyJson = if (config.responsesApiEnabled) {
                    val request = OpenAiResponsesRequest(
                        model = config.modelId,
                        input = apiMessages.toResponsesInput(providerName = name),
                        tools = buildList {
                            addAll(config.tools.orEmpty().toResponsesTools())
                            if (config.openAiWebSearchEnabled) {
                                add(OpenAiResponseTool(type = "web_search"))
                            }
                        }.ifEmpty { null },
                        reasoning = config.thinkingLevel.takeIf { config.thinkingEnabled }
                            ?.let {
                                OpenAiReasoning(
                                    effort = it,
                                    summary = "auto",
                                )
                            },
                        serviceTier = config.openAiServiceTier,
                        temperature = config.temperature,
                        maxOutputTokens = config.maxTokens,
                        topP = config.topP,
                        promptCacheKey = config.promptCacheKey,
                    )
                    request.requireValidWireFormat(name)
                    json.encodeToString(OpenAiResponsesRequest.serializer(), request)
                } else {
                    var request = OpenAiChatRequest(
                        model = config.modelId,
                        messages = apiMessages,
                        stream = true,
                        streamOptions = OpenAiStreamOptions(includeUsage = true),
                        serviceTier = config.openAiServiceTier,
                        tools = config.tools,
                        temperature = config.temperature,
                        maxTokens = config.maxTokens,
                        topP = config.topP,
                        frequencyPenalty = config.frequencyPenalty,
                        presencePenalty = config.presencePenalty,
                        promptCacheKey = config.promptCacheKey,
                    )
                    request = customizeRequest(request, config)
                    request.requireValidWireFormat(name)
                    json.encodeToString(OpenAiChatRequest.serializer(), request)
                }
                requireValidSerializedRequest(
                    provider = name,
                    body = requestBodyJson,
                    requiredStringFields = setOf("model"),
                    requiredArrayFields = setOf(
                        if (config.responsesApiEnabled) "input" else "messages",
                    ),
                )
                return requestBodyJson to base64Files.prepare(requestBodyJson)
            }

            val headers = mutableMapOf("Content-Type" to "application/json")
            if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey}"
            for ((key, value) in getExtraHeaders(config)) headers[key] = value

            val maxAttempts = ProviderRetryPolicy.MAX_ATTEMPTS
            var attempt = 0
            var finished = false

            while (attempt < maxAttempts && !finished) {
                attempt++
                var endpointIndex = 0
                var retryScheduled = false

                while (endpointIndex < endpointUrls.size && !finished && !retryScheduled) {
                    val endpointUrl = endpointUrls[endpointIndex]
                    val resolvedRequest = config.resolveRequest(messages)
                    val base64Files = Base64FileRegistry()
                    val apiMessages = convertToOpenAiMessages(
                        messages = resolvedRequest.messages,
                        systemPrompt = transformSystemPrompt(resolvedRequest.systemPrompt),
                        includeImages = config.includeImages,
                        base64Files = base64Files,
                        forwardAssistantReasoning = forwardsAssistantReasoningContent(config),
                    )
                    val (requestBodyJson, streamingRequest) = buildRequestBody(
                        apiMessages,
                        base64Files,
                    )
                    DebugLog.d(
                        "AgoraAPI",
                        "[$name] request transport=" +
                            "${if (config.responsesApiEnabled) "responses" else "chat"} " +
                            "model=${config.modelId} messages=${apiMessages.size} " +
                            "tools=${config.tools?.size ?: 0}",
                    )
                    // Opening the request can fail before any response headers exist (connect
                    // timeout, TLS failure, reset). Those escaped the retry loop entirely before,
                    // so a single flaky connection became a hard failure. Nothing has streamed at
                    // this point, so replaying is always safe.
                    val handle = try {
                        if (streamingRequest != null) {
                            HttpClient.streamPostBody(
                                endpointUrl,
                                streamingRequest.body,
                                headers,
                                streamingRequest.diagnosticJson,
                            )
                        } else {
                            HttpClient.streamPost(endpointUrl, requestBodyJson, headers)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val retryable = e.asRetryableTransportError()
                        if (retryable != null && attempt < maxAttempts) {
                            val retryDelayMs = retryDelayMillis(attempt)
                            DebugLog.w(
                                "AgoraAPI",
                                "[$name] Transport failure opening stream on attempt " +
                                    "$attempt/$maxAttempts (${e.javaClass.simpleName}), " +
                                    "retrying in ${retryDelayMs}ms",
                            )
                            emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                            delay(retryDelayMs)
                            retryScheduled = true
                            continue
                        }
                        throw e
                    }
                    try {
                        if (handle.code == 200) {
                            // HTTP 200 only proves the request was accepted. Whether the MESSAGE
                            // completed is decided from semantic markers below, so a relay that
                            // cuts the stream at a content-block boundary can no longer pass as a
                            // finished answer.
                            val termination = if (config.responsesApiEnabled) {
                                consumeResponsesStream(handle, config) { emit(it) }
                            } else {
                                consumeSuccessfulStream(handle, config) { emit(it) }
                            }
                            DebugLog.d(
                                "AgoraSSE",
                                "[$name] stream_end ${termination.describe()} " +
                                    "attempt=$attempt/$maxAttempts",
                            )
                            if (termination.isRetryable && attempt < maxAttempts) {
                                // Nothing was surfaced yet, so a replay cannot duplicate output.
                                DebugLog.w(
                                    "AgoraAPI",
                                    "[$name] Incomplete stream on attempt $attempt/$maxAttempts, retrying",
                                )
                                val retryDelayMs = retryDelayMillis(attempt)
                                emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                                delay(retryDelayMs)
                                retryScheduled = true
                            } else {
                                termination.toError(name)?.let { emit(StreamEvent.Error(it)) }
                                finished = true
                            }
                        } else {
                            val errorRaw = handle.errorBody.orEmpty()
                            val hasV1Fallback = endpointIndex + 1 < endpointUrls.size
                            if (hasV1Fallback) {
                                DebugLog.w(
                                    "AgoraAPI",
                                    "[$name] HTTP ${handle.code}; trying endpoint candidate " +
                                        "${endpointIndex + 2}/${endpointUrls.size}",
                                )
                                endpointIndex++
                                continue
                            }

                            val responseBytes = errorRaw.toByteArray(Charsets.UTF_8).size
                            DebugLog.e(
                                "AgoraAPI",
                                "[$name] HTTP ${handle.code} responseBytes=$responseBytes",
                            )

                            if (
                                ProviderRetryPolicy.shouldRetryHttp(
                                    handle.code,
                                    errorRaw,
                                    retryableStatusCodes,
                                ) && attempt < maxAttempts
                            ) {
                                val retryDelayMs = retryDelayMillis(attempt)
                                DebugLog.w("AgoraAPI", "[$name] Transient error ${handle.code} on attempt $attempt/$maxAttempts, retrying in ${retryDelayMs}ms...")
                                emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                                delay(retryDelayMs)
                                retryScheduled = true
                            } else {
                                emit(StreamEvent.Error(buildGenerationError(handle.code, errorRaw, endpointUrls)))
                                finished = true
                            }
                        }
                    } finally {
                        handle.close()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RequestFormatException) {
            DebugLog.e("AgoraAPI", "[$name] blocked invalid request: ${e.violations.joinToString()}")
            emit(StreamEvent.Error(GenerationError.RequestFormat(name, e.violations.joinToString())))
        } catch (e: SocketTimeoutException) {
            emit(StreamEvent.Error(GenerationError.Timeout))
        } catch (e: ConnectException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Connection refused")))
        } catch (e: UnknownHostException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Unknown host")))
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error(GenerationError.Unknown(e)))
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun consumeResponsesStream(
        handle: HttpClient.StreamHandle,
        config: ProviderConfig,
        emit: suspend (StreamEvent) -> Unit,
    ): StreamTermination {
        val router = OpenAiResponsesEventRouter(json)
        var producedContent = false
        var reportedError = false
        var streamError: GenerationError? = null
        var responseBodyReadError: GenerationError? = null
        var timedOut = false
        var consecutiveReadTimeouts = 0

        while (currentCoroutineContext().isActive) {
            val line = try {
                handle.readLine()
            } catch (error: SocketTimeoutException) {
                if (!currentCoroutineContext().isActive) break
                if (++consecutiveReadTimeouts >= 3) {
                    timedOut = true
                    break
                }
                continue
            } catch (error: Exception) {
                if (!currentCoroutineContext().isActive) break
                responseBodyReadError = error.asRetryableResponseBodyReadError() ?: throw error
                break
            } ?: break
            consecutiveReadTimeouts = 0
            if (!line.startsWith("data: ")) continue
            val payload = line.substring(6).trim()
            if (payload == "[DONE]") break

            val routed = try {
                router.route(json.decodeFromString<OpenAiResponseStreamEvent>(payload))
            } catch (error: Exception) {
                DebugLog.e(
                    "AgoraAPI",
                    "[$name] malformed Responses payload exception=${error.javaClass.simpleName}",
                )
                streamError = GenerationError.SseParse(
                    rawLine = payload.take(512),
                    cause = error.localizedMessage ?: "Malformed Responses SSE payload",
                )
                emptyList()
            }
            routed.forEach { event ->
                if (event is StreamEvent.Error && event.error is GenerationError.SseParse) {
                    streamError = event.error
                } else {
                    if (event.carriesModelOutput()) producedContent = true
                    if (event is StreamEvent.Error) reportedError = true
                    emit(event)
                }
            }
            if (
                streamError != null || reportedError || router.streamError != null ||
                router.sawTerminalMarker
            ) break
        }

        if (!currentCoroutineContext().isActive) {
            throw CancellationException("Responses stream cancelled")
        }
        return StreamTermination(
            sawTerminalMarker = router.sawTerminalMarker,
            stopReason = router.stopReason,
            producedContent = producedContent,
            toolCallInFlight = router.toolCallInFlight,
            streamError = responseBodyReadError ?: streamError ?: router.streamError,
            alreadyReportedError = reportedError,
            timedOut = timedOut,
            retryableStreamError = responseBodyReadError != null || !router.sawTerminalMarker,
        )
    }

    /**
     * Consume one 200 SSE stream and report HOW it ended.
     *
     * The returned [StreamTermination] is the only evidence that the message actually completed:
     * socket EOF is produced identically by a clean finish and by a relay cutting the connection
     * between content blocks. Fatal errors the consumer itself raises are flagged so the caller
     * does not report a second, contradictory diagnostic.
     */
    private suspend fun consumeSuccessfulStream(
        handle: HttpClient.StreamHandle,
        config: ProviderConfig,
        emit: suspend (StreamEvent) -> Unit
    ): StreamTermination {
        val pendingToolCalls = mutableMapOf<Int, PendingToolCall>()
        var producedContent = false
        var reportedError = false
        var streamError: GenerationError? = null
        var finishReason: String? = null
        var sawDone = false
        var timedOut = false
        val emitTracked: suspend (StreamEvent) -> Unit = { event ->
            if (event.carriesModelOutput()) producedContent = true
            if (event is StreamEvent.Error) reportedError = true
            emit(event)
        }
        var terminalDeadlineNanos: Long? = null

        suspend fun emitPendingStructuredToolCalls() {
            if (pendingToolCalls.isEmpty()) return
            val pending = pendingToolCalls.values.toList()
            pendingToolCalls.clear()
            val incomplete = pending.firstOrNull { candidate ->
                val callId = candidate.id.ifBlank { candidate.streamKey }
                !callId.matches(safeWireToolCallId) ||
                    !candidate.name.matches(safeWireToolName) || runCatching {
                    json.parseToJsonElement(candidate.args.toString().ifBlank { "{}" }) is
                        kotlinx.serialization.json.JsonObject
                }.getOrDefault(false).not()
            }
            val callIds = pending.map { candidate ->
                candidate.id.ifBlank { candidate.streamKey }
            }
            if (incomplete != null || callIds.distinct().size != callIds.size) {
                emitTracked(
                    StreamEvent.Error(
                        GenerationError.SseParse(
                            rawLine = "tool_calls",
                            cause = when {
                                callIds.distinct().size != callIds.size ->
                                    "Provider returned duplicate tool call ids"
                                incomplete == null -> "Provider returned incomplete tool metadata"
                                !incomplete.name.matches(safeWireToolName) ->
                                    "Provider ended before the tool name was complete"
                                !incomplete.id.ifBlank { incomplete.streamKey }
                                    .matches(safeWireToolCallId) ->
                                    "Provider returned an invalid tool call id"
                                else ->
                                    "Provider ended before the tool arguments formed a complete JSON object"
                            },
                        )
                    )
                )
                return
            }
            val calls = pending.map {
                    StreamEvent.ToolCallRequest(
                        id = it.id.ifBlank { it.streamKey },
                        name = it.name,
                        arguments = it.args.toString().ifBlank { "{}" },
                        streamKey = it.streamKey,
                    )
                }
            if (calls.size == 1) emitTracked(calls.first())
            else emitTracked(StreamEvent.ToolCallsRequest(calls))
        }

        // Read timeouts are tolerated for long thinking pauses (read timeout = 5 min), but a
        // silently-dead connection (NAT drop with no RST) must not hang forever: give up after
        // 3 consecutive timeouts (~15 min without a single byte).
        var consecutiveReadTimeouts = 0
        while (currentCoroutineContext().isActive) {
            terminalDeadlineNanos?.let { deadline ->
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) break
                val remainingMillis =
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                        .coerceAtLeast(1L)
                handle.setReadTimeoutMillis(remainingMillis)
            }
            val line = try {
                handle.readLine()
            } catch (e: SocketTimeoutException) {
                if (!currentCoroutineContext().isActive) break
                // A semantic terminal event already completed the model response. This timeout
                // only closes its optional usage/[DONE] grace tail and is therefore success.
                if (terminalDeadlineNanos != null) break
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
                emitPendingStructuredToolCalls()
                break
            }

            try {
                val response = json.decodeFromString<OpenAiStreamResponse>(jsonStr)

                // A relay signals a mid-stream failure as a bare {"error":{...}} chunk on a 200
                // response. Previously ignoreUnknownKeys discarded it and the generation just
                // stopped with no diagnostic at all.
                response.error?.let { error ->
                    streamError = GenerationError.Api(
                        code = error.code,
                        type = error.type,
                        message = error.message.ifBlank {
                            "Provider reported an error in the response stream"
                        },
                    )
                }
                if (
                    streamError == null &&
                    ProviderRetryPolicy.isFailedToGenerateOutcome(response.outcome)
                ) {
                    streamError = GenerationError.Api(
                        code = null,
                        type = "failed_to_generate",
                        message = response.outcome.orEmpty(),
                    )
                }

                val choice = response.choices?.firstOrNull()

                choice?.delta?.let { delta ->
                    parseDeltaContent(delta, config, emitTracked)

                    delta.toolCalls?.forEach { tc ->
                        val existingEntry = tc.id?.let { id ->
                            pendingToolCalls.entries.firstOrNull { it.value.id == id }
                        }
                        val index = tc.index
                            ?: existingEntry?.key
                            ?: pendingToolCalls.keys.singleOrNull()
                            ?: pendingToolCalls.size
                        val pending = pendingToolCalls.getOrPut(index) { PendingToolCall() }
                        tc.id?.takeIf(String::isNotBlank)?.let { pending.id = it }
                        tc.function?.name?.takeIf(String::isNotBlank)?.let { pending.name = it }
                        tc.function?.arguments?.let {
                            // Snapshot-tolerant: a relay that resends the whole argument string in
                            // every delta must not produce `{"a":1}{"a":1}`, and an empty
                            // placeholder delta must not erase what was already accumulated.
                            pending.args.append(
                                if (it is JsonPrimitive) it.content else it.toString()
                            )
                        }
                        emitTracked(
                            StreamEvent.ToolCallUpdate(
                                streamKey = pending.streamKey,
                                id = pending.id.ifBlank { null },
                                name = pending.name,
                                arguments = pending.args.toString(),
                            )
                        )
                    }
                }

                choice?.finishReason?.takeIf(String::isNotBlank)?.let {
                    finishReason = it.lowercase()
                }

                if (!choice?.finishReason.isNullOrBlank()) {
                    // Several OpenAI-compatible gateways return "stop" (or close directly)
                    // after streaming a perfectly valid structured tool call. The accumulated
                    // call is authoritative; never discard it based on the terminal spelling.
                    emitPendingStructuredToolCalls()
                }

                response.usage?.let { usage ->
                    emitTracked(StreamEvent.UsageUpdate(usage.toTokenUsage()))
                }

                if (streamError != null) break

                if (!choice?.finishReason.isNullOrBlank() && terminalDeadlineNanos == null) {
                    terminalDeadlineNanos =
                        System.nanoTime() +
                            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                                terminalSseGraceMillis.coerceAtLeast(1L)
                            )
                }
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

        // Do not promote an open structured call at transport EOF. Only [DONE] or finish_reason
        // can prove that its metadata is complete; leaving it pending makes the termination error
        // carry toolCallInFlight=true and prevents execution of a truncated invocation.

        if (!currentCoroutineContext().isActive) {
            throw CancellationException("Stream cancelled")
        }

        return StreamTermination(
            // Either signal proves the message ended semantically. Many gateways omit one of them,
            // so requiring both would report false truncations.
            sawTerminalMarker = sawDone || finishReason != null,
            stopReason = finishReason,
            producedContent = producedContent,
            toolCallInFlight = pendingToolCalls.isNotEmpty(),
            streamError = streamError,
            alreadyReportedError = reportedError,
            timedOut = timedOut,
        )
    }

    private fun buildGenerationError(
        statusCode: Int,
        errorRaw: String,
        endpointUrls: List<String>
    ): GenerationError {
        val endpointHint = if (statusCode == 404 && endpointUrls.size > 1) {
            "\nTried ${endpointUrls.joinToString(" and ")}. OpenAI-compatible servers often require a /v1 Base URL."
        } else {
            ""
        }
        val error = providerHttpError(statusCode, errorRaw)
        return if (endpointHint.isEmpty()) {
            error
        } else {
            error.copy(message = error.message + endpointHint)
        }
    }

    private fun fetchModelPages(
        endpointUrl: String,
        headers: Map<String, String>,
    ): List<String> {
        val modelIds = linkedSetOf<String>()
        val seenCursors = mutableSetOf<String>()
        var pageUrl = endpointUrl

        repeat(MAX_MODEL_LIST_PAGES) { pageIndex ->
            val page = try {
                val responseText = HttpClient.fetchModelsResponse(pageUrl, headers)
                    .requireModelFetchBody()
                decodeModelFetchResponse {
                    json.decodeFromString<OpenAiModelListResponse>(responseText)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (pageIndex == 0) throw error
                DebugLog.w(
                    "AgoraAPI",
                    "Stopped paginating $name models after $pageIndex completed pages; " +
                        "returning ${modelIds.size} models",
                )
                return modelIds.sorted()
            }

            modelIds += page.data.map { it.id }
            if (!page.hasMore) return modelIds.sorted()

            val cursor = page.lastId?.takeIf(String::isNotBlank)
                ?: page.data.lastOrNull()?.id?.takeIf(String::isNotBlank)
            if (cursor == null) {
                DebugLog.w(
                    "AgoraAPI",
                    "$name model list reported has_more without a usable cursor; " +
                        "returning ${modelIds.size} models",
                )
                return modelIds.sorted()
            }
            if (!seenCursors.add(cursor)) {
                DebugLog.w(
                    "AgoraAPI",
                    "$name model list repeated a cursor; " +
                        "returning ${modelIds.size} models",
                )
                return modelIds.sorted()
            }

            pageUrl = nextOpenAiModelPageUrl(endpointUrl, cursor)
        }

        DebugLog.w(
            "AgoraAPI",
            "$name model list exceeded $MAX_MODEL_LIST_PAGES pages; " +
                "returning ${modelIds.size} models",
        )
        return modelIds.sorted()
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = withContext(Dispatchers.IO) {
        val effectiveBaseUrl = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val endpointUrls = openAiEndpointCandidates(
            effectiveBaseUrl,
            "models",
            retryMissingV1BaseUrl,
        )
        val headers = openAiAuthHeaders(apiKey)
        var lastFailure: Exception? = null

        for ((index, endpointUrl) in endpointUrls.withIndex()) {
            try {
                val models = fetchModelPages(endpointUrl, headers)
                if (models.isEmpty()) throw ModelFetchEmptyResultException()
                return@withContext models
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastFailure = error
                if (index < endpointUrls.lastIndex) {
                    DebugLog.w(
                        "AgoraAPI",
                        "Failed to fetch $name models; trying endpoint candidate " +
                            "${index + 2}/${endpointUrls.size}",
                    )
                }
            }
        }

        val failure = lastFailure ?: ModelFetchEmptyResultException()
        DebugLog.e(
            "AgoraAPI",
            "Failed to fetch $name models exception=${failure.javaClass.simpleName}",
        )
        throw failure
    }

    private companion object {
        const val MAX_MODEL_LIST_PAGES = 50
    }
}
