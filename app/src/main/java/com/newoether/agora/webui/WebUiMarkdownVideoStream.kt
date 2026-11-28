package com.newoether.agora.webui

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseRangesSpecifier
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The single byte range a media request resolved to; the browser seeks with these. */
internal sealed interface WebUiByteRange {
    data object Whole : WebUiByteRange
    data class Partial(val first: Long, val last: Long) : WebUiByteRange

    /** A well-formed range outside the file, answered with 416 and the current size. */
    data object Unsatisfiable : WebUiByteRange
}

/**
 * Parses at most one byte range. Multipart ranges and validator-bound requests stay whole-file, as
 * in the attachment stream; a malformed header never fails a plain read.
 */
internal fun webUiByteRange(size: Long, header: String?, ifRange: String?): WebUiByteRange {
    val spec = header?.takeIf { ifRange == null }?.let(::parseRangesSpecifier)
        ?.takeIf { it.ranges.size == 1 } ?: return WebUiByteRange.Whole
    val bytes = spec.merge(size).singleOrNull() ?: return WebUiByteRange.Unsatisfiable
    return WebUiByteRange.Partial(bytes.first, bytes.last)
}

/**
 * Streams one Markdown video once, honouring one range so the player can seek. A local file is read
 * from disk; a Conch source is copied chunk by chunk through the file-transfer envelope.
 */
internal suspend fun ApplicationCall.respondMarkdownVideo(
    source: WebUiMarkdownVideoSource,
    isSessionValid: suspend () -> Boolean,
) {
    val size = when (source) {
        is WebUiMarkdownVideoSource.Local -> source.file.length()
        is WebUiMarkdownVideoSource.Conch -> source.size
    }
    val mimeType = when (source) {
        is WebUiMarkdownVideoSource.Local -> source.mimeType
        is WebUiMarkdownVideoSource.Conch -> source.mimeType
    }
    val range = webUiByteRange(
        size,
        request.headers.getAll(HttpHeaders.Range)?.singleOrNull(),
        request.headers[HttpHeaders.IfRange],
    )
    response.header(HttpHeaders.AcceptRanges, "bytes")
    if (range is WebUiByteRange.Unsatisfiable) {
        response.header(HttpHeaders.ContentRange, "bytes */$size")
        respond(HttpStatusCode.RequestedRangeNotSatisfiable)
        return
    }
    val partial = range as? WebUiByteRange.Partial
    val first = partial?.first ?: 0L
    val length = if (partial == null) size else partial.last - partial.first + 1
    if (partial != null) {
        response.header(HttpHeaders.ContentRange, "bytes ${partial.first}-${partial.last}/$size")
    }
    respondOutputStream(
        ContentType.parse(mimeType),
        status = if (partial == null) HttpStatusCode.OK else HttpStatusCode.PartialContent,
        contentLength = length,
    ) {
        withContext(Dispatchers.IO) {
            when (source) {
                is WebUiMarkdownVideoSource.Local -> copyVideoFile(source, size, first, length, isSessionValid)
                is WebUiMarkdownVideoSource.Conch -> copyVideoSource(source, first, length, isSessionValid)
            }
        }
    }
}

private suspend fun OutputStream.copyVideoFile(
    source: WebUiMarkdownVideoSource.Local,
    size: Long,
    first: Long,
    length: Long,
    isSessionValid: suspend () -> Boolean,
) {
    FileInputStream(source.file).use { input ->
        if (input.channel.size() != size) throw IOException("Video size changed before response")
        input.channel.position(first)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            if (!isSessionValid()) throw CancellationException("Session revoked")
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Video ended before its recorded size")
            write(buffer, 0, count)
            remaining -= count
        }
    }
}

private suspend fun OutputStream.copyVideoSource(
    source: WebUiMarkdownVideoSource.Conch,
    first: Long,
    length: Long,
    isSessionValid: suspend () -> Boolean,
) {
    var offset = first
    var remaining = length
    while (remaining > 0) {
        currentCoroutineContext().ensureActive()
        if (!isSessionValid()) throw CancellationException("Session revoked")
        val chunk = source.endpoint.read(source.path, offset, source.size)
        if (chunk.isEmpty()) throw IOException("Video source returned an empty chunk")
        val count = minOf(chunk.size.toLong(), remaining).toInt()
        write(chunk, 0, count)
        offset += count
        remaining -= count
    }
}
