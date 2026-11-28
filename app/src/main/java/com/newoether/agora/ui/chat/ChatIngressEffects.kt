package com.newoether.agora.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import com.newoether.agora.ProcessTextRequest
import com.newoether.agora.ShareRequest
import com.newoether.agora.ui.chat.bottombar.ChatComposerState
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.ConversationComposerSnapshot

/**
 * External ingress into the composer: selected text and system shares. Both start their own
 * conversation, so they share one owner, one draft observer, and one set of durable writes.
 */
@Composable
internal fun ChatIngressEffects(
    viewModel: ChatViewModel,
    composer: ChatComposerState,
    composerSnapshot: ConversationComposerSnapshot,
    ownerId: String,
    switching: Boolean,
    textFieldState: TextFieldState,
    processTextRequest: ProcessTextRequest?,
    onProcessTextConsumed: (String) -> Unit,
    shareRequest: ShareRequest?,
    onShareConsumed: (String) -> Unit,
) {
    ProcessTextComposerEffect(
        ownerId = ownerId,
        draftLoaded = composerSnapshot.loaded,
        switching = switching,
        request = processTextRequest,
        textFieldState = textFieldState,
        onConsumed = onProcessTextConsumed,
        persistText = viewModel.conversationComposer::persistIngressText,
        ingressRetryVersion = composerSnapshot.ingressRetryVersion,
        newChatOnly = true,
    )
    ShareComposerEffect(
        ownerId = ownerId,
        draftLoaded = composerSnapshot.loaded,
        switching = switching,
        request = shareRequest,
        textFieldState = textFieldState,
        onConsumed = onShareConsumed,
        persistText = viewModel.conversationComposer::persistIngressText,
        importAttachment = viewModel.conversationComposer::importAttachment,
        isFrozen = { owner -> viewModel.conversationComposerSubmission.snapshot(owner).isFrozen },
        acceptsLocalSandbox = composer::acceptsLocalSandboxAttachments,
        reportRejected = composer::reportUnsupportedFiles,
        ingressRetryVersion = composerSnapshot.ingressRetryVersion,
    )
}
