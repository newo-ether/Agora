package com.newoether.agora.ui.chat.bottombar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.buildLiveToolBridge
import com.newoether.agora.viewmodel.installVoiceMessageSink
import com.newoether.agora.voice.live.LiveVoiceSession
import com.newoether.agora.voice.live.LiveVoiceState

/**
 * Composer "phone" button that opens/closes the realtime Live Voice connection. Placed next to the
 * STT mic ([ComposerMicButton]). Shown only when Live Voice is enabled and configured in settings;
 * tapping starts a session (requesting mic permission the first time) and tapping again hangs up.
 */
@Composable
internal fun ComposerLiveVoiceButton(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val config by viewModel.settings.liveVoiceConfig.collectAsState()
    val controller = LiveVoiceSession.controller
    val state by controller.state.collectAsState()

    // Hidden unless the user enabled + configured Live Voice.
    if (!config.enabled || config.model.isBlank()) return

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.installVoiceMessageSink()
            controller.start(ctx, config, viewModel.settings.resolveActiveKey(config.provider).orEmpty(), viewModel.buildLiveToolBridge())
        } else {
            Toast.makeText(ctx, ctx.getString(R.string.assistant_voice_needs_mic), Toast.LENGTH_SHORT).show()
        }
    }

    val active = state != LiveVoiceState.IDLE
    IconButton(
        onClick = {
            if (active) {
                controller.stop()
            } else {
                val key = viewModel.settings.resolveActiveKey(config.provider).orEmpty()
                if (key.isBlank()) {
                    Toast.makeText(ctx, ctx.getString(R.string.live_voice_needs_key), Toast.LENGTH_LONG).show()
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    return@IconButton
                }
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    viewModel.installVoiceMessageSink()
                    controller.start(ctx, config, key, viewModel.buildLiveToolBridge())
                }
            }
        },
        modifier = modifier,
    ) {
        Icon(
            if (active) Icons.Default.CallEnd else Icons.Default.Call,
            contentDescription = stringResource(
                if (active) R.string.live_voice_stop else R.string.live_voice_start,
            ),
            tint = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
