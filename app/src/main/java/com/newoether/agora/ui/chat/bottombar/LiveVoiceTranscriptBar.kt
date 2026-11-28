package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.voice.live.LiveVoiceSession
import com.newoether.agora.voice.live.LiveVoiceState

/**
 * Live transcription banner shown above the composer while a Live Voice session is active.
 * Renders the running user (input) and assistant (output) transcripts from the shared session.
 */
@Composable
internal fun LiveVoiceTranscriptBar(modifier: Modifier = Modifier) {
    val controller = LiveVoiceSession.controller
    val state by controller.state.collectAsState()
    val userText by controller.userTranscript.collectAsState()
    val assistantText by controller.assistantTranscript.collectAsState()

    AnimatedVisibility(visible = state != LiveVoiceState.IDLE) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp,
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = if (state == LiveVoiceState.ACTIVE) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        text = if (state == LiveVoiceState.CONNECTING) "Connecting…" else "Live",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (userText.isNotBlank()) {
                    Text(
                        text = userText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (assistantText.isNotBlank()) {
                    Text(
                        text = assistantText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
