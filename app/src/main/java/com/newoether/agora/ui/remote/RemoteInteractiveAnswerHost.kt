package com.newoether.agora.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.remote.RemoteViewModel
import com.newoether.agora.remote.hasInteractiveAnswer
import com.newoether.agora.remote.sendInteractiveFollowup
import com.newoether.agora.ui.chat.InteractiveAnswerViewer
import com.newoether.agora.ui.chat.message.LocalInteractiveConversationId
import com.newoether.agora.ui.chat.message.LocalOpenInteractiveAnswer
import com.newoether.agora.ui.chat.message.LocalSendInteractiveAnswer

private data class RemoteOpenAnswer(val messageId: String, val callId: String, val answer: InteractiveAnswer)

/** Reuse the same inline cards, sandbox, confirmation and expanded viewer as ordinary chat. */
@Composable
internal fun RemoteInteractiveAnswerHost(owner: String, revision: Long, vm: RemoteViewModel, content: @Composable () -> Unit) {
    var selected by remember(owner) { mutableStateOf<RemoteOpenAnswer?>(null) }
    LaunchedEffect(owner, revision, selected) {
        selected?.let { if (!vm.hasInteractiveAnswer(owner, it.messageId, it.callId, it.answer)) selected = null }
    }
    CompositionLocalProvider(
        LocalInteractiveConversationId provides owner,
        LocalSendInteractiveAnswer provides { messageId, callId, answer, text ->
            vm.sendInteractiveFollowup(owner, messageId, callId, answer, text)
        },
        LocalOpenInteractiveAnswer provides { messageId, callId, answer, _ ->
            if (vm.hasInteractiveAnswer(owner, messageId, callId, answer)) selected = RemoteOpenAnswer(messageId, callId, answer)
        },
    ) { content() }
    selected?.let { request ->
        InteractiveAnswerViewer(request.answer, onClose = { selected = null }, onSend = { text ->
            vm.sendInteractiveFollowup(owner, request.messageId, request.callId, request.answer, text)
        })
    }
}
