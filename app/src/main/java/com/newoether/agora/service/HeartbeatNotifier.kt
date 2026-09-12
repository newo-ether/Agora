package com.newoether.agora.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.newoether.agora.MainActivity
import com.newoether.agora.R

/**
 * Sends heartbeat push notifications when the app is backgrounded.
 */
class HeartbeatNotifier(
    private val context: Context,
    private val settingsRepository: com.newoether.agora.data.repository.SettingsRepository,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val CHANNEL_ID = "heartbeat_notifications"

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Heartbeat Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications from background heartbeat checks"
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Sends a heartbeat notification.
     */
    fun sendHeartbeatNotification(title: String, body: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.newoether.agora.OPEN_HEARTBEAT"
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body.take(200))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .build()

        notificationManager.notify(1001, notification)
    }
}