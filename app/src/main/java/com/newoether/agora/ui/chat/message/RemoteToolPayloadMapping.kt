package com.newoether.agora.ui.chat.message

import kotlinx.serialization.json.*

/** Decode only JSON string envelopes; arbitrary objects keep their original shape. */
internal fun String.remoteElement(): JsonElement? {
    var value = runCatching { Json.parseToJsonElement(this) }.getOrNull() ?: return null
    repeat(4) {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return value
        if (text.trimStart().firstOrNull() !in setOf('{', '[', '"')) return value
        value = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return value
    }
    return value
}

/** Known text/content wrappers only. Callers retain the wrapper as supplemental JSON. */
internal fun JsonElement.remoteText(): String? {
    return when (this) {
    is JsonPrimitive -> takeIf { it.isString }?.content
    is JsonArray -> map { block ->
        when (block) {
            is JsonPrimitive -> block.takeIf { it.isString }?.content ?: return null
            is JsonObject -> {
                if (block.remoteString("type") !in setOf("text", "input_text", "output_text", "inputText")) return null
                block.remoteString("text") ?: return null
            }
            else -> return null
        }
    }.joinToString("\n")
    is JsonObject -> listOf("content", "text", "output").firstNotNullOfOrNull { key -> this[key]?.remoteText() }
    }
}

internal fun String.remoteResultObject(): JsonObject? {
    val value = remoteElement() ?: return null
    if (value is JsonObject) return value
    return value.remoteText()?.remoteObject()
}

internal fun remoteShellResult(raw: String): JsonObject? {
    val value = raw.remoteElement() ?: return null
    if (value is JsonArray) {
        val text = value.remoteText() ?: return null
        return text.remoteObject() ?: buildJsonObject { put("output", text) }
    }
    val objectValue = value as? JsonObject ?: return null
    if (objectValue["error"]?.let { it !is JsonNull } == true ||
        listOf("failed", "isError", "is_error").any { (objectValue[it] as? JsonPrimitive)?.booleanOrNull == true }) return null
    val outputKeys = setOf("output", "aggregatedOutput", "aggregated_output", "stdout", "stderr")
    if (objectValue.keys.any { it in outputKeys || it == "exit_code" || it == "exitCode" }) return objectValue
    val content = objectValue["content"] ?: objectValue["text"] ?: return objectValue
    val text = content.remoteText() ?: return null
    return text.remoteObject() ?: buildJsonObject { put("output", text) }
}
