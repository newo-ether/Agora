package com.newoether.agora.viewmodel

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import com.newoether.agora.util.AttachmentFiles

/** One admitted draft owner. The controller retains ownership of its lifetime and locking. */
internal class ComposerOwnerSession {
    fun publish(snapshot: ConversationComposerSnapshot) {
        AttachmentFiles.setLivePaths(this, AttachmentFiles.ownedPaths(snapshot.attachments))
        state.value = snapshot.copy(ingressRetryVersion = ingressRetryVersion)
    }
    val mutex = Mutex()
    val state = kotlinx.coroutines.flow.MutableStateFlow(ConversationComposerSnapshot())
    var durable = ConversationComposerSnapshot()
    var retainCount = 0
    var selectedRetainCount = 0
    var selectionOrder = 0L
    var commandCount = 0
    val jobCount = AtomicInteger()
    val transientAttachmentIds = mutableSetOf<String>()
    val generations = mutableMapOf<String, Long>()
    val jobs = mutableMapOf<String, Job>()
    var frozenSubmissionId: Long? = null
    var ingressRetryVersion = 0L

    fun completeRemovalLocked(attachmentId: String) {
        transientAttachmentIds -= attachmentId
        nextGeneration(attachmentId)
        jobs[attachmentId]?.cancel()
    }
    fun registerJobLocked(
        attachmentId: String,
        generation: Long,
        job: Job,
    ) {
        if (generations[attachmentId] != generation) {
            job.cancel()
            return
        }
        val previous = jobs.put(attachmentId, job)
        jobCount.incrementAndGet()
        previous?.cancel()
        if (!job.start()) {
            if (jobs[attachmentId] === job) jobs.remove(attachmentId)
            check(jobCount.decrementAndGet() >= 0) { "Composer job count underflow" }
        }
    }
    fun nextGeneration(attachmentId: String): Long {
        val next = (generations[attachmentId] ?: 0L) + 1L
        generations[attachmentId] = next
        return next
    }
}
