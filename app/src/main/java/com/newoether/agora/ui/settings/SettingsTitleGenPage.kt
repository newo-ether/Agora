package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.modelAliasDisplayName
import com.newoether.agora.data.modelDisplayName
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.ui.components.optionClickable
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Switch
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTitleGenPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val titleGenEnabled by viewModel.settings.titleGenerationEnabled.collectAsState()
    val titleGenModel by viewModel.settings.titleGenerationModel.collectAsState()
    val titleGenPrompt by viewModel.settings.titleGenerationPrompt.collectAsState()
    val titleGenNotificationsEnabled by
        viewModel.settings.titleGenerationNotificationsEnabled.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val modelProviderNames by viewModel.settings.modelProviderNames.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    var showTitleModelDialog by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_title_gen),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("title-generation.md") }
    ) {
            SettingsGroupColumn {
                SettingsGroup(
                    title = stringResource(R.string.settings_title_gen),
                    items = buildList {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.title_gen_auto)) },
                                supportingContent = { Text(stringResource(R.string.title_gen_auto_desc)) },
                                leadingContent = { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = {
                                    Switch(checked = titleGenEnabled, onCheckedChange = { viewModel.settings.setTitleGenerationEnabled(it) })
                                },
                                modifier = Modifier.clickable { viewModel.settings.setTitleGenerationEnabled(!titleGenEnabled) }
                            )
                        }
                        if (titleGenEnabled) {
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.title_gen_model)) },
                                    supportingContent = {
                                        val displayName = if (titleGenModel == null) stringResource(R.string.title_gen_current_model) else {
                                            modelDisplayName(titleGenModel!!, modelAliases, customProviders, modelProviderNames[titleGenModel] != false)
                                        }
                                        Text(displayName)
                                    },
                                    leadingContent = { Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary) },
                                    modifier = Modifier.clickable { showTitleModelDialog = true }
                                )
                            }
                        }
                        add {
                            SettingsItem(
                                headlineContent = {
                                    Text(stringResource(R.string.title_gen_notifications))
                                },
                                supportingContent = {
                                    Text(stringResource(R.string.title_gen_notifications_desc))
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Notifications,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = titleGenNotificationsEnabled,
                                        onCheckedChange =
                                            viewModel.settings::setTitleGenerationNotificationsEnabled,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    viewModel.settings.setTitleGenerationNotificationsEnabled(
                                        !titleGenNotificationsEnabled,
                                    )
                                },
                            )
                        }
                    }
                )
                SettingsGroup(
                    title = stringResource(R.string.advanced_title),
                    items = listOf({
                        PromptSettingItem(
                            title = stringResource(R.string.title_gen_prompt),
                            description = stringResource(R.string.title_gen_prompt_desc),
                            prompt = titleGenPrompt,
                            onClick = { showPromptDialog = true }
                        )
                    })
                )
            }
            if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }

    if (showTitleModelDialog) {
        val enabledModelsList = enabledModels.toList()
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showTitleModelDialog = false },
            title = { Text(stringResource(R.string.title_gen_select_model), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.title_gen_current_model), fontWeight = if (titleGenModel == null) FontWeight.Bold else FontWeight.Normal) },
                            leadingContent = {
                                RadioButton(selected = titleGenModel == null, onClick = {
                                    viewModel.settings.setTitleGenerationModel(null)
                                    showTitleModelDialog = false
                                })
                            },
                            modifier = Modifier.optionClickable {
                                viewModel.settings.setTitleGenerationModel(null)
                                showTitleModelDialog = false
                            }
                        )
                    }
                    items(enabledModelsList, key = { it }) { model ->
                        val titleParsed = com.newoether.agora.model.ModelId.parse(model)
                        val displayName = modelAliasDisplayName(
                            model,
                            modelAliases,
                            customProviders,
                        )
                        SettingsItem(
                            headlineContent = { Text(displayName, fontWeight = if (titleGenModel == model) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = { Text(providerDisplayName(titleParsed.providerName, customProviders), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)) },
                            leadingContent = {
                                RadioButton(selected = titleGenModel == model, onClick = {
                                    viewModel.settings.setTitleGenerationModel(model)
                                    showTitleModelDialog = false
                                })
                            },
                            modifier = Modifier.optionClickable {
                                viewModel.settings.setTitleGenerationModel(model)
                                showTitleModelDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTitleModelDialog = false }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }

    if (showPromptDialog) {
        PromptEditDialog(
            title = stringResource(R.string.title_gen_prompt),
            initialPrompt = titleGenPrompt,
            onDismiss = { showPromptDialog = false },
            onSave = { viewModel.settings.setTitleGenerationPrompt(it) }
        )
    }
}
