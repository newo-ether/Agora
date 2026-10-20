package com.newoether.agora.ui.chat

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton
import kotlinx.coroutines.launch

/** One deliberate save per expanded-view visit; repeated taps cannot create duplicate copies. */
@Composable
internal fun InteractiveAnswerSaveAction(skillDraft: Boolean = false, onSave: suspend () -> Boolean) {
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val latestSave by rememberUpdatedState(onSave)
    val scope = rememberCoroutineScope()
    TextButton(enabled = !saving && !saved, onClick = {
        if (!saving && !saved) {
            saving = true
            scope.launch { try { saved = latestSave() } finally { saving = false } }
        }
    }) { Text(stringResource(if (saved) R.string.template_saved else if (skillDraft) R.string.skill_save_draft else R.string.template_save)) }
}
