package com.newoether.agora.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for Heartbeat, SMS, and Notification entities.
 *
 * Separated from ChatDao to keep file sizes under the 999-line limit.
 */
@Dao
interface ChatHeartbeatSmsDao {
    // ── Heartbeat DAO ────────────────────────────────────────

    @Query("SELECT * FROM heartbeat_logs ORDER BY timestampEpochMs DESC LIMIT 5")
    suspend fun getRecentHeartbeatLogs(): List<HeartbeatLogEntity>

    @Query("SELECT * FROM heartbeat_logs ORDER BY timestampEpochMs DESC LIMIT 5")
    fun getRecentHeartbeatLogsFlow(): Flow<List<HeartbeatLogEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHeartbeatLog(log: HeartbeatLogEntity)

    // ── SMS DAO ──────────────────────────────────────────────

    @Query("SELECT * FROM sms_messages WHERE id = :id")
    suspend fun getSmsMessageById(id: Long): SmsMessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSmsMessages(messages: List<SmsMessageEntity>)

    @Query("SELECT * FROM sms_sync_state WHERE id = 0")
    suspend fun getSmsSyncState(): SmsSyncStateEntity?

    @Query("SELECT * FROM sms_sync_state WHERE id = 0")
    fun getSmsSyncStateFlow(): Flow<SmsSyncStateEntity?>

    @Upsert
    suspend fun upsertSmsSyncState(state: SmsSyncStateEntity)

    @Query("SELECT * FROM sms_drafts ORDER BY createdAtEpochMs DESC LIMIT 20")
    fun getSmsDraftsFlow(): Flow<List<SmsDraftEntity>>

    @Upsert
    suspend fun upsertSmsDraft(draft: SmsDraftEntity)

    @Query("UPDATE sms_drafts SET status = :status, lastError = :error WHERE id = :id")
    suspend fun updateSmsDraftStatus(id: String, status: SmsDraftStatus, error: String?)

    @Query("DELETE FROM sms_drafts WHERE id NOT IN (SELECT id FROM sms_drafts ORDER BY createdAtEpochMs DESC LIMIT :cap)")
    suspend fun deleteSmsDraftsBeyondCap(cap: Int): Int

    @Query("DELETE FROM sms_drafts WHERE id = :id")
    suspend fun deleteSmsDraft(id: String): Int

    @Query("DELETE FROM sms_drafts WHERE status != 'PENDING' AND createdAtEpochMs < :cutoff")
    suspend fun cleanupOldSmsDrafts(cutoff: Long): Int

    @Query("SELECT * FROM sms_pending ORDER BY id ASC")
    suspend fun getPendingSms(): List<SmsPendingEntity>

    @Query("SELECT COUNT(*) FROM sms_pending")
    fun getPendingSmsCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingSms(messages: List<SmsPendingEntity>)

    @Query("DELETE FROM sms_pending WHERE id IN (:ids)")
    suspend fun deletePendingSms(ids: List<Long>): Int

    // ── Notification DAO ─────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotifications(records: List<NotificationRecordEntity>)

    @Query("SELECT * FROM notifications WHERE id IN (:keys)")
    suspend fun getNotificationsByIds(keys: List<String>): List<NotificationRecordEntity>

    @Query("SELECT * FROM notifications WHERE id = :key")
    suspend fun getNotificationById(key: String): NotificationRecordEntity?

    @Query("SELECT * FROM notifications WHERE package_name = :packageName AND (title LIKE :query OR text LIKE :query) ORDER BY posted_at DESC LIMIT :limit")
    suspend fun searchNotificationsByPackage(query: String, packageName: String, limit: Int): List<NotificationRecordEntity>

    @Query("SELECT * FROM notifications WHERE title LIKE :query OR text LIKE :query ORDER BY posted_at DESC LIMIT :limit")
    suspend fun searchNotifications(query: String, limit: Int): List<NotificationRecordEntity>

    @Query("SELECT DISTINCT package_name FROM notifications")
    suspend fun getNotificationPackages(): List<String>

    @Query("SELECT * FROM notifications ORDER BY posted_at DESC LIMIT :limit")
    suspend fun getRecentNotifications(limit: Int): List<NotificationRecordEntity>

    @Query("DELETE FROM notifications WHERE posted_at < :cutoff")
    suspend fun deleteNotificationsOlderThan(cutoff: Long): Int

    @Query("""
        DELETE FROM notifications
        WHERE package_name = :packageName
          AND posted_at NOT IN (
              SELECT posted_at FROM notifications
              WHERE package_name = :packageName
              ORDER BY posted_at DESC
              LIMIT :cap
          )
        """)
    suspend fun deleteOldestNotificationsForPackage(packageName: String, cap: Int): Int

    @Query("SELECT * FROM notification_sync_state WHERE id = 0")
    suspend fun getNotificationSyncState(): NotificationSyncStateEntity?

    @Upsert
    suspend fun upsertNotificationSyncState(state: NotificationSyncStateEntity)
}