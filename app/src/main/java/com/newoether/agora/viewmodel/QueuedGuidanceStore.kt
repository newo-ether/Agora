package com.newoether.agora.viewmodel

import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.AttachmentFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * A message queued behind an in-progress generation. It deliberately remains outside Room until
 * the next durable boundary accepts it into a fresh Run.
 */
internal data class QueuedSend(
    val id: String,
    val text: String,
    /** Model selected in the originating conversation when Send was tapped. */
    val modelId: String,
    val attachments: List<SelectedAttachment>,
    /** Provenance only; drain always creates a fresh Run and never reuses this id. */
    val runId: String,
    /** Pure projection of canonical READY attachment artifacts. */
    val preparedImages: List<String> = emptyList(),
    val preparedAttachmentMetaJson: String? = null,
    /** Immutable foreground admission; legacy/internal queue producers may capture at drain time. */
    val generationSnapshot: GenerationAdmissionSnapshot? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Null for a message the user typed; set for automatic input such as ask_user answers. */
    val source: MessageSource? = null,
    /** Retained until the ordinary generation owns admission, or this pending input is discarded. */
    val releaseGenerationAdmission: (() -> Unit)? = null,
)

internal data class GuidanceBatchLease(
    val id: String,
    val batch: List<QueuedSend>,
) {
    init {
        require(id.isNotBlank())
        require(batch.isNotEmpty())
    }
}

/**
 * Sole owner of pending and claimed in-memory guidance for one conversation.
 *
 * A claim transfers the complete FIFO batch to one lease. Failed claims return to the front;
 * durable claims transfer attachment ownership to Room; disposal owns only still-pending cleanup.
 */
internal class GuidanceLeaseStore(
    private val reclaimAttachments: (List<SelectedAttachment>) -> Unit,
    private val newLeaseId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val _queuedSends = MutableStateFlow<List<QueuedSend>>(emptyList())
    val queuedSends: StateFlow<List<QueuedSend>> = _queuedSends.asStateFlow()

    private val claimedGuidance = mutableMapOf<String, List<QueuedSend>>()
    private var claimRevision = 0L
    private var disposed = false

    /** Monotonic evidence that pending guidance became the next ordinary generation input. */
    fun currentClaimRevision(): Long = synchronized(lock) { claimRevision }

    fun hasPendingOrClaimedSince(revision: Long): Boolean = synchronized(lock) {
        _queuedSends.value.isNotEmpty() || claimRevision != revision
    }

    fun enqueue(send: QueuedSend) {
        synchronized(lock) {
            check(!disposed) { "Conversation guidance store was disposed" }
            AttachmentFiles.setLivePaths(send, AttachmentFiles.ownedPaths(send.attachments) + send.preparedImages)
            _queuedSends.value = _queuedSends.value + send
        }
    }

    /** Exact outbox identity stays owned while pending or claimed by an in-flight drain. */
    fun containsSubagentResult(childId: String, resultId: String): Boolean = synchronized(lock) {
        (_queuedSends.value.asSequence() + claimedGuidance.values.asSequence().flatten()).any {
            it.source?.subagentResults?.get(childId) == resultId
        }
    }

    /** A committed wait result supersedes only its exact pending automatic notifications. */
    fun removeSubagentResults(receipts: Map<String, String>): List<QueuedSend> = synchronized(lock) {
        val removed = _queuedSends.value.filter { send ->
            val source = send.source
            source?.kind == MessageSource.Kind.SUBAGENT && source.subagentResults.isNotEmpty() &&
                source.subagentResults.all { (id, resultId) -> receipts[id] == resultId }
        }
        _queuedSends.value = _queuedSends.value - removed.toSet()
        removed
    }

    fun remove(id: String): QueuedSend? = synchronized(lock) {
        val removed = _queuedSends.value.firstOrNull { it.id == id } ?: return null
        _queuedSends.value = _queuedSends.value.filterNot { it.id == id }
        removed
    }

    /** Transfer the pending batch to one explicit in-flight owner. */
    fun claim(): GuidanceBatchLease? = synchronized(lock) {
        if (disposed || _queuedSends.value.isEmpty()) return null
        val lease = GuidanceBatchLease(newLeaseId(), _queuedSends.value)
        _queuedSends.value = emptyList()
        check(claimedGuidance.put(lease.id, lease.batch) == null)
        claimRevision += 1
        lease
    }

    /** Only consumed automatic inputs may be retired from a claimed batch before admission. */
    fun retireSubagentResults(lease: GuidanceBatchLease, receipts: Set<Pair<String, String>>): GuidanceBatchLease? =
        synchronized(lock) {
            check(claimedGuidance[lease.id] == lease.batch)
            val retained = lease.batch.filterNot { send ->
                val results = send.source?.subagentResults.orEmpty()
                results.isNotEmpty() && results.all { (id, resultId) -> (id to resultId) in receipts }
            }
            if (retained.isEmpty()) claimedGuidance.remove(lease.id)
            else claimedGuidance[lease.id] = retained
            retained.takeIf { it.isNotEmpty() }?.let { GuidanceBatchLease(lease.id, it) }
        }

    /** Settle one exact lease without allowing duplicate or unknown results to mutate the queue. */
    fun settle(leaseId: String, durable: Boolean): Boolean {
        var orphaned = emptyList<QueuedSend>()
        synchronized(lock) {
            val batch = claimedGuidance.remove(leaseId) ?: return false
            when {
                durable -> batch.forEach {
                    it.releaseGenerationAdmission?.invoke()
                    AttachmentFiles.releaseLivePaths(it)
                }
                disposed -> orphaned = batch
                else -> _queuedSends.value = batch + _queuedSends.value
            }
        }
        orphaned.forEach(::discard)
        return true
    }

    fun discard(send: QueuedSend) {
        send.releaseGenerationAdmission?.invoke()
        AttachmentFiles.releaseLivePaths(send)
        if (send.attachments.isNotEmpty()) reclaimAttachments(send.attachments)
    }

    /** Mark the owner closed and transfer its still-pending batch to the disposal caller. */
    fun disposePending(): List<QueuedSend> = synchronized(lock) {
        disposed = true
        _queuedSends.value.also { _queuedSends.value = emptyList() }
    }
}
