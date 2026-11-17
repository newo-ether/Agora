package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.ToolImageAttachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Durable first-read display snapshots. Receipts identify occurrences; hashes identify bytes. */
internal class MarkdownImageSnapshotStore(private val directory: File) {
    @Serializable
    internal data class Snapshot(val sha256: String, val image: ToolImageAttachment)

    suspend fun load(occurrence: String, fetch: suspend () -> ToolImageAttachment): Snapshot =
        withContext(Dispatchers.IO) {
            val key = digest(occurrence.toByteArray(Charsets.UTF_8))
            val receipt = File(directory, "$key.json")
            read(receipt)?.let { return@withContext it }
            val flightKey = receipt.absolutePath
            val pending = CompletableDeferred<Snapshot>()
            val existing = flights.putIfAbsent(flightKey, pending)
            if (existing != null) return@withContext existing.await()
            try {
                // Native and browser callers share bounded IO and one receipt publication.
                val result = reads.withPermit {
                    read(receipt) ?: publish(receipt, fetch())
                }
                pending.complete(result)
                result
            } catch (error: Throwable) {
                pending.completeExceptionally(error)
                throw error
            } finally {
                flights.remove(flightKey, pending)
            }
        }

    private fun read(receipt: File): Snapshot? {
        if (!receipt.exists()) return null
        val snapshot = Json.decodeFromString<Snapshot>(receipt.readText())
        // A committed snapshot is immutable: never substitute today's remote bytes if lost.
        if (!File(snapshot.image.path).isFile) throw IOException("Markdown image snapshot is missing")
        return snapshot
    }

    private suspend fun publish(receipt: File, image: ToolImageAttachment): Snapshot {
        val source = File(image.path)
        val temporary = File(directory, ".${receipt.name}.${UUID.randomUUID()}.tmp")
        var committed = false
        try {
            val sha256 = source.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().hex()
            }
            val snapshot = Snapshot(sha256, image)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create image snapshot store")
            temporary.outputStream().use { output ->
                output.write(Json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            coroutineContext.ensureActive()
            if (!temporary.renameTo(receipt)) throw IOException("Could not publish image snapshot")
            committed = true
            return snapshot
        } finally {
            temporary.delete()
            if (!committed) source.delete()
        }
    }

    companion object {
        private val flights = ConcurrentHashMap<String, CompletableDeferred<Snapshot>>()
        private val reads = Semaphore(4)
        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
