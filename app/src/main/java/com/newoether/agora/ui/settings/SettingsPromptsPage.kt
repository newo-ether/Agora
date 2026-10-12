package com.newoether.agora.ui.settings

import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.DefaultSystemPrompt
import com.newoether.agora.data.PromptTemplateItem
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareModalBottomSheet as ModalBottomSheet
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.util.UUID
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.sheetOptionClickable
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.TextButton

private const val DUPLICATE_TITLE_TOKEN = "__AGORA_PROMPT_TITLE__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPromptsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val systemPrompts by viewModel.settings.systemPrompts.collectAsState()
    val activeSystemPromptId by viewModel.settings.activeSystemPromptId.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    var editingEntry by remember { mutableStateOf<SystemPromptEntry?>(null) }
    var showDeletePromptConfirm by remember { mutableStateOf<SystemPromptEntry?>(null) }
    var showTemplatePicker by remember { mutableStateOf(false) }
    var templateActionInFlight by remember { mutableStateOf(false) }
    var templateActionGeneration by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val motionPolicy = LocalAgoraMotionPolicy.current
    val templateSheetState = rememberModalBottomSheetState()
    val duplicateTitleTemplate = stringResource(
        R.string.prompts_duplicate_title,
        DUPLICATE_TITLE_TOKEN,
    )

    // Start the destination transition and sheet dismissal from the same tap. Keep the sheet
    // composed until its hide animation completes so neither surface pops out mid-transition.
    val pickTemplate: (SystemPromptEntry) -> Unit = { entry ->
        if (!templateActionInFlight) {
            templateActionInFlight = true
            val actionGeneration = ++templateActionGeneration
            editingEntry = entry
            if (motionPolicy.allowSpatialTransitions) {
                scope.launch {
                    try {
                        templateSheetState.hide()
                    } finally {
                        if (templateActionGeneration == actionGeneration) {
                            showTemplatePicker = false
                            templateActionInFlight = false
                        }
                    }
                }
            } else {
                showTemplatePicker = false
                templateActionInFlight = false
            }
        }
    }

    BackHandler(enabled = editingEntry != null) {
        editingEntry = null
    }

    GuardedAnimatedContent(
        targetState = editingEntry,
        forward = editingEntry != null
    ) { currentEntry ->
        if (currentEntry != null) {
            SettingsSecondaryPane {
                SystemPromptEditorPage(
                    entry = currentEntry,
                    onSave = { title, systemItems, userItems, assistantItems ->
                        if (systemPrompts.any { it.id == currentEntry.id }) {
                            viewModel.settings.updateSystemPrompt(
                                currentEntry.id,
                                title,
                                systemItems,
                                userItems,
                                assistantItems,
                            )
                        } else {
                            viewModel.settings.addSystemPrompt(
                                title,
                                systemItems,
                                userItems,
                                assistantItems,
                            )
                        }
                        editingEntry = null
                    },
                    onBack = { editingEntry = null },
                    showDocFab = showDocFab,
                )
            }
        } else {
            PromptList(
                systemPrompts = systemPrompts,
                activeSystemPromptId = activeSystemPromptId,
                onSelectPrompt = { viewModel.settings.setActiveSystemPrompt(it) },
                onEdit = { editingEntry = it },
                onDuplicate = { entry ->
                    val copyTitle = duplicateTitleTemplate.replace(
                        DUPLICATE_TITLE_TOKEN,
                        entry.title,
                    )
                    editingEntry = entry.duplicateAsDraft(copyTitle)
                },
                onAdd = {
                    templateActionGeneration += 1
                    templateActionInFlight = false
                    showTemplatePicker = true
                },
                onDeleteRequest = { showDeletePromptConfirm = it },
                onBack = onBack
            )
        }
    }

    if (showTemplatePicker) {
        ModalBottomSheet(
            onDismissRequest = {
                templateActionGeneration += 1
                templateActionInFlight = false
                showTemplatePicker = false
            },
            sheetState = templateSheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            DialogWindowEdgeToEdge()
            Text(
                text = stringResource(R.string.prompts_template_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsItem(
                headlineContent = { Text(stringResource(R.string.prompts_template_blank), fontWeight = FontWeight.Medium) },
                supportingContent = { Text(stringResource(R.string.prompts_template_blank_desc), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingContent = {
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                startPadding = SETTINGS_ITEM_SHEET_PADDING,
                endPadding = SETTINGS_ITEM_SHEET_PADDING,
                modifier = Modifier.fillMaxWidth().sheetOptionClickable(
                    enabled = !templateActionInFlight,
                ) {
                    pickTemplate(SystemPromptEntry(title = ""))
                }
            )
            SettingsItem(
                headlineContent = { Text(stringResource(R.string.prompts_template_default), fontWeight = FontWeight.Medium) },
                supportingContent = { Text(stringResource(R.string.prompts_template_default_desc), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingContent = {
                    Icon(Icons.Default.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                startPadding = SETTINGS_ITEM_SHEET_PADDING,
                endPadding = SETTINGS_ITEM_SHEET_PADDING,
                modifier = Modifier.fillMaxWidth().sheetOptionClickable(
                    enabled = !templateActionInFlight,
                ) {
                    pickTemplate(
                        DefaultSystemPrompt.create(java.util.Locale.getDefault()).copy(title = ""),
                    )
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    showDeletePromptConfirm?.let { entry ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showDeletePromptConfirm = null },
            title = { Text(stringResource(R.string.prompts_delete_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.prompts_delete_text, entry.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.settings.deleteSystemPrompt(entry.id)
                        showDeletePromptConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.provider_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeletePromptConfirm = null }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }
}

private fun SystemPromptEntry.duplicateAsDraft(title: String): SystemPromptEntry =
    copy(
        id = UUID.randomUUID().toString(),
        title = title,
        content = "",
        systemItems = resolvedSystemItems.copyWithNewIds(),
        userItems = resolvedUserItems.copyWithNewIds(),
        assistantItems = resolvedAssistantItems.copyWithNewIds(),
        userPrependItems = emptyList(),
        userPostpendItems = emptyList(),
    )

private fun List<PromptTemplateItem>.copyWithNewIds(): List<PromptTemplateItem> =
    map { it.copy(id = UUID.randomUUID().toString()) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptList(
    systemPrompts: List<SystemPromptEntry>,
    activeSystemPromptId: String?,
    onSelectPrompt: (String) -> Unit,
    onEdit: (SystemPromptEntry) -> Unit,
    onDuplicate: (SystemPromptEntry) -> Unit,
    onAdd: () -> Unit,
    onDeleteRequest: (SystemPromptEntry) -> Unit,
    onBack: () -> Unit
) {
    CollapsingSettingsScaffold(
        title = stringResource(R.string.prompts_title),
        onBack = onBack
    ) {
            val promptItems: List<@Composable () -> Unit> = buildList {
                if (systemPrompts.isEmpty()) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.prompts_empty_title), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            supportingContent = { Text(stringResource(R.string.prompts_empty_desc), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                            leadingContent = { Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                            modifier = Modifier.heightIn(min = 64.dp)
                        )
                    }
                }
                systemPrompts.forEach { entry ->
                    add {
                        var showMenu by remember { mutableStateOf(false) }
                        SettingsItem(
                            headlineContent = { Text(entry.title, fontWeight = FontWeight.Medium) },
                            supportingContent = {
                                val preview = entry.resolvedSystemItems
                                    .firstOrNull { it.value.isNotBlank() }
                                    ?.value
                                    ?: entry.content
                                val text = preview.ifBlank { stringResource(R.string.prompts_empty_preview) }
                                Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = {
                                RadioButton(selected = entry.id == activeSystemPromptId, onClick = { onSelectPrompt(entry.id) }, modifier = Modifier.size(24.dp))
                            },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.options), modifier = Modifier.size(18.dp))
                                    }
                                    AgoraDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 16.dp) {
                                        AgoraDropdownMenuItem(text = { Text(stringResource(R.string.provider_edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; onEdit(entry) })
                                        AgoraDropdownMenuItem(text = { Text(stringResource(R.string.prompts_duplicate)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.scale(0.9f)) }, onClick = { showMenu = false; onDuplicate(entry) })
                                        AgoraDropdownMenuItem(text = { Text(stringResource(R.string.provider_delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onDeleteRequest(entry) })
                                    }
                                }
                            },
                            leadingSpacing = 16.dp,
                            modifier = Modifier.clickable { onSelectPrompt(entry.id) }.padding(start = 8.dp)
                        )
                    }
                }

                add {
                    SettingsAddItem(
                        label = stringResource(R.string.prompts_add),
                        onClick = onAdd,
                    )
                }
            }
            SettingsGroupColumn {
                SettingsGroup(title = stringResource(R.string.prompts_title), items = promptItems)
            }
    }
}
