package com.newoether.agora.ui.settings.datacontrol

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.newoether.agora.R
import com.newoether.agora.ui.settings.CollapsingSettingsScaffold
import com.newoether.agora.ui.settings.DocumentationFab
import com.newoether.agora.ui.settings.PillTabSwitcher
import com.newoether.agora.ui.settings.SettingsGroup
import com.newoether.agora.ui.settings.SettingsGroupColumn
import com.newoether.agora.ui.settings.SettingsItem
import com.newoether.agora.data.DataExporter
import com.newoether.agora.data.DataImporter
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.ui.components.optionClickable
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDataControlPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val conversationCount by viewModel.dataControl.conversationCount.collectAsState()
    val memoryCount by viewModel.dataControl.memoryCount.collectAsState()
    val promptCount by viewModel.dataControl.systemPromptCount.collectAsState()
    val exportProgress by viewModel.importExport.exportProgress.collectAsState()
    val importProgress by viewModel.importExport.importProgress.collectAsState()
    val importPreviewLoading by viewModel.importExport.importPreviewLoading.collectAsState()
    val importManifest by viewModel.importExport.importManifest.collectAsState()
    val importPreview by viewModel.importExport.importPreview.collectAsState()

    val claudeImportPreview by viewModel.importExport.claudeImportPreview.collectAsState()
    val claudeImportProgress by viewModel.importExport.claudeImportProgress.collectAsState()
    val claudeImportResult by viewModel.importExport.claudeImportResult.collectAsState()

    val gptImportPreview by viewModel.importExport.gptImportPreview.collectAsState()
    val gptImportProgress by viewModel.importExport.gptImportProgress.collectAsState()
    val gptImportResult by viewModel.importExport.gptImportResult.collectAsState()
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportPreviewDialog by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var invalidImportMessage by remember { mutableStateOf<String?>(null) }

    var showClaudeImportDialog by remember { mutableStateOf(false) }
    var claudeFileUri by remember { mutableStateOf<Uri?>(null) }
    var claudeFileName by remember { mutableStateOf<String?>(null) }
    var showClaudeSuccessDialog by remember { mutableStateOf(false) }
    var claudeImportStrategy by remember {
        mutableStateOf(DataImporter.ImportStrategy.MERGE)
    }
    var claudeSelectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var showGptImportDialog by remember { mutableStateOf(false) }
    var gptFileUri by remember { mutableStateOf<Uri?>(null) }
    var gptFileName by remember { mutableStateOf<String?>(null) }
    var showGptSuccessDialog by remember { mutableStateOf(false) }
    var gptImportStrategy by remember {
        mutableStateOf(DataImporter.ImportStrategy.MERGE)
    }
    var gptSelectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingExternalReplace by remember { mutableStateOf<Pair<Boolean, Set<String>>?>(null) }

    // Auto Backup
    val autoBackupEnabled by viewModel.settings.autoBackupEnabled.collectAsState()
    val autoBackupPeriodHours by viewModel.settings.autoBackupPeriodHours.collectAsState()
    val autoBackupCategories by viewModel.settings.autoBackupCategories.collectAsState()
    val autoBackupDirectory by viewModel.settings.autoBackupDirectory.collectAsState()
    val autoDeleteEnabled by viewModel.settings.autoDeleteEnabled.collectAsState()
    val autoDeletePeriodHours by viewModel.settings.autoDeletePeriodHours.collectAsState()

    val isExporting = exportProgress != null
    val isImporting = importProgress != null

    LaunchedEffect(Unit) { viewModel.dataControl.refreshCounts() }

    // Capture export selections so they survive the SAF picker flow
    var pendingExportCategories by remember { mutableStateOf<Set<DataExporter.ExportCategory>>(emptySet()) }
    var pendingExportIncludeApiKeys by remember { mutableStateOf(false) }

    // SAF launchers
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && pendingExportCategories.isNotEmpty()) {
            viewModel.importExport.exportData(uri, pendingExportCategories, pendingExportIncludeApiKeys)
            pendingExportCategories = emptySet()
            pendingExportIncludeApiKeys = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importUri = uri
            viewModel.importExport.previewImport(uri)
        }
    }

    // Claude chat file picker launcher
    val claudeChatLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            claudeFileUri = uri
            scope.launch {
                val name = withContext(Dispatchers.IO) {
                    com.newoether.agora.util.FileValidator.resolveFileName(context, uri)
                }
                if (claudeFileUri == uri) claudeFileName = name
            }
            viewModel.importExport.previewClaudeChat(uri)
        }
    }

    // GPT chat file picker launcher
    val gptChatLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            gptFileUri = uri
            scope.launch {
                val name = withContext(Dispatchers.IO) {
                    com.newoether.agora.util.FileValidator.resolveFileName(context, uri)
                }
                if (gptFileUri == uri) gptFileName = name
            }
            viewModel.importExport.previewGptChat(uri)
        }
    }

    // Show import preview dialog when preview is loaded
    LaunchedEffect(importPreview, importPreviewLoading) {
        if (importPreview != null && !importPreviewLoading) {
            showImportPreviewDialog = true
        }
    }

    val isClaudeImporting = claudeImportProgress != null
    val isGptImporting = gptImportProgress != null
    val isNativeProgressVisible = importPreviewLoading || isExporting || isImporting
    val nativeProgressTitle = when {
        importPreviewLoading -> R.string.loading_label
        isExporting -> R.string.exporting_label
        else -> R.string.importing_label
    }
    val isThirdPartyImporting = isClaudeImporting || isGptImporting

    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        CollapsingSettingsScaffold(
            title = stringResource(R.string.settings_data_control),
            onBack = onBack,
            floatingActionButton = { if (showDocFab) DocumentationFab("import-export.md") }
        ) {
                // Import/Export group
                SettingsGroupColumn {
                    SettingsGroup(title = stringResource(R.string.settings_data_control), items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.data_import_title)) },
                            supportingContent = { Text(stringResource(R.string.data_import_subtitle)) },
                            leadingContent = {
                                Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/zip", "*/*")) }
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.data_export_title)) },
                            supportingContent = { Text(stringResource(R.string.data_export_subtitle)) },
                            leadingContent = {
                                Icon(Icons.Default.Upload, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { showExportDialog = true }
                        )
                    }
                ))

                // Third party group
                SettingsGroup(title = stringResource(R.string.third_party_import), items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.gpt_import_title)) },
                            supportingContent = { Text(stringResource(R.string.gpt_import_subtitle)) },
                            leadingContent = {
                                Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { gptChatLauncher.launch(arrayOf("application/zip", "*/*")) }
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.claude_import_title)) },
                            supportingContent = { Text(stringResource(R.string.claude_import_subtitle)) },
                            leadingContent = {
                                Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { claudeChatLauncher.launch(arrayOf("application/zip", "*/*")) }
                        )
                    }
                ))

                // ═══════════════════════════════════════════════
                // Auto Backup group
                // ═══════════════════════════════════════════════
                AutoBackupSection(viewModel)
                }

                // Show Claude import dialog when preview is loaded
                LaunchedEffect(claudeImportPreview) {
                    claudeImportPreview?.let { preview ->
                        claudeSelectedIds = preview.conversations.mapTo(mutableSetOf()) { it.uuid }
                        claudeImportStrategy = DataImporter.ImportStrategy.MERGE
                        pendingExternalReplace = null
                        showClaudeImportDialog = true
                    }
                }

                // Show Claude import success dialog when result is available
                LaunchedEffect(claudeImportResult) {
                    if (claudeImportResult != null) {
                        showClaudeSuccessDialog = true
                    }
                }

                // Show GPT import dialog when preview is loaded
                LaunchedEffect(gptImportPreview) {
                    gptImportPreview?.let { preview ->
                        gptSelectedIds = preview.conversations.mapTo(mutableSetOf()) { it.uuid }
                        gptImportStrategy = DataImporter.ImportStrategy.MERGE
                        pendingExternalReplace = null
                        showGptImportDialog = true
                    }
                }

                // Show GPT import success dialog when result is available
                LaunchedEffect(gptImportResult) {
                    if (gptImportResult != null) {
                        showGptSuccessDialog = true
                    }
                }

                if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
        }

        if (isNativeProgressVisible) {
            NativeDataProgressDialog(title = stringResource(nativeProgressTitle))
        }

        if (isThirdPartyImporting) {
            val progress = claudeImportProgress ?: gptImportProgress ?: 0f
            val label = if (isClaudeImporting) {
                stringResource(R.string.claude_import_progress)
            } else {
                stringResource(R.string.gpt_import_progress)
            }
            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onDismissRequest = { },
                title = { Text(label, fontWeight = FontWeight.Bold) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = { },
            )
        }
    }

    // Export dialog
    if (showExportDialog) {
        ExportDataDialog(
            conversationCount = conversationCount,
            memoryCount = memoryCount,
            promptCount = promptCount,
            onDismiss = { showExportDialog = false },
            onExport = { categories, includeApiKeys ->
                showExportDialog = false
                pendingExportCategories = categories
                pendingExportIncludeApiKeys = includeApiKeys
                val filename = "Agora_export_${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.agora"
                exportLauncher.launch(filename)
            }
        )
    }

    // Invalid import error
    if (invalidImportMessage != null) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { invalidImportMessage = null },
            title = { Text(stringResource(R.string.data_import_title), fontWeight = FontWeight.Bold) },
            text = { Text(invalidImportMessage!!) },
            confirmButton = {
                TextButton(onClick = { invalidImportMessage = null }) {
                    Text(stringResource(R.string.provider_close))
                }
            }
        )
    }

    // Import preview dialog
    if (showImportPreviewDialog && importPreview != null && importManifest != null) {
        ImportPreviewDialog(
            manifest = importManifest!!,
            preview = importPreview!!,
            onDismiss = {
                showImportPreviewDialog = false
                viewModel.importExport.clearImportState()
            },
            onImport = { decisions ->
                showImportPreviewDialog = false
                importUri?.let { viewModel.importExport.importData(it, decisions) }
            }
        )
    }

    // Claude import preview dialog
    if (showClaudeImportDialog && claudeImportPreview != null) {
        val preview = claudeImportPreview!!
        val allIds = preview.conversations.map { it.uuid }.toSet()
        val allSelected = claudeSelectedIds.size == allIds.size
        val selectedConvCount = preview.conversations.count { it.uuid in claudeSelectedIds }
        val selectedMsgCount = preview.conversations
            .filter { it.uuid in claudeSelectedIds }
            .sumOf { it.messageCount }

        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                showClaudeImportDialog = false
                pendingExternalReplace = null
                viewModel.importExport.clearClaudeImportState()
            },
            title = { Text(stringResource(R.string.claude_import_title), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.claude_import_strategy),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    PillTabSwitcher(
                        tabs = listOf(
                            stringResource(R.string.import_strategy_merge),
                            stringResource(R.string.import_strategy_replace),
                        ),
                        selectedIndex = if (
                            claudeImportStrategy == DataImporter.ImportStrategy.MERGE
                        ) 0 else 1,
                        onSelect = { index ->
                            claudeImportStrategy = if (index == 0) {
                                DataImporter.ImportStrategy.MERGE
                            } else {
                                DataImporter.ImportStrategy.REPLACE
                            }
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "$selectedConvCount ${stringResource(R.string.claude_import_conversations)}, $selectedMsgCount ${stringResource(R.string.claude_import_messages)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            claudeSelectedIds = if (allSelected) emptySet() else allIds
                        }) {
                            Text(
                                if (allSelected) stringResource(R.string.deselect_all) else stringResource(R.string.select_all),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    HorizontalDivider()
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(preview.conversations.size) { index ->
                            val conv = preview.conversations[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .optionClickable {
                                        claudeSelectedIds = if (conv.uuid in claudeSelectedIds) {
                                            claudeSelectedIds - conv.uuid
                                        } else {
                                            claudeSelectedIds + conv.uuid
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = conv.uuid in claudeSelectedIds,
                                    onCheckedChange = { checked ->
                                        claudeSelectedIds = if (checked) {
                                            claudeSelectedIds + conv.uuid
                                        } else {
                                            claudeSelectedIds - conv.uuid
                                        }
                                    }
                                )
                                Spacer(Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        conv.title.ifEmpty { "Untitled" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1
                                    )
                                    Text(
                                        "${conv.messageCount} messages",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        val finalIds = claudeSelectedIds
                        showClaudeImportDialog = false
                        if (claudeImportStrategy == DataImporter.ImportStrategy.REPLACE) {
                            pendingExternalReplace = true to finalIds
                        } else {
                            viewModel.importExport.clearClaudeImportState()
                            claudeFileUri?.let { uri ->
                                scope.launch {
                                    viewModel.importExport.importClaudeChat(
                                        uri,
                                        claudeImportStrategy,
                                        finalIds,
                                    )
                                }
                            }
                        }
                    },
                    enabled = claudeSelectedIds.isNotEmpty()
                ) {
                    Text(stringResource(R.string.claude_import_import))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClaudeImportDialog = false
                    pendingExternalReplace = null
                    viewModel.importExport.clearClaudeImportState()
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    pendingExternalReplace?.let { (isClaude, selectedIds) ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                pendingExternalReplace = null
                if (isClaude) showClaudeImportDialog = true else showGptImportDialog = true
            },
            title = {
                Text(
                    stringResource(R.string.external_import_replace_confirm_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = { Text(stringResource(R.string.external_import_replace_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingExternalReplace = null
                        if (isClaude) {
                            viewModel.importExport.clearClaudeImportState()
                            claudeFileUri?.let { uri ->
                                scope.launch {
                                    viewModel.importExport.importClaudeChat(
                                        uri,
                                        DataImporter.ImportStrategy.REPLACE,
                                        selectedIds,
                                    )
                                }
                            }
                        } else {
                            viewModel.importExport.clearGptImportState()
                            gptFileUri?.let { uri ->
                                scope.launch {
                                    viewModel.importExport.importGptChat(
                                        uri,
                                        DataImporter.ImportStrategy.REPLACE,
                                        selectedIds,
                                    )
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.external_import_replace_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingExternalReplace = null
                    if (isClaude) showClaudeImportDialog = true else showGptImportDialog = true
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Claude import success dialog
    if (showClaudeSuccessDialog && claudeImportResult != null) {
        val result = claudeImportResult!!
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                showClaudeSuccessDialog = false
                viewModel.importExport.clearClaudeImportState()
            },
            title = { Text(stringResource(R.string.claude_import_success), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(stringResource(R.string.claude_import_success_detail, result.conversationsImported, result.messagesImported))
                    if (result.errors.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Errors: ${result.errors.joinToString(", ")}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showClaudeSuccessDialog = false
                    viewModel.importExport.clearClaudeImportState()
                }) {
                    Text(stringResource(R.string.provider_close))
                }
            }
        )
    }

    // GPT import preview dialog
    if (showGptImportDialog && gptImportPreview != null) {
        val preview = gptImportPreview!!
        val allIds = preview.conversations.map { it.uuid }.toSet()
        val allSelected = gptSelectedIds.size == allIds.size
        val selectedConvCount = preview.conversations.count { it.uuid in gptSelectedIds }
        val selectedMsgCount = preview.conversations
            .filter { it.uuid in gptSelectedIds }
            .sumOf { it.messageCount }

        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                showGptImportDialog = false
                pendingExternalReplace = null
                viewModel.importExport.clearGptImportState()
            },
            title = { Text(stringResource(R.string.gpt_import_title), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.claude_import_strategy),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    PillTabSwitcher(
                        tabs = listOf(
                            stringResource(R.string.import_strategy_merge),
                            stringResource(R.string.import_strategy_replace),
                        ),
                        selectedIndex = if (
                            gptImportStrategy == DataImporter.ImportStrategy.MERGE
                        ) 0 else 1,
                        onSelect = { index ->
                            gptImportStrategy = if (index == 0) {
                                DataImporter.ImportStrategy.MERGE
                            } else {
                                DataImporter.ImportStrategy.REPLACE
                            }
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "$selectedConvCount ${stringResource(R.string.gpt_import_conversations)}, $selectedMsgCount ${stringResource(R.string.gpt_import_messages)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            gptSelectedIds = if (allSelected) emptySet() else allIds
                        }) {
                            Text(
                                if (allSelected) stringResource(R.string.deselect_all) else stringResource(R.string.select_all),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    HorizontalDivider()
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(preview.conversations.size) { index ->
                            val conv = preview.conversations[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .optionClickable {
                                        gptSelectedIds = if (conv.uuid in gptSelectedIds) {
                                            gptSelectedIds - conv.uuid
                                        } else {
                                            gptSelectedIds + conv.uuid
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = conv.uuid in gptSelectedIds,
                                    onCheckedChange = { checked ->
                                        gptSelectedIds = if (checked) {
                                            gptSelectedIds + conv.uuid
                                        } else {
                                            gptSelectedIds - conv.uuid
                                        }
                                    }
                                )
                                Spacer(Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        conv.title.ifEmpty { "Untitled" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1
                                    )
                                    Text(
                                        "${conv.messageCount} messages",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        val finalIds = gptSelectedIds
                        showGptImportDialog = false
                        if (gptImportStrategy == DataImporter.ImportStrategy.REPLACE) {
                            pendingExternalReplace = false to finalIds
                        } else {
                            viewModel.importExport.clearGptImportState()
                            gptFileUri?.let { uri ->
                                scope.launch {
                                    viewModel.importExport.importGptChat(
                                        uri,
                                        gptImportStrategy,
                                        finalIds,
                                    )
                                }
                            }
                        }
                    },
                    enabled = gptSelectedIds.isNotEmpty()
                ) {
                    Text(stringResource(R.string.gpt_import_import))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showGptImportDialog = false
                    pendingExternalReplace = null
                    viewModel.importExport.clearGptImportState()
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // GPT import success dialog
    if (showGptSuccessDialog && gptImportResult != null) {
        val result = gptImportResult!!
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                showGptSuccessDialog = false
                viewModel.importExport.clearGptImportState()
            },
            title = { Text(stringResource(R.string.gpt_import_success), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(stringResource(R.string.gpt_import_success_detail, result.conversationsImported, result.messagesImported))
                    if (result.errors.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Errors: ${result.errors.joinToString(", ")}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showGptSuccessDialog = false
                    viewModel.importExport.clearGptImportState()
                }) {
                    Text(stringResource(R.string.provider_close))
                }
            }
        )
    }
}
