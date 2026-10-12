package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import com.newoether.agora.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalView
import com.newoether.agora.ui.chat.InteractiveAnswerSurface
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.model.interactiveAnswer

internal val LocalInteractiveConversationId = staticCompositionLocalOf<String?> { null }

internal val LocalOpenInteractiveAnswer = staticCompositionLocalOf<(String, String, InteractiveAnswer) -> Unit> {
    { _, _, _ -> }
}

internal val LocalSendInteractiveAnswer = staticCompositionLocalOf<suspend (String, String, InteractiveAnswer, String) -> Boolean> {
    { _, _, _, _ -> false }
}

@Composable
internal fun InteractiveAnswerCards(message: ChatMessage) {
    val conversationId = LocalInteractiveConversationId.current
    val open = LocalOpenInteractiveAnswer.current
    val send = LocalSendInteractiveAnswer.current
    val nativeView = LocalView.current
    message.segments.orEmpty().forEach { segment ->
        val callId = segment.toolCallId ?: return@forEach
        if (segment.type != "tool" || segment.toolName != InteractiveAnswer.TOOL_NAME ||
            segment.toolState != com.newoether.agora.model.ToolExecutionStates.SUCCEEDED) return@forEach
        key(conversationId, message.id, callId) {
            // Growing answer text must not repeatedly decode an unchanged executable payload.
            val answer = remember(segment.toolStructuredResult) { segment.interactiveAnswer() }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(answer?.title ?: stringResource(R.string.interactive_answers), style = MaterialTheme.typography.titleMedium)
                    Text(answer?.description ?: stringResource(R.string.interactive_answer_unavailable), style = MaterialTheme.typography.bodyMedium)
                    if (answer != null) {
                        var height by rememberSaveable(conversationId, message.id, callId) { mutableStateOf(320) }
                        var visible by remember { mutableStateOf(false) }
                        // Reserve the last measured bounds while releasing offscreen browser instances.
                        Box(Modifier.fillMaxWidth().height(height.dp).onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            visible = bounds.height > 0 && bounds.bottom > 0 && bounds.top < nativeView.rootView.height
                        }) {
                            if (visible) {
                                InteractiveAnswerSurface(answer, inline = true, modifier = Modifier.fillMaxWidth(),
                                    inlineHeight = height, onHeightChanged = { height = it },
                                    onSend = { text -> send(message.id, callId, answer, text) })
                            }
                        }
                        com.newoether.agora.ui.components.TextButton(onClick = { open(message.id, callId, answer) }) {
                            Text(stringResource(R.string.interactive_answer_open))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
