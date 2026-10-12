package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.modelAliasDisplayName
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.ui.common.PersistedSliderFeedbackGate
import com.newoether.agora.ui.components.providerIcon
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.optionClickable
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Switch
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTranscriptionPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val imageTranscriptionEnabled by viewModel.settings.imageTranscriptionEnabled.collectAsState()
    val transcriptionEnabledModels by viewModel.settings.imageTranscriptionEnabledModels.collectAsState()
    val transcriptionModel by viewModel.settings.imageTranscriptionModel.collectAsState()
    val batchSize by viewModel.settings.imageTranscriptionBatchSize.collectAsState()
    val transcriptionPrompt by viewModel.settings.imageTranscriptionPrompt.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    var showModelDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var showMenuForModel by remember { mutableStateOf<String?>(null) }
    val batchSizeGate = remember {
        PersistedSliderFeedbackGate(
            initialPersisted = batchSize,
            toDisplay = Int::toFloat,
        )
    }
    LaunchedEffect(batchSize) { batchSizeGate.reconcile(batchSize) }
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    val availableToAdd = remember(enabledModels, transcriptionEnabledModels) {
        enabledModels.filter { it !in transcriptionEnabledModels }.sortedBy { it }
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_transcription),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("transcription.md") }
    ) {
            SettingsGroupColumn {
                SettingsGroup(
                    title = stringResource(R.string.settings_transcription),
                    items = listOf({
                        SettingsItem(
                            headlineContent = {
                                Text(stringResource(R.string.transcription_enable))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.settings_transcription_desc))
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = imageTranscriptionEnabled,
                                    onCheckedChange =
                                        viewModel.settings::setImageTranscriptionEnabled,
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setImageTranscriptionEnabled(
                                    !imageTranscriptionEnabled
                                )
                            },
                        )
                    }),
                )

                if (imageTranscriptionEnabled) {
                SettingsGroup(
                    title = stringResource(R.string.transcription_model),
                    items = listOf({
                        val displayName = transcriptionModel?.let {
                            modelAliasDisplayName(it, modelAliases, customProviders)
                        } ?: stringResource(R.string.transcription_no_model)
                        val selectedProvider = transcriptionModel?.let {
                            providerDisplayName(
                                com.newoether.agora.model.ModelId.parse(it).providerName,
                                customProviders,
                            )
                        }
                        val selectedIconRes = selectedProvider?.let { providerIcon(it) } ?: 0
                        val isSelectedLocal = selectedProvider.equals(Constants.PROVIDER_LOCAL, ignoreCase = true)
                        SettingsItem(
                            headlineContent = {
                                Text(
                                    displayName,
                                    color = if (transcriptionModel == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            supportingContent = if (transcriptionModel != null) {
                                { Text(selectedProvider ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) }
                            } else null,
                            leadingContent = {
                                when {
                                    transcriptionModel == null -> Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                    isSelectedLocal -> Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                    selectedIconRes != 0 -> Icon(painterResource(selectedIconRes), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                    else -> Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                }
                            },
                            modifier = Modifier.heightIn(min = 64.dp).clickable(enabled = enabledModels.isNotEmpty()) { showModelDialog = true }
                        )
                    })
                )

                SettingsGroup(
                    title = stringResource(R.string.transcription_enabled_models),
                items = buildList {
                    if (transcriptionEnabledModels.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.transcription_no_models_enabled), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                supportingContent = { Text(stringResource(R.string.transcription_no_models_hint), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                                leadingContent = { Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                                modifier = Modifier.heightIn(min = 64.dp)
                            )
                        }
                    } else {
                        val sorted = transcriptionEnabledModels.toList().sortedBy { it }
                        for (model in sorted) {
                            val parsedModel = com.newoether.agora.model.ModelId.parse(model)
                            val displayName = modelAliasDisplayName(
                                model,
                                modelAliases,
                                customProviders,
                            )
                            val providerName = providerDisplayName(
                                parsedModel.providerName,
                                customProviders,
                            )
                            add {
                                val iconRes = providerIcon(providerName)
                                val isLocal = providerName.equals(Constants.PROVIDER_LOCAL, ignoreCase = true)
                                SettingsItem(
                                    headlineContent = { Text(displayName) },
                                    supportingContent = { Text(providerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                                    leadingContent = {
                                        when {
                                            isLocal -> Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                            iconRes != 0 -> Icon(painterResource(iconRes), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                            else -> Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                        }
                                    },
                                    trailingContent = {
                                        Box {
                                            IconButton(onClick = { showMenuForModel = model }, modifier = Modifier.size(24.dp)) {
                                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.options), modifier = Modifier.size(18.dp))
                                            }
                                            AgoraDropdownMenu(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                tonalElevation = 16.dp,
                                                expanded = showMenuForModel == model,
                                                onDismissRequest = { showMenuForModel = null }
                                            ) {
                                                AgoraDropdownMenuItem(
                                                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                                    onClick = {
                                                        showMenuForModel = null
                                                        viewModel.settings.removeImageTranscriptionModel(model)
                                                    }
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.clickable { showMenuForModel = model }
                                )
                            }
                        }
                    }
                    if (availableToAdd.isNotEmpty()) {
                        add {
                            SettingsAddItem(
                                label = stringResource(R.string.transcription_add_model),
                                onClick = { showAddDialog = true },
                            )
                        }
                    }
                }
            )

            SettingsGroup(
                title = stringResource(R.string.advanced_title),
                items = buildList {
                    add {
                        PromptSettingItem(
                            title = stringResource(R.string.transcription_prompt),
                            description = stringResource(R.string.transcription_prompt_desc),
                            prompt = transcriptionPrompt,
                            onClick = { showPromptDialog = true }
                        )
                    }
                    add {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = stringResource(R.string.transcription_batch_size),
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = batchSizeGate.displayed.toInt().toString(),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.transcription_batch_size_desc),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    Slider(
                                        value = batchSizeGate.displayed,
                                        onValueChange = batchSizeGate::updateFromGesture,
                                        onValueChangeFinished = {
                                            val committed = batchSizeGate.displayed.toInt()
                                            if (committed == batchSize) {
                                                batchSizeGate.settleWithoutWrite(
                                                    batchSize,
                                                    committed.toFloat(),
                                                )
                                            } else {
                                                batchSizeGate.expectPersisted(
                                                    committed,
                                                    committed.toFloat(),
                                                )
                                                viewModel.settings
                                                    .setImageTranscriptionBatchSize(committed)
                                            }
                                        },
                                        valueRange = 1f..10f,
                                        steps = 8,
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            )
                }
            }
            if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }

    if (showModelDialog) {
        val enabledModelsList = enabledModels.toList()
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showModelDialog = false },
            title = { Text(stringResource(R.string.transcription_select_model), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(enabledModelsList, key = { it }) { model ->
                        val dialogParsed = com.newoether.agora.model.ModelId.parse(model)
                        val displayName = modelAliasDisplayName(
                            model,
                            modelAliases,
                            customProviders,
                        )
                        SettingsItem(
                            headlineContent = { Text(displayName, fontWeight = if (transcriptionModel == model) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = { Text(providerDisplayName(dialogParsed.providerName, customProviders), style = MaterialTheme.typography.bodySmall) },
                            leadingContent = {
                                RadioButton(selected = transcriptionModel == model, onClick = {
                                    viewModel.settings.setImageTranscriptionModel(model)
                                    showModelDialog = false
                                })
                            },
                            modifier = Modifier.optionClickable {
                                viewModel.settings.setImageTranscriptionModel(model)
                                showModelDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModelDialog = false }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }

    if (showAddDialog) {
        var selected by remember { mutableStateOf(emptySet<String>()) }
        val availableList = availableToAdd
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.transcription_add_model), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(availableList, key = { it }) { model ->
                        val addParsed = com.newoether.agora.model.ModelId.parse(model)
                        val displayName = modelAliasDisplayName(
                            model,
                            modelAliases,
                            customProviders,
                        )
                        val checked = model in selected
                        SettingsItem(
                            headlineContent = { Text(displayName) },
                            supportingContent = { Text(providerDisplayName(addParsed.providerName, customProviders), style = MaterialTheme.typography.bodySmall) },
                            leadingContent = {
                                Checkbox(checked = checked, onCheckedChange = {
                                    selected = if (checked) selected - model else selected + model
                                })
                            },
                            modifier = Modifier.optionClickable {
                                selected = if (checked) selected - model else selected + model
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.settings.addImageTranscriptionModels(selected)
                    showAddDialog = false
                }) { Text(stringResource(R.string.provider_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text(stringResource(R.string.provider_cancel)) }
            }
        )
    }

    if (showPromptDialog) {
        PromptEditDialog(
            title = stringResource(R.string.transcription_prompt),
            initialPrompt = transcriptionPrompt,
            onDismiss = { showPromptDialog = false },
            onSave = { viewModel.settings.setImageTranscriptionPrompt(it) }
        )
    }
}
