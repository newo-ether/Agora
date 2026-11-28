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

/** Processes only exact directory debt; ownership checks and detachment share one short transaction. */
internal class ManagedAttachmentDirectoryCleaner(
    private val database: ChatDatabase,
    filesDirectory: File,
    private val references: AttachmentReferenceIndex,
    private val step: (String) -> Int = ManagedDirectoryFiles(filesDirectory)::step,
    private val beforeMutation: suspend () -> Unit = {},
) {
    private val root = File(filesDirectory, "sandbox-home/attachments").absoluteFile

    suspend fun delete(directory: String): Boolean {
        require(ManagedAttachmentDirectories.isDirectory(directory)) { "Invalid managed attachment directory" }
        val target = File(root, directory)
        val virtual = AttachmentReferenceIndex.normalizePath(ManagedAttachmentDirectories.SANDBOX_ROOT + directory)
        val revision = references.prepareCheck(target.path)
        repeat(MAX_STEPS) {
            currentCoroutineContext().ensureActive()
            beforeMutation()
            val result = database.withTransaction {
                if (!references.isCurrent(revision)) return@withTransaction RETRY
                if (database.conversationAttachmentDirectoryDao().isClaimed(directory) ||
                    references.containsWithin(target.path) || references.containsWithin(virtual)) {
                    return@withTransaction DONE
                }
                var outcome = RETRY
                AttachmentFiles.withUnownedDirectory(target) {
                    outcome = step(directory)
                    true
                }
                outcome
            }
            if (result == DONE) return true
            if (result == RETRY) return false
            yield()
        }
        // Detachment and each leaf unlink are resumable under the same durable directory debt.
        return false
    }

    private companion object {
        const val MAX_STEPS = 128
        const val DONE = 0
        const val RETRY = -1
    }
}
