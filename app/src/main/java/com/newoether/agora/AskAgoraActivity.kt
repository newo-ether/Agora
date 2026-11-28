package com.newoether.agora

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Returns to the existing Agora task without returning replacement text to the source app. */
class AskAgoraActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedProcessText(intent)?.let { text ->
            startActivity(processTextLaunchIntent(this, text))
        }
        finish()
    }
}

internal fun processTextLaunchIntent(context: android.content.Context, text: String): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_PROCESS_TEXT
        type = "text/plain"
        putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
