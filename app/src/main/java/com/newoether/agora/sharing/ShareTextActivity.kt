package com.newoether.agora.sharing

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Android share target. It imports an attachment; it never submits a chat or starts generation. */
class ShareTextActivity : ComponentActivity() {
    private var requestId = UUID.randomUUID().toString()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = savedInstanceState?.getString("requestId") ?: requestId
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (intent.action == Intent.ACTION_SEND_MULTIPLE ||
                        (intent.hasExtra(Intent.EXTRA_STREAM) && intent.type != "text/plain") ||
                        (!intent.hasExtra(Intent.EXTRA_STREAM) && !intent.hasExtra(Intent.EXTRA_TEXT) &&
                            (intent.clipData != null || intent.data != null))) {
                        SharedFileInbox(this@ShareTextActivity).receive(requestId, intent)
                    } else {
                        SharedTextInbox(this@ShareTextActivity).receive(requestId, intent)
                    }
                }
                startActivity(Intent(this@ShareTextActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_SHARED_TEXT_REQUEST, requestId)
                })
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                Toast.makeText(this@ShareTextActivity, getString(R.string.shared_content_import_failed), Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("requestId", requestId)
        super.onSaveInstanceState(outState)
    }
}
