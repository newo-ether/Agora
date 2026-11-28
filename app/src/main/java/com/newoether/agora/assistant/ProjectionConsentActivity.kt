package com.newoether.agora.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent, no-UI shim that obtains MediaProjection (screen-capture) consent on behalf of the
 * overlay. The overlay is a Service and cannot request the consent intent itself, so it launches
 * this Activity; on approval the result is handed to [AssistantOverlayService] which creates the
 * MediaProjection under a mediaProjection-typed foreground service (required on Android 14+).
 */
class ProjectionConsentActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            AssistantOverlayService.pendingProjectionResultCode = result.resultCode
            AssistantOverlayService.pendingProjectionData = result.data
            val i = Intent(this, AssistantOverlayService::class.java)
                .setAction(AssistantOverlayService.ACTION_START_SCREEN_CAPTURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        runCatching { launcher.launch(mpm.createScreenCaptureIntent()) }
            .onFailure { finish() }
    }
}
