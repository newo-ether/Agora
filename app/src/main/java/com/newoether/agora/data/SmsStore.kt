package com.newoether.agora.data

import androidx.room.withTransaction
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.SmsMessageEntity
import com.newoether.agora.data.local.SmsPendingEntity
import com.newoether.agora.data.local.SmsSyncStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Store for SMS messages, pending queue, and sync state.
 *
 * Room is the single durable truth: messages, the heartbeat pending queue, and the
 * sync high-water mark all live in Room (no DataStore mirror, so the two can never
 * drift). The system SMS provider remains the source for full bodies and search.
 */
class SmsStore(
    private val chatDao: ChatDao,
    private val database: ChatDatabase,
) {

    /** Sync state (high-water mark, timestamps, error). Null row = never seeded. */
    val syncState: Flow<SmsSyncState> = chatDao.getSmsSyncStateFlow().map { it?.toData() ?: SmsSyncState() }

    /** Number of unread messages fetched on the last poll. */
    val unreadCount: Flow<Int> = syncState.map { it.unreadCount }

    /** Number of messages waiting for the next heartbeat to consume. */
    val pendingCount: Flow<Int> = chatDao.getPendingSmsCount()

    suspend fun getSyncStateOnce(): SmsSyncState = withContext(Dispatchers.IO) {
        chatDao.getSmsSyncState()?.toData() ?: SmsSyncState()
    }

    suspend fun getLastSeenId(): Long = withContext(Dispatchers.IO) {
        chatDao.getSmsSyncState()?.lastSeenId ?: 0L
    }

    suspend fun updateSyncState(state: SmsSyncState) = withContext(Dispatchers.IO) {
        chatDao.upsertSmsSyncState(state.toEntity())
    }

    /**
     * Persists a polled batch atomically: messages, pending queue entries, and the
     * advanced sync state in one transaction.
     */
    suspend fun saveMessages(messages: List<SmsMessageData>, updatedState: SmsSyncState) {
        if (messages.isEmpty()) return
        withContext(Dispatchers.IO) {
            database.withTransaction {
                chatDao.insertSmsMessages(messages.map { it.toEntity() })
                chatDao.insertPendingSms(messages.map { it.toPendingEntity() })
                chatDao.upsertSmsSyncState(updatedState.toEntity())
            }
        }
    }

    /** Snapshot of the pending queue for the next heartbeat. Full bodies are not stored. */
    suspend fun getPendingSnapshot(): List<SmsMessageData> = withContext(Dispatchers.IO) {
        chatDao.getPendingSms().map { pending ->
            SmsMessageData(
                id = pending.id,
                address = pending.address,
                date = pending.date,
                preview = pending.preview,
                body = "",
                read = pending.read,
            )
        }
    }

    /** Removes exactly the pending rows the heartbeat just showed the AI. */
    suspend fun removePending(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isNotEmpty()) chatDao.deletePendingSms(ids)
    }

    /** Looks up a previously synced SMS message by its id (used as a reply fallback). */
    suspend fun getMessageById(id: Long): SmsMessageEntity? = withContext(Dispatchers.IO) {
        chatDao.getSmsMessageById(id)
    }

    private fun SmsMessageData.toEntity() = SmsMessageEntity(
        id = id,
        address = address,
        date = date,
        preview = preview,
        body = body,
        read = read,
    )

    private fun SmsMessageData.toPendingEntity() = SmsPendingEntity(
        id = id,
        address = address,
        date = date,
        preview = preview,
        read = read,
    )
}

/** Sync state for SMS polling. */
data class SmsSyncState(
    val lastSeenId: Long = 0L,
    val lastSyncEpochMs: Long = 0L,
    val lastAttemptEpochMs: Long = 0L,
    val unreadCount: Int = 0,
    val lastError: String? = null,
)

private fun SmsSyncStateEntity.toData() = SmsSyncState(
    lastSeenId = lastSeenId,
    lastSyncEpochMs = lastSyncEpochMs,
    lastAttemptEpochMs = lastAttemptEpochMs,
    unreadCount = unreadCount,
    lastError = lastError,
)

private fun SmsSyncState.toEntity() = SmsSyncStateEntity(
    id = 0,
    lastSeenId = lastSeenId,
    lastSyncEpochMs = lastSyncEpochMs,
    lastAttemptEpochMs = lastAttemptEpochMs,
    unreadCount = unreadCount,
    lastError = lastError,
)