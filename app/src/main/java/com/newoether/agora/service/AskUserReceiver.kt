package com.newoether.agora.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.newoether.agora.AgoraApplication
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Applies an `ask_user` answer given on [AskUserNotifier]'s notification.
 *
 * The answer must reach the same controller the interaction bar uses. The option is carried as an
 * index and resolved against the request that is still waiting, so an action from an answered or
 * replaced question resolves to nothing instead of answering the wrong one.
 */
class AskUserReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val answering = when (intent.action) {
            AskUserNotifier.ACTION_ANSWER -> true
            AskUserNotifier.ACTION_SKIP -> false
            else -> return
        }
        val requestId = intent.getLongExtra(AskUserNotifier.EXTRA_REQUEST_ID, NO_REQUEST)
        if (requestId == NO_REQUEST) return
        val optionIndex = intent.getIntExtra(AskUserNotifier.EXTRA_OPTION_INDEX, -1)
        val sessionId = intent.getStringExtra(AskUserNotifier.EXTRA_SESSION_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = withTimeoutOrNull(CONTAINER_WAIT_MS) {
                    (context.applicationContext as? AgoraApplication)?.awaitContainer()
                }
                if (container == null) {
                    DebugLog.w(TAG, "Dropped notification answer: question queue unavailable")
                    return@launch
                }
                val controller = container.askUserController
                if (controller.notificationSessionId != sessionId) return@launch
                if (!answering) {
                    controller.dismiss(requestId)
                    return@launch
                }
                val option = controller.requestById(requestId)?.options?.getOrNull(optionIndex)
                if (option == null) {
                    DebugLog.w(TAG, "Dropped notification answer: option no longer exists")
                    return@launch
                }
                controller.submit(requestId, listOf(option))
            } catch (e: Exception) {
                DebugLog.e(TAG, "Failed to apply notification answer", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AskUserReceiver"
        const val NO_REQUEST = 0L
        const val CONTAINER_WAIT_MS = 5_000L
    }
}
