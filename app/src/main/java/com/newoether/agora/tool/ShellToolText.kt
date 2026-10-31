package com.newoether.agora.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Model-facing plain text for shell and file tools. The JSON envelope stays the structured result
 * the UI and state logic read; the model gets `key: value` header lines and then, after one blank
 * line, the raw body (command output or file content) with no JSON escaping.
 */
internal val PLAIN_TEXT_SHELL_TOOLS = setOf(
    "shell_exec",
    "shell_job_get",
    "shell_job_wait",
    "shell_job_stop",
    "file_read",
    "file_write",
    "file_edit",
    "file_multi_edit",
    "file_glob",
    "file_grep",
)

/** Tools whose output body honours `tail_lines`. */
internal val TAIL_LINES_SHELL_TOOLS = setOf("shell_exec", "shell_job_get", "shell_job_wait")

/** Envelope fields that repeat the call arguments or only matter to the UI. */
private val OMITTED_KEYS = setOf(
    "type", "command", "durable", "created_at", "started_at", "finished_at", "output_bytes", "workdir",
)

private val BODY_KEYS = listOf("output", "content", "result_raw")

/** file_read paging fields the model does not need: limit echoes the request, has_more = truncated. */
private val FILE_READ_OMITTED_KEYS = setOf("limit", "returned_bytes", "has_more")

/** Line mode pages by line number, so byte offsets and the line count (end - start + 1) are noise. */
private val FILE_READ_LINE_MODE_OMITTED_KEYS = FILE_READ_OMITTED_KEYS + setOf("offset", "next_offset", "lines")

/**
 * Returns null when [json] is not a JSON object, so the caller keeps the original text.
 * [searchRoot] is the glob/grep `path` argument; result paths under it are shown relative to it.
 */
internal fun shellToolModelText(
    toolName: String,
    json: String,
    tailLines: Int? = null,
    searchRoot: String? = null,
    headLines: Int? = null,
): String? {
    val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
    if (obj["error"] == null) {
        when (toolName) {
            "file_edit", "file_multi_edit" -> return fileEditText(obj)
            "file_glob" -> return fileGlobText(obj, searchRoot)
            "file_grep" -> return fileGrepText(obj, searchRoot)
        }
    }
    val omit = when {
        toolName != "file_read" -> emptySet()
        obj["start_line"] != null -> FILE_READ_LINE_MODE_OMITTED_KEYS
        else -> FILE_READ_OMITTED_KEYS
    }
    val supportsSelection = BuiltInToolNames.canonical(toolName) in TAIL_LINES_SHELL_TOOLS
    return genericText(obj, tailLines.takeIf { supportsSelection }, omit, headLines.takeIf { supportsSelection })
}

/**
 * Keeps the JSON envelope as structured content for the UI and state logic, and hands the model
 * plain text instead, so command output and file content arrive without JSON escaping.
 */
internal fun shellModelFacingResult(
    name: String,
    arguments: String,
    result: ToolExecutionResult,
): ToolExecutionResult {
    if (BuiltInToolNames.canonical(name) !in PLAIN_TEXT_SHELL_TOOLS) return result
    if (result.structuredContent != null || result.images.isNotEmpty()) return result
    val args = parseToolArgs(arguments)
    val searchRoot = arg(args, "path").takeIf { name == "file_glob" || name == "file_grep" }
    val text = shellToolModelText(name, result.text, parseTailLines(arguments), searchRoot, parseHeadLines(arguments)) ?: return result
    val requested = arg(args, "context").toIntOrNull()
    val rendered = if (name == "file_grep" && requested != null && requested !in 0..com.newoether.agora.util.SHELL_FILE_GREP_MAX_CONTEXT_LINES) {
        "context: ${requested.coerceIn(0, com.newoether.agora.util.SHELL_FILE_GREP_MAX_CONTEXT_LINES)} (requested $requested)\n$text"
    } else text
    return result.copy(text = rendered, structuredContent = result.text)
}

internal fun parseTailLines(arguments: String): Int? =
    arg(parseToolArgs(arguments), "tail_lines").toIntOrNull()?.takeIf { it > 0 }

internal fun parseHeadLines(arguments: String): Int? =
    arg(parseToolArgs(arguments), "head_lines").toIntOrNull()?.takeIf { it > 0 }

private fun genericText(obj: JsonObject, tailLines: Int?, omit: Set<String> = emptySet(), headLines: Int? = null): String {
    val fields = LinkedHashMap<String, JsonElement>()
    fun collect(source: JsonObject) {
        for ((key, value) in source) {
            if (key == "result" && value is JsonObject) {
                collect(value)
                continue
            }
            if (key !in fields) fields[key] = value
        }
    }
    collect(obj)

    val header = StringBuilder()
    fun line(key: String, value: String) {
        header.append(key).append(": ").append(value).append('\n')
    }

    // A generic `error: error` code only adds noise in front of the message.
    val errorCode = fields.remove("error")?.scalar()
    val message = fields.remove("message")?.scalar()
    when {
        errorCode != null && errorCode != "error" -> {
            line("error", errorCode)
            message?.let { line("message", it.oneLine()) }
        }
        errorCode != null -> line("error", (message ?: "error").oneLine())
        message != null -> line("message", message.oneLine())
    }

    val bodyKey = BODY_KEYS.firstOrNull { (fields[it] as? JsonPrimitive)?.isString == true }
    var body = bodyKey?.let { (fields.remove(it) as JsonPrimitive).content }
    BODY_KEYS.forEach { fields.remove(it) }

    for ((key, value) in fields) {
        if (key in OMITTED_KEYS || key in omit) continue
        val rendered = when {
            value is JsonNull -> null
            value is JsonPrimitive && value.isString -> value.content.takeIf(String::isNotEmpty)?.oneLine()
            value is JsonPrimitive && value.content == "false" -> null
            value is JsonPrimitive -> value.content
            value is JsonArray && value.isEmpty() -> null
            else -> value.toString()
        } ?: continue
        line(key, rendered)
    }

    if (!body.isNullOrEmpty() && (headLines != null || tailLines != null)) {
        val lines = body.removeSuffix("\n").split('\n')
        val head = (headLines ?: 0).coerceIn(0, lines.size)
        val tail = (tailLines ?: 0).coerceIn(0, lines.size)
        val omitted = (lines.size.toLong() - head - tail).coerceAtLeast(0).toInt()
        if (omitted > 0) {
            if (headLines != null) line("output_head_lines", "first $head of ${lines.size}")
            if (tailLines != null) line("output_tail_lines", "last $tail of ${lines.size}")
            line("output_omitted_lines", omitted.toString())
            body = buildList {
                addAll(lines.take(head))
                add("... ($omitted lines omitted) ...")
                addAll(lines.takeLast(tail))
            }.joinToString("\n")
        }
    }
    return when {
        body == null -> header.toString().trimEnd().ifEmpty { "ok" }
        body.isEmpty() -> header.append("\n(no ${bodyKey ?: "output"})").toString().trimStart('\n')
        else -> header.append('\n').append(body).toString().trimStart('\n')
    }
}

