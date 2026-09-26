package com.newoether.agora.service

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
import com.newoether.agora.viewmodel.AskUserController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps an unanswered `ask_user` question actionable while the interaction bar is not on screen.
 *
 * The bar lives in the chat screen, so a question asked while Agora is backgrounded, or while
 * another screen is open, would wait invisibly and the tool call would never return. This mirrors
 * the oldest waiting question into a notification and clears it as soon as the chat screen is
 * showing or nothing is waiting.
 *
 * A notification holds at most three actions, so the options appear as actions only for a short
 * single-choice question. Everything else is answered in the app, which the notification opens.
 */
object AskUserNotifier {

    const val ACTION_ANSWER = "com.newoether.agora.action.ASK_USER_ANSWER"
    const val ACTION_SKIP = "com.newoether.agora.action.ASK_USER_SKIP"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_OPTION_INDEX = "option_index"
    const val EXTRA_SESSION_ID = "ask_user_session"

    private const val TAG = "AskUserNotifier"
    private const val CHANNEL_ID = "ask_user"
    private const val NOTIFICATION_ID = 7302
    // Two options plus Skip fills the three actions a notification may show.
    private const val MAX_OPTION_ACTIONS = 2

    /** Observes the process-wide question queue for the rest of this process. */
    fun start(scope: CoroutineScope, context: Context, controller: AskUserController) {
        val appContext = context.applicationContext
        scope.launch {
            combine(
                controller.requests,
                AppForegroundTracker.foreground,
                AppForegroundTracker.chatPresented,
            ) { requests, foreground, chatPresented ->
                requests.firstOrNull()?.takeIf { !foreground || !chatPresented }
            }.distinctUntilChanged().collect { request ->
                if (request == null) {
                    cancel(appContext)
                } else {
                    showQuestion(appContext, request, controller.notificationSessionId)
                }
            }
        }
    }

    private fun showQuestion(
        context: Context,
        request: AskUserController.Request,
        sessionId: String,
    ) {
        createChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.ask_user_title))
            .setContentText(request.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.question))
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(context))
        if (!request.allowMultiple && request.options.size <= MAX_OPTION_ACTIONS) {
            request.options.forEachIndexed { index, option ->
                builder.addAction(0, option, answerIntent(context, request.id, index, sessionId))
            }
        }
        builder.addAction(
            0,
            context.getString(R.string.ask_user_skip),
            skipIntent(context, request.id, sessionId),
        )
        try {
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, builder.build())
        } catch (e: RuntimeException) {
            DebugLog.w(TAG, "Failed to post ask_user notification", e)
        }
    }

    private fun cancel(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        } catch (e: RuntimeException) {
            DebugLog.w(TAG, "Failed to clear ask_user notification", e)
        }
    }

    private fun answerIntent(
        context: Context,
        requestId: Long,
        optionIndex: Int,
        sessionId: String,
    ): PendingIntent = actionIntent(
        context = context,
        requestId = requestId,
        action = ACTION_ANSWER,
        sessionId = sessionId,
        optionIndex = optionIndex,
    )

    private fun skipIntent(context: Context, requestId: Long, sessionId: String): PendingIntent =
        actionIntent(
            context = context,
            requestId = requestId,
            action = ACTION_SKIP,
            sessionId = sessionId,
            optionIndex = -1,
        )

    private fun actionIntent(
        context: Context,
        requestId: Long,
        action: String,
        sessionId: String,
        optionIndex: Int,
    ): PendingIntent {
        // The data uri keeps one PendingIntent per request and option, so an action left over from
        // an answered question can never carry a newer question's answer.
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            Intent(action, null, context, AskUserReceiver::class.java)
                .setData(
                    android.net.Uri.parse("agora-ask-user://$sessionId/$requestId/$optionIndex")
                )
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_OPTION_INDEX, optionIndex),
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.ask_user_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.ask_user_notification_desc)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
