package com.newoether.agora.notifications

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.newoether.agora.data.NotificationListenerController
import com.newoether.agora.data.NotificationListenerStatus
import com.newoether.agora.data.NotificationReader
import com.newoether.agora.data.NotificationRecord
import com.newoether.agora.data.NotificationStore
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * fdroid implementation of NotificationReader.
 * Uses NotificationStore for data access.
 */
class NotificationReaderImpl(
    private val context: Context,
    private val notificationStore: NotificationStore,
) : NotificationReader {

    override fun isSupported(): Boolean {
        // Check if we have the BIND_NOTIFICATION_LISTENER_SERVICE permission in manifest
        // and if the listener access is granted
        return context.packageManager.checkPermission(
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            context.packageName,
        ) == PackageManager.PERMISSION_GRANTED && isListenerAccessGranted()
    }

    override suspend fun getNotificationById(key: String): NotificationRecord? = withContext(Dispatchers.IO) {
        notificationStore.getNotificationById(key)
    }

    override suspend fun searchNotifications(
        query: String,
        packageName: String?,
        limit: Int,
    ): List<NotificationRecord> = withContext(Dispatchers.IO) {
        notificationStore.searchNotifications(query, packageName, limit)
    }

    override suspend fun getCurrentRecords(limit: Int): List<NotificationRecord> = withContext(Dispatchers.IO) {
        notificationStore.getCurrentRecords(limit)
    }

    private fun isListenerAccessGranted(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            val componentName = android.content.ComponentName(context, AgoraNotificationListenerService::class.java)
            return nm.isNotificationListenerAccessGranted(componentName)
        }
        // Pre-O: check secure settings
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: ""
        return enabledListeners.contains(context.packageName)
    }
}