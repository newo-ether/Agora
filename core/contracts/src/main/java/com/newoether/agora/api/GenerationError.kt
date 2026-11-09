package com.newoether.agora.api

const val LOCAL_CONTEXT_CAPACITY_ERROR_CODE = "local_context_capacity"

/**
 * Typed error hierarchy for LLM generation failures.
 *
 * Replaces ad-hoc string-based error messages in StreamEvent.Error with
 * structured types that enable differentiated UI handling (retry actions,
 * error icons, recovery strategies) per error category.
 *
 * Phase 1b creates the type hierarchy. Phase 7 migrates all provider
 * emit sites from StreamEvent.Error(String) to StreamEvent.Error(GenerationError).
 */
sealed class GenerationError {

    /** HTTP-level error (connection refused, timeout, DNS failure, etc.). */
    data class Network(
        val statusCode: Int,
        val message: String
    ) : GenerationError()

    /** API-level error returned by the provider (invalid key, rate limit, server error). */
    data class Api(
        val code: String?,
        val type: String?,
        val message: String
    ) : GenerationError()

    /** Failed to parse a line from the SSE stream. */
    data class SseParse(
        val rawLine: String,
        val cause: String
    ) : GenerationError()

    /**
     * The model wrote a tool call that cannot be paired with a result: its syntax could not be
     * parsed, or its id or stream identity is missing, invalid or duplicated. Calls that are merely
     * wrong (an unoffered tool, bad arguments) are answered by the tool executor instead.
     */
    data class MalformedToolCall(
        val cause: String,
    ) : GenerationError()

    /**
     * The stream ended without a semantic terminal marker (`message_stop` / `finish_reason` /
     * `[DONE]`), so the response is provably incomplete rather than merely finished.
     *
     * The common shape is a relay closing the connection at a content-block boundary — right
     * where a `tool_use` block was about to begin — which stays invisible if HTTP 200 response
     * headers are treated as proof of success.
     */
    data class IncompleteStream(
        val provider: String,
        val stopReason: String?,
        val toolCallInFlight: Boolean,
        val producedContent: Boolean,
    ) : GenerationError()

    /**
     * The provider stopped because the output token cap was reached (`max_tokens` / `length`).
     * On thinking models that cap covers reasoning and answer together, so a large reasoning
     * budget can exhaust it exactly where a tool call would have started.
     */
    data class OutputTruncated(
        val provider: String,
        val stopReason: String?,
    ) : GenerationError()

    /** A tool execution failed (memory, web search, shell, RAG). */
    data class ToolExecution(
        val toolName: String,
        val arguments: String,
        val message: String
    ) : GenerationError()

    /** Image/video/PDF transcription failed. */
    data class Transcription(
        val imagePath: String,
        val message: String
    ) : GenerationError()

    /** Embedding computation failed. */
    data class Embedding(
        val modelId: String,
        val message: String
    ) : GenerationError()

    /** On-device GGUF model error (file not found, failed to load, etc.). */
    data class LocalModel(
        val message: String,
        val code: String? = null,
    ) : GenerationError()

    /** Missing or invalid configuration (no API key, no base URL, etc.). */
    data class Configuration(
        val message: String
    ) : GenerationError()

    /** The request was rejected locally before any network I/O because its shape was not valid. */
    data class RequestFormat(
        val provider: String,
        val details: String,
    ) : GenerationError()

    /** Wraps an unexpected exception. */
    data class Unknown(
        val cause: Throwable
    ) : GenerationError() {
        fun detail(): String {
            val visited = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<Throwable, Boolean>()
            )
            var current: Throwable? = cause
            while (current != null && visited.add(current)) {
                current.localizedMessage?.takeIf(String::isNotBlank)?.let { return it }
                current = current.cause
            }
            return cause.javaClass.name
        }
    }

    /** Generation was cancelled by the user. */
    object Cancelled : GenerationError()

    /** Request timed out waiting for a server response. */
    object Timeout : GenerationError()

    /** Human-readable message suitable for displaying in the UI. */
    fun userMessage(): String = when (this) {
        is Network -> when (statusCode) {
            401 -> "Authentication failed. Please check your API key."
            429 -> "Rate limit exceeded. Please wait and try again."
            in 500..599 -> "Server error ($statusCode). The service may be temporarily unavailable."
            else -> "Network error ($statusCode): $message"
        }
        is Api -> buildString {
            if (code != null) append("$code")
            if (type != null) append(" [$type]")
            if (isNotEmpty()) append(": ")
            append(message)
        }
        is SseParse -> "Failed to parse server response."
        is MalformedToolCall -> "The model returned a tool call that could not be used: $cause"
        is IncompleteStream -> buildString {
            append("$provider ended the response early")
            if (toolCallInFlight) append(" while a tool call was still being written")
            append(". ")
            append(
                if (stopReason == null) {
                    "No completion signal arrived, so the reply is incomplete."
                } else {
                    "The stream closed after stop_reason=$stopReason with no completion signal."
                }
            )
        }
        is OutputTruncated ->
            "Response hit the output token limit (stop_reason=${stopReason ?: "max_tokens"}) " +
                "and was cut off. Raise Max Tokens, or lower the thinking budget, then retry."
        is ToolExecution -> "Tool '$toolName' failed: $message"
        is Transcription -> "Image transcription failed: $message"
        is Embedding -> "Embedding failed: $message"
        is LocalModel -> message
        is Configuration -> message
        is RequestFormat -> "Request validation failed before sending ($provider): $details"
        is Unknown -> detail()
        Cancelled -> "Generation cancelled."
        Timeout -> "Request timed out."
    }
}
