package com.newoether.agora.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Interface for reading notification records.
 * Implementation only exists in fdroid flavor.
 */
interface NotificationReader {
    /**
     * Checks if notification reading is supported on this build.
     * Returns false on play flavor or if notification listener access is not granted.
     */
    fun isSupported(): Boolean

    /**
     * Gets notification by its key (StatusBarNotification.key).
     */
    suspend fun getNotificationById(key: String): NotificationRecord?

    /**
     * Searches notifications by query text (app_label + title + text).
     */
    suspend fun searchNotifications(query: String, packageName: String? = null, limit: Int = 20): List<NotificationRecord>

    /**
     * Gets current notification records for UI display.
     */
    suspend fun getCurrentRecords(limit: Int = 50): List<NotificationRecord>
}