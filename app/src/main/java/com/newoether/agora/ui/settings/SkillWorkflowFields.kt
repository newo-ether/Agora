package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import com.newoether.agora.R
import com.newoether.agora.skills.SkillConfiguration
import com.newoether.agora.templates.InteractiveTemplate
import com.newoether.agora.ui.components.*
import com.newoether.agora.ui.templates.TemplateMetadataDialog
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SkillWorkflowFields(vm: ChatViewModel, filename: String, configuration: SkillConfiguration,
    onChange: (SkillConfiguration) -> Unit) {
    var templates by remember { mutableStateOf<List<InteractiveTemplate>>(emptyList()) }
    var skills by remember { mutableStateOf<List<String>>(emptyList()) }
    var editing by remember { mutableStateOf<InteractiveTemplate?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm, filename) {
        try { withContext(Dispatchers.IO) { vm.interactiveTemplates.list() to vm.skillManager.listFiles().map { it.name } }.let {
            templates = it.first; skills = it.second.filterNot { name -> name == filename }
        } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { vm.emitSnackbar(failure.message.orEmpty()) }
    }
    val templateChoices = templates.map { it.id to it.name }
    SettingsGroup(title = stringResource(R.string.skill_workflow), items = listOf({
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.templates), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                WorkflowChoice(stringResource(R.string.skill_add_template), null, templateChoices,
                    enabled = configuration.beforeSteps.size + configuration.afterSteps.size < 16, iconOnly = true,
                    onChoose = { id -> if (id != null) onChange(configuration.withTemplates(
                        configuration.beforeSteps + id, configuration.afterSteps)) })
            }
            listOf(true, false).forEach { before ->
                val steps = if (before) configuration.beforeSteps else configuration.afterSteps
                val title = if (before) R.string.skill_before else R.string.skill_after
                fun update(values: List<String>) = onChange(if (before)
                    configuration.withTemplates(values, configuration.afterSteps)
                    else configuration.withTemplates(configuration.beforeSteps, values))
                steps.forEachIndexed { index, id ->
                    val rowTitle = stringResource(title) + if (steps.size > 1) " ${index + 1}" else ""
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WorkflowChoice(rowTitle, id, templateChoices, modifier = Modifier.weight(1f),
                                onChoose = { replacement -> update(steps.toMutableList().apply {
                                    if (replacement == null) removeAt(index) else set(index, replacement)
                                }) })
                            IconButton(onClick = { update(steps.filterIndexed { position, _ -> position != index }) },
                                modifier = Modifier.semantics { contentDescription = "Remove $rowTitle" }) {
                                Icon(Icons.Default.Remove, null)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            var phaseMenu by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { phaseMenu = true }, modifier = Modifier.semantics {
                                    contentDescription = "${if (before) "Before" else "After"}: ${templateChoices.firstOrNull { it.first == id }?.second ?: id}"
                                }) {
                                    Text(stringResource(if (before) R.string.skill_phase_before else R.string.skill_phase_after))
                                    Icon(Icons.Default.ExpandMore, null)
                                }
                                AgoraDropdownMenu(phaseMenu, { phaseMenu = false }) {
                                    listOf(true, false).forEach { targetBefore ->
                                        AgoraDropdownMenuItem(text = { Text(stringResource(if (targetBefore) R.string.skill_phase_before else R.string.skill_phase_after)) }, onClick = {
                                            phaseMenu = false
                                            if (targetBefore != before) {
                                                val remaining = steps.filterIndexed { position, _ -> position != index }
                                                onChange(if (before) configuration.withTemplates(remaining, configuration.afterSteps + id)
                                                    else configuration.withTemplates(configuration.beforeSteps + id, remaining))
                                            }
                                        })
                                    }
                                }
                            }
                            templates.firstOrNull { it.id == id }?.let { template ->
                                IconButton(onClick = { editing = template }) { Icon(Icons.Default.Edit, stringResource(R.string.template_edit)) }
                            }
                            Spacer(Modifier.weight(1f))
                            if (steps.size > 1) {
                                IconButton(enabled = index > 0, onClick = { update(steps.toMutableList().apply {
                                    add(index - 1, removeAt(index))
                                }) }) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.skill_step_up)) }
                                IconButton(enabled = index < steps.lastIndex, onClick = { update(steps.toMutableList().apply {
                                    add(index + 1, removeAt(index))
                                }) }) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.skill_step_down)) }
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.skill_next_compact), style = MaterialTheme.typography.titleSmall)
                    if (configuration.afterSteps.isEmpty()) Text(stringResource(R.string.skill_after_required), style = MaterialTheme.typography.bodySmall)
                }
                WorkflowChoice(stringResource(R.string.skill_next), configuration.nextSkill,
                    skills.map { it to it.removeSuffix(".md") }, enabled = configuration.afterSteps.isNotEmpty(),
                    modifier = Modifier.widthIn(max = 180.dp), onChoose = { onChange(configuration.copy(nextSkill = it)) })
                if (configuration.nextSkill != null) IconButton(onClick = { onChange(configuration.copy(nextSkill = null)) }) {
                    Icon(Icons.Default.Remove, stringResource(R.string.remove))
                }
            }
        }
    }))
    editing?.let { template ->
        TemplateMetadataDialog(template, saving, error, onDismiss = { editing = null; error = null }) { updated ->
            saving = true
            error = null
            scope.launch {
                try {
                    templates = withContext(Dispatchers.IO) {
                        vm.interactiveTemplates.save(updated, replace = true, expected = template)
                        vm.interactiveTemplates.list()
                    }
                    editing = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message.orEmpty() }
                finally { saving = false }
            }
        }
    }
}

@Composable
private fun WorkflowChoice(title: String, selected: String?, choices: List<Pair<String, String>>,
    modifier: Modifier = Modifier, enabled: Boolean = true, iconOnly: Boolean = false, onChoose: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val none = stringResource(R.string.skill_workflow_none)
    val label = choices.firstOrNull { it.first == selected }?.second ?: selected ?: none
    val open = { query = ""; expanded = true }
    if (iconOnly) IconButton(enabled = enabled, onClick = open, modifier = modifier) {
        Icon(Icons.Default.Add, title)
    } else TextButton(enabled = enabled, onClick = open,
        modifier = modifier.semantics { contentDescription = "$title: $label" }) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.skill_workflow_search)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                if (!iconOnly) item { TextButton(onClick = { expanded = false; onChoose(null) }) { Text(none) } }
                val matches = choices.filter { it.second.contains(query, ignoreCase = true) }
                items(matches, key = { it.first }) { (id, name) ->
                    TextButton(onClick = { expanded = false; onChoose(id) }, modifier = Modifier.fillMaxWidth()) { Text(name) }
                }
                if (matches.isEmpty()) item { Text(stringResource(R.string.skill_workflow_empty)) }
            }
        }
    }, confirmButton = {}, dismissButton = {
        TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.cancel)) }
    })
}
