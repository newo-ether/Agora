package com.newoether.agora.api.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Recovers DeepSeek's DSML tool markup that some routes (notably OpenRouter) forward as ordinary
 * assistant text instead of native tool calls (#209).
 *
 * Wire shape, with `｜` (U+FF5C) also accepted as `|`, optional repeated bars and spaces:
 *
 *     <｜DSML｜function_calls>
 *     <｜DSML｜invoke name="execute_shell_command">
 *     <｜DSML｜parameter name="command" string="true">ls</｜DSML｜parameter>
 *     </｜DSML｜invoke>
 *     </｜DSML｜function_calls>
 *
 * Block names seen in the wild are `calls`, `tool_calls`, `tool_call` and `function_calls`.
 * Marker and payload never reach the user: a complete block becomes tool calls, an incomplete or
 * malformed one is dropped so raw markup cannot leak into the answer.
 */
internal object DeepSeekDsml {

    data class Call(val name: String, val arguments: String)

    data class Result(val calls: List<Call>, val text: String, val malformed: Boolean)

    private const val BAR = "[\\s｜|]*"
    private const val BLOCK_NAMES = "(?:function_calls|tool_calls|tool_call|calls)"

    private fun openTag(name: String) = "<$BAR" + "DSML" + "$BAR" + name
    private fun closeTag(name: String) = "</$BAR" + "DSML" + "$BAR" + name

    private val START = Regex("${openTag(BLOCK_NAMES)}\\s*>", RegexOption.IGNORE_CASE)
    private val END = Regex("${closeTag(BLOCK_NAMES)}\\s*>", RegexOption.IGNORE_CASE)
    private val INVOKE_START = Regex(
        "${openTag("invoke")}\\s+name\\s*=\\s*\"([^\"]*)\"\\s*>",
        RegexOption.IGNORE_CASE,
    )
    private val INVOKE_END = Regex("${closeTag("invoke")}\\s*>", RegexOption.IGNORE_CASE)
    private val PARAMETER = Regex(
        "${openTag("parameter")}\\s+name\\s*=\\s*\"([^\"]*)\"" +
            "(?:\\s+string\\s*=\\s*\"(true|false)\")?\\s*>" +
            "([\\s\\S]*?)${closeTag("parameter")}\\s*>",
        RegexOption.IGNORE_CASE,
    )
    private val MARKER = Regex("</?$BAR" + "DSML" + "$BAR[A-Za-z_]+(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val OPENERS = listOf("function_calls", "tool_calls", "tool_call", "calls")
        .map { "<dsml$it>" }

    private fun normalized(source: String): String =
        source.filterNot { it == '｜' || it == '|' || it.isWhitespace() }.lowercase()

    /** True when [source] begins with a complete block opener. */
    fun startsBlock(source: String): Boolean {
        val trimmed = source.trimStart()
        return START.find(trimmed)?.range?.first == 0
    }

    /**
     * True when [source] may still become a block opener once more text arrives, so a marker split
     * across stream chunks is withheld instead of rendered.
     */
    fun couldStartBlock(source: String): Boolean {
        val trimmed = source.trimStart()
        if (!trimmed.startsWith("<") || trimmed.contains('>')) return false
        val candidate = normalized(trimmed)
        return OPENERS.any { it.startsWith(candidate) }
    }

    /**
     * Splits [source] into recovered calls and the prose around them. When a block closes
     * abnormally the tail is dropped and [Result.malformed] is set; callers must not execute a
     * partial call and must not show its markup.
     */
    fun parse(source: String): Result {
        val calls = mutableListOf<Call>()
        val text = StringBuilder()
        var cursor = 0
        var malformed = false
        while (true) {
            val start = START.find(source, cursor) ?: break
            text.append(source, cursor, start.range.first)
            val bodyStart = start.range.last + 1
            val end = END.find(source, bodyStart)
            if (end == null) {
                malformed = true
                val body = parseBody(source.substring(bodyStart))
                calls += body.calls
                cursor = source.length
                break
            }
            val body = parseBody(source.substring(bodyStart, end.range.first))
            calls += body.calls
            if (body.text.isNotEmpty()) text.append(body.text)
            cursor = end.range.last + 1
        }
        if (cursor < source.length) text.append(source, cursor, source.length)
        val visible = MARKER.replace(text.toString(), "").trim()
        return Result(calls = calls, text = visible, malformed = malformed)
    }

    /** True when [source] contains a complete block opener, i.e. DSML recovery applies. */
    fun containsBlock(source: String): Boolean = START.containsMatchIn(source)

    private data class Body(val calls: List<Call>, val text: String)

    private fun parseBody(body: String): Body {
        val calls = mutableListOf<Call>()
        val residue = StringBuilder()
        var cursor = 0
        while (cursor < body.length) {
            val invoke = INVOKE_START.find(body, cursor) ?: break
            residue.append(body, cursor, invoke.range.first)
            val name = invoke.groupValues[1].trim()
            val bodyStart = invoke.range.last + 1
            val invokeEnd = INVOKE_END.find(body, bodyStart)
            val inner = if (invokeEnd == null) body.substring(bodyStart) else body.substring(bodyStart, invokeEnd.range.first)
            if (name.isNotEmpty()) calls += Call(name = name, arguments = argumentsOf(inner))
            cursor = if (invokeEnd == null) body.length else invokeEnd.range.last + 1
        }
        if (cursor < body.length) residue.append(body, cursor, body.length)
        return Body(calls = calls, text = MARKER.replace(residue.toString(), "").trim())
    }

    private fun argumentsOf(inner: String): String {
        val params = linkedMapOf<String, JsonElement>()
        PARAMETER.findAll(inner).forEach { match ->
            val key = match.groupValues[1].trim()
            if (key.isEmpty()) return@forEach
            val forceString = match.groupValues[2].equals("true", ignoreCase = true)
            val raw = match.groupValues[3].trim()
            params[key] = if (forceString) JsonPrimitive(raw) else decodeValue(raw)
        }
        if (params.isEmpty()) return "{}"
        return buildJsonObject { params.forEach { (key, value) -> put(key, value) } }.toString()
    }

    /** Parameter values are JSON when the model omitted `string="true"`; otherwise keep the text. */
    private fun decodeValue(raw: String): JsonElement =
        runCatching { Json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
}
