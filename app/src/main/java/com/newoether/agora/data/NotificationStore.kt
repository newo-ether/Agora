package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.NotificationRecordEntity
import com.newoether.agora.data.local.NotificationSyncStateEntity
import com.newoether.agora.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Store for notification records and sync state.
 * Uses DataStore for sync state and pending queue, Room for records.
 */
class NotificationStore(
    private val settingsRepository: SettingsRepository,
    private val chatDao: ChatDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Keys for DataStore
    private val PENDING_KEY = stringPreferencesKey("notifications_pending")
    private val SYNC_STATE_KEY = stringPreferencesKey("notifications_sync_state")

    /**
     * Current pending queue (FIFO, capped at 100).
     * Contains notification keys waiting for the next heartbeat.
     */
    val pendingQueue: Flow<List<String>> = settingsRepository.settingsManager.dataStore.data
        .map { prefs ->
            val jsonStr = prefs[PENDING_KEY] ?: "[]"
            try { json.decodeFromString<List<String>>(jsonStr) } catch (e: Exception) { emptyList() }
        }

    /**
     * Sync state from DataStore.
     */
    val syncState: Flow<NotificationSyncState> = settingsRepository.settingsManager.dataStore.data
        .map { prefs ->
            val jsonStr = prefs[SYNC_STATE_KEY] ?: "{}"
            try { json.decodeFromString<NotificationSyncState>(jsonStr) } catch (e: Exception) { NotificationSyncState() }
        }

    /**
     * Updates the pending queue in DataStore.
     */
    suspend fun updatePendingQueue(keys: List<String>) {
        settingsRepository.settingsManager.dataStore.edit { prefs ->
            prefs[PENDING_KEY] = json.encodeToString(keys)
        }
    }

    /**
     * Updates the sync state in DataStore.
     */
    suspend fun updateSyncState(state: NotificationSyncState) {
        settingsRepository.settingsManager.dataStore.edit { prefs ->
            prefs[SYNC_STATE_KEY] = json.encodeToString(state)
        }
    }

    /**
     * Adds new notification records to Room and updates pending queue + sync state.
     * Called by NotificationListenerService on each onNotificationPosted.
     */
    suspend fun addNotifications(records: List<NotificationRecord>) = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext

        // Insert into Room (upsert to handle updates)
        val entities = records.map { record ->
            NotificationRecordEntity(
                id = record.id,
                package_name = record.packageName,
                app_label = record.appLabel,
                title = record.title,
                text = record.text,
                subtext = record.subtext,
                posted_at = record.postedAt,
                category = record.category,
                preview = record.preview,
            )
        }
        chatDao.insertNotifications(entities)

        // Update pending queue - add new keys (cap at 100, FIFO)
        val currentPending = pendingQueue.first().toMutableList()
        val newKeys = records.map { it.id }.filterNot { currentPending.contains(it) }
        currentPending.addAll(newKeys)
        val cappedPending = if (currentPending.size > 100) {
            currentPending.takeLast(100)
        } else {
            currentPending
        }
        updatePendingQueue(cappedPending)

        // Update sync state
        val state = syncState.first().copy(
            lastSeenKey = records.maxByOrNull { it.postedAt }?.id,
            recordsCount = (syncState.first().recordsCount + records.size).coerceAtMost(5000), // cap total records
            lastSyncEpochMs = System.currentTimeMillis(),
            lastAttemptEpochMs = System.currentTimeMillis(),
            lastError = null,
        )
        updateSyncState(state)

        // Also update Room sync state
        chatDao.upsertNotificationSyncState(NotificationSyncStateEntity(
            id = 0,
            lastSeenKey = state.lastSeenKey,
            pendingKeysJson = json.encodeToString(state.pendingKeys),
            recordsCount = state.recordsCount,
            lastSyncEpochMs = state.lastSyncEpochMs,
            lastAttemptEpochMs = state.lastAttemptEpochMs,
            lastError = null,
        ))
    }

    /**
     * Returns the pending queue snapshot WITHOUT clearing it. Used by the heartbeat
     * prompt builder so the queue is only consumed after a successful run.
     */
    suspend fun getPendingSnapshot(): List<NotificationRecord> = withContext(Dispatchers.IO) {
        val pendingKeys = pendingQueue.first()
        if (pendingKeys.isEmpty()) return@withContext emptyList()

        chatDao.getNotificationsByIds(pendingKeys).map { entity ->
            NotificationRecord(
                id = entity.id,
                packageName = entity.package_name,
                appLabel = entity.app_label,
                title = entity.title,
                text = entity.text,
                subtext = entity.subtext,
                postedAt = entity.posted_at,
                category = entity.category,
                preview = entity.preview,
            )
        }
    }

    /**
     * Removes exactly the given keys from the pending queue. Messages that arrived
     * during the heartbeat run survive for the next one.
     */
    suspend fun removePending(keys: List<String>) {
        if (keys.isEmpty()) return
        val current = pendingQueue.first()
        updatePendingQueue(current.filterNot { it in keys })
    }

    /**
     * Gets a single notification by its key (id).
     */
    suspend fun getNotificationById(key: String): NotificationRecord? = withContext(Dispatchers.IO) {
        chatDao.getNotificationById(key)?.let { entity ->
            NotificationRecord(
                id = entity.id,
                packageName = entity.package_name,
                appLabel = entity.app_label,
                title = entity.title,
                text = entity.text,
                subtext = entity.subtext,
                postedAt = entity.posted_at,
                category = entity.category,
                preview = entity.preview,
            )
        }
    }

    /**
     * Searches notifications by query text.
     */
    suspend fun searchNotifications(query: String, packageName: String? = null, limit: Int = 20): List<NotificationRecord> = withContext(Dispatchers.IO) {
        val results = if (packageName != null) {
            chatDao.searchNotificationsByPackage("%$query%", packageName, limit)
        } else {
            chatDao.searchNotifications("%$query%", limit)
        }
        results.map { entity ->
            NotificationRecord(
                id = entity.id,
                packageName = entity.package_name,
                appLabel = entity.app_label,
                title = entity.title,
                text = entity.text,
                subtext = entity.subtext,
                postedAt = entity.posted_at,
                category = entity.category,
                preview = entity.preview,
            )
        }
    }

    /**
     * Gets current records for debugging/UI.
     */
    suspend fun getCurrentRecords(limit: Int = 50): List<NotificationRecord> = withContext(Dispatchers.IO) {
        chatDao.getRecentNotifications(limit).map { entity ->
            NotificationRecord(
                id = entity.id,
                packageName = entity.package_name,
                appLabel = entity.app_label,
                title = entity.title,
                text = entity.text,
                subtext = entity.subtext,
                postedAt = entity.posted_at,
                category = entity.category,
                preview = entity.preview,
            )
        }
    }

    /**
     * Clears the pending queue (manual flush).
     */
    suspend fun clearPendingQueue() {
        updatePendingQueue(emptyList())
    }

    /**
     * Performs retention sweep: removes records older than 24h and caps per-package at 50.
     * Called after heartbeat consumption.
     */
    suspend fun performRetentionSweep() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000) // 24h
        chatDao.deleteNotificationsOlderThan(cutoff)

        // Per-package cap 50
        val packages = chatDao.getNotificationPackages()
        for (pkg in packages) {
            chatDao.deleteOldestNotificationsForPackage(pkg, 50)
        }
    }
}