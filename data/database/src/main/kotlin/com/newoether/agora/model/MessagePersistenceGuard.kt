package com.newoether.agora.model

import com.newoether.agora.data.local.DatabaseConstants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Bounds the size of a single persisted `messages` row so it can never exceed the platform
 * CursorWindow (~2MB) and trigger `SQLiteBlobTooBigException` / `Row too big to fit into
 * CursorWindow` (issue #51).
 *
 * Individual tool results are already clipped to the per-result capture limit, but a *model*
 * message aggregates many tool rounds into a single `toolCallJson` column, and the model answer
 * `text` column is otherwise unbounded. This
 * guard bounds the large columns independently: [clipText] caps answer/reasoning text, and
 * [encodeSegmentsBounded] budgets trimmable segment payloads before encoding an oversized
 * aggregate.
 *
 * When trimming is needed, the largest tool-result field (including independently persisted
 * structured/display content, then live output and non-tool content) is halved with a truncation
 * marker. Losing fidelity in the oldest/largest tool results is the correct trade-off: they are
 * already far back in the conversation (likely falling out of the context window) and the
 * alternative is a crash. The algorithm strictly reduces the largest field each iteration and
 * gives up once every field is at the floor, so it always terminates.
 */
object MessagePersistenceGuard {

    /** Floor below which a field is no longer trimmed (keeps a useful residual instead of a
     *  uselessly tiny one, and guarantees termination when a row has many small segments). */
    private const val TRIM_FLOOR_CHARS = 2000
    private const val PAYLOAD_PLACEHOLDER = "x"

    const val TRUNCATION_MARKER = "\n…[truncated for persistence]"

    /**
     * Trim a persisted text column to a safe length. Avoid ending on an unmatched UTF-16 high
     * surrogate so a boundary through an emoji still persists valid Unicode.
     */
    fun clipText(text: String): String {
        if (text.length <= DatabaseConstants.MAX_PERSISTED_TEXT_CHARS) return text
        var end = DatabaseConstants.MAX_PERSISTED_TEXT_CHARS
        if (
            end in 1 until text.length &&
            Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])
        ) {
            end--
        }
        return text.substring(0, end) + TRUNCATION_MARKER
    }

    /**
     * Encode [segments] to JSON, bounded to [maxBytes] UTF-8 bytes. Oversized trimmable payloads
     * are measured and reduced before the aggregate is encoded, avoiding repeated full-list
     * serialization. Returns `null` for an empty list so the column stays SQL NULL (matching prior
     * behaviour where callers passed `null` for "no segments").
     */
    fun encodeSegmentsBounded(
        segments: List<MessageSegment>?,
        maxBytes: Int = DatabaseConstants.MAX_PERSISTED_SEGMENTS_BYTES,
    ): String? = encodeSegmentsBounded(segments, maxBytes) { Json.encodeToString(it) }

    internal fun encodeSegmentsBounded(
        segments: List<MessageSegment>?,
        maxBytes: Int,
        encode: (List<MessageSegment>) -> String,
    ): String? {
        if (segments.isNullOrEmpty()) return null
        val budget = maxBytes.toLong()
        val originalPayloadBytes = trimmablePayloadBytes(segments)
        var originalOversizedJson: String? = null

        // A payload that fits by itself may still fit with metadata. Preserve byte-for-byte output
        // for that ordinary path by using the canonical encoder directly.
        if (originalPayloadBytes <= budget) {
            val json = encode(segments)
            if (utf8Size(json) <= budget) return json
            originalOversizedJson = json
        }

        if (segments.none(::canTrim)) {
            return oversizedResult(segments, maxBytes)
        }

        val (placeholderSegments, placeholderCount) = placeholderProjection(segments)
        val fixedBytes = utf8Size(encode(placeholderSegments)) - placeholderCount
        var current: List<MessageSegment> = segments
        var projectedBytes = fixedBytes + originalPayloadBytes

        while (projectedBytes > budget) {
            val pick = current.withIndex().maxByOrNull { (_, segment) ->
                trimmableSize(segment)
            } ?: return oversizedResult(current, maxBytes)
            val segment = pick.value
            if (!canTrim(segment)) return oversizedResult(current, maxBytes)

            val trimmed = trimLargest(segment)
            val oldPayloadBytes = trimmablePayloadBytes(segment)
            val newPayloadBytes = trimmablePayloadBytes(trimmed)
            if (newPayloadBytes >= oldPayloadBytes) {
                return oversizedResult(current, maxBytes)
            }

            current = current.toMutableList().also { it[pick.index] = trimmed }
            projectedBytes -= oldPayloadBytes - newPayloadBytes
        }

        val json = if (current === segments) {
            originalOversizedJson ?: encode(current)
        } else {
            encode(current)
        }
        if (utf8Size(json) <= budget) return json

        // The estimator mirrors kotlinx.serialization JSON escaping. Keep the exact encoded-byte
        // check authoritative so a future serializer behavior change cannot persist an oversized
        // row or silently discard protected continuation state.
        return oversizedResult(current, maxBytes)
    }

    private fun oversizedResult(
        segments: List<MessageSegment>,
        maxBytes: Int,
    ): String? {
        // Metadata can exceed the budget even when no result/content field is trimmable.
        // Persisting ordinary oversized metadata would recreate #51, so it still degrades to SQL
        // NULL. Provider continuation state is protected protocol data, however: losing it while
        // retaining tool outputs would create an invalid next request, so fail explicitly.
        if (segments.any { it.responseOutputItems.isNotEmpty() }) {
            error("Responses continuation state exceeds the $maxBytes-byte persistence budget")
        }
        if (segments.any { it.toolRoundId != null }) {
            error("Tool round references exceed the $maxBytes-byte persistence budget")
        }
        return null
    }

    private fun placeholderProjection(
        segments: List<MessageSegment>,
    ): Pair<List<MessageSegment>, Long> {
        var placeholderCount = 0L
        fun replace(value: String?): String? {
            if (value.isNullOrEmpty()) return value
            placeholderCount++
            return PAYLOAD_PLACEHOLDER
        }

        val projected = segments.map { segment ->
            val content = if (segment.type == "tool" || segment.content.isEmpty()) {
                segment.content
            } else {
                placeholderCount++
                PAYLOAD_PLACEHOLDER
            }
            segment.copy(
                content = content,
                toolResult = replace(segment.toolResult),
                toolProgress = replace(segment.toolProgress),
                toolResultText = replace(segment.toolResultText),
                toolStructuredResult = replace(segment.toolStructuredResult),
            )
        }
        return projected to placeholderCount
    }

    private fun trimmablePayloadBytes(segments: List<MessageSegment>): Long =
        segments.sumOf { trimmablePayloadBytes(it) }

    private fun trimmablePayloadBytes(segment: MessageSegment): Long {
        var bytes = segment.toolResult?.let(::jsonStringPayloadUtf8Size) ?: 0L
        bytes += segment.toolResultText?.let(::jsonStringPayloadUtf8Size) ?: 0L
        bytes += segment.toolStructuredResult?.let(::jsonStringPayloadUtf8Size) ?: 0L
        bytes += segment.toolProgress?.let(::jsonStringPayloadUtf8Size) ?: 0L
        if (segment.type != "tool") {
            bytes += jsonStringPayloadUtf8Size(segment.content)
        }
        return bytes
    }

    /** Size of the field that trimming would shrink, driving largest-first selection. */
    private fun trimmableSize(segment: MessageSegment): Int {
        val result = segment.toolResult?.length ?: 0
        val resultText = segment.toolResultText?.length ?: 0
        val structuredResult = segment.toolStructuredResult?.length ?: 0
        val progress = segment.toolProgress?.length ?: 0
        val content = if (segment.type == "tool") 0 else segment.content.length
        return maxOf(result, resultText, structuredResult, progress, content)
    }

    private fun canTrim(segment: MessageSegment): Boolean =
        (segment.toolResult?.let { it.length > TRIM_FLOOR_CHARS } == true) ||
            (segment.toolResultText?.let { it.length > TRIM_FLOOR_CHARS } == true) ||
            (segment.toolStructuredResult?.let { it.length > TRIM_FLOOR_CHARS } == true) ||
            (segment.toolProgress?.let { it.length > TRIM_FLOOR_CHARS } == true) ||
            (segment.type != "tool" && segment.content.length > TRIM_FLOOR_CHARS)

    /** Halve the largest trimmable field of [segment], preferring the tool result on ties. */
    private fun trimLargest(segment: MessageSegment): MessageSegment {
        val result = segment.toolResult
        val resultText = segment.toolResultText
        val structuredResult = segment.toolStructuredResult
        val progress = segment.toolProgress
        val contentSize = if (segment.type == "tool") 0 else segment.content.length
        val largest = maxOf(
            result?.length ?: 0,
            resultText?.length ?: 0,
            structuredResult?.length ?: 0,
            progress?.length ?: 0,
            contentSize,
        )
        if (
            result != null &&
            result.length == largest &&
            result.length > TRIM_FLOOR_CHARS
        ) {
            return segment.copy(toolResult = halveWithMarker(result))
        }
        if (
            resultText != null &&
            resultText.length == largest &&
            resultText.length > TRIM_FLOOR_CHARS
        ) {
            return segment.copy(toolResultText = halveWithMarker(resultText))
        }
        if (
            structuredResult != null &&
            structuredResult.length == largest &&
            structuredResult.length > TRIM_FLOOR_CHARS
        ) {
            return segment.copy(toolStructuredResult = halveWithMarker(structuredResult))
        }
        if (
            progress != null &&
            progress.length == largest &&
            progress.length > TRIM_FLOOR_CHARS
        ) {
            return segment.copy(toolProgress = halveWithMarker(progress))
        }
        if (segment.type != "tool" && segment.content.length > TRIM_FLOOR_CHARS) {
            return segment.copy(content = halveWithMarker(segment.content))
        }
        return segment
    }

    private fun halveWithMarker(value: String): String {
        if (value.length <= TRIM_FLOOR_CHARS) return value
        var preferredEnd = maxOf(
            value.length / 2,
            TRIM_FLOOR_CHARS - TRUNCATION_MARKER.length,
        )
        preferredEnd = minOf(
            preferredEnd,
            value.length - TRUNCATION_MARKER.length - 1,
        )
        if (
            preferredEnd in 1 until value.length &&
            Character.isHighSurrogate(value[preferredEnd - 1]) &&
            Character.isLowSurrogate(value[preferredEnd])
        ) {
            preferredEnd--
        }

        val maxPrefixBytes =
            jsonStringPayloadUtf8Size(value) -
                jsonStringPayloadUtf8Size(TRUNCATION_MARKER) -
                1L
        val end = prefixEndWithinJsonPayloadBytes(value, preferredEnd, maxPrefixBytes)
        return value.substring(0, end) + TRUNCATION_MARKER
    }

    private fun prefixEndWithinJsonPayloadBytes(
        value: String,
        maxEnd: Int,
        maxBytes: Long,
    ): Int {
        var bytes = 0L
        var index = 0
        while (index < maxEnd) {
            val char = value[index]
            var width = 1
            val charBytes = when {
                char == '"' || char == '\\' -> 2L
                char == '\b' || char == '\t' || char == '\n' ||
                    char == '\u000C' || char == '\r' -> 2L
                char.code < 0x20 -> 6L
                char.code < 0x80 -> 1L
                char.code < 0x800 -> 2L
                Character.isHighSurrogate(char) &&
                    index + 1 < value.length &&
                    Character.isLowSurrogate(value[index + 1]) -> {
                    width = 2
                    4L
                }
                Character.isSurrogate(char) -> 1L
                else -> 3L
            }
            if (index + width > maxEnd || bytes + charBytes > maxBytes) break
            bytes += charBytes
            index += width
        }
        return index
    }

    /** UTF-8 bytes emitted for a string payload after kotlinx.serialization JSON escaping. */
    private fun jsonStringPayloadUtf8Size(value: String): Long {
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            val char = value[index]
            bytes += when {
                char == '"' || char == '\\' -> 2L
                char == '\b' || char == '\t' || char == '\n' ||
                    char == '\u000C' || char == '\r' -> 2L
                char.code < 0x20 -> 6L
                char.code < 0x80 -> 1L
                char.code < 0x800 -> 2L
                Character.isHighSurrogate(char) &&
                    index + 1 < value.length &&
                    Character.isLowSurrogate(value[index + 1]) -> {
                    index++
                    4L
                }
                Character.isSurrogate(char) -> 1L
                else -> 3L
            }
            index++
        }
        return bytes
    }

    /** UTF-8 size without allocating a second byte array for an already-encoded JSON string. */
    private fun utf8Size(value: String): Long {
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            val char = value[index]
            bytes += when {
                char.code < 0x80 -> 1L
                char.code < 0x800 -> 2L
                Character.isHighSurrogate(char) &&
                    index + 1 < value.length &&
                    Character.isLowSurrogate(value[index + 1]) -> {
                    index++
                    4L
                }
                Character.isSurrogate(char) -> 1L
                else -> 3L
            }
            index++
        }
        return bytes
    }
}
