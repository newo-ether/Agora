package com.newoether.agora.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

/**
 * Streams version 5 archive entries from single-record iterators. No caller-visible step ever
 * materialises the whole snapshot: each conversation entry is assembled while its already
 * rewritten records stream past, byte-equivalent to the previous per-entry JsonObject assembly.
 *
 * Conversation items and media are added as raw entries with precomputed sizes and CRCs, so the
 * CRC of their stored bytes is known and recorded in [NativeBackupFormat.RAW_CHECKSUMS_ENTRY].
 */
internal object NativeBackupV5Writer {
    private val CONVERSATION_OPEN = "{\"conversations\":[".encodeToByteArray()
    private val RUNS_SEPARATOR = "],\"runs\":[".encodeToByteArray()
    private val MESSAGES_SEPARATOR = "],\"messages\":[".encodeToByteArray()
    private val LOOPS_SEPARATOR = "],\"loops\":[".encodeToByteArray()
    private val ARRAY_CLOSE = "]}".encodeToByteArray()
    private val TASKS_OPEN = "{\"tasks\":[".encodeToByteArray()
    private val RECORD_SEPARATOR = ','.code
    private const val BUFFER_BYTES = 256 * 1024
    /** Level 3 deflates JSON about twice as fast as the default level for roughly 12% more bytes. */
    internal const val ENTRY_DEFLATE_LEVEL = 3

    /** Raw-copies an unchanged conversation item and its referenced media from the baseline. */
    fun copyConversationFromBaseline(
        zip: ZipArchiveOutputStream,
        baseline: NativeBackupV5Baseline,
        baselineEntry: NativeConversationIndexEntry,
        copiedMedia: MutableSet<String>,
        checksums: MutableMap<String, Long>,
    ): NativeConversationIndexEntry {
        baseline.copyRaw(baselineEntry.entry, zip, checksums)
        baselineEntry.mediaEntries.forEach { media ->
            if (copiedMedia.add(media)) baseline.copyRaw(media, zip, checksums)
        }
        return baselineEntry
    }

    /**
     * Writes one `conv/items/<hex-id>.json` entry from streamed records. The callers supply
     * already archive-rewritten JSON, and [mediaEntries] lists the media entries this payload
     * references so an incremental baseline can raw-copy them later. The payload is deflated into
     * a scratch file in [scratchDir] first, which yields the sizes and both CRCs up front.
     */
    fun writeConversationEntry(
        zip: ZipArchiveOutputStream,
        conversationId: String,
        dataChangedAt: Long,
        conversationJson: String,
        runs: Iterator<String>,
        messages: Iterator<String>,
        loops: Iterator<String>,
        mediaEntries: Set<String>,
        scratchDir: File,
        checksums: MutableMap<String, Long>,
    ): NativeConversationIndexEntry {
        val entry = NativeBackupFormat.conversationEntry(conversationId)
        writeDeflatedEntry(zip, entry, scratchDir, checksums) { output ->
            output.write(CONVERSATION_OPEN)
            output.writeJson(conversationJson)
            output.write(RUNS_SEPARATOR)
            output.writeRecords(runs)
            output.write(MESSAGES_SEPARATOR)
            output.writeRecords(messages)
            output.write(LOOPS_SEPARATOR)
            output.writeRecords(loops)
            output.write(ARRAY_CLOSE)
        }
        return NativeConversationIndexEntry(
            id = conversationId,
            dataChangedAt = dataChangedAt,
            entry = entry,
            mediaEntries = mediaEntries.sorted(),
        )
    }

    /**
     * Adds already compressed media without deflating it again. [size] and [crc] come from a
     * first read of the same source; the bytes of [input] are checked against them while copied,
     * so a source that changed in between fails the export instead of storing a corrupt entry.
     */
    fun writeStoredEntry(
        zip: ZipArchiveOutputStream,
        name: String,
        input: InputStream,
        size: Long,
        crc: Long,
        checksums: MutableMap<String, Long>,
    ) {
        val entry = ZipArchiveEntry(name).apply {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            this.crc = crc
        }
        val verifying = CountingCrcInputStream(BufferedInputStream(input, BUFFER_BYTES))
        verifying.use { zip.addRawArchiveEntry(entry, it) }
        if (verifying.count != size || verifying.crc.value != crc) {
            throw IOException("Media changed while it was being backed up: $name")
        }
        checksums[name] = crc
    }

