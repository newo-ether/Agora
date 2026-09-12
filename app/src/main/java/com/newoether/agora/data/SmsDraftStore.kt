package com.newoether.agora.data

import androidx.room.withTransaction
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.SmsDraftEntity
import com.newoether.agora.data.local.SmsDraftStatus
import com.newoether.agora.sms.SmsSendResult
import com.newoether.agora.sms.SmsSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Store for SMS drafts (outgoing messages awaiting user confirmation).
 *
 * Room is the single durable truth. The draft list is a Room flow; the cap (20) is
 * enforced by evicting the oldest rows atomically on insert, mirroring Kai's
 * `takeLast(MAX_DRAFTS)`. Sending is orchestrated here: PENDING → SENDING →
 * SENT/FAILED, where the actual dispatch goes through [SmsSender] — never directly
 * from the AI, always from the user's tap in the review banner.
 */
class SmsDraftStore(
    private val chatDao: ChatDao,
    private val database: ChatDatabase,
) {

    /** Current drafts, newest first, capped at 20. */
    val drafts: Flow<List<SmsDraft>> = chatDao.getSmsDraftsFlow().map { entities ->
        entities.map { it.toData() }
    }

    /** Adds a new draft; oldest drafts beyond the cap are evicted atomically. */
    suspend fun addDraft(draft: SmsDraft) = withContext(Dispatchers.IO) {
        database.withTransaction {
            chatDao.upsertSmsDraft(draft.toEntity())
            chatDao.deleteSmsDraftsBeyondCap(MAX_DRAFTS)
        }
    }

    /** Targeted status transition — no read-modify-write, so concurrent calls cannot race. */
    suspend fun updateStatus(draftId: String, status: SmsDraftStatus, error: String? = null) =
        withContext(Dispatchers.IO) {
            chatDao.updateSmsDraftStatus(draftId, status, error)
        }

    suspend fun removeDraft(draftId: String) = withContext(Dispatchers.IO) {
        chatDao.deleteSmsDraft(draftId)
    }

    /**
     * User-triggered send: flips the draft to SENDING, dispatches via [sender], then
     * records SENT or FAILED (with the failure reason). Returns false if the draft is
     * missing or no longer PENDING.
     */
    suspend fun sendDraft(draftId: String, sender: SmsSender): Boolean {
        val draft = drafts.first().find { it.id == draftId } ?: return false
        if (draft.status != SmsDraftStatus.PENDING) return false
        updateStatus(draftId, SmsDraftStatus.SENDING)
        return when (val result = sender.sendSms(draft.address, draft.body)) {
            is SmsSendResult.Success -> {
                updateStatus(draftId, SmsDraftStatus.SENT)
                true
            }
            is SmsSendResult.Failure -> {
                updateStatus(draftId, SmsDraftStatus.FAILED, result.message)
                false
            }
        }
    }

    /** Removes non-pending drafts older than [cutoffEpochMs] (7 days). */
    suspend fun cleanupOldDrafts(cutoffEpochMs: Long = System.currentTimeMillis() - CLEANUP_AGE_MS) =
        withContext(Dispatchers.IO) {
            chatDao.cleanupOldSmsDrafts(cutoffEpochMs)
        }

    private fun SmsDraft.toEntity() = SmsDraftEntity(
        id = id,
        address = address,
        body = body,
        createdAtEpochMs = createdAtEpochMs,
        inReplyToSmsId = inReplyToSmsId,
        status = status,
        lastError = lastError,
    )

    private fun SmsDraftEntity.toData() = SmsDraft(
        id = id,
        address = address,
        body = body,
        createdAtEpochMs = createdAtEpochMs,
        inReplyToSmsId = inReplyToSmsId,
        status = status,
        lastError = lastError,
    )

    companion object {
        private const val MAX_DRAFTS = 20
        private const val CLEANUP_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    }
}

/**
 * An outgoing SMS the AI has staged. Nothing is sent until the user taps Send in the
 * review banner — the existence of a draft is the defensive gate between AI intent
 * and real-world action.
 */
data class SmsDraft(
    val id: String = java.util.UUID.randomUUID().toString(),
    val address: String,
    val body: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val inReplyToSmsId: Long? = null,
    val status: SmsDraftStatus = SmsDraftStatus.PENDING,
    val lastError: String? = null,
)