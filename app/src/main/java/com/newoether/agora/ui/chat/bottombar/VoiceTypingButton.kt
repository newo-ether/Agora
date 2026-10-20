package com.newoether.agora.ui.chat.bottombar

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.voice.ApiVoiceRecorder
import com.newoether.agora.voice.VoiceTypingMode
import com.newoether.agora.voice.voiceProviderKey
import com.newoether.agora.voice.AndroidVoiceRecognizer
import com.newoether.agora.voice.VoiceDraftTarget
import com.newoether.agora.voice.VoiceTypingConfig
import com.newoether.agora.voice.VoiceTypingState

/** Key this component by draft owner: late results cannot cross into another conversation. */
@Composable
internal fun VoiceTypingButton(
    settings: SettingsRepository,
    text: TextFieldState,
    enabled: Boolean,
    onBusyChanged: (Boolean) -> Unit,
) {
    val config by settings.voiceTyping.config.collectAsState(initial = VoiceTypingConfig())
    if (!config.enabled) return
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val latestEnabled by rememberUpdatedState(enabled)
    val latestBusy by rememberUpdatedState(onBusyChanged)
    var state by remember { mutableStateOf(VoiceTypingState.IDLE) }
    var target by remember { mutableStateOf<VoiceDraftTarget?>(null) }
    val recognizer = remember(context) {
        AndroidVoiceRecognizer(context.applicationContext,
            onState = { state = it; latestBusy(it != VoiceTypingState.IDLE) },
            onText = { target?.insert(it); target = null },
            onPartialText = { target?.preview(it) },
            onError = { target?.cancel(); target = null; Toast.makeText(context, it, Toast.LENGTH_LONG).show() })
    }
    val recorder = remember(context, scope) {
        ApiVoiceRecorder(context.applicationContext, scope,
            onState = { state = it; latestBusy(it != VoiceTypingState.IDLE) },
            onText = { target?.insert(it); target = null },
            onError = { target?.cancel(); target = null; Toast.makeText(context, it, Toast.LENGTH_LONG).show() })
    }
    fun cancel() { target?.cancel(); target = null; recognizer.cancel(); recorder.cancel() }
    fun start() {
        if (!latestEnabled) return
        target = VoiceDraftTarget(text) { latestEnabled }
        keyboard?.hide()
        if (config.mode.usesApi) recorder.start(config, voiceProviderKey(config, settings.apiKeys.value, settings.activeApiKeyIds.value))
        else recognizer.start(config.language, onDevice = config.mode == VoiceTypingMode.ANDROID_OFFLINE)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else Toast.makeText(context, R.string.voice_typing_permission, Toast.LENGTH_LONG).show()
    }
    DisposableEffect(recognizer, recorder, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancel() }
    }
    LaunchedEffect(enabled, config) { cancel() }
    IconButton(enabled = enabled, onClick = {
        when (state) {
            VoiceTypingState.LISTENING -> if (config.mode.usesApi) recorder.stop() else recognizer.stop()
            VoiceTypingState.FINISHING -> cancel()
            VoiceTypingState.IDLE -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                else { keyboard?.hide(); permission.launch(Manifest.permission.RECORD_AUDIO) }
            }
        }
    }, modifier = Modifier.size(COMPOSER_CONTROL_HEIGHT).border(1.dp, MaterialTheme.colorScheme.onSurface, CircleShape)) {
        Icon(when (state) {
            VoiceTypingState.IDLE -> Icons.Default.Mic
            VoiceTypingState.LISTENING -> Icons.Default.Stop
            VoiceTypingState.FINISHING -> Icons.Default.Close
        }, contentDescription = stringResource(when (state) {
            VoiceTypingState.IDLE -> R.string.voice_typing_start
            VoiceTypingState.LISTENING -> R.string.voice_typing_stop
            VoiceTypingState.FINISHING -> R.string.voice_typing_cancel
        }))
    }
}
