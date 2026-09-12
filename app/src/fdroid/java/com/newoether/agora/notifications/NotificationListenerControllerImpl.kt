package com.newoether.agora.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.newoether.agora.data.NotificationListenerController
import com.newoether.agora.data.NotificationListenerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * fdroid implementation of NotificationListenerController.
 * Checks access and opens system settings.
 */
class NotificationListenerControllerImpl(
    private val context: Context,
) : NotificationListenerController {

    override fun isListenerAccessGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
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

    override suspend fun openNotificationListenerSettings() = withContext(Dispatchers.IO) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        } else {
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override suspend fun getListenerStatus(): NotificationListenerStatus = withContext(Dispatchers.IO) {
        val hasAccess = isListenerAccessGranted()
        val intentEnabled = hasAccess || true // Intent can remain enabled even if access is missing
        NotificationListenerStatus(hasAccess = hasAccess, intentEnabled = intentEnabled)
    }
}