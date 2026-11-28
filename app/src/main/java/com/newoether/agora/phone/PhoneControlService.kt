package com.newoether.agora.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.newoether.agora.AgoraApplication
import com.newoether.agora.R
import com.newoether.agora.util.appLanguageResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** In-memory control is never restored after process death. */
class PhoneControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private var runtime: PhoneUseRuntime? = null
    private var observed: PhoneControlLease.Held? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.phone_title), NotificationManager.IMPORTANCE_LOW,
        ))
        ServiceCompat.startForeground(this, 1084, notification(null),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        observation?.cancel()
        observation = scope.launch {
            val control = (application as AgoraApplication).awaitContainer()?.phoneUse
                ?: run { stopSelfResult(startId); return@launch }
            runtime = control
            if (intent?.action == STOP) {
                val conversation = intent.getStringExtra("conversation")
                if (conversation != null) control.lease.revokeEpochByUser(
                    conversation, intent.getLongExtra("epoch", -1),
                )
            }
            if (intent?.action == SHOW_PREVIEW &&
                control.lease.held.value?.epoch == intent.getLongExtra("epoch", -1)) control.reopenPreview()
            combine(control.lease.held, control.actionName, control.previewClosed) { _, _, _ -> }.collect {
                // Use the current value after suspension. An older null must not stop a new claim.
                val held = control.lease.held.value
                observed = held
                if (held == null) {
                    if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
                } else getSystemService(NotificationManager::class.java)
                    .notify(1084, notification(held, control.actionName.value, control.previewClosed.value))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observed?.let { held ->
            runtime?.lease?.releaseEpoch(held.epoch, "control_service_stopped")
        }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Ongoing control notice with Stop. It asks to be a Live Update, so Android 16 and OEM skins
     * built on it (OxygenOS / ColorOS 16 Live Alerts) show it as a status bar chip with the elapsed
     * time; older systems ignore the request and show an ordinary ongoing notification.
     */
    private fun notification(
        held: PhoneControlLease.Held?,
        action: String? = null,
        previewClosed: Boolean = false,
    ): android.app.Notification {
        val resources = appLanguageResources(runtime?.settings?.appLanguage?.value ?: "system")
        val status = action ?: resources.getString(R.string.phone_thinking)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_phone_use)
            .setContentTitle(held?.title ?: resources.getString(R.string.phone_title))
            .setContentText(status)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setRequestPromotedOngoing(true)
            .apply {
                if (held != null) {
                    // The chip counts up from the claim; claimedAt is on the elapsed-realtime clock.
                    setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - held.claimedAt))
                    setShowWhen(true)
                    setUsesChronometer(true)
                }
                if (held != null && previewClosed) addAction(0,
                    resources.getString(R.string.phone_background_show), PendingIntent.getService(
                        this@PhoneControlService, held.epoch.hashCode() + 1,
                        Intent(this@PhoneControlService, PhoneControlService::class.java)
                            .setAction(SHOW_PREVIEW)
                            .setData(android.net.Uri.parse("agora-phone:show/${held.epoch}"))
                            .putExtra("epoch", held.epoch),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ))
                if (held != null) addAction(0, resources.getString(R.string.phone_stop), PendingIntent.getService(
                    this@PhoneControlService, held.epoch.hashCode(),
                    Intent(this@PhoneControlService, PhoneControlService::class.java)
                        .setAction(STOP).setData(android.net.Uri.parse("agora-phone:stop/${held.epoch}"))
                        .putExtra("conversation", held.owner.conversationId).putExtra("run", held.owner.runId)
                        .putExtra("epoch", held.epoch),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ))
            }.build()
    }

    companion object {
        private const val CHANNEL = "phone-control"
        private const val STOP = "com.newoether.agora.PHONE_STOP"
        private const val SHOW_PREVIEW = "com.newoether.agora.PHONE_SHOW_PREVIEW"
    }
}
