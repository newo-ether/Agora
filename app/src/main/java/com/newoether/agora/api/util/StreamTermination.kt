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
    /** Protocol/read failures may replay before output; API errors require shared classification. */
    val retryableStreamError: Boolean = true,
) {
    val truncatedByTokenCap: Boolean get() = stopReason in TOKEN_CAP_REASONS

    /**
     * Response-read failures always consume the existing retry budget. Their failed attempt is
     * explicitly replaced before replay, so prior successful tool passes stay intact. Other
     * failures retain the no-output and token-cap gates. Cancellation never reaches this decision.
     */
    val isRetryable: Boolean
        get() = !alreadyReportedError &&
            (ProviderRetryPolicy.requiresResponseReadRetry(streamError) ||
                (!producedContent && !truncatedByTokenCap &&
                    when (val error = streamError) {
                        is GenerationError.Api -> ProviderRetryPolicy.shouldRetryStreamError(error)
                        null -> timedOut || toolCallInFlight || !sawTerminalMarker
                        else -> retryableStreamError || ProviderRetryPolicy.shouldRetryStreamError(error)
                    }))

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
 * Ordinary [java.net.UnknownHostException] failures remain excluded because they usually mean a
 * wrong base URL or absent connectivity. The explicit unreadable-response outcome takes priority.
 */
internal fun Throwable.asRetryableTransportError(): GenerationError? {
    asUnreadableServerResponseError()?.let { return it }
    return when (this) {
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
        else -> asRetryableResponseBodyReadError()
            ?: matchingFailureMessage(ProviderRetryPolicy::isFailedToGenerateOutcome)?.let { message ->
                GenerationError.Network(statusCode = 0, message = message)
            }
    }
}

private fun Throwable.asUnreadableServerResponseError(): GenerationError? =
    matchingFailureMessage(
        ProviderRetryPolicy::isUnreadableServerResponseFailure,
        allowDns = true,
    )?.let { GenerationError.Network(statusCode = 0, message = it) }

/** Body reads retain exact unreadable-response priority before other transport classification. */
internal fun Throwable.asRetryableResponseBodyReadError(): GenerationError? =
    asUnreadableServerResponseError() ?: matchingFailureMessage(
        ProviderRetryPolicy::isUnreadableServerResponseFailure,
        includePrematureEof = true,
    )?.let { message ->
        GenerationError.Network(statusCode = 0, message = message)
    }

private fun Throwable.matchingFailureMessage(
    matches: (String?) -> Boolean,
    includePrematureEof: Boolean = false,
    allowDns: Boolean = false,
): String? {
    val visited = mutableSetOf<Throwable>()
    var failure: Throwable? = this
    var matchedMessage: String? = null
    while (failure != null && visited.add(failure)) {
        if ((!allowDns && failure is java.net.UnknownHostException) ||
            failure is kotlinx.coroutines.CancellationException
        ) return null
        if (matchedMessage == null) {
            matchedMessage = when {
                includePrematureEof && failure is java.io.EOFException ->
                    failure.localizedMessage ?: "Unexpected end of stream (EOFException)"
                matches(failure.localizedMessage) -> failure.localizedMessage
                includePrematureEof && failure is java.io.IOException &&
                    ProviderRetryPolicy.isUnexpectedEndFailure(failure.localizedMessage) ->
                    failure.localizedMessage
                else -> null
            }
        }
        failure = failure.cause
    }
    return matchedMessage
}

/**
 * True for substantive model output. Formatting-only chunks keep the current pass empty; usage /
 * retry / error bookkeeping does not count. Tool events and thinking signatures prevent ordinary
 * replay; explicit response-read failures replace even those partially surfaced attempts.
 */
internal fun StreamEvent.carriesModelOutput(): Boolean = when (this) {
    is StreamEvent.TextChunk -> text.isNotBlank()
    is StreamEvent.ImageChunk -> bytes.isNotEmpty()
    is StreamEvent.CitationUpdate -> false
    is StreamEvent.ThoughtChunk -> thought.isNotBlank() || signature != null
    is StreamEvent.HostedToolCallUpdate -> true
    is StreamEvent.ToolCallUpdate -> true
    is StreamEvent.ToolCallRequest -> true
    is StreamEvent.ToolCallsRequest -> true
    is StreamEvent.UsageUpdate -> false
    is StreamEvent.Retrying -> false
    is StreamEvent.Error -> false
}
