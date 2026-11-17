package com.newoether.agora.viewmodel

import androidx.room.withTransaction
import com.newoether.agora.data.local.AttachmentReferenceIndex
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ManagedAttachmentDirectories
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
    private val directoryStep: (String) -> Int = ManagedDirectoryFiles(filesDirectory)::step,
    private val exactUnlink: (String) -> Boolean = ManagedDirectoryFiles(filesDirectory)::unlink,
    private val beforeExactMutation: suspend () -> Unit = {},
) {
    private val references = AttachmentReferenceIndex(database)

    suspend fun sweep(): Boolean {
        references.refresh()
        var completed = true
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
                completed = deleteExact(file.absolutePath) && completed
            }
            yield()
        }
        return completed
    }

    suspend fun deleteDirectory(directory: String): Boolean =
        ManagedAttachmentDirectoryCleaner(database, filesDirectory, references, directoryStep).delete(directory)

    suspend fun deleteExact(path: String): Boolean {
        val root = filesDirectory.canonicalFile
        val managedRoot = File(root, "sandbox-home/attachments")
        // Classify the submitted spelling before resolving any untrusted Sandbox symlink.
        val absolute = File(path.removePrefix("file://")).absoluteFile.toPath().normalize().toString()
        val managedAlias = File(filesDirectory.absoluteFile, "sandbox-home/attachments")
            .toPath().normalize().toString()
        val relative = listOf(managedRoot.path, managedAlias).firstNotNullOfOrNull { prefix ->
            when {
                absolute == prefix -> ""
                absolute.startsWith(prefix + File.separator) -> absolute.removePrefix(prefix + File.separator)
                else -> null
            }
        }
        val virtual = relative?.let { ManagedAttachmentDirectories.SANDBOX_ROOT + it.replace(File.separatorChar, '/') }
        val directory = virtual?.let {
            requireNotNull(ManagedAttachmentDirectories.fromSandboxPath(it)) { "Invalid managed attachment debt" }
        }
        val normalized = if (relative != null) File(managedRoot, relative).path
            else AttachmentReferenceIndex.normalizePath(path)
        val file = File(normalized)
        require(file != root && file.path.startsWith(root.path + File.separator)) {
            "Attachment debt is outside app-private storage"
        }
        val revision = references.prepareCheck(normalized)
        beforeExactMutation()
        currentCoroutineContext().ensureActive()
        return database.withTransaction {
            if (!references.isCurrent(revision)) return@withTransaction false
            if (references.contains(normalized)) return@withTransaction true
            if (relative != null) {
                if (references.contains(AttachmentReferenceIndex.normalizePath(requireNotNull(virtual))) ||
                    database.conversationAttachmentDirectoryDao().isClaimed(requireNotNull(directory))) {
                    return@withTransaction true
                }
                // The live-owner fence covers the descriptor-relative unlink, with no Java fallback.
                return@withTransaction AttachmentFiles.withUnownedFile(file) { exactUnlink(relative) }
            }
            AttachmentFiles.deleteIfUnowned(file)
        }
    }

    private companion object {
        const val MINIMUM_FILE_AGE_MS = 60 * 60 * 1000L
    }
}
