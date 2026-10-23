package com.newoether.agora.ui.chat.bottombar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.newoether.agora.R
import com.newoether.agora.stt.SherpaSttBootstrap
import com.newoether.agora.stt.VoiceInputManager

/**
 * Composer mic button: on-device dictation into the chat input via sherpa-onnx [VoiceInputManager].
 *
 * While listening, interim text replaces the live partial; on each utterance endpoint the final
 * text is committed. Tapping again stops. The button is only shown when the STT model is installed
 * (downloaded from Settings > Assistant > Voice input); otherwise it routes the user there. Mic
 * permission is requested inline on first use.
 */
@Composable
internal fun ComposerMicButton(
    textFieldState: TextFieldState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val voice = remember { VoiceInputManager(context.applicationContext) }
    var listening by remember { mutableStateOf(false) }
    var modelInstalled by remember { mutableStateOf(SherpaSttBootstrap.isInstalled(context)) }

    // The text already in the field when listening starts; STT output is appended after it so
    // dictation never destroys what the user typed.
    var basePrefix by remember { mutableStateOf("") }
    // Text committed by finalized utterances during this listening session.
    var committed by remember { mutableStateOf("") }

    fun applyTranscript(current: String) {
        val joined = buildString {
            append(basePrefix)
            if (basePrefix.isNotEmpty() && !basePrefix.endsWith(" ")) append(' ')
            append(current)
        }
        textFieldState.edit {
            replace(0, length, joined)
        }
    }

    fun startListening() {
        basePrefix = textFieldState.text.toString().trimEnd()
        committed = ""
        listening = true
        voice.start(
            onPartial = { partial ->
                applyTranscript((committed + " " + partial).trim())
            },
            onFinal = { finalText ->
                committed = (committed + " " + finalText).trim()
                applyTranscript(committed)
            },
            onError = { msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            },
            onEnd = { listening = false },
        )
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startListening()
        else Toast.makeText(context, context.getString(R.string.assistant_voice_needs_mic), Toast.LENGTH_SHORT).show()
    }

    DisposableEffect(Unit) { onDispose { voice.stop() } }

    IconButton(
        onClick = {
            modelInstalled = SherpaSttBootstrap.isInstalled(context)
            when {
                listening -> voice.stop()
                !modelInstalled -> {
                    // Route the user to download the model.
                    Toast.makeText(
                        context,
                        context.getString(R.string.stt_model_needed),
                        Toast.LENGTH_LONG,
                    ).show()
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED ->
                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                else -> startListening()
            }
        },
        enabled = enabled || listening,
        modifier = modifier,
    ) {
        if (listening) {
            Icon(
                Icons.Default.Stop,
                contentDescription = stringResource(R.string.stt_stop),
                tint = MaterialTheme.colorScheme.error,
            )
        } else {
            Icon(
                Icons.Default.Mic,
                contentDescription = stringResource(R.string.stt_dictate),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
