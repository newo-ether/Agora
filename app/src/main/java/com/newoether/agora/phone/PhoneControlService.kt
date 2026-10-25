package com.newoether.agora.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
                val run = intent.getStringExtra("run")
                if (conversation != null && run != null) control.revoke(
                    PhoneControlLease.Owner(conversation, run), intent.getLongExtra("epoch", -1),
                )
            }
            control.lease.held.collect {
                // Use the current value after suspension. An older null must not stop a new claim.
                val held = control.lease.held.value
                observed = held
                if (held == null) {
                    if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
                } else getSystemService(NotificationManager::class.java).notify(1084, notification(held))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observed?.let { held ->
            val control = runtime
            if (control?.lease?.held?.value?.epoch == held.epoch) {
                control.lease.releaseOwner(held.owner, "control_service_stopped")
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(held: PhoneControlLease.Held?): android.app.Notification {
        val resources = appLanguageResources(runtime?.settings?.appLanguage?.value ?: "system")
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(resources.getString(R.string.phone_title))
            .setContentText(held?.title ?: resources.getString(R.string.phone_thinking))
            .setOngoing(true).setOnlyAlertOnce(true)
            .apply {
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
    }
}
