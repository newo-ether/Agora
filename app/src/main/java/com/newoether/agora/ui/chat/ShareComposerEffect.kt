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
import androidx.compose.ui.text.TextRange
import com.newoether.agora.ShareRequest
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.sharedAttachment
import kotlinx.coroutines.flow.first

/**
 * A share is acknowledged only after its attachments reach the durable composer and its text
 * reaches the durable draft. A shared item always starts its own conversation, as selected text does.
 */
@Composable
internal fun ShareComposerEffect(
    ownerId: String,
    draftLoaded: Boolean,
    switching: Boolean,
    request: ShareRequest?,
    textFieldState: TextFieldState,
    onConsumed: (String) -> Unit,
    persistText: suspend (String, String) -> Boolean,
    importAttachment: suspend (String, SelectedAttachment) -> Boolean,
    isFrozen: (String) -> Boolean,
    acceptsLocalSandbox: () -> Boolean,
    reportRejected: (List<String?>) -> Unit,
    ingressRetryVersion: Long = 0L,
) {
    val consume by rememberUpdatedState(onConsumed)
    val persist by rememberUpdatedState(persistText)
    val import by rememberUpdatedState(importAttachment)
    val frozen by rememberUpdatedState(isFrozen)
    val sandbox by rememberUpdatedState(acceptsLocalSandbox)
    val reject by rememberUpdatedState(reportRejected)
    val retryVersion by rememberUpdatedState(ingressRetryVersion)
    var reportedRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var importedRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var persistedRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    // Uncommitted display state must not survive a fresh controller loading an older disk draft.
    var displayedRequestId by remember(ownerId, textFieldState) { mutableStateOf<String?>(null) }
    val ready = when {
        request == null || !request.hasContent -> true
        else -> draftLoaded && !switching &&
            ownerId == com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID
    }
    LaunchedEffect(ownerId, ready, request?.id, textFieldState) {
        val current = request ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect

        if (reportedRequestId != current.id) {
            val rejected = current.rejectedMimeTypes.toMutableList()
            val sources = current.attachments.mapNotNull { attachment ->
                sharedAttachment(attachment, sandbox()).also { selected ->
                    if (selected == null) rejected += attachment.mimeType
                }
            }
            if (rejected.isNotEmpty()) reject(rejected)
            reportedRequestId = current.id
        }
        if (!current.hasContent) {
            consume(current.id)
            return@LaunchedEffect
        }

        if (importedRequestId != current.id) {
            val sources = current.attachments.mapNotNull { sharedAttachment(it, sandbox()) }
            var index = 0
            var stalled = 0
            while (index < sources.size && stalled < MAX_IMPORT_ATTEMPTS) {
                // A frozen submission refuses new attachments; wait for it to release.
                while (frozen(ownerId)) {
                    snapshotFlow { retryVersion }.first { !frozen(ownerId) }
                }
                val admitted = runCatching { import(ownerId, sources[index]) }.getOrDefault(false)
                if (admitted) {
                    index++
                    stalled = 0
                } else {
                    snapshotFlow { retryVersion }.first { !frozen(ownerId) }
                    stalled++
                }
            }
            importedRequestId = current.id
        }

        val text = current.text
        if (text != null && persistedRequestId != current.id) {
            if (displayedRequestId != current.id) {
                textFieldState.edit {
                    replace(0, length, appendSelectedText(toString(), text))
                    selection = TextRange(length)
                }
                displayedRequestId = current.id
            }
            // A failed write retains ingress. Text edits and release of the existing
            // owner's frozen submission wake this same observer without a polling timer.
            snapshotFlow { textFieldState.text.toString() to retryVersion }.first { (draft, _) ->
                persist(ownerId, draft)
            }
            persistedRequestId = current.id
        }
        consume(current.id)
    }
}

private const val MAX_IMPORT_ATTEMPTS = 3
