package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.modelAliasDisplayName
import com.newoether.agora.data.modelDisplayName
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.ui.common.PersistedSliderFeedbackGate
import com.newoether.agora.ui.components.optionClickable
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Switch
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContextPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val window by viewModel.settings.maxContextWindow.collectAsState()
    val visualize by viewModel.settings.visualizeContextRollout.collectAsState()
    val compact by viewModel.settings.contextCompactEnabled.collectAsState()
    val thresholdPercent by
        viewModel.settings.contextCompactThresholdPercent.collectAsState()
    val compactModel by viewModel.settings.contextCompactModel.collectAsState()
    val compactPrompt by viewModel.settings.contextCompactPrompt.collectAsState()
    val preserveSystemPrompt by viewModel.settings.contextCompactPreserveSystemPrompt.collectAsState()
    val retainCount by viewModel.settings.contextCompactRetainCount.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val aliases by viewModel.settings.modelAliases.collectAsState()
    val modelProviderNames by viewModel.settings.modelProviderNames.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    var modelDialog by remember { mutableStateOf(false) }
    var promptDialog by remember { mutableStateOf(false) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.context_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("context.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.context_window_default),
                items = listOf(
                    {
                        val presets = ContextBudget.PRESETS
                        fun nearestIndex(value: Int): Int = presets.indices.minByOrNull {
                            kotlin.math.abs(presets[it] - value)
                        } ?: 3
                        val sliderGate = remember {
                            PersistedSliderFeedbackGate(
                                initialPersisted = window,
                                toDisplay = { value ->
                                nearestIndex(value).toFloat()
                                },
                            )
                        }
                        LaunchedEffect(window) { sliderGate.reconcile(window) }
                        val draftIndex = sliderGate.displayed
                        val draft = presets[draftIndex.toInt().coerceIn(0, presets.lastIndex)]
                        ContextSliderItem(
                            icon = Icons.Default.Memory,
                            label = stringResource(R.string.context_window),
                            description = stringResource(R.string.context_window_desc),
                            displayValue = ContextBudget.compactLabel(draft),
                            sliderValue = draftIndex,
                            valueRange = 0f..presets.lastIndex.toFloat(),
                            steps = presets.size - 2,
                            onValueChange = sliderGate::updateFromGesture,
                            onValueChangeFinished = {
                                if (draft != window) {
                                    sliderGate.expectPersisted(draft, draftIndex)
                                    viewModel.settings.setMaxContextWindow(draft)
                                } else {
                                    sliderGate.settleWithoutWrite(window, draftIndex)
                                }
                            },
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.context_visualize)) },
                            supportingContent = { Text(stringResource(R.string.context_visualize_desc)) },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Visibility,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = visualize,
                                    onCheckedChange = viewModel.settings::setVisualizeContextRollout,
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setVisualizeContextRollout(!visualize)
                            },
                        )
                    },
                ),
            )
            SettingsGroup(
                title = stringResource(R.string.context_compact),
                items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.context_compact_model)) },
                            supportingContent = {
                                Text(
                                    compactModel?.let {
                                        modelDisplayName(it, aliases, customProviders, modelProviderNames[it] != false)
                                    } ?: stringResource(R.string.title_gen_current_model)
                                )
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Chat,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            modifier = Modifier.clickable { modelDialog = true },
                        )
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.context_compact_auto)) },
                            supportingContent = { Text(stringResource(R.string.context_compact_auto_desc)) },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Compress,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = compact,
                                    onCheckedChange = viewModel.settings::setContextCompactEnabled,
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setContextCompactEnabled(!compact)
                            },
                        )
                    }
                    if (compact) {
                        add {
                            val sliderGate = remember {
                                PersistedSliderFeedbackGate(
                                    initialPersisted = thresholdPercent,
                                    toDisplay = { it.toFloat() },
                                )
                            }
                            LaunchedEffect(thresholdPercent) {
                                sliderGate.reconcile(thresholdPercent)
                            }
                            val draft = sliderGate.displayed
                            ContextSliderItem(
                                icon = Icons.Default.Compress,
                                label = stringResource(R.string.context_compact_threshold),
                                description = stringResource(
                                    R.string.context_compact_threshold_desc,
                                ),
                                displayValue = "${draft.toInt()}%",
                                sliderValue = draft,
                                valueRange = 50f..100f,
                                steps = 0,
                                onValueChange = { sliderGate.updateFromGesture(kotlin.math.round(it)) },
                                onValueChangeFinished = {
                                    val committed = draft.toInt().coerceIn(50, 100)
                                    if (committed != thresholdPercent) {
                                        sliderGate.expectPersisted(
                                            committed,
                                            committed.toFloat(),
                                        )
                                        viewModel.settings
                                            .setContextCompactThresholdPercent(committed)
                                    } else {
                                        sliderGate.settleWithoutWrite(
                                            thresholdPercent,
                                            committed.toFloat(),
                                        )
                                    }
                                },
                            )
                        }
                    }
                    add {
                        val sliderGate = remember {
                            PersistedSliderFeedbackGate(
                                initialPersisted = retainCount,
                                toDisplay = { it.toFloat() },
                            )
                        }
                        LaunchedEffect(retainCount) { sliderGate.reconcile(retainCount) }
                        val draft = sliderGate.displayed
                        ContextSliderItem(
                            icon = Icons.Default.Memory,
                            label = stringResource(R.string.context_compact_retain),
                            description = stringResource(R.string.context_compact_retain_desc),
                            displayValue = draft.toInt().toString(),
                            sliderValue = draft,
                            valueRange = 0f..20f,
                            steps = 19,
                            onValueChange = sliderGate::updateFromGesture,
                            onValueChangeFinished = {
                                val committed = draft.toInt()
                                if (committed != retainCount) {
                                    sliderGate.expectPersisted(committed, committed.toFloat())
                                    viewModel.settings.setContextCompactRetainCount(committed)
                                } else {
                                    sliderGate.settleWithoutWrite(retainCount, committed.toFloat())
                                }
                            },
                        )
                    }
                    add {
                        SettingsItem(
                            headlineContent = {
                                Text(stringResource(R.string.context_compact_preserve_system))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.context_compact_preserve_system_desc))
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Compress,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = preserveSystemPrompt,
                                    onCheckedChange = viewModel.settings::setContextCompactPreserveSystemPrompt,
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings
                                    .setContextCompactPreserveSystemPrompt(!preserveSystemPrompt)
                            },
                        )
                    }
                    add {
                        PromptSettingItem(
                            title = stringResource(R.string.context_compact_prompt),
                            description = stringResource(R.string.context_compact_prompt_desc),
                            prompt = compactPrompt,
                            onClick = { promptDialog = true },
                        )
                    }
                },
            )
        }
        if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }

    if (modelDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { modelDialog = false },
            title = {
                Text(
                    stringResource(R.string.context_compact_select_model),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        CompactModelItem(
                            label = stringResource(R.string.title_gen_current_model),
                            provider = null,
                            selected = compactModel == null,
                            onClick = {
                                viewModel.settings.setContextCompactModel(null)
                                modelDialog = false
                            },
                        )
                    }
                    items(enabledModels.toList(), key = { it }) { model ->
                        val parsed = ModelId.parse(model)
                        CompactModelItem(
                            label = modelAliasDisplayName(
                                model,
                                aliases,
                                customProviders,
                            ),
                            provider = providerDisplayName(parsed.providerName, customProviders),
                            selected = compactModel == model,
                            onClick = {
                                viewModel.settings.setContextCompactModel(model)
                                modelDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { modelDialog = false }) {
                    Text(stringResource(R.string.provider_cancel))
                }
            },
        )
    }
    if (promptDialog) {
        PromptEditDialog(
            title = stringResource(R.string.context_compact_prompt),
            initialPrompt = compactPrompt,
            onDismiss = { promptDialog = false },
            onSave = viewModel.settings::setContextCompactPrompt,
        )
    }
}

@Composable
internal fun CompactModelItem(
    label: String,
    provider: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SettingsItem(
        headlineContent = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        supportingContent = provider?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        },
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
        modifier = Modifier.optionClickable(onClick = onClick),
    )
}

@Composable
private fun ContextSliderItem(
    icon: ImageVector,
    label: String,
    description: String,
    displayValue: String,
    sliderValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = displayValue,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Slider(
                    value = sliderValue,
                    onValueChange = onValueChange,
                    valueRange = valueRange,
                    steps = steps,
                    onValueChangeFinished = onValueChangeFinished,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
    }
}
