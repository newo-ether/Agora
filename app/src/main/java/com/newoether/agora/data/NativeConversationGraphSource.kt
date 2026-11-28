package com.newoether.agora.data

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.nio.channels.OverlappingFileLockException
import com.newoether.agora.data.local.MessageEntity
import java.io.SequenceInputStream
import java.nio.file.Files
import java.util.Collections
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal interface NativeGraphEntrySource {
    fun has(name: String): Boolean
    fun bytes(name: String): ByteArray?
    fun stream(name: String): InputStream?
}

internal class NativeConversationGraphSource private constructor(
    private val archive: NativeGraphEntrySource,
    private val legacyEntry: String?,
    private val spool: Spool?,
    private val cacheDir: File,
) : Closeable {
    private val handedOut = mutableListOf<InputStream>()

    fun open(): InputStream {
        val stream = legacyEntry?.let {
            checkNotNull(archive.stream(it))
        } ?: checkNotNull(spool).open()
        synchronized(handedOut) { handedOut += stream }
        return stream
    }

    /**
     * Decode and restore archive records before acquiring the writer. This private, transient
     * stream contains only objects written by this process; it is never an archive input format.
     * reset() prevents the serialization handle table retaining the entire imported history.
     */
    suspend fun <T> withPreparedMessages(
        prepare: suspend ((MessageEntity) -> Unit) -> Unit,
        commit: suspend (Sequence<MessageEntity>) -> T,
    ): T {
        val (file, access) = openPreparedFile(cacheDir)
        try {
            // On Android an unlinked open file survives only until its last handle closes,
            // including process death. Hosts that cannot unlink open files use the lock recovery below.
            file.delete()
            val channel = access.channel
            val outputStream = object : FilterOutputStream(Channels.newOutputStream(channel)) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
                override fun close() = flush()
            }
            ObjectOutputStream(outputStream.buffered()).use { output ->
                prepare { message ->
                    output.writeObject(message)
                    output.reset()
                }
                output.writeObject(null)
            }
            channel.position(0)
            val inputStream = object : FilterInputStream(Channels.newInputStream(channel)) {
                override fun close() = Unit
            }
            return ObjectInputStream(inputStream.buffered()).use { input ->
                commit(sequence {
                    while (true) {
                        val message = input.readObject() ?: break
                        yield(message as MessageEntity)
                    }
                })
            }
        } finally {
            try { access.close() } finally { file.delete() }
        }
    }

    override fun close() {
        synchronized(handedOut) {
            handedOut.forEach { stream -> runCatching { stream.close() } }
            handedOut.clear()
        }
        spool?.delete()
    }
    /**
     * The merged v5 graph, kept as one JSON array file per field. Reading it chains the arrays
     * under their field names, so each item is decompressed once however often the graph is read.
     */
    private class Spool(private val directory: File) {
        fun part(field: String): File = File(directory, "$field.json")
        fun open(): InputStream {
            val pieces = mutableListOf<InputStream>()
            try {
                GRAPH_FIELDS.forEachIndexed { index, field ->
                    val separator = if (index == 0) "{" else ","
                    pieces += ByteArrayInputStream("$separator\"$field\":".encodeToByteArray())
                    pieces += part(field).inputStream()
                }
                pieces += ByteArrayInputStream("}".encodeToByteArray())
            } catch (error: Throwable) {
                pieces.forEach { runCatching { it.close() } }
                throw error
            }
            return SequenceInputStream(Collections.enumeration(pieces))
        }
        fun delete() {
            if (!directory.deleteRecursively()) directory.deleteOnExit()
        }
    }

    companion object {
        private val preparedFileGuard = Any()
        private const val PREPARED_PREFIX = "agora-import-messages-"

        /** Only our unlocked temporary files are disposable; another active import keeps its lock. */
        private fun openPreparedFile(cacheDir: File): Pair<File, RandomAccessFile> = synchronized(preparedFileGuard) {
            cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith(PREPARED_PREFIX) && file.name.endsWith(".bin") &&
                    !Files.isSymbolicLink(file.toPath())
            }.orEmpty().forEach { stale ->
                val unused = runCatching {
                    RandomAccessFile(stale, "rw").use { access ->
                        val lock = try { access.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                        if (lock == null) false else { lock.release(); true }
                    }
                }.getOrDefault(false)
                if (unused) stale.delete()
            }
            val file = File.createTempFile(PREPARED_PREFIX, ".bin", cacheDir)
            var access: RandomAccessFile? = null
            try {
                access = RandomAccessFile(file, "rw")
                access.channel.lock()
                file to access
            } catch (error: Throwable) {
                access?.close()
                file.delete()
                throw error
            }
        }

        fun open(archive: NativeGraphEntrySource, version: Int, cacheDir: File): NativeConversationGraphSource {
            if (version < 5) {
                require(archive.has(NativeBackupFormat.CONVERSATIONS_ENTRY))
                return NativeConversationGraphSource(
                    archive, NativeBackupFormat.CONVERSATIONS_ENTRY, null, cacheDir,
                )
            }
            val index = archive.bytes(NativeBackupFormat.CONVERSATION_INDEX_ENTRY)
                ?.decodeToString()
                ?.let { Json.decodeFromString<NativeConversationIndex>(it) }
                ?: error("${NativeBackupFormat.CONVERSATION_INDEX_ENTRY} is missing")
            val spool = Spool(Files.createTempDirectory(cacheDir.toPath(), "agora-import-v5-").toFile())
            val writers = mutableMapOf<String, JsonWriter>()
            try {
                val seenIds = mutableSetOf<String>()
                index.conversations.forEach { item ->
                    require(seenIds.add(item.id)) { "Duplicate conversation id in index" }
                    require(item.entry == NativeBackupFormat.conversationEntry(item.id)) {
                        "Index entry ${item.entry} does not match id ${item.id}"
                    }
                }
                GRAPH_FIELDS.forEach { field ->
                    writers[field] = JsonWriter(
                        OutputStreamWriter(spool.part(field).outputStream().buffered(), Charsets.UTF_8),
                    ).apply { beginArray() }
                }
                // One pass per item: every field is routed to its own array file.
                index.conversations.forEach { item ->
                    archive.stream(item.entry)?.use { splitItem(it, writers) }
                        ?: error("${item.entry} is missing")
                }
                archive.stream(NativeBackupFormat.TASKS_ENTRY)?.use {
                    copyArrayField(it, "tasks", writers.getValue("tasks"))
                } ?: error("${NativeBackupFormat.TASKS_ENTRY} is missing")
                GRAPH_FIELDS.forEach { field ->
                    writers.remove(field)!!.apply { endArray() }.close()
                }
                return NativeConversationGraphSource(archive, null, spool, cacheDir)
            } catch (error: Throwable) {
                writers.values.forEach { runCatching { it.close() } }
                spool.delete()
                throw error
            }
        }
        private val GRAPH_FIELDS = listOf("conversations", "runs", "messages", "loops", "tasks")
        // The readers below are not closed: the caller owns the stream, and reading it to its end
        // after the parse is what verifies an archive entry.
        private fun splitItem(stream: InputStream, writers: Map<String, JsonWriter>) {
            val reader = JsonReader(InputStreamReader(stream, Charsets.UTF_8))
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                val writer = writers[name]?.takeIf { name != "tasks" }
                if (writer == null) {
                    reader.skipValue()
                    continue
                }
                reader.beginArray()
                while (reader.hasNext()) copyValue(reader, writer)
                reader.endArray()
            }
            reader.endObject()
            stream.readToEnd()
        }
        private fun copyArrayField(stream: InputStream, field: String, writer: JsonWriter) {
            val reader = JsonReader(InputStreamReader(stream, Charsets.UTF_8))
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == field) {
                    reader.beginArray()
                    while (reader.hasNext()) copyValue(reader, writer)
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            stream.readToEnd()
        }

        private fun copyValue(reader: JsonReader, writer: JsonWriter) {
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray(); writer.beginArray()
                    while (reader.hasNext()) copyValue(reader, writer)
                    reader.endArray(); writer.endArray()
                }
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject(); writer.beginObject()
                    while (reader.hasNext()) {
                        writer.name(reader.nextName())
                        copyValue(reader, writer)
                    }
                    reader.endObject(); writer.endObject()
                }
                JsonToken.STRING -> writer.value(reader.nextString())
                JsonToken.NUMBER -> {
                    val raw = reader.nextString()
                    val asLong = raw.toLongOrNull()
                    if (asLong != null) writer.value(asLong) else writer.value(raw.toDouble())
                }
                JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); writer.nullValue() }
                else -> error("Unexpected JSON token ${reader.peek()}")
            }
        }
    }
}