    /** Writes `conv/tasks.json` from raw task records. */
    fun writeTasksEntry(zip: ZipArchiveOutputStream, tasks: Iterator<String>) {
        zip.putArchiveEntry(ZipArchiveEntry(NativeBackupFormat.TASKS_ENTRY))
        zip.write(TASKS_OPEN)
        zip.writeRecords(tasks)
        zip.write(ARRAY_CLOSE)
        zip.closeArchiveEntry()
    }

    /** Writes `conv/index.json` describing every emitted conversation entry. */
    fun writeConversationIndex(zip: ZipArchiveOutputStream, index: List<NativeConversationIndexEntry>) {
        zip.putArchiveEntry(ZipArchiveEntry(NativeBackupFormat.CONVERSATION_INDEX_ENTRY))
        zip.writeJson(Json.encodeToString(NativeConversationIndex(index)))
        zip.closeArchiveEntry()
    }

    /** Writes the raw checksums of every conversation item and media entry in this archive. */
    fun writeRawChecksums(zip: ZipArchiveOutputStream, checksums: Map<String, Long>) {
        zip.putArchiveEntry(ZipArchiveEntry(NativeBackupFormat.RAW_CHECKSUMS_ENTRY))
        zip.writeJson(Json.encodeToString(NativeBackupRawChecksums(checksums.toSortedMap())))
        zip.closeArchiveEntry()
    }

    private fun writeDeflatedEntry(
        zip: ZipArchiveOutputStream,
        name: String,
        scratchDir: File,
        checksums: MutableMap<String, Long>,
        writePayload: (OutputStream) -> Unit,
    ) {
        val scratch = File.createTempFile("agora-backup-entry-", ".deflate", scratchDir)
        try {
            val deflater = Deflater(ENTRY_DEFLATE_LEVEL, true)
            val file = FileOutputStream(scratch)
            val compressed = CountingCrcOutputStream(BufferedOutputStream(file, BUFFER_BYTES))
            val deflating = DeflaterOutputStream(compressed, deflater, BUFFER_BYTES)
            val plain = CountingCrcOutputStream(deflating)
            try {
                val buffered = BufferedOutputStream(plain, BUFFER_BYTES)
                writePayload(buffered)
                buffered.flush()
                deflating.finish()
                compressed.flush()
            } finally {
                file.close()
                deflater.end()
            }
            val entry = ZipArchiveEntry(name).apply {
                method = ZipEntry.DEFLATED
                size = plain.count
                compressedSize = compressed.count
                crc = plain.crc.value
            }
            BufferedInputStream(FileInputStream(scratch), BUFFER_BYTES).use { input ->
                zip.addRawArchiveEntry(entry, input)
            }
            checksums[name] = compressed.crc.value
        } finally {
            scratch.delete()
        }
    }

    private fun OutputStream.writeRecords(records: Iterator<String>) {
        var first = true
        while (records.hasNext()) {
            if (!first) write(RECORD_SEPARATOR)
            first = false
            writeJson(records.next())
        }
    }

    private fun OutputStream.writeJson(value: String) {
        write(value.encodeToByteArray())
    }
}

/** Counts and checksums every byte written through it. */
private class CountingCrcOutputStream(output: OutputStream) : FilterOutputStream(output) {
    val crc = CRC32()
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        crc.update(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        crc.update(b, off, len)
        count += len
    }
}

/** Counts and checksums every byte read through it. */
private class CountingCrcInputStream(input: InputStream) : java.io.FilterInputStream(input) {
    val crc = CRC32()
    var count = 0L
        private set

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            crc.update(value)
            count++
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read > 0) {
            crc.update(b, off, read)
            count += read
        }
        return read
    }

    override fun skip(n: Long): Long = throw IOException("Skipping is not supported")

    override fun markSupported(): Boolean = false
}
