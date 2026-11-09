package com.newoether.agora.api.util

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.util.NetworkRetryPolicy

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
     * transient failures also replace partial output; token-cap results are terminal.
     * Cancellation never reaches this decision.
     */
    val isRetryable: Boolean
        get() = !alreadyReportedError &&
            (ProviderRetryPolicy.requiresResponseReadRetry(streamError) ||
                (!truncatedByTokenCap &&
                    if (streamError == null) timedOut || toolCallInFlight || !sawTerminalMarker
                    else ProviderRetryPolicy.shouldRetryStreamError(streamError)))

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

/** Provider replay is owned by the provider loop, including failures while reading a body. */
internal fun Throwable.asRetryableTransportError(): GenerationError? = retryableNetworkError()

internal fun Throwable.asRetryableResponseBodyReadError(): GenerationError? = retryableNetworkError()

private fun Throwable.retryableNetworkError(): GenerationError? {
    NetworkRetryPolicy.throwIfCancelled(this)
    if (!NetworkRetryPolicy.isTransient(this)) {
        return NetworkRetryPolicy.causes(this).firstOrNull {
            ProviderRetryPolicy.isFailedToGenerateOutcome(it.message)
        }?.let { GenerationError.Network(0, it.message.orEmpty()) }
    }
    val chain = NetworkRetryPolicy.causes(this)
    val detail = chain.firstOrNull { ProviderRetryPolicy.isUnreadableServerResponseFailure(it.message) }
        ?: chain.firstOrNull { NetworkRetryPolicy.isTransientMessage(it.message) }
        ?: chain.last()
    return GenerationError.Network(
        statusCode = 0,
        message = detail.message?.takeIf { NetworkRetryPolicy.isTransientMessage(it) }
            ?: "The server response could not be read. (${detail.javaClass.simpleName})",
    )
}

/**
 * True for substantive model output. Formatting-only chunks keep the current pass empty; usage /
 * retry / error bookkeeping does not count. The provider uses this flag to roll back only the
 * failed attempt before replay, preserving earlier completed tool passes.
 */
fun StreamEvent.carriesModelOutput(): Boolean = when (this) {
    is StreamEvent.TextChunk -> text.isNotBlank()
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
