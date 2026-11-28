package com.newoether.agora.tool
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.SHELL_FILE_EDIT_MAX_OPERATIONS
import com.newoether.agora.util.ShellFileEdit
import com.newoether.agora.util.ShellFileEditResult
import com.newoether.agora.util.ShellGrepResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
internal fun parseToolArgs(arguments: String): Map<String, JsonElement> {
    return try {
        val argsStr = arguments.ifBlank { "{}" }
        Json.decodeFromString<Map<String, JsonElement>>(argsStr)
    } catch (_: Exception) { emptyMap() }
}
internal fun jsonError(type: String, message: String, server: String? = null, command: String? = null): String {
    return buildJsonObject {
        if (type.isNotBlank()) put("type", type)
        put("error", "error"); put("message", message)
        if (server != null) put("server", server)
        if (command != null) put("command", command)
    }.toString()
}
/**
 * Backend file-walk failures arrive as raw runtime text (for example
 * "walk: context deadline exceeded"). The tool result carries one readable sentence instead; the
 * original goes to the debug log. Known patterns get a specific cause and remedy.
 */
internal fun readableFileToolFailure(tool: String, raw: String?): String {
    val detail = raw?.trim().orEmpty()
    if (detail.isNotEmpty()) {
        // Plain JVM unit tests have no android.util.Log; logging must never mask the result.
        runCatching { DebugLog.e("ShellToolProvider", "$tool failed: $detail") }
    }
    val lower = detail.lowercase()
    return when {
        "deadline exceeded" in lower || "timed out" in lower || "timeout" in lower ->
            "$tool failed: the server took too long to scan the files. " +
                "Narrow the path or glob, then retry."
        "permission denied" in lower ->
            "$tool failed: the server was not allowed to read part of that tree. " +
                "Pick a path it can read."
        else ->
            "$tool failed: an internal error interrupted the request. " +
                "Retry once; if it keeps failing, narrow the request."
    }
}
/** String value of [key]; missing, non-primitive and JSON null all read as "" (not "null"). */
internal fun arg(args: Map<String, JsonElement>, key: String): String {
    val value = args[key]
    if (value == null || value is JsonNull) return ""
    return (value as? JsonPrimitive)?.content ?: ""
}
internal fun boolArg(args: Map<String, JsonElement>, key: String): Boolean =
    (args[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true

/** Successful file_edit / file_multi_edit JSON; both share type "file_edit" for one detail view. */
internal fun fileEditSuccessJson(server: String, path: String, result: ShellFileEditResult, editCount: Int): String =
    buildJsonObject {
        put("type", "file_edit")
        put("server", server)
        put("path", path)
        put("replaced", result.replacements)
        if (editCount > 1) put("edits", editCount)
        if (result.sha256.isNotBlank()) put("sha256", result.sha256)
        if (result.snippets.isNotEmpty()) {
            putJsonArray("snippets") {
                result.snippets.forEach { snippet ->
                    add(buildJsonObject {
                        put("start_line", snippet.startLine)
                        putJsonArray("lines") { snippet.lines.forEach { add(JsonPrimitive(it)) } }
                    })
                }
            }
        }
        if (result.snippetsTruncated) put("snippets_truncated", true)
    }.toString()

/**
 * file_grep tool JSON; context arrays are omitted when empty.
 *
 * The result is stored and shown as JSON, and the generation pipeline clips stored results at
 * [maxLength]. A clipped envelope no longer parses, so the card and the detail sheet lose the match
 * list. Whole matches are dropped from the end instead, and `truncated` is set, so the JSON always
 * stays complete and within [maxLength].
 */
internal fun fileGrepJson(
    pattern: String,
    grep: ShellGrepResult,
    contextRequested: Boolean,
    maxLength: Int = Constants.MAX_TOOL_RESULT_LENGTH,
): String {
    val matches = grep.matches.map { m ->
        buildJsonObject {
            put("path", m.path); put("line", m.line); put("content", m.content)
            if (m.before.isNotEmpty()) {
                putJsonArray("before") { m.before.forEach { add(JsonPrimitive(it)) } }
            }
            if (m.after.isNotEmpty()) {
                putJsonArray("after") { m.after.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }
    fun envelope(kept: List<JsonObject>, truncated: Boolean): String = buildJsonObject {
        put("type", "file_grep"); put("pattern", pattern)
        put("matches", JsonArray(kept))
        put("truncated", truncated)
        if (grep.skippedLargeFiles > 0) put("skipped_large_files", grep.skippedLargeFiles)
        if (contextRequested && !grep.contextSupported) {
            put("context_supported", false)
            put("warning", "this Conch server ignores context; upgrade Conch for context lines")
        }
    }.toString()

    val full = envelope(matches, grep.truncated)
    if (full.length <= maxLength) return full
    // Fixed part measured exactly as emitted below (truncated=true, no matches).
    var remaining = maxLength - envelope(emptyList(), truncated = true).length
    var keep = 0
    for (match in matches) {
        val cost = match.toString().length + if (keep > 0) 1 else 0
        if (cost > remaining) break
        remaining -= cost
        keep++
    }
    return envelope(matches.subList(0, keep), truncated = true)
}

/**
 * file_edit: exactly one replacement from old_string / new_string / replace_all. `edits` is not
 * part of this tool; a batch sent here is rejected with a pointer to file_multi_edit instead of
 * being half-applied.
 */
internal fun parseFileEditArgs(args: Map<String, JsonElement>): Result<List<ShellFileEdit>> = runCatching {
    require(editsArray(args) == null) { "file_edit takes a single replacement; use file_multi_edit for edits" }
    val oldString = arg(args, "old_string")
    require(oldString.isNotEmpty()) { "old_string is required" }
    listOf(ShellFileEdit(oldString, arg(args, "new_string"), boolArg(args, "replace_all")))
}

/**
 * file_multi_edit: only the `edits` array (also accepted as a JSON-encoded string, which some models
 * send). Top-level old_string / new_string / replace_all are not part of this tool's schema and are
 * ignored, so placeholder values a model adds there can never conflict with the batch.
 */
internal fun parseFileMultiEditArgs(args: Map<String, JsonElement>): Result<List<ShellFileEdit>> = runCatching {
    val edits = editsArray(args) ?: throw IllegalArgumentException("edits is required")
    require(edits.isNotEmpty()) { "edits must not be empty" }
    require(edits.size <= SHELL_FILE_EDIT_MAX_OPERATIONS) {
        "edits exceeds $SHELL_FILE_EDIT_MAX_OPERATIONS operations"
    }
    edits.mapIndexed { index, element ->
        val item = element as? JsonObject
            ?: throw IllegalArgumentException("edits[$index] must be an object")
        val oldString = (item["old_string"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        require(oldString.isNotEmpty()) { "edits[$index]: old_string is required" }
        val newString = (item["new_string"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("edits[$index]: new_string is required")
        ShellFileEdit(
            oldString = oldString,
            newString = newString,
            replaceAll = (item["replace_all"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true,
        )
    }
}

/** The `edits` argument as an array, or null when absent, null or a blank string. */
private fun editsArray(args: Map<String, JsonElement>): JsonArray? = when (val raw = args["edits"]) {
    null, JsonNull -> null
    is JsonArray -> raw
    is JsonPrimitive -> if (raw.isString && raw.content.isNotBlank()) {
        runCatching { Json.parseToJsonElement(raw.content) as? JsonArray }.getOrNull()
            ?: throw IllegalArgumentException("edits must be an array")
    } else {
        null
    }
    else -> throw IllegalArgumentException("edits must be an array")
}
