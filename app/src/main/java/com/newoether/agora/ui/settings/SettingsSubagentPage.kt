package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import com.newoether.agora.ui.icons.LocalModelIcon
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.DEFAULT_SUBAGENT_MAX_CONCURRENT
import com.newoether.agora.data.DEFAULT_SUBAGENT_MAX_TOTAL
import com.newoether.agora.data.SUBAGENT_MAX_CONCURRENT_RANGE
import com.newoether.agora.data.SUBAGENT_MAX_TOTAL_RANGE
import com.newoether.agora.data.SubagentCapsuleDisplayModes
import com.newoether.agora.data.modelAliasDisplayName
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.model.ModelId
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.optionClickable
import com.newoether.agora.ui.components.providerIcon
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ChatViewModel

@Composable
fun SettingsSubagentPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val subagentToolsEnabled by viewModel.settings.subagentToolsEnabled.collectAsState()
    val subagentDeleteEnabled by viewModel.settings.subagentDeleteEnabled.collectAsState()
    val subagentMaxTotal by viewModel.settings.subagentMaxTotal.collectAsState()
    val subagentMaxConcurrent by viewModel.settings.subagentMaxConcurrent.collectAsState()
    val subagentEnabledModels by viewModel.settings.subagentEnabledModels.collectAsState()
    val subagentRecommendedModel by viewModel.settings.subagentRecommendedModel.collectAsState()
    val capsuleDisplayMode by viewModel.settings.subagentCapsuleDisplayMode.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()

    SettingsSubagentContent(
        subagentToolsEnabled = subagentToolsEnabled,
        subagentEnabledModels = subagentEnabledModels,
        subagentRecommendedModel = subagentRecommendedModel,
        enabledModels = enabledModels,
        modelAliases = modelAliases,
        customProviders = customProviders,
        onSubagentToolsEnabledChange = viewModel.settings::setSubagentToolsEnabled,
        onAddModels = viewModel.settings::addSubagentModels,
        onRemoveModel = viewModel.settings::removeSubagentModel,
        onRecommendedModelChange = viewModel.settings::setSubagentRecommendedModel,
        onBack = onBack,
        capsuleDisplayMode = capsuleDisplayMode,
        onCapsuleDisplayModeChange = viewModel.settings::setSubagentCapsuleDisplayMode,
        subagentMaxTotal = subagentMaxTotal,
        subagentMaxConcurrent = subagentMaxConcurrent,
        onSubagentMaxTotalChange = viewModel.settings::setSubagentMaxTotal,
        onSubagentMaxConcurrentChange = viewModel.settings::setSubagentMaxConcurrent,
        subagentDeleteEnabled = subagentDeleteEnabled,
        onSubagentDeleteEnabledChange = viewModel.settings::setSubagentDeleteEnabled,
    )
}

