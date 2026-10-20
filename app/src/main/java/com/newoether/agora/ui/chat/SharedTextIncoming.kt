package com.newoether.agora.ui.chat

import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.sharing.SharedTextInbox
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Chooses a draft for incoming Android shares. Nothing sends automatically. */
@Composable
internal fun SharedTextIncoming(viewModel: ChatViewModel, requestId: String?, onConsumed: (String) -> Unit) {
    if (requestId == null) return
    val context = LocalContext.current
    val store = remember(context) { SharedTextInbox(context) }
    val scope = rememberCoroutineScope()
    var attachments by remember(requestId) { mutableStateOf<List<SelectedAttachment>?>(null) }
    var owner by remember(requestId) { mutableStateOf<String?>(null) }
    var error by remember(requestId) { mutableStateOf<String?>(null) }
    var retry by remember(requestId) { mutableIntStateOf(0) }
    var choosing by remember(requestId) { mutableStateOf(false) }
    LaunchedEffect(requestId, retry) {
        try {
            val loaded = withContext(Dispatchers.IO) {
                if (store.consumed(requestId)) null else store.loadMany(requestId) to store.owner(requestId)
            }
            if (loaded == null) { onConsumed(requestId); return@LaunchedEffect }
            attachments = loaded.first
            owner = loaded.second
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: context.getString(R.string.shared_content_load_failed) }
    }
    LaunchedEffect(owner, attachments, retry) {
        val destination = owner ?: return@LaunchedEffect
        val incoming = attachments ?: return@LaunchedEffect
        try {
            if (destination != NEW_CHAT_WORKSPACE_ID) withContext(Dispatchers.IO) {
                val repository = (context.applicationContext as com.newoether.agora.AgoraApplication).requireContainer().conversationRepository
                check(repository.getConversation(destination) != null) { "This chat was deleted. Share the excerpt again to choose another draft." }
            }
            viewModel.conversationComposer.load(destination)
            try {
                withContext(NonCancellable) {
                    check(viewModel.conversationComposer.attachPreparedMany(destination, incoming)) {
                        "Draft is busy; retry when sending finishes"
                    }
                    withContext(Dispatchers.IO) { store.consume(requestId) }
                }
            } finally { withContext(NonCancellable) { viewModel.conversationComposer.release(destination) } }
            if (destination == NEW_CHAT_WORKSPACE_ID) viewModel.createNewChat() else viewModel.selectConversation(destination)
            viewModel.emitSnackbar(context.getString(R.string.shared_content_attached))
            onConsumed(requestId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: context.getString(R.string.shared_content_attach_failed) }
    }
    if (attachments != null && owner == null && error == null) {
        val conversations by viewModel.conversations.collectAsState()
        fun choose(destination: String) {
            if (choosing) return
            choosing = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { store.choose(requestId, destination) }
                    owner = destination
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: context.getString(R.string.shared_content_choose_failed) }
                finally { choosing = false }
            }
        }
        SharedTextDestinationDialog(
            attachment = requireNotNull(attachments).first(),
            attachmentCount = requireNotNull(attachments).size,
            conversations = conversations.orEmpty(),
            choosing = choosing,
            onChoose = ::choose,
            onDismiss = { onConsumed(requestId) },
        )
    }
    error?.let { message ->
        AlertDialog(onDismissRequest = { onConsumed(requestId) }, title = { Text(stringResource(R.string.shared_content_title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null; retry++ }) { Text(stringResource(R.string.retry)) } },
            dismissButton = { TextButton(onClick = { onConsumed(requestId) }) { Text(stringResource(R.string.cancel)) } })
    }
}
