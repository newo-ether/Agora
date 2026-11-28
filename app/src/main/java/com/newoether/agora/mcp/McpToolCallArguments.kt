package com.newoether.agora.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_PRESENTATION_ARGUMENT_CHARS = 4_096

/**
 * Reads complete root-level IDs from a bounded JSON prefix. A later large arguments payload may
 * still be streaming. Exact execution parsing owns validation and targets outside this prefix.
 */
internal fun mcpToolIdForPresentation(rawArguments: String?): String? {
    val source = rawArguments?.take(MAX_PRESENTATION_ARGUMENT_CHARS) ?: return null
    var index = 0
    var toolId: String? = null
    var seenToolId = false
    fun skipWhitespace() {
        while (index < source.length && source[index] in " \t\r\n") index++
    }
    skipWhitespace()
    if (source.getOrNull(index++) != '{') return null
    skipWhitespace()
    if (source.getOrNull(index) == '}') return null
    while (index < source.length) {
        if (source[index] != '"') return null
        val keyEnd = presentationValueEnd(source, index) ?: return toolId
        val key = runCatching { Json.parseToJsonElement(source.substring(index, keyEnd)) }
            .getOrNull() as? JsonPrimitive ?: return null
        index = keyEnd
        skipWhitespace()
        if (index == source.length) return toolId
        if (source[index++] != ':') return null
        skipWhitespace()
        val valueEnd = presentationValueEnd(source, index) ?: return toolId
        val value = runCatching { Json.parseToJsonElement(source.substring(index, valueEnd)) }
            .getOrNull() ?: return null
        if (key.content == "arguments" && value !is JsonObject) return null
        if (key.content == "tool_id") {
            if (seenToolId) return null
            seenToolId = true
            toolId = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() }
        }
        index = valueEnd
        skipWhitespace()
        when (source.getOrNull(index)) {
            null -> return toolId
            '}' -> {
                index++
                skipWhitespace()
                return toolId.takeIf { index == source.length }
            }
            ',' -> {
                index++
                skipWhitespace()
            }
            else -> return null
        }
    }
    return toolId
}

/** Finds a complete value without inspecting beyond the bounded source. JSON validates its slice. */
private fun presentationValueEnd(source: String, start: Int): Int? {
    if (start >= source.length) return null
    val delimiters = ArrayDeque<Char>()
    var quoted = false
    var escaped = false
    for (index in start until source.length) {
        val char = source[index]
        if (quoted) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> {
                    quoted = false
                    if (delimiters.isEmpty()) return index + 1
                }
            }
        } else {
            when (char) {
                '"' -> quoted = true
                '{' -> delimiters.addLast('}')
                '[' -> delimiters.addLast(']')
                '}', ']' -> {
                    if (delimiters.isEmpty()) return index.takeIf { it > start }
                    if (delimiters.removeLast() != char) return null
                    if (delimiters.isEmpty()) return index + 1
                }
                ',', ' ', '\t', '\r', '\n' -> if (delimiters.isEmpty()) {
                    return index.takeIf { it > start }
                }
            }
        }
    }
    return null
}
