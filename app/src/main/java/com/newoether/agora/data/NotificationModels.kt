package com.newoether.agora.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Data class for notification record.
 * Mirrors Kai's NotificationRecord.
 */
@Serializable
data class NotificationRecord(
    val id: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val subtext: String? = null,
    val postedAt: Long,
    val category: String? = null,
    val preview: String,
) {
    companion object {
        /** Maximum length for preview text. */
        const val MAX_PREVIEW_LENGTH = 200
    }
}

/**
 * Sync state for notifications.
 * Mirrors Kai's NotificationSyncState.
 */
@Serializable
data class NotificationSyncState(
    val lastSeenKey: String? = null,
    val pendingKeys: List<String> = emptyList(),
    val recordsCount: Int = 0,
    val lastSyncEpochMs: Long = 0L,
    val lastAttemptEpochMs: Long = 0L,
    val lastError: String? = null,
)

/**
 * Status for notification listener access.
 */
data class NotificationListenerStatus(
    val hasAccess: Boolean,
    val intentEnabled: Boolean,
)