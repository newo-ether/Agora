package com.newoether.agora.ui.templates

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.templates.InteractiveTemplate
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton

/** Compact metadata editing retained for the existing Skill workflow configuration. */
@Composable
internal fun TemplateMetadataDialog(template: InteractiveTemplate, busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: (InteractiveTemplate) -> Unit) {
    var name by remember(template.id) { mutableStateOf(template.name) }
    var description by remember(template.id) { mutableStateOf(template.description) }
    var directory by remember(template.id) { mutableStateOf(template.sourceDirectory.orEmpty()) }
    val updated = template.copy(name = name.trim(), description = description.trim(), shortcut = null, sourceDirectory = directory.trim().ifBlank { null }, linkedSkill = null)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.template_edit)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.template_name)) })
            OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.template_description)) })
            if (template.sourceFolderId == null) OutlinedTextField(directory, { directory = it }, label = { Text(stringResource(R.string.template_source_directory)) })
            else Text(stringResource(R.string.template_folder_edit_hint))
        }
    }, confirmButton = {
        TextButton(enabled = !busy && runCatching { updated.validate(); require(description.trim().split(Regex("\\s+")).filter(String::isNotEmpty).size <= 10) }.isSuccess, onClick = { onSave(updated) }) { Text(stringResource(R.string.save)) }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
