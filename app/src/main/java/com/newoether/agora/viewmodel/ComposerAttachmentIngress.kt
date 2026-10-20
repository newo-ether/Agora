package com.newoether.agora.viewmodel

import com.newoether.agora.model.SelectedAttachment
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.withLock

/** The picker's existing transient ingress; copied external shares use the durable CAS boundary. */
internal suspend fun ComposerOwnerSession.importAttachment(
    attachment: SelectedAttachment,
    stagingJob: (SelectedAttachment, Long) -> Job,
): Boolean = mutex.withLock {
    if (frozenSubmissionId != null) return@withLock false
    check(state.value.attachments.none { it.localId == attachment.localId }) {
        "Attachment identity already belongs to this composer"
    }
    val processing = attachment.asProcessing()
    transientAttachmentIds += attachment.localId
    publish(state.value.copy(attachments = state.value.attachments + processing))
    val generation = nextGeneration(attachment.localId)
    registerJobLocked(attachment.localId, generation, stagingJob(processing, generation))
    true
}
