package com.newoether.agora.assistant

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Entry point to run an assistant turn from captured screen context. Starts
 * [AssistantService] as a foreground service so Agora's engine runs with the
 * required foreground-service importance (the engine's FGS acquire fails-closed
 * from a backgrounded process).
 */
object AssistInvocation {

    const val EXTRA_SCREEN_CONTEXT = "agora.assist.screen_context"
    const val EXTRA_USER_INTENT = "agora.assist.user_intent"

    fun launch(context: Context, screenContext: String, userIntent: String = DEFAULT_INTENT) {
        val i = Intent(context, AssistantService::class.java).apply {
            putExtra(EXTRA_SCREEN_CONTEXT, screenContext)
            putExtra(EXTRA_USER_INTENT, userIntent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i)
        } else {
            context.startService(i)
        }
    }

    // Default when invoked purely by the assist gesture with no spoken/typed
    // intent yet: summarize/offer help on what's on screen.
    private const val DEFAULT_INTENT =
        "Based on the screen context, briefly tell me what's on screen and how you can help."
}
