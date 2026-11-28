package com.newoether.agora.tool
import com.newoether.agora.util.Constants
import com.newoether.agora.util.SHELL_FILE_READ_MAX_BYTES
import com.newoether.agora.util.ShellFileReadResult
import com.newoether.agora.util.shellFileLineCount
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val FILE_READ_DEFAULT_LINES = 200
internal const val FILE_READ_MAX_LINES = 5_000
/** Line mode reads at most this many backend slices (16 x 1MB) before giving up on start_line. */
internal const val FILE_READ_MAX_LINE_SCAN_READS = 16
/** Line mode cuts single lines at this many chars so one long line cannot stall paging. */
internal const val FILE_READ_MAX_LINE_CHARS = 16_000

/** Parsed file_read paging arguments; [lineMode] is set only by a non-default start_line/max_lines. */
internal data class FileReadArgs(
    val offset: Long,
    val limit: Long,
    val lineMode: Boolean,
    val startLine: Int,
    val maxLines: Int,
)

/**
 * Parses file_read paging arguments. Models that fill every schema field (structured output)
 * send null or 0 for parameters they mean to leave out, so blank, null and 0 all mean "not set"
 * for limit, start_line and max_lines. Returns the error message for invalid input.
 */
internal fun parseFileReadArgs(args: Map<String, kotlinx.serialization.json.JsonElement>): Pair<FileReadArgs?, String?> {
    val offset = arg(args, "offset").toLongOrNull() ?: 0L
    val limit = arg(args, "limit").toLongOrNull() ?: 0L
    val rawStartLine = arg(args, "start_line").takeUnless { it == "0" }.orEmpty()
    val rawMaxLines = arg(args, "max_lines").takeUnless { it == "0" }.orEmpty()
    val startLine = if (rawStartLine.isBlank()) 1 else rawStartLine.toIntOrNull()
        ?: return null to "start_line must be an integer"
    if (startLine < 1) return null to "start_line must be at least 1"
    val maxLines = if (rawMaxLines.isBlank()) FILE_READ_DEFAULT_LINES else rawMaxLines.toIntOrNull()
        ?: return null to "max_lines must be an integer"
    if (maxLines < 1) return null to "max_lines must be at least 1"
    val lineMode = rawStartLine.isNotBlank() || rawMaxLines.isNotBlank()
    return FileReadArgs(offset, limit, lineMode, startLine, maxLines) to null
}

internal fun boundedFileReadJson(
    server: String,
    path: String,
    result: ShellFileReadResult,
    maxLength: Int = Constants.MAX_TOOL_RESULT_LENGTH,
): String {
    require(maxLength > 0)
    // Bytes the backend consumed for the full slice. Older Conch servers do not report it; the UTF-8
    // length of the content is exact for valid UTF-8 there.
    val fullConsumed = if (result.bytesRead >= 0) {
        result.bytesRead
    } else {
        result.content.toByteArray(Charsets.UTF_8).size.toLong()
    }
    fun encode(content: String, truncated: Boolean, trimmed: Boolean): String = buildJsonObject {
        put("type", "file_read")
        put("server", server)
        put("path", path)
        put("content", content)
        put("lines", shellFileLineCount(content))
        put("total_lines", result.totalLines)
        put("total_bytes", result.totalBytes)
        val contentBytes = content.toByteArray(Charsets.UTF_8).size
        put("returned_bytes", contentBytes)
        put("offset", result.offset)
        put("limit", result.limit)
        if (result.sha256.isNotEmpty()) put("sha256", result.sha256)
        put("truncated", truncated)
        if (truncated) {
            // A JSON-trimmed slice ends on a character boundary, so its UTF-8 length is the safe
            // continuation point; an untrimmed one continues after every byte the backend consumed.
            put("next_offset", result.offset + if (trimmed) contentBytes.toLong() else fullConsumed)
        }
    }.toString()
    val fullResult = encode(result.content, result.truncated, trimmed = false)
    if (fullResult.length <= maxLength) return fullResult
    var low = 0
    var high = result.content.length
    var best = encode("", truncated = true, trimmed = true)
    while (low <= high) {
        val midpoint = low + (high - low) / 2
        val safeLength = if (
            midpoint in 1 until result.content.length &&
            result.content[midpoint - 1].isHighSurrogate() &&
            result.content[midpoint].isLowSurrogate()
        ) {
            midpoint - 1
        } else {
            midpoint
        }
        val candidate = encode(result.content.substring(0, safeLength), truncated = true, trimmed = true)
        if (candidate.length <= maxLength) {
            best = candidate
            low = midpoint + 1
        } else {
            high = midpoint - 1
        }
    }
    return best
}

