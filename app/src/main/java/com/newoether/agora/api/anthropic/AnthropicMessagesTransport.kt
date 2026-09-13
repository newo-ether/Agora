package com.newoether.agora.api.anthropic

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.providerHttpError
import com.newoether.agora.api.util.ProviderRetryPolicy
import com.newoether.agora.api.util.RequestFormatException
import com.newoether.agora.api.util.StreamTermination
import com.newoether.agora.api.util.asRetryableResponseBodyReadError
import com.newoether.agora.api.util.asRetryableTransportError
import com.newoether.agora.api.util.carriesModelOutput
import com.newoether.agora.api.util.requireValidSerializedRequest
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json

internal val anthropicProtocolJson =
    Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/**
 * Owns one Anthropic Messages request/stream lifecycle: request validation, transport retry,
 * SSE consumption, and the semantic termination proof for the returned message.
 *
 * The caller owns the protocol-independent request plan (model generation, thinking, tools) and
 * supplies it through [buildRequest], which is invoked once per attempt so a retry re-resolves a
 * time-dependent request exactly like the initial dispatch.
 */
internal suspend fun streamAnthropicMessages(
    providerName: String,
    url: String,
    apiKey: String,
    buildRequest: suspend () -> AnthropicRequest,
    emit: suspend (StreamEvent) -> Unit,
) {
    try {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "x-api-key" to apiKey,
            "anthropic-version" to "2023-06-01",
        )
        val maxAttempts = ProviderRetryPolicy.MAX_ATTEMPTS
        val retryableCodes = setOf(429, 502, 503, 504)
        var attempt = 0
        var done = false

        while (attempt < maxAttempts && !done) {
            attempt++
            val requestBody = buildRequest()
            requestBody.requireValidWireFormat()
            val requestBodyJson = anthropicProtocolJson.encodeToString(
                AnthropicRequest.serializer(),
                requestBody,
            )
            requireValidSerializedRequest(
                provider = providerName,
                body = requestBodyJson,
                requiredStringFields = setOf("model"),
                requiredArrayFields = setOf("messages"),
            )
            DebugLog.d(
                "AgoraAPI",
                "[$providerName] request model=${requestBody.model} " +
                    "messages=${requestBody.messages.size} " +
                    "thinking=${requestBody.thinking?.type ?: "omitted"} " +
                    "tools=${requestBody.tools?.size ?: 0}",
            )
            // Opening the request can fail before any response headers exist (connect
            // timeout, TLS failure, reset). Those escaped the retry loop entirely before, so a
            // single flaky connection became a hard failure. Nothing has streamed at this
            // point, so replaying is always safe.
            val handle = try {
                HttpClient.streamPost(url, requestBodyJson, headers)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val retryable = e.asRetryableTransportError()
                if (retryable != null && attempt < maxAttempts) {
                    DebugLog.w(
                        "AgoraAPI",
                        "[$providerName] Transport failure opening the stream on attempt " +
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
                                val event = anthropicProtocolJson
                                    .decodeFromString<AnthropicStreamEvent>(jsonStr)
                                eventRouter.route(event).forEach { routed ->
                                    emitTracked(routed)
                                }
                            } catch (e: Exception) {
                                DebugLog.e(
                                    "AgoraAPI",
                                    "[$providerName] malformed stream payload " +
                                        "exception=${e.javaClass.simpleName}",
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
                        "[$providerName] stream_end ${termination.describe()} " +
                        "tool_use_blocks=${eventRouter.toolUseBlockStarts} " +
                        "attempt=$attempt/$maxAttempts"
                    )

                    if (termination.isRetryable && attempt < maxAttempts) {
                        // Nothing was surfaced yet, so replaying cannot duplicate visible output.
                        DebugLog.w("AgoraAPI",
                            "[$providerName] Incomplete stream on attempt $attempt/$maxAttempts, retrying")
                        val retryDelayMs = ProviderRetryPolicy.delayMillis(attempt)
                        emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                        delay(retryDelayMs)
                    } else {
                        done = true
                        termination.toError(providerName)?.let { emit(StreamEvent.Error(it)) }
                    }
                } else {
                    val errorRaw = handle.errorBody.orEmpty()
                    val responseBytes = errorRaw.toByteArray(Charsets.UTF_8).size
                    DebugLog.e(
                        "AgoraAPI",
                        "[$providerName] HTTP ${handle.code} responseBytes=$responseBytes",
                    )

                    if (
                        ProviderRetryPolicy.shouldRetryHttp(
                            handle.code,
                            errorRaw,
                            retryableCodes,
                        ) && attempt < maxAttempts
                    ) {
                        val retryDelayMs = ProviderRetryPolicy.delayMillis(attempt)
                        DebugLog.w("AgoraAPI", "[$providerName] Transient error ${handle.code} on attempt $attempt/$maxAttempts, retrying in ${retryDelayMs}ms...")
                        emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                        delay(retryDelayMs)
                    } else {
                        done = true
                        val genError = providerHttpError(handle.code, errorRaw)
                        emit(StreamEvent.Error(genError))
                    }
                }
            } finally {
                handle.close()
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: RequestFormatException) {
        DebugLog.e(
            "AgoraAPI",
            "[$providerName] blocked invalid request: ${e.violations.joinToString()}",
        )
        emit(StreamEvent.Error(GenerationError.RequestFormat(providerName, e.violations.joinToString())))
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
}
