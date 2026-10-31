package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.SSH_AUTH_PASSWORD
import com.newoether.agora.data.SSH_AUTH_PRIVATE_KEY
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.data.isShellDeviceNameAvailable
import com.newoether.agora.ui.components.rememberUnsavedChangesBack
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import com.newoether.agora.util.SshClient
import com.newoether.agora.util.validateSshPrivateKey
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One explicit-save draft. Its opening snapshot never follows persisted device emissions. */
@Composable
internal fun SettingsShellDeviceEditor(
    initial: ShellDeviceConfig,
    isNew: Boolean,
    viewModel: ChatViewModel,
    existingDevices: List<ShellDeviceConfig>,
    showDocFab: Boolean,
    isCurrent: () -> Boolean,
    onBack: () -> Unit,
) {
    val openingSnapshot = remember {
        if (isNew && initial.sshUser == "root") initial.copy(sshUser = "") else initial
    }
    var draft by remember { mutableStateOf(openingSnapshot) }
    var portInput by remember { mutableStateOf(openingSnapshot.sshPort.toString()) }
    var guideVisible by remember { mutableStateOf(false) }
    var closed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveAttempt by remember { mutableIntStateOf(0) }
    var saveRejected by remember { mutableStateOf(false) }
    var importingKey by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<Int?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var verifyError by remember { mutableStateOf<String?>(null) }
    var verifiedDraft by remember { mutableStateOf<ShellDeviceConfig?>(null) }
    var pendingVerify by remember { mutableStateOf<Pair<String, String>?>(null) }
    var validationJob by remember { mutableStateOf<Job?>(null) }
    var verifyJob by remember { mutableStateOf<Job?>(null) }
    val latestIsCurrent by rememberUpdatedState(isCurrent)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val saveFailureMessage by rememberUpdatedState(stringResource(R.string.img_save_failed))
    val pageActive = LocalSettingsScrollRetentionActive.current

    fun close() {
        closed = true
        validationJob?.cancel()
        verifyJob?.cancel()
    }
    fun ownsEditor() = !closed && latestIsCurrent() && !guideVisible
    fun leave() {
        if (!ownsEditor()) return
        close()
        onBack()
    }
    // Crossfade may reactivate the same composition before disposal. This entry never reopens.
    SideEffect {
        if (!pageActive || !latestIsCurrent()) close()
    }
    DisposableEffect(Unit) {
        onDispose { close() }
    }
    val guardedBack = if (pageActive && ownsEditor()) {
        rememberUnsavedChangesBack(
            hasUnsavedChanges = draft != openingSnapshot || portInput != openingSnapshot.sshPort.toString() || importingKey,
            onBack = ::leave,
        )
    } else ({})
    BackHandler(enabled = pageActive && ownsEditor()) { guardedBack() }

    fun update(next: ShellDeviceConfig) {
        if (!ownsEditor() || saving) return
        if (next == draft) return
        if (next.type != draft.type) importingKey = false
        draft = next
        saveRejected = false
        authError = null
        pendingVerify = null
        verifiedDraft = null
    }
    fun normalizedDraft(): ShellDeviceConfig {
        val ssh = draft.type == "ssh"
        val privateKey = ssh && draft.sshAuthMode == SSH_AUTH_PRIVATE_KEY
        return draft.copy(
            name = draft.name.trim(),
            description = draft.description.trim(),
            serverUrl = if (ssh) "" else draft.serverUrl.trim(),
            apiKey = if (ssh) "" else draft.apiKey.trim(),
            conchPublicKey = if (ssh) "" else draft.conchPublicKey,
            sshHost = if (ssh) draft.sshHost.trim() else "",
            sshPort = portInput.toIntOrNull() ?: 22,
            sshUser = if (ssh) draft.sshUser.trim().ifBlank { "root" } else "root",
            sshAuthMode = if (ssh) draft.sshAuthMode else SSH_AUTH_PASSWORD,
            sshPassword = if (ssh && !privateKey) draft.sshPassword else "",
            sshPrivateKey = if (privateKey) draft.sshPrivateKey else "",
            sshPrivateKeyName = if (privateKey) draft.sshPrivateKeyName else "",
            sshKeyPassphrase = if (privateKey) draft.sshKeyPassphrase else "",
            sshHostKey = if (ssh) draft.sshHostKey else "",
        )
    }
    val nameAvailable = isShellDeviceNameAvailable(draft, existingDevices)
    val nameError = saveRejected || (draft.name.isNotBlank() && !nameAvailable)
    val canSave = ownsEditor() && nameAvailable && !saving && !importingKey && !verifying &&
        (draft.type != "ssh" || draft.sshAuthMode != SSH_AUTH_PRIVATE_KEY || draft.sshPrivateKey.isNotBlank())

    fun saveDraft() {
        val nameIsAvailable = isShellDeviceNameAvailable(draft, existingDevices)
        if (!ownsEditor() || !nameIsAvailable || saving || importingKey || verifying ||
            (draft.type == "ssh" && draft.sshAuthMode == SSH_AUTH_PRIVATE_KEY && draft.sshPrivateKey.isBlank())) return
        saving = true
        authError = null
        val request = normalizedDraft()
        val attempt = ++saveAttempt
        validationJob = scope.launch {
            try {
                if (request.type == "ssh" && request.sshAuthMode == SSH_AUTH_PRIVATE_KEY) {
                    withContext(Dispatchers.IO) {
                        validateSshPrivateKey(request.sshPrivateKey, request.sshKeyPassphrase)
                    }
                }
                if (!ownsEditor() || saveAttempt != attempt) return@launch
                val onResult: (Boolean) -> Unit = { saved ->
                    // Repository completion may outlive both the page and a later edit of this ID.
                    scope.launch {
                        if (ownsEditor() && saveAttempt == attempt && saving) {
                            if (saved) leave()
                            else {
                                saving = false
                                saveRejected = true
                            }
                        }
                    }
                }
                val onError: (Exception) -> Unit = {
                    scope.launch {
                        if (ownsEditor() && saveAttempt == attempt && saving) {
                            saving = false
                            viewModel.emitSnackbar(saveFailureMessage)
                        }
                    }
                }
                if (isNew) viewModel.settings.addShellDevice(request, onResult, onError)
                else viewModel.settings.updateShellDevice(request, onResult, onError)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (ownsEditor() && saveAttempt == attempt) {
                    saving = false
                    authError = sshPrivateKeyErrorResource(error)
                }
            }
        }
    }

    GuardedAnimatedContent(targetState = guideVisible, forward = guideVisible,
        contentKey = { if (it) "conch-guide" else "editor" }) { guide ->
        if (guide) {
            SettingsSecondaryPane {
                SettingsConchGuidePage(onBack = { if (!closed && latestIsCurrent()) guideVisible = false })
            }
        } else {
            val paneActive by rememberUpdatedState(LocalSettingsScrollRetentionActive.current)
            var paneClosed by remember { mutableStateOf(false) }
            SideEffect { if (!paneActive) paneClosed = true }
            val entryOwnsEditor = ::ownsEditor
            val ownsEditor = { !paneClosed && paneActive && entryOwnsEditor() }
            val latestSaveDraft by rememberUpdatedState({ saveDraft() })
            val save = { if (ownsEditor()) latestSaveDraft() }
            val updateDraft = ::update
            val update: (ShellDeviceConfig) -> Unit = { if (ownsEditor()) updateDraft(it) }
            val editorBack = guardedBack
            val guardedBack = { if (ownsEditor()) editorBack() }
    CollapsingSettingsScaffold(
        title = if (isNew) stringResource(R.string.shell_add_device) else initial.name,
        onBack = guardedBack,
        actions = {
            Box(Modifier.size(48.dp).testTag("shell-save-slot"), contentAlignment = Alignment.Center) {
                Crossfade(
                    targetState = saving,
                    modifier = Modifier.fillMaxSize(),
                    animationSpec = tween(250),
                    label = "shell-device-save",
                ) { busy ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (busy) {
                            MotionAwareCircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = save, enabled = canSave && ownsEditor()) {
                                Icon(Icons.Default.Save, stringResource(R.string.save))
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = { if (showDocFab) DocumentationFab("shell.md") },
    ) {
        SettingsGroupColumn {
            if (isNew) SettingsInstallationHelp(
                conch = true,
                enabled = ownsEditor() && !saving && !verifying && !importingKey,
                onClick = {
                    if (ownsEditor() && !saving && !verifying && !importingKey) guideVisible = true
                },
            )
            SettingsGroup(
                title = stringResource(R.string.mcp_connection),
                items = buildList {
                    add {
                        SettingsIconContent(Icons.Default.SwapHoriz) {
                            Text(
                                stringResource(R.string.shell_device_type),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(10.dp))
                            PillTabSwitcher(
                                tabs = listOf("Conch", "SSH"),
                                selectedIndex = if (draft.type == "ssh") 1 else 0,
                                onSelect = { index ->
                                    val type = if (index == 1) "ssh" else "conch"
                                    if (ownsEditor() && !saving && draft.type != type) {
                                        update(ShellDeviceConfig(
                                            id = draft.id, name = draft.name,
                                            description = draft.description, type = type, sshUser = "",
                                        ))
                                        portInput = "22"
                                    }
                                },
                                allowLabelOverflow = true,
                            )
                        }
                    }
                    add {
                        SettingsIconContent(
                            Icons.AutoMirrored.Filled.Label,
                            modifier = Modifier.testTag("shell-device-identity"),
                        ) {
                            McpLabeledField(
                                label = stringResource(R.string.shell_device_name),
                                modifier = Modifier.testTag("shell-field-name"),
                                value = draft.name,
                                onValueChange = { update(draft.copy(name = it)) },
                                isError = nameError,
                                supportingText = if (nameError) stringResource(R.string.custom_provider_name_error) else null,
                                placeholder = stringResource(R.string.shell_device_name_hint),
                            )
                            Spacer(Modifier.height(12.dp))
                            McpLabeledField(
                                label = stringResource(R.string.shell_device_desc),
                                modifier = Modifier.testTag("shell-field-description"),
                                value = draft.description,
                                onValueChange = { update(draft.copy(description = it)) },
                                placeholder = stringResource(R.string.shell_device_desc_hint),
                            )
                        }
                    }
                    if (draft.type == "ssh") {
                        add {
                            SettingsIconContent(Icons.Default.Dns) {
                                BoxWithConstraints(Modifier.fillMaxWidth().testTag("shell-ssh-endpoint")) {
                                    val fontScale = LocalDensity.current.fontScale
                                    val inline = maxWidth >= 264.dp * fontScale
                                    if (inline) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            McpLabeledField(
                                                label = stringResource(R.string.shell_device_host),
                                                modifier = Modifier.weight(1f).testTag("shell-field-host"),
                                                value = draft.sshHost,
                                                onValueChange = { update(draft.copy(sshHost = it)) },
                                                placeholder = stringResource(R.string.shell_device_host_hint),
                                            )
                                            McpLabeledField(
                                                label = stringResource(R.string.shell_device_port),
                                                modifier = Modifier.width(96.dp).testTag("shell-field-port"),
                                                value = portInput,
                                                onValueChange = {
                                                    if (ownsEditor() && !saving) {
                                                        portInput = it.filter(Char::isDigit)
                                                        pendingVerify = null
                                                        verifiedDraft = null
                                                    }
                                                },
                                                keyboardType = KeyboardType.Number,
                                            )
                                        }
                                    } else {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            McpLabeledField(
                                                label = stringResource(R.string.shell_device_host),
                                                modifier = Modifier.fillMaxWidth().testTag("shell-field-host"),
                                                value = draft.sshHost,
                                                onValueChange = { update(draft.copy(sshHost = it)) },
                                                placeholder = stringResource(R.string.shell_device_host_hint),
                                            )
                                            McpLabeledField(
                                                label = stringResource(R.string.shell_device_port),
                                                modifier = Modifier.fillMaxWidth().testTag("shell-field-port"),
                                                value = portInput,
                                                onValueChange = {
                                                    if (ownsEditor() && !saving) {
                                                        portInput = it.filter(Char::isDigit)
                                                        pendingVerify = null
                                                        verifiedDraft = null
                                                    }
                                                },
                                                keyboardType = KeyboardType.Number,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        add {
                            SettingsIconContent(Icons.Default.Link) {
                                McpLabeledField(
                                    label = stringResource(R.string.shell_device_url),
                                    modifier = Modifier.testTag("shell-field-url"),
                                    value = draft.serverUrl,
                                    onValueChange = { update(draft.copy(serverUrl = it)) },
                                    placeholder = stringResource(R.string.shell_device_url_hint),
                                    keyboardType = KeyboardType.Uri,
                                )
                            }
                        }
                    }
                },
            )
            if (draft.type == "ssh") {
                SettingsSshAuthenticationEditor(
                    device = draft,
                    onChange = update,
                    onImportingChange = { if (ownsEditor() && !saving) importingKey = it },
                    saveError = authError,
                    isCurrent = { ownsEditor() && pageActive },
                    enabled = !saving,
                )
                SettingsGroup(
                    title = stringResource(R.string.shell_ssh_host_key),
                    items = listOf {
                        Column {
                            val pinned = draft.sshHostKey.isNotBlank()
                            val fingerprint = remember(draft.sshHostKey) {
                                if (pinned) SshClient.fingerprintSha256(draft.sshHostKey) else ""
                            }
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.shell_ssh_host_key)) },
                                supportingContent = {
                                    Text(
                                        stringResource(if (pinned) R.string.shell_ssh_host_key_pinned else R.string.shell_ssh_host_key_not_pinned),
                                        color = if (pinned) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                    )
                                    if (pinned) Text(fingerprint)
                                },
                                leadingContent = {
                                    Icon(
                                        if (pinned) Icons.Default.VerifiedUser else Icons.Default.GppMaybe,
                                        null,
                                        tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                },
                                trailingContent = if (pinned) ({
                                    IconButton(
                                        onClick = { update(draft.copy(sshHostKey = "")) },
                                        enabled = ownsEditor() && !saving,
                                    ) {
                                        Icon(Icons.Default.Delete, stringResource(R.string.shell_ssh_unpin), tint = MaterialTheme.colorScheme.error)
                                    }
                                }) else null,
                            )
                            verifyError?.let {
                                Text(it, modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                            }
                            OutlinedButton(
                                onClick = {
                                    if (ownsEditor() && !saving && !verifying) {
                                        verifying = true
                                        verifyError = null
                                        val request = normalizedDraft()
                                        verifyJob = scope.launch {
                                            try {
                                                val result = viewModel.sshHostKeyVerifier.verify(request)
                                                if (ownsEditor() && request == normalizedDraft()) {
                                                    result.fold(
                                                        onSuccess = { verifiedDraft = request; pendingVerify = it },
                                                        onFailure = { verifyError = it.message },
                                                    )
                                                }
                                            } finally {
                                                if (ownsEditor()) verifying = false
                                            }
                                        }
                                    }
                                },
                                enabled = ownsEditor() && !saving && !verifying && !importingKey && draft.sshHost.isNotBlank() &&
                                    (draft.sshAuthMode != SSH_AUTH_PRIVATE_KEY || draft.sshPrivateKey.isNotBlank()),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Crossfade(
                                    targetState = verifying,
                                    modifier = Modifier.size(24.dp),
                                    animationSpec = tween(250),
                                    label = "shell-host-key-verify",
                                ) { busy ->
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        if (busy) MotionAwareCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        else Icon(Icons.Default.Shield, null, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.shell_ssh_verify_host_key))
                            }
                        }
                    },
                )
            } else {
                SettingsGroup(title = stringResource(R.string.shell_authentication), items = listOf {
                    SettingsIconContent(Icons.Default.Key) {
                        McpLabeledField(
                            label = stringResource(R.string.shell_device_key),
                            modifier = Modifier.testTag("shell-field-key"),
                            value = draft.apiKey,
                            onValueChange = { update(draft.copy(apiKey = it)) },
                            placeholder = stringResource(R.string.shell_device_key_hint),
                            password = true,
                        )
                    }
                })
            }
        }
        if (showDocFab) Spacer(Modifier.height(80.dp))
    }
        }
    }

    pendingVerify?.let { (key, fingerprint) ->
        if (ownsEditor()) {
            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onDismissRequest = { pendingVerify = null },
                icon = { Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text(stringResource(R.string.shell_ssh_verify_title), fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(stringResource(R.string.shell_ssh_verify_message))
                        Spacer(Modifier.height(12.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(fingerprint, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (ownsEditor() && verifiedDraft == normalizedDraft() && draft.type == "ssh") {
                            update(draft.copy(sshHostKey = key))
                        }
                        pendingVerify = null
                        verifiedDraft = null
                    }) { Text(stringResource(R.string.shell_ssh_trust)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingVerify = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}
