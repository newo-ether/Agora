package com.newoether.agora.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.settings.SettingsItem

/** Details-group row for the task prompt. The row previews the prompt and opens the editor, so a
 *  long prompt no longer competes with the rest of the page for height. */
@Composable
internal fun TaskPromptRow(prompt: String, onClick: () -> Unit) {
    SettingsItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(stringResource(R.string.task_prompt)) },
        supportingContent = {
            Text(
                prompt.ifBlank { stringResource(R.string.task_prompt_hint) },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(Icons.Default.EditNote, null, tint = MaterialTheme.colorScheme.primary)
        },
    )
}

/** Prompt editor dialog. The field is height-capped and scrolls, so a long prompt cannot push the
 *  dialog actions off screen. Editing is committed on Save, so Cancel keeps the stored prompt. */
@Composable
internal fun TaskPromptDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_prompt), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = {
                    Text(
                        stringResource(R.string.task_prompt_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                minLines = 4,
                maxLines = 12,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        confirmButton = {
            TextButton(enabled = draft.isNotBlank(), onClick = { onSave(draft) }) {
                Text(stringResource(R.string.provider_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
