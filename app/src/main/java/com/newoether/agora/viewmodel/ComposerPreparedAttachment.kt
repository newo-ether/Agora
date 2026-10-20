package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** CAS boundary for externally prepared immutable bytes; shares the ordinary Composer session. */
internal suspend fun ComposerOwnerSession.attachPrepared(
    drafts: ComposerDraftController,
    ownerId: String,
    attachment: SelectedAttachment,
    reload: suspend () -> Unit,
): Boolean = attachPreparedMany(drafts,ownerId,listOf(attachment),reload)

/**
 * One CAS commit for copied bytes: no partially attached group on failure or frozen send.
 * Ready receipts stay immutable. Staged file receipts may already have advanced through the normal
 * processor on replay, so their stable IDs identify adoption without resetting prepared state.
 */
internal suspend fun ComposerOwnerSession.attachPreparedMany(
    drafts:ComposerDraftController, ownerId:String, incoming:List<SelectedAttachment>, reload:suspend () -> Unit,
    processingJob: ((SelectedAttachment, Long) -> Job)? = null,
):Boolean = withContext(NonCancellable) {
    mutex.withLock {
        if (frozenSubmissionId != null) return@withLock false
        require(incoming.isNotEmpty() && incoming.size<=32 && incoming.map { it.localId }.distinct().size==incoming.size)
        require(incoming.all { it.localPath != null && (it.importState == AttachmentImportState.READY ||
            (processingJob != null && it.importState == AttachmentImportState.PROCESSING)) })
        fun resumeCopiedImports() {
            if (processingJob == null) return
            val ids = incoming.mapTo(hashSetOf()) { it.localId }
            state.value.attachments.filter { it.localId in ids && it.shouldStartProcessingJob() && jobs[it.localId] == null }
                .forEach { attachment ->
                    val generation = nextGeneration(attachment.localId)
                    registerJobLocked(attachment.localId, generation, processingJob(attachment, generation))
                }
        }
        while (true) {
            if(incoming.any { attachment -> durable.attachments.any { it.localId==attachment.localId && it!=attachment &&
                (attachment.importState != AttachmentImportState.PROCESSING || processingJob == null) } })return@withLock false
            val newAttachments=incoming.filter { attachment -> durable.attachments.none { it.localId==attachment.localId } }
            if(newAttachments.isEmpty()) { resumeCopiedImports(); return@withLock true }
            val current = state.value
            val attachments = durable.attachments + newAttachments
            val result = drafts.persist(ownerId, durable.revision, current.text, attachments)
            if (!result.succeeded) return@withLock false
            if (!result.matchesRequested) {
                reload()
                continue
            }
            durable = durable.copy(text = current.text, attachments = attachments, revision = result.revision)
            publish(current.copy(attachments = current.attachments + newAttachments, revision = result.revision))
            resumeCopiedImports()
            return@withLock true
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }
}
