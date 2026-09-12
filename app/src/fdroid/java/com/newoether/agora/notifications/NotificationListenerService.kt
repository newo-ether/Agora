package com.newoether.agora.notifications

import android.app.Notification
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.newoether.agora.data.NotificationRecord
import com.newoether.agora.data.NotificationStore
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android NotificationListenerService for fdroid flavor.
 * Captures notifications and stores them in NotificationStore.
 */
class AgoraNotificationListenerService : NotificationListenerService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private var notificationStore: NotificationStore? = null
    private var settingsRepository: com.newoether.agora.data.repository.SettingsRepository? = null

    companion object {
        private const val TAG = "AgoraNotificationListener"
    }

    override fun onCreate() {
        super.onCreate()
        // Do NOT resolve the container here: this service is created by the OS and can be
        // bound during a cold start, before the database startup gate reaches Ready.
        // The store is resolved lazily on the first posted notification.
        DebugLog.d(TAG, "AgoraNotificationListenerService created")
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
        DebugLog.d(TAG, "AgoraNotificationListenerService destroyed")
    }

    private fun resolveNotificationStore(): NotificationStore? {
        notificationStore?.let { return it }
        val app = applicationContext as? com.newoether.agora.AgoraApplication ?: return null
        val container = app.containerIfAvailable() ?: return null
        settingsRepository = container.settingsRepository
        return container.notificationStore.also { notificationStore = it }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        if (notification == null) return

        // Filter out ongoing and foreground service notifications
        val flags = notification.flags
        val isOngoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isForegroundService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification.category == Notification.CATEGORY_SERVICE
        } else false

        if (isOngoing || isForegroundService) {
            DebugLog.d(TAG, "Skipping ongoing/foreground notification from ${sbn.packageName}")
            return
        }

        // Skip notifications with blank title and text
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

        if (title.isBlank() && text.isBlank() && bigText.isBlank()) {
            DebugLog.d(TAG, "Skipping blank notification from ${sbn.packageName}")
            return
        }

        // Skip VISIBILITY_SECRET
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notification.visibility == Notification.VISIBILITY_SECRET) {
            DebugLog.d(TAG, "Skipping VISIBILITY_SECRET notification from ${sbn.packageName}")
            return
        }

        // Hard-block Kai itself and system UI
        val packageName = sbn.packageName
        if (packageName == "com.newoether.agora" || packageName.startsWith("com.android.systemui")) {
            DebugLog.d(TAG, "Skipping hard-blocked package: $packageName")
            return
        }

        // Use bigText if available, otherwise text
        val body = if (bigText.isNotBlank()) bigText else text
        val preview = if (body.length > 200) body.take(200) + "…" else body

        // Get app label
        var appLabel = packageName
        try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            appLabel = pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            DebugLog.w(TAG, "Could not get app label for $packageName", e)
        }

        val record = NotificationRecord(
            id = sbn.key,
            packageName = packageName,
            appLabel = appLabel,
            title = title,
            text = body,
            subtext = if (subText.isBlank()) null else subText,
            postedAt = sbn.postTime,
            category = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notification.category else null,
            preview = preview,
        )

        DebugLog.d(TAG, "Captured notification: $record")

        val store = resolveNotificationStore()
        if (store == null || settingsRepository == null) {
            // Cold-start window: the process container isn't published yet. Fail closed
            // without crashing the listener (the OS would keep rebinding it in a loop).
            DebugLog.w(TAG, "Container not ready; skipping notification from ${sbn.packageName}")
            return
        }

        // Apply whitelist filtering
        val allowedApps = settingsRepository?.notificationsAllowedApps?.value ?: emptySet()
        if (!allowedApps.contains(packageName)) {
            DebugLog.d(TAG, "Skipping non-whitelisted notification from ${sbn.packageName}")
            return
        }

        scope.launch {
            store.addNotifications(listOf(record))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // For now, we don't remove from store - let retention sweep handle it
        DebugLog.d(TAG, "Notification removed: ${sbn.key}")
    }

    override fun onListenerConnected() {
        DebugLog.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        DebugLog.d(TAG, "Notification listener disconnected")
    }
}