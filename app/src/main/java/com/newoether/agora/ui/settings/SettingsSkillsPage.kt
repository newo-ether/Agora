package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
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
import com.newoether.agora.skills.*
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.TextButton
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.*

private data class SkillEditTarget(val document: SkillDocument, val original: String?, val description: String)

/** Same settings owner and shared list/detail navigation as System Prompts. */
@Composable
fun SettingsSkillsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = viewModel.skillManager
    val revision by manager.catalogRevision.collectAsState()
    val access by viewModel.settings.accessSkills.collectAsState()
    var files by remember { mutableStateOf<List<com.newoether.agora.data.SkillManager.SkillFileInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SkillEditTarget?>(null) }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    fun perform(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { viewModel.emitSnackbar(failure.message ?: context.getString(R.string.unknown_error)) }
            finally { busy = false }
        }
    }
    LaunchedEffect(revision) {
        try { files = withContext(Dispatchers.IO) { manager.listFiles() }; loaded = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { viewModel.emitSnackbar(failure.message.orEmpty()) }
    }
    BackHandler { if (!busy) { if (editing == null) onBack() else editing = null } }
    GuardedAnimatedContent(editing, forward = editing != null) { target ->
        if (target != null) {
            SkillEditorPage(viewModel, target.document, target.description, busy,
                onBack = { if (!busy) editing = null },
                onSave = { document, description -> perform {
                    withContext(Dispatchers.IO) {
                        manager.saveDocument(document, description,
                            originalName = target.document.name.takeIf { target.original != null }, expected = target.original)
                    }
                    editing = null
                } })
        } else CollapsingSettingsScaffold(title = stringResource(R.string.skills_title), onBack = onBack) {
            SettingsGroupColumn {
                SettingsGroup(title = stringResource(R.string.memory_access_title), items = listOf({
                    SettingsItem(headlineContent = { Text(stringResource(R.string.skills_access)) },
                        supportingContent = { Text(stringResource(R.string.skills_access_desc)) },
                        leadingContent = { Icon(Icons.Default.Extension, null) },
                        trailingContent = { Switch(access, viewModel.settings::setAccessSkills) },
                        modifier = Modifier.clickable { viewModel.settings.setAccessSkills(!access) })
                }))
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.skill_search)) }, modifier = Modifier.fillMaxWidth())
                SettingsGroup(title = stringResource(R.string.skills_saved_title), items = buildList {
                    if (!loaded) add { Text(stringResource(R.string.loading_label), Modifier.padding(16.dp)) }
                    if (loaded && files.isEmpty()) add { Text(stringResource(R.string.skills_empty), Modifier.padding(16.dp)) }
                    files.filter { it.name.contains(query, true) || it.description.contains(query, true) }.forEach { file -> add {
                        var menu by remember(file.name) { mutableStateOf(false) }
                        SettingsItem(headlineContent = { Text(file.name.removeSuffix(".md")) },
                            supportingContent = { Text(file.description.ifBlank { file.name }) },
                            leadingContent = { Icon(Icons.Default.Extension, null) },
                            trailingContent = { Box {
                                IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, stringResource(R.string.template_actions)) }
                                com.newoether.agora.ui.components.AgoraDropdownMenu(menu, { menu = false }) {
                                    com.newoether.agora.ui.components.AgoraDropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                                        menu = false; deleting = file.name
                                    })
                                }
                            } }, modifier = Modifier.clickable(enabled = !busy) { perform {
                                val body = withContext(Dispatchers.IO) { manager.readFile(file.name) }
                                editing = SkillEditTarget(SkillDocument.decode(file.name, body), body, file.description)
                            } })
                    } }
                    add { SettingsAddItem(label = stringResource(R.string.skills_add), onClick = {
                        if (!busy) editing = SkillEditTarget(SkillDocument("", "", SkillConfiguration()), null, "")
                    }) }
                })
                SettingsGroup(title = stringResource(R.string.skill_create_options), items = listOf({
                    SettingsItem(headlineContent = { Text(stringResource(R.string.skill_create_ai)) },
                        supportingContent = { Text(stringResource(R.string.skill_create_ai_desc)) },
                        leadingContent = { Icon(Icons.Default.Edit, null) },
                        modifier = Modifier.clickable(enabled = !busy) {
                            val owner = if (viewModel.isNewChatMode.value) com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID else viewModel.currentConversationId.value ?: com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID
                            perform {
                                viewModel.conversationComposer.load(owner)
                                try {
                                    val appended = viewModel.conversationComposer.appendText(owner, context.getString(R.string.skill_create_ai_prompt))
                                    check(viewModel.conversationComposer.persistText(owner, appended)) { context.getString(R.string.skill_draft_save_failed) }
                                    viewModel.emitSnackbar(context.getString(R.string.skill_draft_ready))
                                } finally { withContext(NonCancellable) { viewModel.conversationComposer.release(owner) } }
                            }
                        })
                }))
            }
        }
    }
    deleting?.let { name -> AlertDialog(onDismissRequest = { if (!busy) deleting = null },
        title = { Text(stringResource(R.string.delete)) }, text = { Text(name) },
        confirmButton = { TextButton(enabled = !busy, onClick = { perform {
            withContext(Dispatchers.IO) { manager.deleteFile(name) }; deleting = null
        } }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }, enabled = !busy) { Text(stringResource(R.string.cancel)) } }) }
}