private fun fileEditText(obj: JsonObject): String = buildString {
    append("edited: ").append(obj.str("path").orEmpty()).append('\n')
    obj.str("server")?.let { append("server: ").append(it).append('\n') }
    obj.str("replaced")?.let { append("replaced: ").append(it).append('\n') }
    obj.str("edits")?.let { append("edits: ").append(it).append('\n') }
    obj.str("sha256")?.let { append("sha256: ").append(it).append('\n') }
    val snippets = (obj["snippets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    if (obj.str("snippets_truncated") == "true") append("snippets_truncated: true\n")
    if (snippets.isEmpty()) return@buildString
    append('\n')
    snippets.forEachIndexed { index, snippet ->
        if (index > 0) append("...\n")
        val start = (snippet["start_line"] as? JsonPrimitive)?.intOrNull ?: 1
        (snippet["lines"] as? JsonArray).orEmpty().forEachIndexed { offset, value ->
            append(String.format(java.util.Locale.ROOT, "%6d\t", start + offset))
            append((value as? JsonPrimitive)?.contentOrNull.orEmpty())
            append('\n')
        }
    }
}.trimEnd('\n')

/**
 * Shortens result paths under [root] to root-relative ones. The root is echoed once in the header
 * only when at least one path was shortened, so absolute fallbacks stay unambiguous.
 */
private class SearchPathShortener(root: String?) {
    private val prefix = root?.trim()?.trimEnd('/', '\\')?.takeIf { it.isNotEmpty() }
    var shortened = false
        private set
    val root: String? get() = prefix

    fun shorten(path: String): String {
        val base = prefix ?: return path
        if (path.length <= base.length + 1 || !path.startsWith(base)) return path
        val separator = path[base.length]
        if (separator != '/' && separator != '\\') return path
        shortened = true
        return path.substring(base.length + 1)
    }
}

private fun fileGlobText(obj: JsonObject, searchRoot: String?): String {
    val shortener = SearchPathShortener(searchRoot)
    val files = (obj["files"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .map(shortener::shorten)
    return buildString {
        if (shortener.shortened) append("root: ").append(shortener.root).append('\n')
        if (obj.str("truncated") == "true") append("truncated: true\n")
        if (isNotEmpty()) append('\n')
        if (files.isEmpty()) append("(no files)") else files.joinTo(this, "\n")
    }
}

private fun fileGrepText(obj: JsonObject, searchRoot: String?): String {
    val shortener = SearchPathShortener(searchRoot)
    val body = fileGrepBody(obj, shortener)
    return buildString {
        if (shortener.shortened) append("root: ").append(shortener.root).append('\n')
        if (obj.str("truncated") == "true") append("truncated: true\n")
        obj.str("skipped_large_files")?.let { append("skipped_large_files: ").append(it).append('\n') }
        obj.str("warning")?.let { append("warning: ").append(it).append('\n') }
        if (isNotEmpty()) append('\n')
        append(body)
    }
}

private fun fileGrepBody(obj: JsonObject, shortener: SearchPathShortener): String = buildString {
    val matches = (obj["matches"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    if (matches.isEmpty()) {
        append("(no matches)")
        return@buildString
    }
    val withContext = matches.any { it["before"] != null || it["after"] != null }
    matches.forEachIndexed { index, match ->
        if (withContext && index > 0) append("--\n")
        val path = shortener.shorten(match.str("path").orEmpty())
        val lineNumber = match.str("line")?.toIntOrNull() ?: 0
        val before = (match["before"] as? JsonArray).orEmpty()
        before.forEachIndexed { i, value ->
            append(path).append('-').append(lineNumber - before.size + i).append('-')
                .append((value as? JsonPrimitive)?.contentOrNull.orEmpty()).append('\n')
        }
        append(path).append(':').append(lineNumber).append(':').append(match.str("content").orEmpty()).append('\n')
        (match["after"] as? JsonArray).orEmpty().forEachIndexed { i, value ->
            append(path).append('-').append(lineNumber + 1 + i).append('-')
                .append((value as? JsonPrimitive)?.contentOrNull.orEmpty()).append('\n')
        }
    }
}.trimEnd('\n')

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonElement.scalar(): String? = (this as? JsonPrimitive)?.contentOrNull

/** Header values must stay on one line; multi-line text is JSON-quoted instead. */
private fun String.oneLine(): String =
    if ('\n' in this || '\r' in this) JsonPrimitive(this).toString() else this
