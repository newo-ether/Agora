package com.newoether.agora.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** Each CursorWindow holds at most one 64 KiB slice; no cursor survives JSON decoding. */
internal class AttachmentReferencePayload(
    private val database: SupportSQLiteDatabase,
    private val table: String,
    private val column: String,
    private val ownerId: String,
    private val context: CoroutineContext,
) : InputStream() {
    private var position = 1L
    private var chunk = ByteArray(0)
    private var offset = 0
    private var end = false

    private fun availableChunk(): Boolean {
        context.ensureActive()
        if (offset < chunk.size) return true
        if (end) return false
        chunk = database.query(
            "SELECT SUBSTR(CAST($column AS BLOB), ?, ?) FROM $table WHERE id = ?",
            arrayOf<Any>(position, CHUNK_SIZE, ownerId),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getBlob(0) else ByteArray(0)
        }
        position += chunk.size
        offset = 0
        end = chunk.size < CHUNK_SIZE
        return chunk.isNotEmpty()
    }

    override fun read(): Int = if (availableChunk()) chunk[offset++].toInt() and 0xff else -1

    override fun read(bytes: ByteArray, start: Int, length: Int): Int {
        require(start >= 0 && length >= 0 && start <= bytes.size - length)
        if (length == 0) return 0
        if (!availableChunk()) return -1
        val count = minOf(length, chunk.size - offset)
        chunk.copyInto(bytes, start, offset, offset + count)
        offset += count
        return count
    }

    companion object { private const val CHUNK_SIZE = 64 * 1024 }
}
