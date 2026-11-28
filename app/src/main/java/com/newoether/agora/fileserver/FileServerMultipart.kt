package com.newoether.agora.fileserver

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Streaming multipart/form-data parser for the file server's upload path.
 *
 * The body is spooled to a temp file as it arrives so a multi-gigabyte upload is never held in
 * memory as one blob. Boundary offsets are then located by a windowed scan (so a marker split
 * across read chunks is still found), and each part's content is copied straight to its
 * destination file. Filenames are sanitized to their last path segment and `.`/`..` are rejected,
 * so a crafted part name cannot escape the upload directory.
 */
internal object FileServerMultipart {

    const val SPOOL_BLOCK = 1 shl 18 // 256KB streaming chunks
    private const val HEADER_MAX = 1 shl 14 // 16KB part-header scan limit

    /**
     * Parses the spooled multipart [body] delimited by [boundary], writing each file part into
     * [uploadDir]. Returns the number of files saved.
     */
    fun parseInto(body: File, boundary: String, uploadDir: File): Int {
        val pattern = boundary.toByteArray()
        val bLen = pattern.size
        if (bLen <= 2) return 0
        if (!uploadDir.exists()) uploadDir.mkdirs()
        val positions = findBoundaryOffsets(body, pattern)
        RandomAccessFile(body, "r").use { raf ->
            var saved = 0
            for (k in 1 until positions.size) {
                val prev = positions[k - 1]
                val next = positions[k]

                // Locate the end of this part's headers ("\r\n\r\n").
                val headerStart = prev + bLen + 2 // skip "--boundary\r\n"
                val headerEnd = findHeaderEnd(raf, headerStart, next)
                if (headerEnd < 0) continue

                val headerLen = (headerEnd - headerStart).toInt()
                if (headerLen <= 0) continue
                val headerText = ByteArray(headerLen)
                raf.seek(headerStart)
                raf.readFully(headerText)
                val partHeaders = String(headerText, Charsets.UTF_8)
                val filenameMatch = Regex("filename=\"([^\"]+)\"").find(partHeaders) ?: continue
                val filename = filenameMatch.groupValues[1]
                val safeFilename = filename.split("/", "\\").lastOrNull()
                    ?.takeIf { it.isNotBlank() && it != "." && it != ".." }
                    ?: continue

                val contentStart = headerEnd + 4 // skip "\r\n\r\n"
                val contentEnd = next - 2 // strip trailing "\r\n"
                if (contentEnd <= contentStart) continue

                FileOutputStream(File(uploadDir, safeFilename)).use { outFs ->
                    raf.seek(contentStart)
                    val copyBuf = ByteArray(SPOOL_BLOCK)
                    var remaining = contentEnd - contentStart
                    while (remaining > 0) {
                        val toRead = minOf(copyBuf.size.toLong(), remaining).toInt()
                        raf.readFully(copyBuf, 0, toRead)
                        outFs.write(copyBuf, 0, toRead)
                        remaining -= toRead
                    }
                }
                saved++
            }
            return saved
        }
    }

    /**
     * Scans [f] for every occurrence of [pattern], keeping a tail window between reads so a marker
     * split across chunk boundaries is still detected.
     */
    private fun findBoundaryOffsets(f: File, pattern: ByteArray): List<Long> {
        val bLen = pattern.size
        val positions = mutableListOf<Long>()
        BufferedInputStream(FileInputStream(f), SPOOL_BLOCK).use { ins ->
            val buf = ByteArray(SPOOL_BLOCK)
            var prev = byteArrayOf()
            var base = 0L
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                val data = if (prev.isEmpty()) buf.copyOf(n) else prev + buf.copyOf(n)
                var i = 0
                val end = data.size - bLen
                while (i <= end) {
                    if (matchesAt(data, i, pattern)) {
                        positions.add(base + i)
                        i += bLen
                    } else {
                        i++
                    }
                }
                val keep = data.size - (bLen - 1)
                if (keep > 0) {
                    prev = data.copyOfRange(keep, data.size)
                    base += keep
                } else {
                    prev = data
                }
            }
        }
        return positions
    }

    private fun findHeaderEnd(raf: RandomAccessFile, start: Long, limit: Long): Long {
        val maxRead = (limit - start).coerceAtMost(HEADER_MAX.toLong())
        if (maxRead < 4) return -1
        val buf = ByteArray(maxRead.toInt())
        raf.seek(start)
        raf.readFully(buf)
        for (i in 0..buf.size - 4) {
            if (buf[i] == '\r'.code.toByte() && buf[i + 1] == '\n'.code.toByte() &&
                buf[i + 2] == '\r'.code.toByte() && buf[i + 3] == '\n'.code.toByte()
            ) {
                return start + i
            }
        }
        return -1
    }

    private fun matchesAt(data: ByteArray, pos: Int, pattern: ByteArray): Boolean {
        for (j in pattern.indices) {
            if (data[pos + j] != pattern[j]) return false
        }
        return true
    }
}
