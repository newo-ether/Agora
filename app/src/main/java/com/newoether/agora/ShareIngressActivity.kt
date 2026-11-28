package com.newoether.agora

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Receives a system share, copies every stream out of the sender's URI grant, then hands the staged
 * request to the existing Agora task. Manifest-declared because only the receiving activity can read
 * a grant that isn't persistable, and this activity finishes before the grant expires.
 */
class ShareIngressActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = shareIntentPayload(intent) ?: run {
            finish()
            return
        }
        lifecycleScope.launch {
            val requestId = UUID.randomUUID().toString()
            val (attachments, rejected) = stageSharedAttachments(applicationContext, payload, requestId)
            startActivity(
                shareLaunchIntent(
                    this@ShareIngressActivity,
                    ShareRequest(requestId, payload.text, attachments, rejected),
                ),
            )
            finish()
        }
    }
}

internal fun shareLaunchIntent(context: Context, request: ShareRequest): Intent =
    Intent(context, MainActivity::class.java).apply {
        putExtra(EXTRA_SHARE_REQUEST, request.toJson())
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
