package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.ui.chat.message.LocalInteractiveConversationId
import com.newoether.agora.ui.chat.message.LocalSendInteractiveAnswer
import com.newoether.agora.ui.chat.message.LocalOpenInteractiveAnswer
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.hasInteractiveAnswer

private data class OpenInteractiveAnswer(val conversationId: String, val messageId: String, val callId: String, val answer: InteractiveAnswer)

/** One viewer for the selected chat; branch/selection changes revoke its originating artifact. */
@Composable
internal fun InteractiveAnswerHost(viewModel: ChatViewModel, content: @Composable () -> Unit) {
    var selected by remember { mutableStateOf<OpenInteractiveAnswer?>(null) }
    val conversationId by viewModel.currentConversationId.collectAsState()
    val messages by viewModel.messages.collectAsState()
    LaunchedEffect(conversationId, messages, selected) {
        selected?.let {
            if (!viewModel.hasInteractiveAnswer(it.conversationId, it.messageId)) selected = null
        }
    }
    val originatingConversationId = conversationId
    CompositionLocalProvider(
        LocalInteractiveConversationId provides originatingConversationId,
        LocalSendInteractiveAnswer provides { messageId, callId, answer, text ->
            originatingConversationId?.let { id -> viewModel.sendInteractiveFollowup(id, messageId, callId, answer, text) } ?: false
        },
        LocalOpenInteractiveAnswer provides { messageId, callId, answer ->
            conversationId?.let { id ->
                if (viewModel.hasInteractiveAnswer(id, messageId)) {
                    selected = OpenInteractiveAnswer(id, messageId, callId, answer)
                }
            }
        },
    ) { content() }
    selected?.let { request ->
        InteractiveAnswerViewer(request.answer, onClose = { selected = null }, onSend = { text ->
            viewModel.sendInteractiveFollowup(request.conversationId, request.messageId, request.callId, request.answer, text)
        })
    }
}
