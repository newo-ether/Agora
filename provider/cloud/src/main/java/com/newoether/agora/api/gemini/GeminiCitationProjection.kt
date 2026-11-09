package com.newoether.agora.api.gemini

import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.CitationRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal fun JsonObject.toGeminiCitations(answerText: String): List<CitationRecord> {
    val chunks = arrayContent("groundingChunks", "grounding_chunks") ?: return emptyList()
    val supports = arrayContent("groundingSupports", "grounding_supports") ?: return emptyList()
    val citations = mutableListOf<CitationRecord>()

    supports.forEach { supportElement ->
        val support = supportElement as? JsonObject ?: return@forEach
        val segment = support.objectContent("segment")
        val anchor = segment?.toGeminiAnchor(answerText)
        val chunkIndices = support.arrayContent("groundingChunkIndices", "grounding_chunk_indices")
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            .distinct()

        chunkIndices.forEach chunkLoop@{ chunkIndex ->
            val chunk = chunks.getOrNull(chunkIndex) as? JsonObject ?: return@chunkLoop
            val web = chunk.objectContent("web") ?: return@chunkLoop
            CitationPolicy.create(
                provider = "gemini",
                kind = "url",
                title = web.stringContent("title"),
                url = web.stringContent("uri"),
                anchors = listOfNotNull(anchor),
                answerText = answerText,
            )?.let(citations::add)
        }
    }

    return CitationPolicy.deduplicate(citations, answerText)
}

private fun JsonObject.toGeminiAnchor(answerText: String): CitationAnchor? {
    val startByte = intContent("startIndex", "start_index") ?: 0
    val endByte = intContent("endIndex", "end_index") ?: return null
    val (startIndex, endIndex) = utf8ByteRangeToUtf16(answerText, startByte, endByte)
        ?: return null
    val citedText = answerText.substring(startIndex, endIndex)
    val suppliedText = stringContent("text")
    if (suppliedText != null && suppliedText != citedText) return null
    return CitationAnchor(
        startIndex = startIndex,
        endIndex = endIndex,
        citedText = citedText,
    )
}

private fun utf8ByteRangeToUtf16(
    text: String,
    startByte: Int,
    endByte: Int,
): Pair<Int, Int>? {
    if (startByte < 0 || endByte <= startByte) return null
    val boundaries = utf8BoundaryMap(text) ?: return null
    if (endByte >= boundaries.size) return null
    val startIndex = boundaries[startByte]
    val endIndex = boundaries[endByte]
    if (startIndex < 0 || endIndex <= startIndex) return null
    return startIndex to endIndex
}

private fun utf8BoundaryMap(text: String): IntArray? {
    val boundaries = IntArray(text.toByteArray(Charsets.UTF_8).size + 1) { -1 }
    var charIndex = 0
    var byteIndex = 0
    boundaries[0] = 0
    while (charIndex < text.length) {
        val first = text[charIndex]
        val codePoint = when {
            Character.isHighSurrogate(first) -> {
                val second = text.getOrNull(charIndex + 1)
                if (second == null || !Character.isLowSurrogate(second)) return null
                Character.toCodePoint(first, second)
            }
            Character.isLowSurrogate(first) -> return null
            else -> first.code
        }
        charIndex += Character.charCount(codePoint)
        byteIndex += when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }
        if (byteIndex >= boundaries.size) return null
        boundaries[byteIndex] = charIndex
    }
    return boundaries.takeIf { byteIndex == it.lastIndex }
}

private fun JsonObject.arrayContent(vararg keys: String): JsonArray? =
    keys.firstNotNullOfOrNull { key -> this[key] as? JsonArray }

private fun JsonObject.objectContent(vararg keys: String): JsonObject? =
    keys.firstNotNullOfOrNull { key -> this[key] as? JsonObject }

private fun JsonObject.stringContent(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
    }

private fun JsonObject.intContent(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.intOrNull }
