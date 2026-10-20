package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import com.newoether.agora.ui.components.Text
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.TopAppBar
import com.newoether.agora.ui.components.DialogWindowEinkPresentation
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.newoether.agora.R
import com.newoether.agora.model.InteractiveAnswer

/** Optional expanded presentation of the same sandbox used by inline chat artifacts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InteractiveAnswerViewer(answer: InteractiveAnswer, onClose: () -> Unit, onSend: suspend (InteractivePrompt) -> Boolean, promptPrefix: String = "", confirmPrompt: Boolean = true, onSave: (suspend () -> Boolean)? = null, previewOnly: Boolean = false, onFileAccess: suspend (com.newoether.agora.templates.TemplateFileRequest) -> kotlinx.serialization.json.JsonElement = { error("Connect a template folder first") }) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogWindowEinkPresentation(LocalAgoraMotionPolicy.current.eInkMode)
        Scaffold(topBar = {
            TopAppBar(title = { Text(answer.title) }, navigationIcon = {
                com.newoether.agora.ui.components.IconButton(onClick = onClose) {
                    com.newoether.agora.ui.components.Icon(androidx.compose.material.icons.Icons.Default.Close,
                        contentDescription = stringResource(R.string.interactive_answer_close))
                }
            }, actions = { onSave?.let { InteractiveAnswerSaveAction(skillDraft = answer.skillDraft != null, onSave = it) } })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (previewOnly) Text(stringResource(R.string.template_preview_hint), Modifier.fillMaxWidth().padding(16.dp))
                InteractiveAnswerSurface(answer, inline = false, onFileAccess = onFileAccess, modifier = Modifier.weight(1f).fillMaxWidth(),
                    onSent = onClose, onSend = onSend, promptPrefix = promptPrefix, confirmPrompt = confirmPrompt,
                    allowSubmission = !previewOnly)
            }
        }
    }
}
