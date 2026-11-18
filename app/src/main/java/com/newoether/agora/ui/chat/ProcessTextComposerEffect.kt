package com.newoether.agora.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.newoether.agora.ProcessTextRequest
import kotlinx.coroutines.flow.first

internal fun appendSelectedText(draft: String, selected: String): String = when {
    draft.isEmpty() -> selected
    draft.endsWith("\n\n") -> draft + selected
    draft.endsWith('\n') -> draft + "\n" + selected
    else -> draft + "\n\n" + selected
}

/** A request is acknowledged only after its text reaches the existing durable draft owner. */
@Composable
internal fun ProcessTextComposerEffect(
    ownerId: String,
    draftLoaded: Boolean,
    switching: Boolean,
    request: ProcessTextRequest?,
    textFieldState: TextFieldState,
    onConsumed: (String) -> Unit,
    persistText: suspend (String, String) -> Boolean,
    ingressRetryVersion: Long = 0L,
    /** Selected text always starts a conversation instead of joining the open one. */
    newChatOnly: Boolean = false,
) {
    val consume by rememberUpdatedState(onConsumed)
    val persist by rememberUpdatedState(persistText)
    val retryVersion by rememberUpdatedState(ingressRetryVersion)
    var persistedRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    // Uncommitted display state must not survive a fresh controller loading an older disk draft.
    var displayedRequestId by remember(ownerId, textFieldState) { mutableStateOf<String?>(null) }
    val ready = draftLoaded && !switching &&
        (!newChatOnly || ownerId == com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID)
    LaunchedEffect(ownerId, ready, request?.id, textFieldState) {
        if (!ready || request == null) return@LaunchedEffect
        if (persistedRequestId != request.id) {
            if (displayedRequestId != request.id) {
                textFieldState.edit {
                    replace(0, length, appendSelectedText(toString(), request.text))
                    selection = androidx.compose.ui.text.TextRange(length)
                }
                displayedRequestId = request.id
            }
            // A failed write retains ingress. Text edits and release of the existing
            // owner's frozen submission wake this same observer without a polling timer.
            snapshotFlow { textFieldState.text.toString() to retryVersion }.first { (text, _) ->
                persist(ownerId, text)
            }
            persistedRequestId = request.id
        }
        consume(request.id)
    }
}
