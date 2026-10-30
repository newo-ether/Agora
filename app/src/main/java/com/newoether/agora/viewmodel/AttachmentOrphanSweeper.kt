package com.newoether.agora.viewmodel

import androidx.room.withTransaction
import com.newoether.agora.data.local.AttachmentReferenceIndex
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.util.AttachmentFiles
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Reclaims private attachments only while durable and live ownership are stable. */
internal class AttachmentOrphanSweeper(
    private val database: ChatDatabase,
    private val filesDirectory: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val references = AttachmentReferenceIndex(database)

    suspend fun sweep() {
        references.refresh()
        val cutoffNow = now()
        val prefixes = arrayOf("att_", "vid_", "img_", "pdf_")
        val candidates = sequence {
            yieldAll(filesDirectory.listFiles { file ->
                file.isFile && prefixes.any { file.name.startsWith(it) }
            }.orEmpty().asSequence())
            yieldAll(File(filesDirectory, "images").listFiles { file ->
                file.isFile && file.name.startsWith("camera_")
            }.orEmpty().asSequence())
            listOf("run-inputs", "fork-attachments").forEach { directory ->
                yieldAll(File(filesDirectory, directory).listFiles { it.isFile }.orEmpty().asSequence())
            }
        }
        for (file in candidates) {
            currentCoroutineContext().ensureActive()
            if (cutoffNow - file.lastModified() > MINIMUM_FILE_AGE_MS) {
                deleteExact(file.absolutePath)
            }
            yield()
        }
    }

    suspend fun deleteExact(path: String): Boolean {
        val normalized = AttachmentReferenceIndex.normalizePath(path)
        val root = filesDirectory.canonicalFile
        val file = File(normalized).canonicalFile
        require(file != root && file.path.startsWith(root.path + File.separator)) {
            "Attachment debt is outside app-private storage"
        }
        val revision = references.prepareCheck(normalized)
        val completed = database.withTransaction {
            if (!references.isCurrent(revision)) return@withTransaction false
            if (references.contains(normalized)) return@withTransaction true
            // Send's durable transfer and the live-owner unlink fence remain atomic.
            AttachmentFiles.deleteIfUnowned(file)
        }
        val sandboxRoot = File(root, "sandbox-home").canonicalFile
        val parent = file.parentFile?.canonicalFile
        if (completed && parent != null && parent != sandboxRoot &&
            parent.path.startsWith(sandboxRoot.path + File.separator)) {
            runCatching { parent.takeIf { it.listFiles().isNullOrEmpty() }?.delete() }
        }
        return completed
    }

    private companion object {
        const val MINIMUM_FILE_AGE_MS = 60 * 60 * 1000L
    }
}
