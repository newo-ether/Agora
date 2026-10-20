package com.newoether.agora.ui.chat

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.Button
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class InteractivePrompt(val text: String, val sourceFiles: List<String> = emptyList()) {
    companion object {
        fun fromBridge(value: kotlinx.serialization.json.JsonElement?): InteractivePrompt? {
            val objectValue = value as? kotlinx.serialization.json.JsonObject
            val rawText = if (objectValue != null) objectValue["text"] else value
            val text = (rawText as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val rawFiles = objectValue?.get("sourceFiles")
            val files = if (rawFiles == null) emptyList() else {
                val array = rawFiles as? kotlinx.serialization.json.JsonArray ?: return null
                if (array.size > 16) return null
                array.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null }
            }
            if (files.distinct().size != files.size || files.any { !com.newoether.agora.templates.isTemplateFilePath(it) }) return null
            return InteractivePrompt(text, files)
        }
    }
}

internal data class PendingInteractivePrompt(val id: Int, val text: String, val reply: (Boolean, Boolean) -> Unit, val sourceFiles: List<String> = emptyList())

/** A saved interface's explicit Run gesture is sufficient; generated answers retain confirmation. */
@Composable
internal fun InteractiveFollowupPrompt(
    request: PendingInteractivePrompt,
    confirmPrompt: Boolean,
    onSend: suspend (InteractivePrompt) -> Boolean,
    onFinished: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sending by remember(request) { mutableStateOf(false) }
    val latestSend by rememberUpdatedState(onSend)
    val latestFinished by rememberUpdatedState(onFinished)
    suspend fun dispatch() {
        if (sending) return
        sending = true
        val accepted = try { latestSend(InteractivePrompt(request.text, request.sourceFiles)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        request.reply(accepted, false)
        latestFinished(accepted)
    }
    LaunchedEffect(request, confirmPrompt) { if (!confirmPrompt) dispatch() }
    if (confirmPrompt) AlertDialog(
        onDismissRequest = { if (!sending) { request.reply(false, true); latestFinished(false) } },
        title = { Text(stringResource(R.string.interactive_answer_followup)) },
        text = { Text(request.text) },
        confirmButton = {
            Button(enabled = !sending, onClick = { scope.launch { dispatch() } }) {
                Text(stringResource(R.string.interactive_answer_send))
            }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = { request.reply(false, true); latestFinished(false) }) {
                Text(stringResource(R.string.interactive_answer_cancel))
            }
        },
    )
}
