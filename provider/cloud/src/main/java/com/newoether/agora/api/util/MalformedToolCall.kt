package com.newoether.agora.api.util

import com.newoether.agora.api.StreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Reserved name for a tool call the model emitted in a form that cannot be executed or paired:
 * an invalid or duplicate id, a missing or invalid tool name, arguments that are not a JSON
 * object, or a text tool payload that could not be parsed.
 *
 * Instead of failing the whole run, the damaged call is replaced by one pairable call under this
 * name. The tool executor never runs it; it answers with an error result that explains what was
 * wrong, so the model sees the failure in its own context and can re-issue a correct call.
 */
const val MALFORMED_TOOL_CALL_NAME = "agora_malformed_tool_call"

private const val ORIGINAL_NAME_MAX_CHARS = 200
private const val ORIGINAL_ARGUMENTS_MAX_CHARS = 2_000

/** Builds the pairable stand-in for a damaged call. [cause] is shown to the model verbatim. */
fun malformedToolCallRequest(
    cause: String,
    originalName: String? = null,
    originalArguments: String? = null,
    streamKey: String? = null,
    /** Provider replay metadata (for example a Gemini thought signature) the stand-in must keep. */
    signature: String? = null,
): StreamEvent.ToolCallRequest {
    val suffix = UUID.randomUUID().toString().replace("-", "")
    val arguments = buildJsonObject {
        put("error", cause)
        originalName?.takeIf { it.isNotEmpty() }?.let {
            put("original_name", it.take(ORIGINAL_NAME_MAX_CHARS))
        }
        originalArguments?.takeIf { it.isNotEmpty() }?.let {
            put("original_arguments", it.take(ORIGINAL_ARGUMENTS_MAX_CHARS))
        }
    }
    return StreamEvent.ToolCallRequest(
        id = "call_malformed_$suffix",
        name = MALFORMED_TOOL_CALL_NAME,
        arguments = arguments.toString(),
        signature = signature,
        streamKey = streamKey?.takeIf { it.isNotBlank() } ?: "malformed_tool_$suffix",
    )
}

/** The error result the model receives for a [MALFORMED_TOOL_CALL_NAME] call. */
fun malformedToolCallResultText(arguments: String): String {
    val fields = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
    fun field(key: String) = (fields?.get(key) as? JsonPrimitive)?.content
    return buildString {
        append("Error: the previous tool call was malformed and was not executed")
        field("error")?.let { append(". Cause: ").append(it) }
        field("original_name")?.let { append(". Tool name as sent: ").append(it) }
        field("original_arguments")?.let { append(". Arguments as sent: ").append(it) }
        append(
            ". Re-issue the call with one of the offered tool names and a complete JSON object " +
                "as arguments. If the arguments were cut off, send a smaller payload.",
        )
    }
}
