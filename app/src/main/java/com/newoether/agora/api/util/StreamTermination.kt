package com.newoether.agora.api.util

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.StreamEvent

/**
 * How a streaming provider response actually ended.
 *
 * Transport EOF proves nothing on its own. A relay that closes the connection at a content-block
 * boundary leaves the socket in exactly the same state as a clean finish, which is how a
 * `tool_use` block that was about to start disappears while the text portion looks complete.
 * Completion must be proven by a SEMANTIC terminal marker: `message_stop` / `stop_reason` for
 * Anthropic, `finish_reason` / `[DONE]` for OpenAI-compatible endpoints.
 *
 * Treating HTTP 200 response headers as success — the previous behaviour — makes a mid-stream
 * truncation indistinguishable from a completed answer, so a truncated turn was persisted as a
 * successful one and the retry loop never saw it.
 */
internal data class StreamTermination(
    /** A semantic end-of-message signal arrived, not merely socket EOF. */
    val sawTerminalMarker: Boolean,
    /** Provider-reported reason, normalized to lowercase. Null when never reported. */
    val stopReason: String?,
    /** Answer text, thinking, or a tool-call event reached the consumer. */
    val producedContent: Boolean,
    /** A tool-use block was still open when the stream ended. */
    val toolCallInFlight: Boolean,
    /**
     * A failure the provider delivered inside the 200 stream (`event: error`, or a bare
     * `{"error":{...}}` chunk). Held rather than emitted so the retry decision can still be made.
     */
    val streamError: GenerationError? = null,
    /** The consumer already surfaced a fatal [StreamEvent.Error] for this attempt. */
    val alreadyReportedError: Boolean = false,
    /** Reads stopped because the socket went silent past the read-timeout budget. */
    val timedOut: Boolean = false,
    /** Whether an in-stream API error is safe to replay. Explicit provider terminal failures are not. */
    val retryableStreamError: Boolean = true,
) {
    val truncatedByTokenCap: Boolean get() = stopReason in TOKEN_CAP_REASONS

    /**
     * Replaying is safe only while nothing has been surfaced: retrying after partial output would
     * duplicate visible content and bill the same completion twice. A stream that produced nothing
     * is indistinguishable from a dropped connection, so retrying it costs nothing.
     *
     * A token-cap stop is never retried — the same request would truncate at the same place.
     */
    val isRetryable: Boolean
        get() = !producedContent &&
            !alreadyReportedError &&
            !truncatedByTokenCap &&
            (timedOut ||
                streamError != null &&
                (retryableStreamError || ProviderRetryPolicy.shouldRetryStreamError(streamError)) ||
                toolCallInFlight || !sawTerminalMarker)

    /** Terminal diagnostic to surface, or null when the stream ended cleanly. */
    fun toError(provider: String): GenerationError? = when {
        alreadyReportedError -> null
        streamError != null -> streamError
        timedOut -> GenerationError.Timeout
        truncatedByTokenCap -> GenerationError.OutputTruncated(provider, stopReason)
        toolCallInFlight || !sawTerminalMarker -> GenerationError.IncompleteStream(
            provider = provider,
            stopReason = stopReason,
            toolCallInFlight = toolCallInFlight,
            producedContent = producedContent,
        )
        else -> null
    }

    fun describe(): String =
        "stop_reason=$stopReason terminal_marker=$sawTerminalMarker " +
            "produced_content=$producedContent tool_in_flight=$toolCallInFlight " +
            "timed_out=$timedOut stream_error=${streamError != null} " +
            "reported_error=$alreadyReportedError retryable=$isRetryable"

    private companion object {
        /**
         * Output-cap spellings across protocols: Anthropic `max_tokens`, OpenAI `length`,
         * Gemini-style `max_output_tokens`.
         */
        val TOKEN_CAP_REASONS = setOf("max_tokens", "length", "max_output_tokens")
    }
}

/**
 * Maps a transport exception raised while OPENING the request to a retryable error, or null when
 * replaying cannot help.
 *
 * These are thrown by `call.execute()` before any response headers exist, so they escaped the
 * providers' retry loops entirely and surfaced as a hard failure on the first flaky connection.
 * Nothing has been streamed at that point, so a replay is always safe.
 *
 * [java.net.UnknownHostException] is deliberately excluded: it almost always means a wrong base URL
 * or absent connectivity, and retrying only delays a diagnostic the user needs to see.
 */
internal fun Throwable.asRetryableTransportError(): GenerationError? = when (this) {
    // SocketTimeoutException extends InterruptedIOException, so the order of these matters.
    is java.net.SocketTimeoutException -> GenerationError.Timeout
    is java.io.InterruptedIOException -> GenerationError.Timeout
    is java.net.ConnectException ->
        GenerationError.Network(statusCode = 0, message = localizedMessage ?: "Connection refused")
    is java.net.SocketException ->
        GenerationError.Network(statusCode = 0, message = localizedMessage ?: "Connection reset")
    is javax.net.ssl.SSLException ->
        GenerationError.Network(statusCode = 0, message = localizedMessage ?: "TLS failure")
    is java.net.UnknownHostException -> null
    else -> matchingFailureMessage(ProviderRetryPolicy::isRetryableUpstreamFailure)?.let { message ->
        GenerationError.Network(statusCode = 0, message = message)
    }
}

/** A matching read failure after response headers re-enters the existing pre-output retry gate. */
internal fun Throwable.asRetryableResponseBodyReadError(): GenerationError? =
    matchingFailureMessage(ProviderRetryPolicy::isUnreadableServerResponseFailure)?.let { message ->
        GenerationError.Network(statusCode = 0, message = message)
    }

private fun Throwable.matchingFailureMessage(
    matches: (String?) -> Boolean,
): String? {
    val visited = mutableSetOf<Throwable>()
    var failure: Throwable? = this
    var matchedMessage: String? = null
    while (failure != null && visited.add(failure)) {
        if (failure is java.net.UnknownHostException) return null
        if (matchedMessage == null && matches(failure.localizedMessage)) {
            matchedMessage = failure.localizedMessage
        }
        failure = failure.cause
    }
    return matchedMessage
}

/**
 * True for events carrying model output the user can already see. Used to decide whether a replay
 * would duplicate content; usage / retry / error bookkeeping does not count.
 */
internal fun StreamEvent.carriesModelOutput(): Boolean = when (this) {
    is StreamEvent.TextChunk -> text.isNotEmpty()
    is StreamEvent.ImageChunk -> bytes.isNotEmpty()
    is StreamEvent.CitationUpdate -> false
    is StreamEvent.ThoughtChunk -> thought.isNotEmpty() || signature != null
    is StreamEvent.HostedToolCallUpdate -> true
    is StreamEvent.ToolCallUpdate -> true
    is StreamEvent.ToolCallRequest -> true
    is StreamEvent.ToolCallsRequest -> true
    is StreamEvent.UsageUpdate -> false
    is StreamEvent.Retrying -> false
    is StreamEvent.Error -> false
}
