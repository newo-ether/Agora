package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Switch
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMemoryPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val accessSavedMemories by viewModel.settings.accessSavedMemories.collectAsState()
    val accessActiveMemory by viewModel.settings.accessActiveMemory.collectAsState()
    val catalogRevision by viewModel.memoryManager.catalogRevision.collectAsState()
    val activeMemoryRevision by viewModel.memoryManager.activeMemoryRevision.collectAsState()
    val scope = rememberCoroutineScope()
    val unknownError = stringResource(R.string.unknown_error)
    var activeMemoryContent by remember { mutableStateOf("") }
    var memoryFiles by remember { mutableStateOf<List<com.newoether.agora.data.MemoryManager.MemoryFileInfo>>(emptyList()) }
    var memoryLoaded by remember { mutableStateOf(false) }
    var memoryOperationInFlight by remember { mutableStateOf(false) }
    var showFileEditor by remember { mutableStateOf<String?>(null) }
    var fileEditorContent by remember { mutableStateOf("") }
    var fileEditorDesc by remember { mutableStateOf("") }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var newFileContent by remember { mutableStateOf("") }
    var newFileDesc by remember { mutableStateOf("") }
    var showDeleteFileConfirm by remember { mutableStateOf<String?>(null) }

    fun reportMemoryFailure(action: String, error: Throwable) {
        DebugLog.e("SettingsMemory", action, error)
        viewModel.emitSnackbar(error.localizedMessage ?: unknownError)
    }

    LaunchedEffect(catalogRevision, activeMemoryRevision) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                viewModel.memoryManager.getActiveMemory() to viewModel.memoryManager.listFiles()
            }
        }
        loaded.onSuccess { (active, files) ->
            activeMemoryContent = active
            memoryFiles = files
        }.onFailure { error ->
            reportMemoryFailure("Unable to load memories", error)
        }
        memoryLoaded = true
    }
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.memory_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("memory.md") }
    ) {
            SettingsGroupColumn {
                SettingsGroup(
                    title = stringResource(R.string.memory_access_title),
                    items = listOf(
                        {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.memory_access_saved)) },
                                supportingContent = { Text(stringResource(R.string.memory_access_saved_desc)) },
                                leadingContent = { Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = {
                                    Switch(checked = accessSavedMemories, onCheckedChange = { viewModel.settings.setAccessSavedMemories(it) })
                                },
                                modifier = Modifier.clickable { viewModel.settings.setAccessSavedMemories(!accessSavedMemories) }
                            )
                        },
                        {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.memory_access_active)) },
                                supportingContent = { Text(stringResource(R.string.memory_access_active_desc)) },
                                leadingContent = { Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = {
                                    Switch(checked = accessActiveMemory, onCheckedChange = { viewModel.settings.setAccessActiveMemory(it) })
                                },
                                modifier = Modifier.clickable { viewModel.settings.setAccessActiveMemory(!accessActiveMemory) }
                            )
                        }
                    )
                )

                SettingsGroup(
                    title = stringResource(R.string.memory_active_title),
                    items = listOf(
                        {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.memory_active_context)) },
                                supportingContent = {
                                    if (memoryLoaded) {
                                        Text(
                                            if (activeMemoryContent.isBlank()) stringResource(R.string.memory_active_empty)
                                            else activeMemoryContent.take(100) + if (activeMemoryContent.length > 100) "..." else ""
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                },
                                leadingContent = { Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable(
                                    enabled = memoryLoaded && !memoryOperationInFlight,
                                ) {
                                    showFileEditor = "ACTIVE_MEMORY"
                                    fileEditorContent = activeMemoryContent
                                }
                            )
                        }
                    )
                )

                SettingsGroup(
                    title = stringResource(R.string.memory_saved_title),
                items = buildList {
                    if (!memoryLoaded) {
                        add {
                            Box(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    } else if (memoryFiles.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.memory_no_files), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                supportingContent = { Text(stringResource(R.string.memory_create_hint), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                                leadingContent = { Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                                modifier = Modifier.heightIn(min = 64.dp)
                            )
                        }
                    } else {
                        memoryFiles.forEach { file ->
                            add {
                                var showFileMenu by remember { mutableStateOf(false) }
                                val displayName = file.name.removeSuffix(".md")
                                SettingsItem(
                                    headlineContent = { Text(displayName, fontWeight = FontWeight.Medium) },
                                    supportingContent = if (file.description.isNotBlank()) {{ Text(file.description) }} else null,
                                    leadingContent = { Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) },
                                    trailingContent = {
                                        Box {
                                            IconButton(onClick = { showFileMenu = true }) {
                                                Icon(
                                                    Icons.Default.MoreVert,
                                                    stringResource(R.string.menu),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            AgoraDropdownMenu(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                tonalElevation = 16.dp,
                                                expanded = showFileMenu,
                                                onDismissRequest = { showFileMenu = false }
                                            ) {
                                                AgoraDropdownMenuItem(
                                                    text = { Text(stringResource(R.string.provider_edit)) },
                                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                                    onClick = {
                                                        showFileMenu = false
                                                        if (!memoryOperationInFlight) {
                                                            memoryOperationInFlight = true
                                                            scope.launch {
                                                                val loaded = withContext(Dispatchers.IO) {
                                                                    runCatching {
                                                                        viewModel.memoryManager.readFile(file.name) to
                                                                            viewModel.memoryManager.getDescription(file.name)
                                                                    }
                                                                }
                                                                loaded.onSuccess { (content, description) ->
                                                                    fileEditorContent = content
                                                                    fileEditorDesc = description
                                                                    showFileEditor = file.name
                                                                }.onFailure { error ->
                                                                    reportMemoryFailure(
                                                                        "Unable to open memory file",
                                                                        error,
                                                                    )
                                                                }
                                                                memoryOperationInFlight = false
                                                            }
                                                        }
                                                    },
                                                    enabled = !memoryOperationInFlight,
                                                )
                                                AgoraDropdownMenuItem(
                                                    text = { Text(stringResource(R.string.provider_delete), color = MaterialTheme.colorScheme.error) },
                                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                                    onClick = {
                                                        showFileMenu = false
                                                        showDeleteFileConfirm = file.name
                                                    }
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    add {
                        SettingsAddItem(
                            label = stringResource(R.string.memory_add),
                            enabled = memoryLoaded && !memoryOperationInFlight,
                            onClick = { showNewFileDialog = true },
                        )
                    }
                }
            )
            }
            if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }

    // Delete file confirmation
    showDeleteFileConfirm?.let { fileName ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showDeleteFileConfirm = null },
            title = { Text(stringResource(R.string.memory_delete_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.memory_delete_text, fileName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!memoryOperationInFlight) {
                            memoryOperationInFlight = true
                            scope.launch {
                                val deleted = withContext(Dispatchers.IO) {
                                    runCatching {
                                        viewModel.memoryManager.deleteFile(fileName)
                                        viewModel.memoryManager.listFiles()
                                    }
                                }
                                deleted.onSuccess { files ->
                                    memoryFiles = files
                                    showDeleteFileConfirm = null
                                }.onFailure { error ->
                                    reportMemoryFailure("Unable to delete memory file", error)
                                }
                                memoryOperationInFlight = false
                            }
                        }
                    },
                    enabled = !memoryOperationInFlight,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.provider_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteFileConfirm = null }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }

    // File Editor Dialog
    showFileEditor?.let { fileName ->
        val isActiveMemory = fileName == "ACTIVE_MEMORY"
        var editFileName by remember { mutableStateOf(if (isActiveMemory) "" else fileName.removeSuffix(".md")) }
        var editContent by remember { mutableStateOf(fileEditorContent) }
        var editDesc by remember { mutableStateOf(fileEditorDesc) }

        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                showFileEditor = null
                fileEditorContent = ""
                fileEditorDesc = ""
            },
            title = { Text(if (isActiveMemory) stringResource(R.string.memory_edit_active) else stringResource(R.string.memory_edit), fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    if (isActiveMemory) {
                        Text(
                            stringResource(R.string.memory_active_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    } else {
                        OutlinedTextField(
                            value = editFileName,
                            onValueChange = { editFileName = it },
                            label = { Text(stringResource(R.string.memory_title_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    if (!isActiveMemory) {
                        OutlinedTextField(
                            value = editDesc,
                            onValueChange = { editDesc = it },
                            label = { Text(stringResource(R.string.memory_desc_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        label = { Text(stringResource(R.string.memory_content_hint)) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 400.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!memoryOperationInFlight) {
                            val contentSnapshot = editContent
                            val descriptionSnapshot = editDesc
                            val editedName = editFileName.trim()
                            val newName = editedName.takeIf {
                                !isActiveMemory && it != fileName.removeSuffix(".md")
                            }
                            memoryOperationInFlight = true
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    runCatching {
                                        if (isActiveMemory) {
                                            viewModel.memoryManager.updateActiveMemory(contentSnapshot)
                                            viewModel.memoryManager.getActiveMemory() to null
                                        } else {
                                            viewModel.memoryManager.editFile(
                                                name = fileName,
                                                content = contentSnapshot,
                                                newName = newName,
                                                description = descriptionSnapshot,
                                            )
                                            null to viewModel.memoryManager.listFiles()
                                        }
                                    }
                                }
                                saved.onSuccess { (active, files) ->
                                    if (active != null) activeMemoryContent = active
                                    if (files != null) memoryFiles = files
                                    showFileEditor = null
                                    fileEditorContent = ""
                                    fileEditorDesc = ""
                                }.onFailure { error ->
                                    reportMemoryFailure("Unable to save memory", error)
                                }
                                memoryOperationInFlight = false
                            }
                        }
                    },
                    enabled = !memoryOperationInFlight &&
                        (isActiveMemory || editFileName.isNotBlank()),
                ) { Text(stringResource(R.string.provider_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFileEditor = null
                    fileEditorContent = ""
                    fileEditorDesc = ""
                }) { Text(stringResource(R.string.provider_cancel)) }
            }
        )
    }

    // New File Dialog
    if (showNewFileDialog) {
        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showNewFileDialog = false },
            title = { Text(stringResource(R.string.memory_add_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text(stringResource(R.string.memory_title_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newFileDesc,
                        onValueChange = { newFileDesc = it },
                        label = { Text(stringResource(R.string.memory_desc_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newFileContent,
                        onValueChange = { newFileContent = it },
                        label = { Text(stringResource(R.string.memory_content_hint)) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newFileName.isNotBlank() && !memoryOperationInFlight) {
                            val nameSnapshot = newFileName
                            val contentSnapshot = newFileContent
                            val descriptionSnapshot = newFileDesc
                            memoryOperationInFlight = true
                            scope.launch {
                                val created = withContext(Dispatchers.IO) {
                                    runCatching {
                                        viewModel.memoryManager.createFile(
                                            nameSnapshot,
                                            contentSnapshot,
                                            descriptionSnapshot,
                                        )
                                        viewModel.memoryManager.listFiles()
                                    }
                                }
                                created.onSuccess { files ->
                                    memoryFiles = files
                                    showNewFileDialog = false
                                    newFileName = ""
                                    newFileContent = ""
                                    newFileDesc = ""
                                }.onFailure { error ->
                                    reportMemoryFailure("Unable to create memory file", error)
                                }
                                memoryOperationInFlight = false
                            }
                        }
                    },
                    enabled = newFileName.isNotBlank() && !memoryOperationInFlight,
                ) { Text(stringResource(R.string.memory_create)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNewFileDialog = false
                    newFileName = ""
                    newFileContent = ""
                    newFileDesc = ""
                }) { Text(stringResource(R.string.provider_cancel)) }
            }
        )
    }
}
