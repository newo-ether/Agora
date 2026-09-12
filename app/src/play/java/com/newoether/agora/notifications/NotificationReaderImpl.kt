package com.newoether.agora.notifications

import com.newoether.agora.data.NotificationReader
import com.newoether.agora.data.NotificationRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * No-op implementation for play flavor - notifications not supported.
 */
class NotificationReaderImpl : NotificationReader {

    override fun isSupported(): Boolean = false

    override suspend fun getNotificationById(key: String): NotificationRecord? = withContext(Dispatchers.IO) {
        null
    }

    override suspend fun searchNotifications(
        query: String,
        packageName: String?,
        limit: Int,
    ): List<NotificationRecord> = withContext(Dispatchers.IO) {
        emptyList()
    }

    override suspend fun getCurrentRecords(limit: Int): List<NotificationRecord> = withContext(Dispatchers.IO) {
        emptyList()
    }
}