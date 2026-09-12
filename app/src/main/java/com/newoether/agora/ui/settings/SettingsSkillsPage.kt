package com.newoether.agora.ui.settings

import android.content.Context
import android.net.Uri
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.parseGitHubSkillUrl
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareModalBottomSheet as ModalBottomSheet
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.FileValidator
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

private const val MAX_SKILL_IMPORT_BYTES = 1_048_576

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSkillsPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val accessSkills by viewModel.settings.accessSkills.collectAsState()
    val catalogRevision by viewModel.skillManager.catalogRevision.collectAsState()
    val showDocumentationFab by viewModel.settings.showDocumentationFab.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val motionPolicy = LocalAgoraMotionPolicy.current
    val addSkillSheetState = rememberModalBottomSheetState()
    val unknownError = stringResource(R.string.unknown_error)

    var skillFiles by remember {
        mutableStateOf<List<SkillManager.SkillFileInfo>>(emptyList())
    }
    var skillsLoaded by remember { mutableStateOf(false) }
    var skillOperationInFlight by remember { mutableStateOf(false) }

    var showFileEditor by remember { mutableStateOf<String?>(null) }
    var fileEditorContent by remember { mutableStateOf("") }
    var fileEditorDescription by remember { mutableStateOf("") }

    var showAddSkillSheet by remember { mutableStateOf(false) }
    var addSkillActionInFlight by remember { mutableStateOf(false) }
    var addSkillActionGeneration by remember { mutableStateOf(0L) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var newFileDescription by remember { mutableStateOf("") }
    var newFileContent by remember { mutableStateOf("") }

    var showDeleteFileConfirm by remember { mutableStateOf<String?>(null) }
    var showGithubDialog by remember { mutableStateOf(false) }
    var githubUrl by remember { mutableStateOf("") }
    var showMarketplaces by remember { mutableStateOf(false) }

    fun reportSkillFailure(action: String, error: Throwable) {
        DebugLog.e("SettingsSkills", action, error)
        viewModel.emitSnackbar(error.localizedMessage ?: unknownError)
    }

    suspend fun loadSkills(): Result<List<SkillManager.SkillFileInfo>> =
        withContext(Dispatchers.IO) {
            runCatching { viewModel.skillManager.listFiles() }
        }

    val markdownPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && !skillOperationInFlight) {
            skillOperationInFlight = true
            scope.launch {
                val imported = withContext(Dispatchers.IO) {
                    runCatching {
                        val (fileName, content) = readSkillMarkdown(context, uri)
                        viewModel.skillManager.createFile(
                            name = fileName,
                            content = content,
                            description = "",
                        )
                        viewModel.skillManager.listFiles()
                    }
                }
                imported.onSuccess { files ->
                    skillFiles = files
                }.onFailure { error ->
                    reportSkillFailure("Unable to import skill file", error)
                }
                skillOperationInFlight = false
            }
        }
    }

    fun runAddSkillAction(action: () -> Unit) {
        if (addSkillActionInFlight || skillOperationInFlight) return
        addSkillActionInFlight = true
        val actionGeneration = ++addSkillActionGeneration
        action()
        if (motionPolicy.allowSpatialTransitions) {
            scope.launch {
                try {
                    addSkillSheetState.hide()
                } finally {
                    if (addSkillActionGeneration == actionGeneration) {
                        showAddSkillSheet = false
                        addSkillActionInFlight = false
                    }
                }
            }
        } else {
            showAddSkillSheet = false
            addSkillActionInFlight = false
        }
    }

    LaunchedEffect(catalogRevision) {
        loadSkills()
            .onSuccess { skillFiles = it }
            .onFailure { error -> reportSkillFailure("Unable to load skills", error) }
        skillsLoaded = true
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.skills_title),
        onBack = onBack,
        floatingActionButton = {
            if (showDocumentationFab) DocumentationFab("skills.md")
        },
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.memory_access_title),
                items = listOf(
                    {
                        SettingsItem(
                            headlineContent = {
                                Text(stringResource(R.string.skills_access))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.skills_access_desc))
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Extension,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = accessSkills,
                                    onCheckedChange = viewModel.settings::setAccessSkills,
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setAccessSkills(!accessSkills)
                            },
                        )
                    },
                ),
            )

            SettingsGroup(
                title = stringResource(R.string.skills_saved_title),
                items = buildList {
                    if (!skillsLoaded) {
                        add {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 64.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    } else if (skillFiles.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = {
                                    Text(
                                        stringResource(R.string.skills_empty),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        stringResource(R.string.skills_create_hint),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.6f,
                                        ),
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Extension,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.4f,
                                        ),
                                    )
                                },
                                modifier = Modifier.heightIn(min = 64.dp),
                            )
                        }
                    } else {
                        skillFiles.forEach { file ->
                            add {
                                var showFileMenu by remember { mutableStateOf(false) }
                                val displayName = file.name.removeSuffix(".md")
                                SettingsItem(
                                    headlineContent = {
                                        Text(
                                            displayName,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    },
                                    supportingContent = if (file.description.isNotBlank()) {
                                        { Text(file.description) }
                                    } else {
                                        null
                                    },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.Extension,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary.copy(
                                                alpha = 0.6f,
                                            ),
                                        )
                                    },
                                    trailingContent = {
                                        Box {
                                            IconButton(
                                                onClick = { showFileMenu = true },
                                                enabled = !skillOperationInFlight,
                                            ) {
                                                Icon(
                                                    Icons.Default.MoreVert,
                                                    stringResource(R.string.menu),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            DropdownMenu(
                                                containerColor =
                                                    MaterialTheme.colorScheme.surfaceContainer,
                                                tonalElevation = 16.dp,
                                                expanded = showFileMenu,
                                                onDismissRequest = { showFileMenu = false },
                                                shape = RoundedCornerShape(12.dp),
                                            ) {
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            stringResource(
                                                                R.string.provider_edit,
                                                            ),
                                                        )
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Edit, null)
                                                    },
                                                    enabled = !skillOperationInFlight,
                                                    onClick = {
                                                        showFileMenu = false
                                                        if (!skillOperationInFlight) {
                                                            skillOperationInFlight = true
                                                            scope.launch {
                                                                val opened =
                                                                    withContext(Dispatchers.IO) {
                                                                        runCatching {
                                                                            viewModel.skillManager
                                                                                .readFile(file.name) to
                                                                                viewModel.skillManager
                                                                                    .getDescription(
                                                                                        file.name,
                                                                                    )
                                                                        }
                                                                    }
                                                                opened.onSuccess {
                                                                        (content, description) ->
                                                                    fileEditorContent = content
                                                                    fileEditorDescription =
                                                                        description
                                                                    showFileEditor = file.name
                                                                }.onFailure { error ->
                                                                    reportSkillFailure(
                                                                        "Unable to open skill file",
                                                                        error,
                                                                    )
                                                                }
                                                                skillOperationInFlight = false
                                                            }
                                                        }
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            stringResource(
                                                                R.string.provider_delete,
                                                            ),
                                                            color =
                                                                MaterialTheme.colorScheme.error,
                                                        )
                                                    },
                                                    leadingIcon = {
                                                        Icon(
                                                            Icons.Default.Delete,
                                                            contentDescription = null,
                                                            tint =
                                                                MaterialTheme.colorScheme.error,
                                                        )
                                                    },
                                                    enabled = !skillOperationInFlight,
                                                    onClick = {
                                                        showFileMenu = false
                                                        showDeleteFileConfirm = file.name
                                                    },
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    add {
                        SettingsAddItem(
                            label = stringResource(R.string.skills_add),
                            enabled = skillsLoaded && !skillOperationInFlight,
                            onClick = {
                                addSkillActionGeneration += 1
                                addSkillActionInFlight = false
                                showAddSkillSheet = true
                            },
                        )
                    }
                },
            )
        }
        if (showDocumentationFab) {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    if (showMarketplaces) {
        SettingsSkillMarketplacePage(
            viewModel = viewModel,
            onBack = { showMarketplaces = false }
        )
        return
    }

    if (showAddSkillSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                addSkillActionGeneration += 1
                addSkillActionInFlight = false
                showAddSkillSheet = false
            },
            sheetState = addSkillSheetState,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            DialogWindowEdgeToEdge()
            Text(
                text = stringResource(R.string.skills_add),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.skills_add_from_markdown),
                        fontWeight = FontWeight.Medium,
                    )
                },
                supportingContent = {
                    Text(
                        stringResource(R.string.skills_add_from_markdown_desc),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !addSkillActionInFlight) {
                        runAddSkillAction {
                            markdownPicker.launch(
                                arrayOf("text/markdown", "text/plain", "application/octet-stream"),
                            )
                        }
                    },
            )
            SettingsItem(
                headlineContent = { Text("Add from GitHub URL", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("Download a skill from a GitHub repository or file", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingContent = { Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.fillMaxWidth().clickable(enabled = !addSkillActionInFlight) {
                    runAddSkillAction { showGithubDialog = true }
                }
            )
            SettingsItem(
                headlineContent = { Text("Browse Marketplaces", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("Discover and install community-created skills", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingContent = { Icon(Icons.Default.Store, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.fillMaxWidth().clickable(enabled = !addSkillActionInFlight) {
                    runAddSkillAction { showMarketplaces = true }
                }
            )
            SettingsItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.skills_add_manually),
                        fontWeight = FontWeight.Medium,
                    )
                },
                supportingContent = {
                    Text(
                        stringResource(R.string.skills_add_manually_desc),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !addSkillActionInFlight) {
                        runAddSkillAction {
                            showNewFileDialog = true
                        }
                    },
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    showDeleteFileConfirm?.let { fileName ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                if (!skillOperationInFlight) showDeleteFileConfirm = null
            },
            title = {
                Text(
                    stringResource(R.string.skills_delete_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(stringResource(R.string.skills_delete_message, fileName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!skillOperationInFlight) {
                            skillOperationInFlight = true
                            scope.launch {
                                val deleted = withContext(Dispatchers.IO) {
                                    runCatching {
                                        viewModel.skillManager.deleteFile(fileName)
                                        viewModel.skillManager.listFiles()
                                    }
                                }
                                deleted.onSuccess { files ->
                                    skillFiles = files
                                    showDeleteFileConfirm = null
                                }.onFailure { error ->
                                    reportSkillFailure(
                                        "Unable to delete skill file",
                                        error,
                                    )
                                }
                                skillOperationInFlight = false
                            }
                        }
                    },
                    enabled = !skillOperationInFlight,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.provider_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteFileConfirm = null },
                    enabled = !skillOperationInFlight,
                ) {
                    Text(stringResource(R.string.provider_cancel))
                }
            },
        )
    }

    showFileEditor?.let { fileName ->
        var editFileName by remember {
            mutableStateOf(fileName.removeSuffix(".md"))
        }
        var editDescription by remember {
            mutableStateOf(fileEditorDescription)
        }
        var editContent by remember {
            mutableStateOf(fileEditorContent)
        }

        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                if (!skillOperationInFlight) {
                    showFileEditor = null
                    fileEditorContent = ""
                    fileEditorDescription = ""
                }
            },
            title = {
                Text(
                    stringResource(R.string.skills_edit),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = editFileName,
                        onValueChange = { editFileName = it },
                        label = { Text(stringResource(R.string.skills_name)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editDescription,
                        onValueChange = { editDescription = it },
                        label = {
                            Text(stringResource(R.string.skills_description))
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        label = { Text(stringResource(R.string.skills_content)) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 400.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!skillOperationInFlight) {
                            val contentSnapshot = editContent
                            val descriptionSnapshot = editDescription
                            val editedName = editFileName.trim()
                            val newName = editedName.takeIf {
                                it != fileName.removeSuffix(".md")
                            }
                            skillOperationInFlight = true
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    runCatching {
                                        viewModel.skillManager.editFile(
                                            name = fileName,
                                            content = contentSnapshot,
                                            newName = newName,
                                            description = descriptionSnapshot,
                                        )
                                        viewModel.skillManager.listFiles()
                                    }
                                }
                                saved.onSuccess { files ->
                                    skillFiles = files
                                    showFileEditor = null
                                    fileEditorContent = ""
                                    fileEditorDescription = ""
                                }.onFailure { error ->
                                    reportSkillFailure(
                                        "Unable to save skill file",
                                        error,
                                    )
                                }
                                skillOperationInFlight = false
                            }
                        }
                    },
                    enabled =
                        !skillOperationInFlight && editFileName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.provider_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showFileEditor = null
                        fileEditorContent = ""
                        fileEditorDescription = ""
                    },
                    enabled = !skillOperationInFlight,
                ) {
                    Text(stringResource(R.string.provider_cancel))
                }
            },
        )
    }

    if (showNewFileDialog) {
        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                if (!skillOperationInFlight) showNewFileDialog = false
            },
            title = {
                Text(
                    stringResource(R.string.skills_add),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text(stringResource(R.string.skills_name)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newFileDescription,
                        onValueChange = { newFileDescription = it },
                        label = {
                            Text(stringResource(R.string.skills_description))
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newFileContent,
                        onValueChange = { newFileContent = it },
                        label = { Text(stringResource(R.string.skills_content)) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newFileName.isNotBlank() && !skillOperationInFlight) {
                            val nameSnapshot = newFileName
                            val descriptionSnapshot = newFileDescription
                            val contentSnapshot = newFileContent
                            skillOperationInFlight = true
                            scope.launch {
                                val created = withContext(Dispatchers.IO) {
                                    runCatching {
                                        viewModel.skillManager.createFile(
                                            name = nameSnapshot,
                                            content = contentSnapshot,
                                            description = descriptionSnapshot,
                                        )
                                        viewModel.skillManager.listFiles()
                                    }
                                }
                                created.onSuccess { files ->
                                    skillFiles = files
                                    showNewFileDialog = false
                                    newFileName = ""
                                    newFileDescription = ""
                                    newFileContent = ""
                                }.onFailure { error ->
                                    reportSkillFailure(
                                        "Unable to create skill file",
                                        error,
                                    )
                                }
                                skillOperationInFlight = false
                            }
                        }
                    },
                    enabled =
                        newFileName.isNotBlank() && !skillOperationInFlight,
                ) {
                    Text(stringResource(R.string.skills_create))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNewFileDialog = false
                        newFileName = ""
                        newFileDescription = ""
                        newFileContent = ""
                    },
                    enabled = !skillOperationInFlight,
                ) {
                    Text(stringResource(R.string.provider_cancel))
                }
            },
        )
    }

    if (showGithubDialog) {
        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                if (!skillOperationInFlight) showGithubDialog = false
            },
            title = {
                Text(
                    "Add from GitHub",
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = githubUrl,
                        onValueChange = { githubUrl = it },
                        label = { Text("GitHub URL") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (githubUrl.isNotBlank() && !skillOperationInFlight) {
                            val parsed = parseGitHubSkillUrl(githubUrl)
                            if (parsed == null) {
                                reportSkillFailure("Unable to install skill from GitHub", IllegalArgumentException("Invalid GitHub URL"))
                            } else {
                                skillOperationInFlight = true
                                scope.launch {
                                    val installed = withContext(Dispatchers.IO) {
                                        runCatching {
                                            viewModel.skillManager.installFromGitHub(parsed.owner, parsed.repo, parsed.ref, parsed.path)
                                        }
                                    }
                                    installed.onSuccess {
                                        showGithubDialog = false
                                        githubUrl = ""
                                    }.onFailure { error ->
                                        reportSkillFailure("Unable to install skill from GitHub", error)
                                    }
                                    skillOperationInFlight = false
                                }
                            }
                        }
                    },
                    enabled = githubUrl.isNotBlank() && !skillOperationInFlight,
                ) {
                    Text("Install")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showGithubDialog = false
                        githubUrl = ""
                    },
                    enabled = !skillOperationInFlight,
                ) {
                    Text(stringResource(R.string.provider_cancel))
                }
            },
        )
    }
}

private fun readSkillMarkdown(
    context: Context,
    uri: Uri,
): Pair<String, String> {
    val reportedSize = FileValidator.resolveFileSize(context, uri)
    require(reportedSize == null || reportedSize <= MAX_SKILL_IMPORT_BYTES) {
        "Skill file must be 1 MB or smaller"
    }
    val sourceFileName = FileValidator.resolveFileName(context, uri)
        ?.takeIf(String::isNotBlank)
        ?: "skill-${System.currentTimeMillis()}.md"
    require(sourceFileName.endsWith(".md", ignoreCase = true)) {
        "Select a Markdown (.md) file"
    }
    val fileName = sourceFileName.dropLast(3) + ".md"
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream(
            minOf(reportedSize?.toInt() ?: 8_192, MAX_SKILL_IMPORT_BYTES),
        )
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_SKILL_IMPORT_BYTES) {
                "Skill file must be 1 MB or smaller"
            }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: error("Unable to open skill file")
    val content = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    return fileName to content
}
