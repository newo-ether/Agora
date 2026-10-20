package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.skills.*
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.viewmodel.ChatViewModel

/** Shared editor for Settings and chat-authored drafts, with optional native workflow choices. */
@Composable
internal fun SkillEditorPage(viewModel: ChatViewModel, original: SkillDocument, initialDescription: String,
    busy: Boolean, onBack: () -> Unit, onSave: (SkillDocument, String) -> Unit) {
    val docs by viewModel.settings.showDocumentationFab.collectAsState()
    var name by remember(original) { mutableStateOf(original.name) }
    var description by remember(original) { mutableStateOf(initialDescription) }
    var instructions by remember(original) { mutableStateOf(original.instructions) }
    var workflow by remember(original) { mutableStateOf(original.configuration) }
    var shortcut by remember(original) { mutableStateOf(original.configuration.shortcut) }
    fun document() = original.copy(name = name.trim(), instructions = instructions,
        description = description.trim(), configuration = workflow.copy(shortcut = shortcut.trim().removePrefix("/")))
    val valid = name.isNotBlank() && runCatching { document().encode() }.isSuccess
    CollapsingSettingsScaffold(title = stringResource(R.string.skills_edit), onBack = { if (!busy) onBack() },
        floatingActionButton = { if (docs) DocumentationFab("skills.md") },
        actions = { IconButton(onClick = { onSave(document(), description.trim()) }, enabled = valid && !busy) {
            Icon(Icons.Default.Save, stringResource(R.string.save))
        } }) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.skill_identity), items = listOf({
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SkillField(name, { name = it }, R.string.skills_name)
                    OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.skills_description)) },
                        supportingText = { Text(stringResource(R.string.skill_description_hint)) },
                        isError = description.trim().split(Regex("\\s+")).filter(String::isNotEmpty).size > 10,
                        modifier = Modifier.fillMaxWidth())
                    SkillField(shortcut, { shortcut = it }, R.string.skill_shortcut)
                }
            }))
            SettingsGroup(title = stringResource(R.string.skills_content), items = listOf({
                OutlinedTextField(instructions, { instructions = it }, modifier = Modifier.fillMaxWidth().padding(16.dp)
                    .heightIn(min = 240.dp, max = 480.dp), label = { Text(stringResource(R.string.skills_content)) },
                    supportingText = { Text(stringResource(R.string.skill_instructions_hint)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            }))
            SkillWorkflowFields(viewModel, original.name, workflow) { workflow = it }
            if (docs) Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun SkillField(value: String, onChange: (String) -> Unit, label: Int) {
    OutlinedTextField(value, onChange, label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth())
}
