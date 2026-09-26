package com.newoether.agora.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.newoether.agora.R
import com.newoether.agora.data.DefaultSystemPrompt
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.ui.settings.SystemPromptEditorPage
import kotlinx.coroutines.launch

/**
 * The app's single system-prompt picker: a global-default row, one row per saved prompt, and
 * Create / Cancel / Save. Create opens the full-screen prompt editor and selects the new prompt on
 * return. Chat and Tasks both show this dialog, so the two pickers cannot drift apart.
 *
 * [selectionKey] resets the pending choice when the owner's persisted selection changes.
 */
@Composable
internal fun SystemPromptPickerDialog(
    settings: SettingsRepository,
    initialSelectedId: String?,
    selectionKey: Any?,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val systemPrompts by settings.systemPrompts.collectAsState()
    val activeSystemPromptId by settings.activeSystemPromptId.collectAsState()
    val showDocFab by settings.showDocumentationFab.collectAsState()
    var promptDraft by remember { mutableStateOf<SystemPromptEntry?>(null) }
    var pendingCreatedPromptId by remember { mutableStateOf<String?>(null) }
    var savingPromptDraft by remember { mutableStateOf(false) }
    val promptEditorScope = rememberCoroutineScope()
    var selectedPromptId by remember(selectionKey) { mutableStateOf(initialSelectedId) }

    // Select a newly created prompt only once it is visible in the list.
    val createdPromptId = pendingCreatedPromptId?.takeIf { id -> systemPrompts.any { it.id == id } }
    LaunchedEffect(createdPromptId) {
        createdPromptId?.let { id ->
            selectedPromptId = id
            pendingCreatedPromptId = null
        }
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.system_prompt), fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { selectedPromptId = null }.padding(8.dp)
                    ) {
                        RadioButton(
                            selected = selectedPromptId == null,
                            onClick = { selectedPromptId = null }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.global_default_format, globalDefaultTitle(systemPrompts, activeSystemPromptId)))
                    }
                }
                items(systemPrompts, key = { it.id }) { prompt ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { selectedPromptId = prompt.id }.padding(8.dp)
                    ) {
                        RadioButton(
                            selected = selectedPromptId == prompt.id,
                            onClick = { selectedPromptId = prompt.id }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(prompt.title)
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { promptDraft = DefaultSystemPrompt.create().copy(title = "") }) {
                    Text(stringResource(R.string.memory_create))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { onSave(selectedPromptId) }) {
                    Text(stringResource(R.string.save))
                }
            }
        },
    )

    promptDraft?.let { draft ->
        Dialog(
            onDismissRequest = { if (!savingPromptDraft) promptDraft = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            DialogWindowEdgeToEdge()
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                SystemPromptEditorPage(
                    entry = draft,
                    isNew = true,
                    saveEnabled = !savingPromptDraft,
                    onSave = { title, systemItems, userItems, assistantItems ->
                        if (!savingPromptDraft) {
                            savingPromptDraft = true
                            promptEditorScope.launch {
                                try {
                                    pendingCreatedPromptId = settings.addSystemPromptAndAwait(
                                        id = draft.id,
                                        title = title,
                                        systemItems = systemItems,
                                        userItems = userItems,
                                        assistantItems = assistantItems,
                                    )
                                    promptDraft = null
                                } finally {
                                    savingPromptDraft = false
                                }
                            }
                        }
                    },
                    onBack = { if (!savingPromptDraft) promptDraft = null },
                    showDocFab = showDocFab,
                )
            }
        }
    }
}

/** Title shown for "use the global default": the active prompt's title, or "No system prompt". */
@Composable
internal fun globalDefaultTitle(prompts: List<SystemPromptEntry>, activeSystemPromptId: String?): String =
    prompts.find { it.id == activeSystemPromptId }?.title ?: stringResource(R.string.no_system_prompt)
