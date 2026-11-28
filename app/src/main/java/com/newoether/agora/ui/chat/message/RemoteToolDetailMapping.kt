package com.newoether.agora.ui.chat.message

import kotlinx.serialization.json.*

/** Read-only native-format adapter. Null retains the original JSON view. */
internal fun mapRemoteToolDetail(
    source: String?, name: String?, rawArguments: String?, rawResult: String?, state: String?,
): ToolDetailPresentation? {
    return runCatching { parseRemoteToolDetail(source, name, rawArguments, rawResult, state) }.getOrNull()
}

private fun parseRemoteToolDetail(
    source: String?, name: String?, rawArguments: String?, rawResult: String?, state: String?,
): ToolDetailPresentation? {
    if (source !in setOf("filo", "codex-desktop", "codex-cli", "claude-desktop", "claude-cli")) return null
    val tool = name?.removePrefix("functions/")?.removePrefix("functions.") ?: return null
    val arguments = rawArguments?.remoteObject()
        ?: if (tool == "apply_patch") {
            val patch = (rawArguments?.remoteElement() as? JsonPrimitive)?.takeIf { it.isString }?.content ?: rawArguments
            patch?.takeIf { it.startsWith("*** Begin Patch") }?.let { buildJsonObject { put("patch", it) } } ?: return null
        } else return null
    val active = state in setOf("calling", "running") || rawResult == null && state == null
    if (state == "stopped" || state == "failed" && tool !in remoteShellTools) return null
    val consumed = mutableSetOf<String>()
    val supplemental = mutableListOf<String>()
    fun take(key: String): String? = arguments.remoteString(key)?.also { consumed += key }
    fun path() = (take("file_path") ?: take("path"))?.takeIf(String::isNotBlank)
    fun file(path: String?, text: String, lines: List<ToolDetailCodeRow.Line>? = null) =
        ToolDetailBody.FileContent(path?.let { toolDetailPath(it) }, null, text, "", null, lines)
    fun detail(kind: ToolKind, body: ToolDetailBody, code: ToolDetailCodeArgument? = null) =
        ToolDetailPresentation(
            kind, JsonObject(arguments - consumed).takeIf { it.isNotEmpty() }?.toString(),
            "", "", null, body, code, supplemental.toList(),
        )
    return when (tool) {
        in remoteShellTools -> {
            val commandKey = if (arguments.containsKey("command")) "command" else "cmd"
            val commandValue = arguments[commandKey] ?: return null
            val command = when (commandValue) {
                is JsonPrimitive -> commandValue.takeIf { it.isString }?.content
                is JsonArray -> commandValue.map {
                    (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                }.takeIf { it.isNotEmpty() }?.joinToString(" ") { part ->
                    if (part.any(Char::isWhitespace)) "\"${part.replace("\"", "\\\"") }\"" else part
                }
                else -> null
            } ?: return null
            if (commandValue is JsonArray) supplemental += buildJsonObject { put(commandKey, commandValue) }.toString()
            consumed += commandKey
            val result = rawResult?.let(::remoteShellResult)
            val output: String?
            val exit: Int?
            if (result != null) {
                if (result["error"]?.let { it !is JsonNull } == true ||
                    (result["failed"] as? JsonPrimitive)?.booleanOrNull == true) return null
                val outputKeys = listOf("output", "aggregatedOutput", "aggregated_output", "stdout", "stderr")
                if (outputKeys.any { result[it] != null && result[it] !is JsonNull && result[it]?.remoteText() == null }) return null
                val exitKey = if (result.containsKey("exit_code")) "exit_code" else "exitCode"
                val exitValue = result[exitKey]
                exit = if (exitValue == null || exitValue is JsonNull) null else
                    (exitValue as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
                val selected = outputKeys.take(3).firstOrNull { result[it]?.remoteText() != null }
                val usedKeys = if (selected != null) setOf(selected) else setOf("stdout", "stderr")
                output = selected?.let { result[it]?.remoteText() }
                    ?: listOfNotNull(result["stdout"]?.remoteText(), result["stderr"]?.remoteText()).joinToString("\n")
                if (result != rawResult?.remoteElement() || outputKeys.any { result[it] is JsonObject || result[it] is JsonArray }) {
                    rawResult?.let { supplemental += it }
                }
                if (outputKeys.none(result::containsKey) && exit == null) return null
                JsonObject(result - usedKeys - exitKey).takeIf { it.isNotEmpty() }?.let { supplemental += it.toString() }
            } else {
                val decoded = rawResult?.remoteElement()
                if (decoded is JsonObject || decoded is JsonArray ||
                    decoded == null && rawResult?.trimStart()?.let { it.startsWith("{") || it.startsWith("[") } == true) return null
                val literalResult = (decoded as? JsonPrimitive)?.takeIf { it.isString }?.content ?: rawResult
                val match = literalResult?.let {
                    Regex("(?s)^(.*?Process exited with code (-?\\d+)\\r?\\n(?:.*?\\r?\\n)?Output:\\r?\\n)(.*)$").matchEntire(it)
                }
                exit = match?.groupValues?.get(2)?.toIntOrNull()
                output = match?.groupValues?.get(3) ?: literalResult
                match?.groupValues?.get(1)?.let { supplemental += it }
            }
            if (state == "failed" && exit == null) return null
            detail(ToolKind.SHELL_EXECUTE, ToolDetailBody.Shell(exit?.toString().orEmpty(), "", null, output),
                ToolDetailCodeArgument(commandKey, command, null))
        }
        "Read", "read_file", "file_read" -> {
            val path = path() ?: return null
            val value = rawResult?.remoteElement()
            val result = value as? JsonObject
            val text = when {
                result != null && result.remoteString("path")?.let { it != path } == true -> return null
                result != null -> when {
                    result.keys.any { it in setOf("content", "text") } ->
                        result["content"]?.remoteText() ?: result["text"]?.remoteText() ?: return null
                    else -> rawResult
                }
                value is JsonArray -> value.remoteText() ?: rawResult
                value is JsonPrimitive && value.isString -> value.content
                else -> rawResult
            }
            if (result != null || value is JsonArray) rawResult?.let { supplemental += it }
            if (value == null && rawResult?.trimStart()?.let { it.startsWith("{") || it.startsWith("[") } == true) return null
            val rows = text?.let(::remoteReadLines)
            detail(ToolKind.FILE_READ, if (text == null && active) ToolDetailBody.Active("", null)
                else file(path, rows?.joinToString("\n") { it.text } ?: text ?: return null, rows))
        }
        "Write", "write_file", "file_write" -> {
            val path = path() ?: return null
            val text = take("content") ?: return null
            rawResult?.takeIf(String::isNotBlank)?.let { supplemental += it }
            detail(ToolKind.FILE_WRITE, file(path, text, numberedCodeLines(text)))
        }
        "Edit", "MultiEdit", "file_edit", "file_multi_edit" -> {
            if (arguments.containsKey("changes")) {
                consumed += "changes"
                val edits = remoteFileChanges(arguments["changes"] as? JsonArray ?: return null) ?: return null
                if (rawResult != null) {
                    val result = rawResult.remoteObject()
                    if (result == null || result["changes"] != arguments["changes"]) supplemental += rawResult
                    else JsonObject(result - "changes").takeIf { it.isNotEmpty() }?.let { supplemental += it.toString() }
                }
                detail(ToolKind.FILE_EDIT, edits)
            } else {
                val path = path() ?: return null
                fun validEdit(value: JsonObject): Boolean = value.remoteString("old_string")?.isNotEmpty() == true &&
                    value.remoteString("new_string") != null && (value["replace_all"] == null ||
                    (value["replace_all"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != null)
                val edits = arguments["edits"]
                if (edits != null) {
                    if (edits !is JsonArray || edits.isEmpty() || edits.any { it !is JsonObject || !validEdit(it) }) return null
                    if (edits.any { (it as JsonObject).keys.any { key -> key !in setOf("old_string", "new_string", "replace_all") } }) {
                        supplemental += arguments.toString()
                    }
                } else if (!validEdit(arguments)) return null
                val normalized = JsonObject(arguments + ("path" to JsonPrimitive(path)))
                val hunks = fileEditHunks(normalized) ?: return null
                consumed += if (edits != null) setOf("edits") else setOf("old_string", "new_string", "replace_all")
                rawResult?.takeIf(String::isNotBlank)?.let { supplemental += it }
                detail(ToolKind.FILE_EDIT, ToolDetailBody.Edit(toolDetailPath(path), null, "", hunks, ""))
            }
        }
        "apply_patch" -> {
            val patch = take("patch") ?: take("input") ?: return null
            val body = remotePatchBody(patch) ?: return null
            rawResult?.takeIf(String::isNotBlank)?.let { supplemental += it }
            detail(ToolKind.FILE_EDIT, body)
        }
        "Glob", "LS", "file_glob", "list_dir" -> {
            if (tool in setOf("Glob", "file_glob") && arguments.remoteString("pattern") == null) return null
            if (tool in setOf("LS", "list_dir") && arguments.remoteString("path") == null) return null
            if (rawResult == null) return if (active) detail(ToolKind.FILE_GLOB, ToolDetailBody.Active("", null)) else null
            val result = rawResult.remoteResultObject()
            val values = rawResult.remoteElement() as? JsonArray
            val paths = if (result != null || values != null) {
                val entries = values ?: result?.get("files") as? JsonArray ?: return null
                supplemental += rawResult
                entries.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null }
            } else {
                if (rawResult.trimStart().let { it.startsWith("{") || it.startsWith("[") }) return null
                rawResult.lineSequence().filter(String::isNotBlank).toList()
            }
            detail(ToolKind.FILE_GLOB, ToolDetailBody.Paths(paths.map { toolDetailPath(it) }))
        }
        "Grep", "file_grep" -> {
            if (arguments.remoteString("pattern") == null) return null
            if (rawResult == null) return if (active) detail(ToolKind.FILE_GREP, ToolDetailBody.Active("", null)) else null
            val result = rawResult.remoteObject()
            val matches = if (result != null) {
                val values = result["matches"] as? JsonArray ?: return null
                if (values.any { !validRemoteGrepMatch(it) }) return null
                JsonObject(result - "matches").takeIf { it.isNotEmpty() }?.let { supplemental += it.toString() }
                values
            } else {
                JsonArray(rawResult.lineSequence().filter(String::isNotBlank).toList().map { line ->
                    val match = Regex("^(.*?):(\\d+):(.*)$").matchEntire(line) ?: return null
                    val number = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
                    buildJsonObject { put("path", match.groupValues[1]); put("line", number); put("content", match.groupValues[3]) }
                }.toList())
            }
            detail(ToolKind.FILE_GREP, ToolDetailBody.Grep(grepGroups(matches, null, "", grepHighlighter(arguments))))
        }
        "web_search", "WebSearch" -> {
            if (arguments.remoteString("query") == null && arguments["action"] !is JsonObject) return null
            if (rawResult == null) return if (active) detail(ToolKind.WEB_SEARCH, ToolDetailBody.Active("", null)) else null
            val result = rawResult.remoteObject() ?: return null
            val values = result["results"] as? JsonArray ?: return null
            val results = values.map { value ->
                val item = value as? JsonObject ?: return null
                if (item.keys.any { it !in setOf("title", "url", "snippet", "description") }) return null
                val title = item.remoteString("title") ?: return null
                val url = item.remoteString("url") ?: return null
                for (key in listOf("snippet", "description")) {
                    if (item[key] != null && item[key] !is JsonNull && item.remoteString(key) == null) return null
                }
                if (item.remoteString("snippet") != null && item.remoteString("description") != null) supplemental += item.toString()
                ToolDetailSearchResult(title, url, com.newoether.agora.model.CitationPolicy.safeHttpUrl(url),
                    item.remoteString("snippet") ?: item.remoteString("description"))
            }
            JsonObject(result - "results").takeIf { it.isNotEmpty() }?.let { supplemental += it.toString() }
            detail(ToolKind.WEB_SEARCH, ToolDetailBody.Search(results))
        }
        "WebFetch", "web_fetch" -> {
            if (arguments.remoteString("url") == null) return null
            detail(ToolKind.WEB_FETCH, if (rawResult == null && active) ToolDetailBody.Active("", null)
                else file(null, rawResult ?: return null))
        }
        else -> null
    }
}

private val remoteShellTools = setOf("Bash", "shell", "exec_command", "shell_command", "execute_shell_command")
internal fun isRemoteShellTool(name: String): Boolean =
    name.removePrefix("functions/").removePrefix("functions.") in remoteShellTools
internal fun JsonObject.remoteString(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun String.remoteObject(): JsonObject? = remoteElement() as? JsonObject

private fun validRemoteGrepMatch(value: JsonElement): Boolean {
    val item = value as? JsonObject ?: return false
    if (item.keys.any { it !in setOf("path", "line", "content", "before", "after") }) return false
    if (item.remoteString("path").isNullOrBlank() || item.remoteString("content") == null) return false
    val line = item["line"]
    if (line != null && (line !is JsonPrimitive || line.isString || line.intOrNull?.let { it > 0 } != true)) return false
    for (key in listOf("before", "after")) {
        if (!item.containsKey(key)) continue
        val rows = item[key] as? JsonArray ?: return false
        if (rows.any { it !is JsonPrimitive || !it.isString } || rows.isNotEmpty() && line == null) return false
    }
    return true
}

/** Only remove a gutter if every returned line has an explicit, consecutive native line number. */
private fun remoteReadLines(text: String): List<ToolDetailCodeRow.Line>? {
    val lines = text.removeSuffix("\n").split('\n')
    val rows = lines.map { line ->
        val match = Regex("^\\s*(\\d+)(?:→|\\t|[|] ?)(.*)$").matchEntire(line.removeSuffix("\r")) ?: return null
        ToolDetailCodeRow.Line(match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null, match.groupValues[2])
    }
    return rows.takeIf { it.zipWithNext().all { (a, b) -> b.number == a.number!! + 1 } }
}
