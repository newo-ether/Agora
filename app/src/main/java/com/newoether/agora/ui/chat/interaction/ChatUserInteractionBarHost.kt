package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.newoether.agora.viewmodel.ChatViewModel

/**
 * Binds the chat screen's controllers to [UserInteractionBar] and anchors it above the composer.
 *
 * Keeping the wiring here means the bar itself stays a plain view of a request list, and the chat
 * screen only needs to say where the bar goes and how tall it turned out.
 *
 * [onHeightChanged] carries the measured height in pixels back to the caller, which uses it to lift
 * the controls that would otherwise sit underneath the bar.
 */
@Composable
internal fun BoxScope.ChatUserInteractionBar(
    viewModel: ChatViewModel,
    conversationId: String?,
    autoWrapCodeBlocks: Boolean,
    bottomBarHeight: Dp,
    onHeightChanged: (Float) -> Unit,
) {
    val questions by viewModel.askUser.requests.collectAsState()
    val shellCommand by viewModel.shellConfirmation.pendingShellCommand.collectAsState()
    val interactions = remember(questions, shellCommand, conversationId) {
        userInteractions(conversationId, questions, shellCommand)
    }
    UserInteractionBar(
        interactions = interactions,
        autoWrapCodeBlocks = autoWrapCodeBlocks,
        onAnswerQuestion = { id, choices -> viewModel.askUser.submit(id, choices) },
        onSkipQuestion = { id -> viewModel.askUser.dismiss(id) },
        onShellDecision = { id, allow, alwaysAllowServer ->
            viewModel.shellConfirmation.resolve(id, allow, alwaysAllowServer)
        },
        onHeightChanged = onHeightChanged,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomBarHeight),
    )
}
