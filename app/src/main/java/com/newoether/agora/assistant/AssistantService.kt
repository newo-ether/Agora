package com.newoether.agora.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.newoether.agora.AgoraApplication
import com.newoether.agora.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that runs one assistant turn through Agora's engine and
 * surfaces the result as a notification.
 *
 * Starting as a foreground service first is required: Agora's generation core
 * raises a lease-based FGS for the model call window, and that acquire
 * fails-closed unless the process already has foreground(-service) importance.
 */
class AssistantService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(working = true)

        val screen = intent?.getStringExtra(AssistInvocation.EXTRA_SCREEN_CONTEXT).orEmpty()
        val userIntent = intent?.getStringExtra(AssistInvocation.EXTRA_USER_INTENT).orEmpty()

        scope.launch {
            val controller = AssistantController(applicationContext as AgoraApplication)
            val answer = try {
                controller.ask(screenContext = screen, userIntent = userIntent)
            } catch (e: Exception) {
                Log.e(TAG, "assistant turn error: ${e.message}", e)
                "Agora error: ${e.message}"
            }
            postResult(answer)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(working: Boolean) {
        ensureChannel()
        val n = buildNotification(
            text = if (working) "Thinking…" else "Assistant",
            ongoing = working,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun postResult(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(RESULT_ID, buildNotification(text = text, ongoing = false))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Assistant", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Agora assistant responses" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String, ongoing: Boolean): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return b
            .setContentTitle("Agora")
            .setContentText(text.take(120))
            .setStyle(Notification.BigTextStyle().bigText(text.take(2000)))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tap)
            .setOngoing(ongoing)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
    }

    private companion object {
        const val TAG = "AgoraAssistSvc"
        const val CHANNEL_ID = "agora.assistant"
        const val NOTIF_ID = 71
        const val RESULT_ID = 72
    }
}