/** One line returned by line-mode file_read, without its terminator. */
internal data class FileReadLine(
    val number: Int,
    val text: String,
    val endOffset: Long,
    /** The line was longer than [FILE_READ_MAX_LINE_CHARS] and was cut. */
    val clipped: Boolean = false,
)

internal data class FileReadLines(
    val lines: List<FileReadLine>,
    /** True when the file continues past the last returned line. */
    val hasMore: Boolean,
    val totalLines: Int,
    val totalBytes: Long,
    /** Set when start_line was not reached within the scan budget or the file ended first. */
    val notReached: Boolean = false,
    /** Byte offset where scanning stopped when [notReached] because of the scan budget. */
    val scanStoppedAt: Long = -1L,
    /** Whole-file sha256 from the first backend slice; empty when the backend omits it. */
    val sha256: String = "",
)

/**
 * Collects [maxLines] lines from [startLine] (1-based, counted from [offset]) by paging through
 * [read]. Lines split on LF; a trailing CR is stripped from the returned text but counted in
 * [FileReadLine.endOffset]. A backend error is passed to [onError] and the result is null.
 */
internal suspend fun collectFileReadLines(
    offset: Long,
    startLine: Int,
    maxLines: Int,
    maxContentBytes: Int = SHELL_FILE_READ_MAX_BYTES.toInt(),
    read: suspend (offset: Long, limit: Long) -> ShellFileReadResult,
    onError: (String) -> Unit,
): FileReadLines? {
    require(startLine >= 1 && maxLines >= 1)
    val collected = mutableListOf<FileReadLine>()
    var position = offset.coerceAtLeast(0)
    var lineNumber = 1
    // Bytes of the current (unterminated) line consumed so far, and its text when it is in range.
    var pendingBytes = 0L
    val pending = StringBuilder()
    var contentBytes = 0
    var totalLines = 0
    var totalBytes = 0L
    var sha256 = ""
    var first = true

    fun inRange() = lineNumber >= startLine
    fun finishLine(lineEnd: Long) {
        if (inRange()) {
            var text = pending.toString().removeSuffix("\r")
            val clipped = text.length > FILE_READ_MAX_LINE_CHARS
            if (clipped) {
                var cut = FILE_READ_MAX_LINE_CHARS
                if (text[cut - 1].isHighSurrogate()) cut -= 1
                text = text.substring(0, cut)
            }
            collected += FileReadLine(lineNumber, text, lineEnd, clipped)
            contentBytes += text.toByteArray(Charsets.UTF_8).size + 1
        }
        pending.setLength(0)
        pendingBytes = 0
        lineNumber += 1
    }
    fun full() = collected.size >= maxLines || contentBytes >= maxContentBytes

    repeat(FILE_READ_MAX_LINE_SCAN_READS) {
        val slice = read(position, SHELL_FILE_READ_MAX_BYTES)
        slice.error?.let { onError(it); return null }
        if (first) {
            totalLines = slice.totalLines
            totalBytes = slice.totalBytes
            sha256 = slice.sha256
            first = false
        }
        val consumed = if (slice.bytesRead >= 0) {
            slice.bytesRead
        } else {
            slice.content.toByteArray(Charsets.UTF_8).size.toLong()
        }
        // Byte offset of the character being scanned; leading bytes the backend trimmed (offset
        // inside a rune) are attributed to the first character.
        var cursor = position + (consumed - slice.content.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
        val text = slice.content
        var segmentStart = 0
        while (segmentStart <= text.length) {
            val newline = text.indexOf('\n', segmentStart)
            val segmentEnd = if (newline < 0) text.length else newline
            val segment = text.substring(segmentStart, segmentEnd)
            val segmentBytes = segment.toByteArray(Charsets.UTF_8).size
            if (inRange()) {
                val room = FILE_READ_MAX_LINE_CHARS + 1 - pending.length
                if (room > 0) pending.append(segment, 0, minOf(room, segment.length))
            }
            pendingBytes += segmentBytes
            cursor += segmentBytes
            if (newline < 0) break
            cursor += 1
            finishLine(cursor)
            if (full()) {
                val atEnd = !slice.truncated && newline == text.length - 1
                return FileReadLines(collected, hasMore = !atEnd, totalLines, totalBytes, sha256 = sha256)
            }
            segmentStart = newline + 1
        }
        position += consumed
        if (!slice.truncated) {
            // End of file: an unterminated final line still counts.
            if (pendingBytes > 0 || pending.isNotEmpty()) finishLine(position)
            return FileReadLines(
                collected,
                hasMore = false,
                totalLines = totalLines,
                totalBytes = totalBytes,
                notReached = collected.isEmpty() && startLine > 1,
                sha256 = sha256,
            )
        }
        if (consumed <= 0) {
            onError("file_read made no progress at byte $position")
            return null
        }
    }
    if (collected.isNotEmpty()) {
        // Budget exhausted mid-range: report what we have and point at the unread remainder.
        return FileReadLines(collected, hasMore = true, totalLines, totalBytes, sha256 = sha256)
    }
    return FileReadLines(
        collected,
        hasMore = true,
        totalLines = totalLines,
        totalBytes = totalBytes,
        notReached = true,
        scanStoppedAt = position,
        sha256 = sha256,
    )
}

/** Runs a line-mode file_read through [read] and returns the bounded tool JSON or an error. */
internal suspend fun lineModeFileReadJson(
    server: String,
    path: String,
    offset: Long,
    startLine: Int,
    maxLines: Int,
    read: suspend (offset: Long, limit: Long) -> ShellFileReadResult,
): String {
    var readError: String? = null
    val lines = try {
        collectFileReadLines(
            offset = offset,
            startLine = startLine,
            maxLines = maxLines.coerceIn(1, FILE_READ_MAX_LINES),
            read = read,
            onError = { readError = it },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return fileReadErrorJson(server, path, e.message ?: "Read failed")
    } ?: return fileReadErrorJson(server, path, readError ?: "Read failed")
    return boundedFileLinesJson(server, path, offset.coerceAtLeast(0), startLine, lines)
}

internal fun boundedFileLinesJson(
    server: String,
    path: String,
    offset: Long,
    startLine: Int,
    result: FileReadLines,
    maxLength: Int = Constants.MAX_TOOL_RESULT_LENGTH,
): String {
    require(maxLength > 0)
    fun encode(count: Int): String = buildJsonObject {
        val lines = result.lines.subList(0, count)
        val width = (lines.lastOrNull()?.number ?: startLine).toString().length.coerceAtLeast(6)
        put("type", "file_read")
        put("server", server)
        put("path", path)
        put(
            "content",
            lines.joinToString("\n") { line -> line.number.toString().padStart(width) + "\t" + line.text },
        )
        put("start_line", startLine)
        put("end_line", lines.lastOrNull()?.number ?: (startLine - 1))
        put("lines", count)
        put("total_lines", result.totalLines)
        put("total_bytes", result.totalBytes)
        if (result.sha256.isNotEmpty()) put("sha256", result.sha256)
        put("offset", offset)
        val hasMore = result.hasMore || count < result.lines.size
        put("has_more", hasMore)
        put("truncated", hasMore)
        if (hasMore) {
            val last = lines.lastOrNull()
            put("next_start_line", (last?.number ?: (startLine - 1)) + 1)
            when {
                last != null -> put("next_offset", last.endOffset)
                result.scanStoppedAt >= 0 -> put("next_offset", result.scanStoppedAt)
            }
        }
        if (lines.any { it.clipped }) put("long_lines_clipped_at_chars", FILE_READ_MAX_LINE_CHARS)
        if (result.notReached) {
            put(
                "message",
                if (result.scanStoppedAt >= 0) {
                    "start_line $startLine was not reached within the first 16MB scanned from offset $offset; " +
                        "continue with offset=${result.scanStoppedAt} (line numbers then restart at 1)"
                } else {
                    "start_line $startLine is past the end of the file"
                },
            )
        }
    }.toString()
    val full = encode(result.lines.size)
    if (full.length <= maxLength) return full
    // Drop trailing lines until the envelope fits; always a whole number of lines.
    var low = 0
    var high = result.lines.size - 1
    var best = encode(0)
    while (low <= high) {
        val mid = low + (high - low) / 2
        val candidate = encode(mid)
        if (candidate.length <= maxLength) {
            best = candidate
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}
