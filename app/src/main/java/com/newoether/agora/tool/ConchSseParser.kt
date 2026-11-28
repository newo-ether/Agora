package com.newoether.agora.tool

import com.newoether.agora.util.SHELL_COMMAND_OUTPUT_MAX_BYTES
import com.newoether.agora.util.shellUtf8Prefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal data class ConchSseResult(
    val output: String,
    val exitCode: Int?,
    val errorMessage: String?,
    val timedOut: Boolean,
    val warningMessage: String?,
    /** Set when the foreground wait budget was spent: the command keeps running as this job. */
    val backgroundedJobId: String? = null,
)

/**
 * Strictly parses Conch's one-event/one-data SSE protocol.
 *
 * Streaming output is intentionally emitted before the terminal frame arrives, but malformed,
 * unknown, incomplete, duplicate-terminal, and post-terminal input fails closed. Both retained
 * and emitted output share the same byte budget.
 */
internal suspend fun parseConchSseLines(
    encrypted: Boolean,
    readLine: () -> String?,
    decrypt: (String) -> String,
    onOutput: suspend (String) -> Unit,
): ConchSseResult {
    val output = StringBuilder()
    var outputBytes = 0
    var exitCode: Int? = null
    var errorMessage: String? = null
    var timedOut = false
    var warningMessage: String? = null
    var backgroundedJobId: String? = null
    var outputTruncationReported = false
    var currentEvent: String? = null
    var terminalSeen = false
    var expectedSequence = 0L

    fun fail(reason: String): Nothing =
        throw IllegalStateException("Invalid Conch SSE stream: $reason")

    fun recordWarning(message: String) {
        val bounded = message.take(MAX_WARNING_CHARS)
        warningMessage = when (val existing = warningMessage) {
            null -> bounded
            else -> (existing + "\n" + bounded).take(MAX_WARNING_CHARS)
        }
    }

    fun recordOutputTruncation() {
        if (outputTruncationReported) return
        outputTruncationReported = true
        val notice =
            "Conch output was truncated at $SHELL_COMMAND_OUTPUT_MAX_BYTES UTF-8 bytes."
        warningMessage = when (val existing = warningMessage) {
            null -> notice
            else -> (notice + "\n" + existing).take(MAX_WARNING_CHARS)
        }
    }

    while (currentCoroutineContext().isActive) {
        val line = readLine() ?: break
        when {
            line.isEmpty() -> {
                if (currentEvent != null) fail("event $currentEvent has no data frame")
            }
            line.startsWith("event: ") -> {
                if (terminalSeen) fail("event received after terminal frame")
                if (currentEvent != null) fail("event $currentEvent has no data frame")
                val event = line.substring(7).trim()
                if (event !in SUPPORTED_EVENTS) fail("unsupported event $event")
                currentEvent = event
            }
            line.startsWith("data: ") -> {
                if (terminalSeen) fail("data received after terminal frame")
                val event = currentEvent ?: fail("data frame has no event")
                currentEvent = null
                var dataText = line.substring(6).trim()
                if (encrypted) {
                    dataText = try {
                        decrypt(dataText)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        fail(
                            "decryption failed: " +
                                (error.message ?: error.javaClass.simpleName),
                        )
                    }
                }
                val data = try {
                    Json.parseToJsonElement(dataText).jsonObject
                } catch (_: Exception) {
                    fail("event $event contains invalid JSON")
                }
                if (encrypted) {
                    val sequence = data["_conch_sequence"] as? JsonPrimitive
                    if (data.requiredString("_conch_event", event) != event ||
                        sequence == null || sequence.isString ||
                        sequence.content.toLongOrNull() != expectedSequence ||
                        expectedSequence == Long.MAX_VALUE
                    ) fail("event authentication or sequence mismatch")
                    expectedSequence += 1
                }
                when (event) {
                    "line" -> {
                        val text = data.requiredString("line", event, allowBlank = true)
                        val stream = data.requiredString("stream", event)
                        if (stream !in OUTPUT_STREAMS) {
                            fail("event line has unsupported stream $stream")
                        }
                        val rawDelta = "$text\n"
                        val prefix = rawDelta.shellUtf8Prefix(
                            SHELL_COMMAND_OUTPUT_MAX_BYTES - outputBytes,
                        )
                        if (prefix.isNotEmpty()) {
                            output.append(prefix)
                            outputBytes += prefix.toByteArray(Charsets.UTF_8).size
                            onOutput(prefix)
                        }
                        if (prefix.length < rawDelta.length) recordOutputTruncation()
                    }
                    "warning" -> {
                        recordWarning(data.requiredString("message", event))
                    }
                    "result" -> {
                        exitCode = data.requiredInt("exit_code", event)
                        terminalSeen = true
                    }
                    "error" -> {
                        errorMessage = data.requiredString("message", event)
                        timedOut = data.optionalBoolean("timed_out", event) ?: false
                        terminalSeen = true
                    }
                    "backgrounded" -> {
                        // Terminal for the stream, not for the process: the command keeps running
                        // as a durable job and the client tracks it through the job endpoints.
                        backgroundedJobId = data.requiredString("job_id", event)
                        terminalSeen = true
                    }
                }
            }
            else -> fail("unexpected line")
        }
    }

    currentCoroutineContext().ensureActive()
    if (currentEvent != null) fail("event $currentEvent has no data frame")
    if (!terminalSeen) fail("stream ended without a terminal result or error")

    return ConchSseResult(
        output = output.toString().removeSuffix("\n"),
        exitCode = exitCode,
        errorMessage = errorMessage,
        timedOut = timedOut,
        warningMessage = warningMessage,
        backgroundedJobId = backgroundedJobId,
    )
}

private fun JsonObject.requiredString(
    key: String,
    event: String,
    allowBlank: Boolean = false,
): String {
    val value = this[key] as? JsonPrimitive
    if (value == null || !value.isString || (!allowBlank && value.content.isBlank())) {
        throw IllegalStateException("Invalid Conch SSE stream: event $event requires string $key")
    }
    return value.content
}

private fun JsonObject.requiredInt(key: String, event: String): Int {
    val value = this[key] as? JsonPrimitive
    if (value == null || value.isString) {
        throw IllegalStateException("Invalid Conch SSE stream: event $event requires integer $key")
    }
    return value.content.toIntOrNull()
        ?: throw IllegalStateException(
            "Invalid Conch SSE stream: event $event requires integer $key",
        )
}

private fun JsonObject.optionalBoolean(key: String, event: String): Boolean? {
    val value = this[key] ?: return null
    val primitive = value as? JsonPrimitive
    if (primitive == null || primitive.isString) {
        throw IllegalStateException("Invalid Conch SSE stream: event $event requires boolean $key")
    }
    return primitive.content.toBooleanStrictOrNull()
        ?: throw IllegalStateException(
            "Invalid Conch SSE stream: event $event requires boolean $key",
        )
}

private val SUPPORTED_EVENTS = setOf("line", "warning", "result", "error", "backgrounded")
private val OUTPUT_STREAMS = setOf("stdout", "stderr")
private const val MAX_WARNING_CHARS = 8_192
