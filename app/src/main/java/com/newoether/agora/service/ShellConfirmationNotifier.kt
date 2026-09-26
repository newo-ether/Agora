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
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ShellConfirmationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps an unanswered shell confirmation actionable while the app has no visible dialog.
 *
 * A pending confirmation stays pending when the app is backgrounded, so the tool loop would stall
 * invisibly. This mirrors each backgrounded prompt into an ongoing notification whose Allow / Deny
 * actions answer the same prompt id, and clears it as soon as a decision exists or the app returns
 * to the foreground (where the dialog itself is shown).
 */
object ShellConfirmationNotifier {

    const val ACTION_ALLOW = "com.newoether.agora.action.SHELL_CONFIRM_ALLOW"
    const val ACTION_DENY = "com.newoether.agora.action.SHELL_CONFIRM_DENY"
    const val EXTRA_PROMPT_ID = "prompt_id"
    const val EXTRA_SESSION_ID = "confirmation_session"

    private const val TAG = "ShellConfirmationNotifier"
    private const val CHANNEL_ID = "shell_confirmation"
    private const val NOTIFICATION_ID =7301
    private const val SUMMARY_MAX_CHARS =120

    /** Observes the process-wide confirmation queue for the rest of this process. */
    fun start(scope: CoroutineScope, context: Context, controller: ShellConfirmationController) {
        val appContext = context.applicationContext
        scope.launch {
            combine(
                controller.pendingShellCommand,
                AppForegroundTracker.foreground,
                AppForegroundTracker.chatPresented,
            ) { pending, foreground, chatPresented ->
                // The interaction bar that answers a prompt lives in the chat screen, so being in
                // the foreground on any other screen is as invisible as being backgrounded.
                pending?.takeIf { !foreground || !chatPresented }
            }.collect { pending ->
                if (pending == null) {
                    cancel(appContext)
                } else {
                    showPrompt(appContext, pending, controller.notificationSessionId)
                }
            }
        }
    }

    private fun showPrompt(context: Context, pending: ShellConfirmationController.PendingShellCommand, sessionId: String) {
        createChannel(context)
        val summary = pending.summary.lineSequence().first().take(SUMMARY_MAX_CHARS)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.shell_confirm_title, pending.server))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.shell_confirm_allow), actionIntent(context, pending.id, ACTION_ALLOW, sessionId))
            .addAction(0, context.getString(R.string.shell_confirm_deny), actionIntent(context, pending.id, ACTION_DENY, sessionId))
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } catch (e: RuntimeException) {
            DebugLog.w(TAG, "Failed to post shell confirmation notification", e)
        }
    }

    private fun cancel(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        } catch (e: RuntimeException) {
            DebugLog.w(TAG, "Failed to clear shell confirmation notification", e)
        }
    }

    private fun actionIntent(context: Context, promptId: Long, action: String, sessionId: String): PendingIntent {
        // One request code per prompt so a notification from an answered prompt can never carry a
        // newer prompt's answer.
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(
            context,
            (promptId % Int.MAX_VALUE).toInt(),
            Intent(action, null, context, ShellConfirmationReceiver::class.java)
                .setData(android.net.Uri.parse("agora-shell-confirm://$sessionId/$promptId"))
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_PROMPT_ID, promptId),
            flags,
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val launchFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            launchFlags,
        )
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT< Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.shell_confirm_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.shell_confirm_notification_desc)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
