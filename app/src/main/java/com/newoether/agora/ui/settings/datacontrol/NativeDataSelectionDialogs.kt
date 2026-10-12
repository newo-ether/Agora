package com.newoether.agora.ui.settings.datacontrol

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.settings.PillTabSwitcher
import com.newoether.agora.data.DataExporter
import com.newoether.agora.data.DataImporter
import com.newoether.agora.data.NativeBackupFormat
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.TextButton

@Composable
internal fun ExportDataDialog(
    conversationCount: Int,
    memoryCount: Int,
    promptCount: Int,
    onDismiss: () -> Unit,
    onExport: (categories: Set<DataExporter.ExportCategory>, includeApiKeys: Boolean) -> Unit
) {
    var exportConversations by remember { mutableStateOf(true) }
    var exportMemories by remember { mutableStateOf(true) }
    var exportPrompts by remember { mutableStateOf(true) }
    var exportSettings by remember { mutableStateOf(true) }
    var exportApiKeys by remember { mutableStateOf(false) }

    val anyChecked = exportConversations || exportMemories || exportPrompts || exportSettings || exportApiKeys

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.data_export_title), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                CheckRow(exportConversations, { exportConversations = it },
                    "${stringResource(R.string.export_category_conversations)} ($conversationCount)")
                CheckRow(exportMemories, { exportMemories = it },
                    "${stringResource(R.string.export_category_memories)} ($memoryCount)")
                CheckRow(exportPrompts, { exportPrompts = it },
                    "${stringResource(R.string.export_category_system_prompts)} ($promptCount)")
                CheckRow(exportSettings, { exportSettings = it },
                    stringResource(R.string.export_category_settings))
                CheckRow(exportApiKeys, { exportApiKeys = it },
                    stringResource(R.string.export_category_api_keys))
                if (exportApiKeys) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.export_api_keys_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                    val cats = mutableSetOf<DataExporter.ExportCategory>()
                    if (exportConversations) cats.add(DataExporter.ExportCategory.CONVERSATIONS)
                    if (exportMemories) cats.add(DataExporter.ExportCategory.MEMORIES)
                    if (exportPrompts) cats.add(DataExporter.ExportCategory.SYSTEM_PROMPTS)
                    if (exportSettings) cats.add(DataExporter.ExportCategory.SETTINGS)
                    if (exportApiKeys) cats.add(DataExporter.ExportCategory.API_KEYS)
                    onExport(cats, exportApiKeys)
                }, enabled = anyChecked) {
                Text(stringResource(R.string.export_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
internal fun CheckRow(checked: Boolean, onToggle: (Boolean) -> Unit, label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ImportPreviewDialog(
    manifest: DataImporter.ImportManifest,
    preview: DataImporter.ImportPreview,
    onDismiss: () -> Unit,
    onImport: (Map<DataExporter.ExportCategory, DataImporter.ImportStrategy>) -> Unit
) {
    var convStrategy by remember { mutableStateOf(DataImporter.ImportStrategy.MERGE) }
    var memStrategy by remember { mutableStateOf(DataImporter.ImportStrategy.MERGE) }
    var promptStrategy by remember { mutableStateOf(DataImporter.ImportStrategy.MERGE) }
    var settingsStrategy by remember { mutableStateOf(DataImporter.ImportStrategy.MERGE) }
    var keysStrategy by remember { mutableStateOf(DataImporter.ImportStrategy.SKIP) }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_preview_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.import_from, manifest.exportedAt.take(19).replace("T", " "), manifest.appVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!preview.isSupportedVersion) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(
                            R.string.import_unsupported_backup_version,
                            manifest.version,
                            NativeBackupFormat.CURRENT_VERSION,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(24.dp))

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (preview.hasConversationGraph) {
                        StrategyRow(
                            stringResource(
                                R.string.import_conversation_graph_counts,
                                preview.conversationCount,
                                preview.taskCount,
                            ),
                            convStrategy, { convStrategy = it })
                    }
                    if (preview.memoryCount > 0) {
                        StrategyRow(
                            "${stringResource(R.string.export_category_memories)} (${preview.memoryCount})",
                            memStrategy, { memStrategy = it })
                    }
                    if (preview.systemPromptCount > 0) {
                        StrategyRow(
                            "${stringResource(R.string.export_category_system_prompts)} (${preview.systemPromptCount})",
                            promptStrategy, { promptStrategy = it })
                    }
                    if (preview.settingsPresent) {
                        StrategyRow(
                            stringResource(R.string.export_category_settings),
                            settingsStrategy, { settingsStrategy = it })
                    }
                    if (preview.apiKeysPresent) {
                        Column {
                            StrategyRow(
                                stringResource(R.string.export_category_api_keys),
                                keysStrategy, { keysStrategy = it })
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, null, modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.import_api_keys_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                val decisions = mutableMapOf<DataExporter.ExportCategory, DataImporter.ImportStrategy>()
                if (preview.hasConversationGraph) {
                    decisions[DataExporter.ExportCategory.CONVERSATIONS] = convStrategy
                }
                if (preview.memoryCount > 0) decisions[DataExporter.ExportCategory.MEMORIES] = memStrategy
                if (preview.systemPromptCount > 0) decisions[DataExporter.ExportCategory.SYSTEM_PROMPTS] = promptStrategy
                if (preview.settingsPresent) decisions[DataExporter.ExportCategory.SETTINGS] = settingsStrategy
                if (preview.apiKeysPresent) decisions[DataExporter.ExportCategory.API_KEYS] = keysStrategy
                onImport(decisions)
                },
                enabled = preview.isSupportedVersion,
            ) { Text(stringResource(R.string.import_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun StrategyRow(
    label: String,
    strategy: DataImporter.ImportStrategy,
    onSelect: (DataImporter.ImportStrategy) -> Unit
) {
    val strategies = listOf(
        DataImporter.ImportStrategy.MERGE,
        DataImporter.ImportStrategy.REPLACE,
        DataImporter.ImportStrategy.SKIP,
    )
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        PillTabSwitcher(
            tabs = listOf(
                stringResource(R.string.import_strategy_merge),
                stringResource(R.string.import_strategy_replace),
                stringResource(R.string.import_strategy_skip),
            ),
            selectedIndex = strategies.indexOf(strategy),
            onSelect = { onSelect(strategies[it]) },
        )
    }
}