@Composable
internal fun SettingsSubagentContent(
    subagentToolsEnabled: Boolean,
    subagentEnabledModels: Set<String>?,
    subagentRecommendedModel: String?,
    enabledModels: Set<String>,
    modelAliases: Map<String, String>,
    customProviders: List<CustomProviderConfig>,
    onSubagentToolsEnabledChange: (Boolean) -> Unit,
    onAddModels: (Set<String>) -> Unit,
    onRemoveModel: (String) -> Unit,
    onRecommendedModelChange: (String?) -> Unit,
    onBack: () -> Unit,
    capsuleDisplayMode: String = SubagentCapsuleDisplayModes.DEFAULT,
    onCapsuleDisplayModeChange: (String) -> Unit = {},
    subagentMaxTotal: Int = DEFAULT_SUBAGENT_MAX_TOTAL,
    subagentMaxConcurrent: Int = DEFAULT_SUBAGENT_MAX_CONCURRENT,
    onSubagentMaxTotalChange: (Int) -> Unit = {},
    onSubagentMaxConcurrentChange: (Int) -> Unit = {},
    subagentDeleteEnabled: Boolean = false,
    onSubagentDeleteEnabledChange: (Boolean) -> Unit = {},
) {
    val allowedModels = remember(subagentEnabledModels, enabledModels) {
        subagentEnabledModels.orEmpty().intersect(enabledModels).sorted()
    }
    val availableToAdd = remember(enabledModels, allowedModels) {
        (enabledModels - allowedModels.toSet()).sorted()
    }
    val recommendedModel = subagentRecommendedModel?.takeIf { it in allowedModels }
    var showRecommendedDialog by remember(subagentToolsEnabled) { mutableStateOf(false) }
    var showAddDialog by remember(subagentToolsEnabled) { mutableStateOf(false) }
    var showMenuForModel by remember(subagentToolsEnabled) { mutableStateOf<String?>(null) }
    var showDisplayMenu by remember { mutableStateOf(false) }
    val displayMode = SubagentCapsuleDisplayModes.normalize(capsuleDisplayMode)
    val displayOptions = listOf(
        SubagentCapsuleDisplayModes.INDIVIDUAL to R.string.subagents_capsule_display_individual,
        SubagentCapsuleDisplayModes.GROUPED to R.string.subagents_capsule_display_grouped,
    )

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_subagents),
        onBack = onBack,
        scrollState = rememberSettingsScrollState(),
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.settings_subagents),
                items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.subagents_enable)) },
                            supportingContent = { Text(stringResource(R.string.subagents_enable_desc)) },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.AccountTree,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Switch(checked = subagentToolsEnabled, onCheckedChange = null)
                            },
                            modifier = Modifier.toggleable(
                                value = subagentToolsEnabled,
                                role = Role.Switch,
                                onValueChange = onSubagentToolsEnabledChange,
                            ),
                        )
                    }
                    if (subagentToolsEnabled) add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.subagents_allow_delete)) },
                            supportingContent = { Text(stringResource(R.string.subagents_allow_delete_desc)) },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.Delete,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Switch(checked = subagentDeleteEnabled, onCheckedChange = null)
                            },
                            modifier = Modifier.toggleable(
                                value = subagentDeleteEnabled,
                                role = Role.Switch,
                                onValueChange = onSubagentDeleteEnabledChange,
                            ),
                        )
                    }
                },
            )
            if (subagentToolsEnabled) {
                SettingsGroup(
                    title = stringResource(R.string.subagents_recommended_model_title),
                    items = listOf {
                        val providerName = recommendedModel?.let {
                            providerDisplayName(ModelId.parse(it).providerName, customProviders)
                        }
                        SettingsItem(
                            headlineContent = {
                                Text(
                                    recommendedModel?.let {
                                        modelAliasDisplayName(it, modelAliases, customProviders)
                                    } ?: stringResource(R.string.subagents_no_recommended_model),
                                    color = if (recommendedModel == null) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            supportingContent = providerName?.let { provider ->
                                {
                                    Text(
                                        provider,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                }
                            },
                            leadingContent = { SubagentModelIcon(providerName) },
                            modifier = Modifier
                                .testTag("subagent-recommended-model")
                                .heightIn(min = 64.dp)
                                .clickable(enabled = allowedModels.isNotEmpty()) {
                                    showRecommendedDialog = true
                                },
                        )
                    },
                )
                SettingsGroup(
                    title = stringResource(R.string.subagents_model_list_title),
                    items = buildList {
                        if (allowedModels.isEmpty()) {
                            add {
                                SettingsItem(
                                    headlineContent = {
                                        Text(
                                            stringResource(R.string.subagents_no_models),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            stringResource(R.string.subagents_no_models_desc),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        )
                                    },
                                    leadingContent = { SubagentModelIcon(null) },
                                    modifier = Modifier.heightIn(min = 64.dp),
                                )
                            }
                        }
                        for (model in allowedModels) {
                            add {
                                val providerName = providerDisplayName(
                                    ModelId.parse(model).providerName,
                                    customProviders,
                                )
                                SettingsItem(
                                    headlineContent = {
                                        Text(modelAliasDisplayName(model, modelAliases, customProviders))
                                    },
                                    supportingContent = {
                                        Text(
                                            providerName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        )
                                    },
                                    leadingContent = { SubagentModelIcon(providerName) },
                                    trailingContent = {
                                        Box {
                                            IconButton(
                                                onClick = { showMenuForModel = model },
                                                modifier = Modifier.size(24.dp),
                                            ) {
                                                Icon(
                                                    Icons.Rounded.MoreVert,
                                                    contentDescription = stringResource(R.string.options),
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                            AgoraDropdownMenu(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                tonalElevation = 16.dp,
                                                expanded = showMenuForModel == model,
                                                onDismissRequest = { showMenuForModel = null },
                                            ) {
                                                AgoraDropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            stringResource(R.string.delete),
                                                            color = MaterialTheme.colorScheme.error,
                                                        )
                                                    },
                                                    leadingIcon = {
                                                        Icon(
                                                            Icons.Rounded.Delete,
                                                            null,
                                                            tint = MaterialTheme.colorScheme.error,
                                                        )
                                                    },
                                                    onClick = {
                                                        showMenuForModel = null
                                                        onRemoveModel(model)
                                                    },
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .testTag("subagent-model:$model")
                                        .clickable { showMenuForModel = model },
                                )
                            }
                        }
                        if (availableToAdd.isNotEmpty()) {
                            add {
                                SettingsAddItem(
                                    label = stringResource(R.string.subagents_add_models),
                                    onClick = { showAddDialog = true },
                                )
                            }
                        }
                    },
                )
            }
            SettingsGroup(
                title = stringResource(R.string.subagents_capsule_display_title),
                items = listOf {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.subagents_capsule_display_title)) },
                        supportingContent = { Text(stringResource(R.string.subagents_capsule_display_desc)) },
                        leadingContent = {
                            Icon(Icons.Rounded.AccountTree, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            Box {
                                Text(
                                    stringResource(displayOptions.first { it.first == displayMode }.second),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(96.dp).padding(end = 4.dp),
                                    textAlign = TextAlign.End,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                AgoraDropdownMenu(
                                    expanded = showDisplayMenu,
                                    onDismissRequest = { showDisplayMenu = false },
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    tonalElevation = 16.dp,
                                ) {
                                    displayOptions.forEach { (mode, label) ->
                                        AgoraDropdownMenuItem(
                                            text = { Text(stringResource(label)) },
                                            leadingIcon = {
                                                if (mode == displayMode) {
                                                    Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                                                }
                                            },
                                            onClick = {
                                                if (mode != displayMode) onCapsuleDisplayModeChange(mode)
                                                showDisplayMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.testTag("subagent-capsule-display")
                            .clickable { showDisplayMenu = true },
                    )
                },
            )
            SettingsGroup(
                title = stringResource(R.string.subagents_limits_title),
                items = listOf(
                    {
                        SubagentLimitSlider(
                            value = subagentMaxTotal,
                            valueRange = SUBAGENT_MAX_TOTAL_RANGE,
                            title = R.string.subagents_max_total,
                            description = R.string.subagents_max_total_desc,
                            onValueChange = onSubagentMaxTotalChange,
                            modifier = Modifier.testTag("subagent-max-total"),
                        )
                    },
                    {
                        SubagentLimitSlider(
                            value = subagentMaxConcurrent,
                            valueRange = SUBAGENT_MAX_CONCURRENT_RANGE.first..minOf(
                                SUBAGENT_MAX_CONCURRENT_RANGE.last,
                                subagentMaxTotal.coerceIn(SUBAGENT_MAX_TOTAL_RANGE),
                            ),
                            title = R.string.subagents_max_concurrent,
                            description = R.string.subagents_max_concurrent_desc,
                            onValueChange = onSubagentMaxConcurrentChange,
                            modifier = Modifier.testTag("subagent-max-concurrent"),
                        )
                    },
                ),
            )
        }
    }

    if (subagentToolsEnabled && showRecommendedDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showRecommendedDialog = false },
            title = {
                Text(
                    stringResource(R.string.subagents_recommended_model_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item(key = "no-preference") {
                        SettingsItem(
                            headlineContent = {
                                Text(stringResource(R.string.subagents_no_recommended_model))
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = recommendedModel == null,
                                    onClick = {
                                        onRecommendedModelChange(null)
                                        showRecommendedDialog = false
                                    },
                                )
                            },
                            modifier = Modifier.optionClickable {
                                onRecommendedModelChange(null)
                                showRecommendedDialog = false
                            },
                        )
                    }
                    items(allowedModels, key = { it }) { model ->
                        SettingsItem(
                            headlineContent = {
                                Text(
                                    modelAliasDisplayName(model, modelAliases, customProviders),
                                    fontWeight = if (recommendedModel == model) {
                                        FontWeight.Bold
                                    } else FontWeight.Normal,
                                )
                            },
                            supportingContent = {
                                Text(
                                    providerDisplayName(ModelId.parse(model).providerName, customProviders),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = recommendedModel == model,
                                    onClick = {
                                        onRecommendedModelChange(model)
                                        showRecommendedDialog = false
                                    },
                                )
                            },
                            modifier = Modifier.optionClickable {
                                onRecommendedModelChange(model)
                                showRecommendedDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRecommendedDialog = false }) {
                    Text(stringResource(R.string.provider_cancel))
                }
            },
        )
    }

    if (subagentToolsEnabled && showAddDialog) {
        var selected by remember { mutableStateOf(emptySet<String>()) }
        val selectedAvailable = selected.intersect(availableToAdd.toSet())
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(stringResource(R.string.subagents_add_models_title), fontWeight = FontWeight.Bold)
            },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(availableToAdd, key = { it }) { model ->
                        val checked = model in selected
                        SettingsItem(
                            headlineContent = {
                                Text(modelAliasDisplayName(model, modelAliases, customProviders))
                            },
                            supportingContent = {
                                Text(
                                    providerDisplayName(ModelId.parse(model).providerName, customProviders),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            leadingContent = {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selected = if (checked) selected - model else selected + model
                                    },
                                )
                            },
                            modifier = Modifier.optionClickable {
                                selected = if (checked) selected - model else selected + model
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddModels(selectedAvailable)
                        showAddDialog = false
                    },
                ) {
                    Text(stringResource(R.string.provider_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.provider_cancel))
                }
            },
        )
    }
}

@Composable
private fun SubagentModelIcon(providerName: String?) {
    val icon = providerName?.let(::providerIcon) ?: 0
    val tint = if (providerName == null) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    } else MaterialTheme.colorScheme.primary
    when {
        providerName == null -> Icon(Icons.Rounded.Layers, null, tint = tint, modifier = Modifier.size(24.dp))
        providerName.equals(Constants.PROVIDER_LOCAL, ignoreCase = true) -> {
            Icon(LocalModelIcon, null, tint = tint, modifier = Modifier.size(24.dp))
        }
        icon != 0 -> Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(24.dp))
        else -> Icon(Icons.Rounded.Api, null, tint = tint, modifier = Modifier.size(24.dp))
    }
}
