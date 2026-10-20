package com.newoether.agora.ui.templates

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.templates.*
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.TextButton
import com.newoether.agora.ui.settings.*
import kotlinx.coroutines.*

/** Settings owns browsing and editing. Preview never writes a chat or executes a workflow. */
@Composable
internal fun TemplatesScreen(repository: InteractiveTemplateRepository,
    preparePreview: suspend (InteractiveTemplate) -> InteractiveAnswer, onBack: () -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var templates by remember(repository) { mutableStateOf<List<InteractiveTemplate>>(emptyList()) }
    var loaded by remember(repository) { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<InteractiveTemplate?>(null) }
    var deleting by remember { mutableStateOf<InteractiveTemplate?>(null) }
    var replacing by remember { mutableStateOf<InteractiveTemplate?>(null) }
    var exporting by remember { mutableStateOf<InteractiveTemplate?>(null) }

    fun perform(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                operation()
                templates = withContext(ioDispatcher) { repository.list() }
                loaded = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: context.getString(R.string.template_operation_failed) }
            finally { busy = false }
        }
    }
    LaunchedEffect(repository) { perform {} }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = replacing
        replacing = null
        if (uri != null) perform {
            withContext(ioDispatcher) {
                val bytes = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytesBounded(InteractiveTemplate.MAX_FILE_BYTES)
                } ?: error(context.getString(R.string.template_read_failed))
                val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                val imported = InteractiveTemplate.decode(text)
                repository.save(if (target == null) imported else target.copy(answer = imported.answer), replace = target != null, expected = target)
            }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val selected = exporting
        exporting = null
        if (uri != null && selected != null) perform {
            withContext(ioDispatcher) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(selected.encode().toByteArray(Charsets.UTF_8)) }
                    ?: error(context.getString(R.string.template_write_failed))
            }
        }
    }
    fun back() { if (!busy) { if (editing == null) onBack() else { editing = null; error = null } } }
    BackHandler { back() }
    GuardedAnimatedContent(editing, forward = editing != null) { target ->
        if (target != null) TemplateEditorPage(target, busy, error, preparePreview, onBack = { back() }, onSave = { updated ->
            perform {
                withContext(ioDispatcher) { repository.save(updated, replace = true, expected = target) }
                editing = null
            }
        }) else CollapsingSettingsScaffold(title = stringResource(R.string.templates), onBack = { back() }, actions = {
            TextButton(enabled = !busy, onClick = { replacing = null; importFile.launch(arrayOf("application/json", "text/plain")) }) {
                Text(stringResource(R.string.template_import))
            }
        }) {
            SettingsGroupColumn {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.template_search)) }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                SettingsGroup(title = stringResource(R.string.templates), items = buildList {
                    if (!loaded) add { Text(stringResource(R.string.loading_label), Modifier.padding(16.dp)) }
                    if (loaded && templates.isEmpty()) add { Text(stringResource(R.string.template_empty), Modifier.padding(16.dp)) }
                    templates.filter { it.name.contains(query, true) || it.description.contains(query, true) }.forEach { template -> add {
                        var menu by remember(template.id) { mutableStateOf(false) }
                        SettingsItem(headlineContent = { Text(template.name) },
                            supportingContent = { Text(template.description.ifBlank { template.answer.description }) },
                            leadingContent = { Icon(Icons.Default.Code, null) },
                            trailingContent = { Box {
                                IconButton(enabled = !busy, onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.template_actions)) }
                                AgoraDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    TemplateMenuAction(R.string.template_duplicate, { menu = false }) { perform { withContext(ioDispatcher) { repository.save(template.duplicate()) } } }
                                    TemplateMenuAction(R.string.template_replace, { menu = false }) { replacing = template; importFile.launch(arrayOf("application/json", "text/plain")) }
                                    TemplateMenuAction(R.string.template_export, { menu = false }) { exporting = template; exportFile.launch("${template.id}.agora-template.json") }
                                    TemplateMenuAction(R.string.template_delete, { menu = false }) { deleting = template }
                                }
                            } }, modifier = Modifier.clickable(enabled = !busy) { error = null; editing = template })
                    } }
                })
            }
        }
    }
    deleting?.let { template ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text(stringResource(R.string.template_delete)) },
            text = { Text(template.name) }, confirmButton = {
                TextButton(enabled = !busy, onClick = { perform { withContext(ioDispatcher) { repository.delete(template.id) }; deleting = null } }) {
                    Text(stringResource(R.string.template_delete), color = MaterialTheme.colorScheme.error)
                }
            }, dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun TemplateMenuAction(label: Int, onDismiss: () -> Unit, onAction: () -> Unit) {
    AgoraDropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onDismiss(); onAction() })
}
