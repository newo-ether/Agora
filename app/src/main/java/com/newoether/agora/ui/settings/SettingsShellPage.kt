package com.newoether.agora.ui.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.newoether.agora.R
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.data.isShellDeviceNameAvailable
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.AgoraExposedDropdownMenu
import com.newoether.agora.ui.components.SecretVisibilityToggle
import com.newoether.agora.ui.components.rememberSecretVisible
import com.newoether.agora.ui.components.secretVisualTransformation
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Switch
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.OutlinedButton
import com.newoether.agora.ui.components.TextButton
import com.newoether.agora.ui.components.Button
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.motion.AnimatedVisibility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsShellPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val shellEnabled by viewModel.settings.shellEnabled.collectAsState()
    val shellConfirmEnabled by viewModel.settings.shellConfirmEnabled.collectAsState()
    val shellDevices by viewModel.settings.shellDevices.collectAsState()
    val sandboxEnabled by viewModel.settings.sandboxEnabled.collectAsState()
    val sandboxSharedStorageEnabled by
        viewModel.settings.sandboxSharedStorageEnabled.collectAsState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var newlyAddedDeviceId by remember { mutableStateOf<String?>(null) }
    var newDevice by remember { mutableStateOf<ShellDeviceConfig?>(null) }
    var deleteConfirmDeviceId by remember { mutableStateOf<String?>(null) }
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    // ── Sandbox navigation ──
    var showSandboxMgmt by remember { mutableStateOf(false) }
    var sandboxEntryCount by remember { mutableIntStateOf(0) }
    BackHandler(enabled = showSandboxMgmt) { showSandboxMgmt = false }

    GuardedAnimatedContent(
        targetState = showSandboxMgmt,
        forward = showSandboxMgmt
    ) { isMgmt ->
        if (isMgmt && viewModel.sandboxManager != null) {
            SettingsSecondaryPane {
                key(sandboxEntryCount) {
                    SettingsSandboxPage(
                        sandboxManager = viewModel.sandboxManager!!,
                        onBack = { showSandboxMgmt = false },
                        showDocFab = showDocFab,
                        sharedStorageEnabled = sandboxSharedStorageEnabled,
                        onSharedStorageEnabledChange =
                            viewModel.settings::setSandboxSharedStorageEnabled,
                    )
                }
            }
        } else {
            val scrollState = rememberSettingsScrollState()
            CollapsingSettingsScaffold(
                title = stringResource(R.string.shell_title),
                onBack = onBack,
                scrollState = scrollState,
                floatingActionButton = { if (showDocFab) DocumentationFab("shell.md") }
            ) {
            SettingsGroupColumn {
                SettingsGroup(title = stringResource(R.string.shell_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.shell_enable)) },
                        supportingContent = { Text(stringResource(R.string.shell_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = shellEnabled, onCheckedChange = { viewModel.settings.setShellEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.settings.setShellEnabled(!shellEnabled) }
                    )
                }
                if (shellEnabled) {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.shell_confirm_setting)) },
                            supportingContent = { Text(stringResource(R.string.shell_confirm_setting_desc)) },
                            leadingContent = { Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Switch(checked = shellConfirmEnabled, onCheckedChange = { viewModel.shellConfirmation.setEnabled(it) }) },
                            modifier = Modifier.clickable { viewModel.shellConfirmation.setEnabled(!shellConfirmEnabled) }
                        )
                    }
                }
            })

            if (shellEnabled) {
                // ── Local Sandbox ───────────────────────────
                if (viewModel.isSandboxFlavor) {
                    SandboxSection(viewModel, sandboxEnabled, onManage = { sandboxEntryCount++; showSandboxMgmt = true })
                } else {
                    SandboxNotSupportedSection()
                }

                // ── Remote Devices ──────────────────────────
                SettingsGroup(title = stringResource(R.string.shell_devices), items = buildList {
                    val displayedDevices = shellDevices + listOfNotNull(
                        newDevice?.takeUnless { draft -> shellDevices.any { it.id == draft.id } },
                    )
                    if (displayedDevices.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.shell_no_devices), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                leadingContent = { Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                                modifier = Modifier.heightIn(min = 64.dp)
                            )
                        }
                    } else {
                        displayedDevices.forEach { device ->
                            add {
                                key(device.id) {
                                    DeviceEditor(
                                        viewModel, device, scrollState, density, newlyAddedDeviceId,
                                        existingDevices = shellDevices,
                                        isDraft = device.id == newDevice?.id,
                                        onNewDeviceId = { newlyAddedDeviceId = it },
                                        onSaved = { if (it == newDevice?.id) newDevice = null },
                                        onDeleteConfirm = {
                                            if (it == newDevice?.id) newDevice = null
                                            else deleteConfirmDeviceId = it
                                        },
                                    )
                                }
                            }
                        }
                    }
                    add {
                        SettingsAddItem(
                            label = stringResource(R.string.shell_add_device),
                            enabled = newDevice == null,
                            onClick = {
                                if (newDevice == null) {
                                    val newId = UUID.randomUUID().toString()
                                    newlyAddedDeviceId = newId
                                    newDevice = ShellDeviceConfig(id = newId, name = "")
                                }
                            },
                        )
                    }
                })
            }
            }

            // ── Delete confirm dialog ──
            deleteConfirmDeviceId?.let { deviceId ->
                val device = shellDevices.find { it.id == deviceId }
                AlertDialog(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    onDismissRequest = { deleteConfirmDeviceId = null },
                    title = { Text(stringResource(R.string.shell_delete_confirm_title), fontWeight = FontWeight.Bold) },
                    text = { Text(stringResource(R.string.shell_delete_confirm_message, device?.name?.ifBlank { stringResource(R.string.search_untitled) } ?: "")) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.settings.removeShellDevice(deviceId); deleteConfirmDeviceId = null },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) }
                    },
                    dismissButton = { TextButton(onClick = { deleteConfirmDeviceId = null }) { Text(stringResource(R.string.cancel)) } }
                )
            }

            if (showDocFab) Spacer(Modifier.height(80.dp))
            } // Scaffold content
        } // else
    } // GuardedAnimatedContent
}

// ═══════════════════════════════════════════════════════════════
// Sandbox Section — toggle + manage link, all detail on dedicated page
// ═══════════════════════════════════════════════════════════════

@Composable
private fun SandboxSection(viewModel: ChatViewModel, sandboxEnabled: Boolean, onManage: () -> Unit) {
    SettingsGroup(title = stringResource(R.string.sandbox_title), items = buildList {
        add {
            SettingsItem(
                headlineContent = { Text(stringResource(R.string.sandbox_enable)) },
                supportingContent = { Text(stringResource(R.string.sandbox_enable_desc)) },
                leadingContent = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { Switch(checked = sandboxEnabled, onCheckedChange = { viewModel.settings.setSandboxEnabled(it) }) },
                modifier = Modifier.clickable { viewModel.settings.setSandboxEnabled(!sandboxEnabled) }
            )
        }
        if (sandboxEnabled) {
            add {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.sandbox_manage)) },
                    supportingContent = { Text(stringResource(R.string.sandbox_manage_desc)) },
                    leadingContent = { Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { onManage() }
                )
            }
        }
    })
}

@Composable
private fun SandboxNotSupportedSection() {
    SettingsGroup(title = stringResource(R.string.sandbox_title), items = listOf {
        SettingsItem(
            headlineContent = { Text(stringResource(R.string.sandbox_not_supported)) },
            supportingContent = { Text(stringResource(R.string.sandbox_not_supported_desc)) },
            leadingContent = {
                Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            }
        )
    })
}

// ═══════════════════════════════════════════════════════════════
// Device Editor (extracted for readability)
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceEditor(
    viewModel: ChatViewModel,
    device: ShellDeviceConfig,
    scrollState: androidx.compose.foundation.ScrollState,
    density: androidx.compose.ui.unit.Density,
    newlyAddedDeviceId: String?,
    existingDevices: List<ShellDeviceConfig>,
    isDraft: Boolean,
    onNewDeviceId: (String?) -> Unit,
    onSaved: (String) -> Unit,
    onDeleteConfirm: (String?) -> Unit
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val isNewlyAdded = device.id == newlyAddedDeviceId
    var expanded by remember(device.id) { mutableStateOf(false) }
    var nameInput by remember(device.id) { mutableStateOf(device.name) }
    var saving by remember(device.id) { mutableStateOf(false) }
    var saveRejected by remember(device.id) { mutableStateOf(false) }
    val nameAvailable = isShellDeviceNameAvailable(device.copy(name = nameInput), existingDevices)
    val nameError = saveRejected || (nameInput.isNotBlank() && !nameAvailable)
    var descInput by remember(device.id) { mutableStateOf(device.description) }
    var typeInput by remember(device.id) { mutableStateOf(device.type) }
    var typeMenuExpanded by remember(device.id) { mutableStateOf(false) }
    var urlInput by remember(device.id) { mutableStateOf(device.serverUrl) }
    var keyInput by remember(device.id) { mutableStateOf(device.apiKey) }
    var sshHostInput by remember(device.id) { mutableStateOf(device.sshHost) }
    var sshPortInput by remember(device.id) { mutableStateOf(device.sshPort.toString()) }
    var sshUserInput by remember(device.id) { mutableStateOf(device.sshUser) }
    var sshPwInput by remember(device.id) { mutableStateOf(device.sshPassword) }
    var sshHostKeyInput by remember(device.id) { mutableStateOf(device.sshHostKey) }
    val nameFocusRequester = remember { FocusRequester() }
    val urlFocusRequester = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var verifying by remember(device.id) { mutableStateOf(false) }
    // Captured (key, fingerprint) awaiting the user's trust decision.
    var pendingVerify by remember(device.id) { mutableStateOf<Pair<String, String>?>(null) }
    var verifyError by remember(device.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(device) {
        nameInput = device.name; descInput = device.description; typeInput = device.type
        urlInput = device.serverUrl; keyInput = device.apiKey
        sshHostInput = device.sshHost; sshPortInput = device.sshPort.toString()
        sshUserInput = device.sshUser; sshPwInput = device.sshPassword
        sshHostKeyInput = device.sshHostKey
    }

    LaunchedEffect(isNewlyAdded) {
        if (isNewlyAdded) {
            expanded = true; delay(50); urlFocusRequester.requestFocus()
            val target = scrollState.maxValue + (250 * density.density).toInt()
            if (motionPolicy.allowProgrammaticScrollMotion) {
                scrollState.animateScrollTo(target, tween(500))
            } else {
                scrollState.scrollTo(target)
            }
            onNewDeviceId(null)
        }
    }

    Column {
        SettingsItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name.ifBlank { stringResource(R.string.search_untitled) }, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(4.dp),
                        color = if (typeInput == "ssh") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(if (typeInput == "ssh") "SSH" else "Conch",
                            Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (typeInput == "ssh") MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            },
            supportingContent = {
                if (device.description.isNotBlank()) Text(device.description)
                else if (typeInput == "ssh" && sshHostInput.isNotBlank()) Text("$sshUserInput@$sshHostInput:$sshPortInput")
            },
            leadingContent = { Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, stringResource(R.string.edit))
                }
            },
            modifier = Modifier.clickable { expanded = !expanded }
        )

        AnimatedVisibility(
            visible = expanded,
            enter = if (motionPolicy.allowSpatialTransitions) {
                expandVertically()
            } else {
                fadeIn()
            },
            exit = if (motionPolicy.allowSpatialTransitions) {
                shrinkVertically()
            } else {
                fadeOut()
            },
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Spacer(Modifier.height(8.dp))

                // Type selector
                ExposedDropdownMenuBox(expanded = typeMenuExpanded, onExpandedChange = { typeMenuExpanded = it }) {
                    OutlinedTextField(
                        value = if (typeInput == "ssh") "SSH" else "Conch",
                        onValueChange = {}, readOnly = true,
                        label = { Text(stringResource(R.string.shell_device_type)) },
                        leadingIcon = { Icon(Icons.Default.Cable, null) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenuExpanded) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    AgoraExposedDropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false }
                    ) {
                        AgoraDropdownMenuItem(text = { Text("Conch") }, onClick = { typeInput = "conch"; typeMenuExpanded = false }, leadingIcon = { Icon(Icons.Default.Cable, null) })
                        AgoraDropdownMenuItem(text = { Text("SSH") }, onClick = { typeInput = "ssh"; typeMenuExpanded = false }, leadingIcon = { Icon(Icons.Default.Cable, null) })
                    }
                }
                Spacer(Modifier.height(10.dp))

                // Conditional fields
                var keyVisible by rememberSecretVisible()
                var passwordVisible by rememberSecretVisible()
                if (typeInput == "conch") {
                    OutlinedTextField(value = urlInput, onValueChange = { urlInput = it }, label = { Text(stringResource(R.string.shell_device_url)) },
                        placeholder = { Text(stringResource(R.string.shell_device_url_hint)) }, leadingIcon = { Icon(Icons.Default.Link, null) },
                        singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().focusRequester(urlFocusRequester))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = keyInput, onValueChange = { keyInput = it }, label = { Text(stringResource(R.string.shell_device_key)) },
                        placeholder = { Text(stringResource(R.string.shell_device_key_hint)) }, leadingIcon = { Icon(Icons.Default.Key, null) },
                        visualTransformation = secretVisualTransformation(keyVisible),
                        trailingIcon = { SecretVisibilityToggle(keyVisible) { keyVisible = !keyVisible } },
                        singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(value = sshHostInput, onValueChange = { sshHostInput = it }, label = { Text(stringResource(R.string.shell_device_host)) },
                        placeholder = { Text(stringResource(R.string.shell_device_host_hint)) }, leadingIcon = { Icon(Icons.Default.Dns, null) },
                        singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().focusRequester(urlFocusRequester))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = sshPortInput, onValueChange = { sshPortInput = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.shell_device_port)) }, leadingIcon = { Icon(Icons.Default.SettingsEthernet, null) },
                            singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(0.4f))
                        OutlinedTextField(value = sshUserInput, onValueChange = { sshUserInput = it },
                            label = { Text(stringResource(R.string.shell_device_user)) }, leadingIcon = { Icon(Icons.Default.Person, null) },
                            singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(0.6f))
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = sshPwInput, onValueChange = { sshPwInput = it }, label = { Text(stringResource(R.string.shell_device_password)) },
                        leadingIcon = { Icon(Icons.Default.Password, null) }, visualTransformation = secretVisualTransformation(passwordVisible),
                        trailingIcon = { SecretVisibilityToggle(passwordVisible) { passwordVisible = !passwordVisible } },
                        singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())

                    // ── Host-key pinning (TOFU) ──
                    Spacer(Modifier.height(10.dp))
                    val pinned = sshHostKeyInput.isNotBlank()
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            if (pinned) Icons.Default.VerifiedUser else Icons.Default.GppMaybe,
                            null, modifier = Modifier.size(18.dp),
                            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(if (pinned) R.string.shell_ssh_host_key_pinned else R.string.shell_ssh_host_key_not_pinned),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (pinned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                            )
                            if (pinned) {
                                Text(
                                    com.newoether.agora.util.SshClient.fingerprintSha256(sshHostKeyInput),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (pinned) {
                            TextButton(onClick = { sshHostKeyInput = "" }) { Text(stringResource(R.string.shell_ssh_unpin)) }
                        }
                    }
                    verifyError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            verifyError = null; verifying = true
                            scope.launch {
                                val result = viewModel.sshHostKeyVerifier.verify(
                                    sshHostInput.trim(), sshPortInput.toIntOrNull() ?: 22,
                                    sshUserInput.trim().ifBlank { "root" }, sshPwInput
                                )
                                verifying = false
                                result.fold(
                                    onSuccess = { pendingVerify = it },
                                    onFailure = { verifyError = it.message }
                                )
                            }
                        },
                        enabled = !verifying && sshHostInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (verifying) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.shell_ssh_verifying))
                        } else {
                            Icon(Icons.Default.Shield, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.shell_ssh_verify_host_key))
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = nameInput, onValueChange = { nameInput = it; saveRejected = false }, label = { Text(stringResource(R.string.shell_device_name)) },
                    isError = nameError,
                    supportingText = if (nameError) ({ Text(stringResource(R.string.custom_provider_name_error)) }) else null,
                    placeholder = { Text(stringResource(R.string.shell_device_name_hint)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) },
                    singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().focusRequester(nameFocusRequester))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = descInput, onValueChange = { descInput = it }, label = { Text(stringResource(R.string.shell_device_desc)) },
                    placeholder = { Text(stringResource(R.string.shell_device_desc_hint)) }, leadingIcon = { Icon(Icons.Default.Description, null) },
                    singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onDeleteConfirm(device.id) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.shell_remove_device))
                    }
                    Button(onClick = {
                        saving = true
                        val updated = device.copy(
                            name = nameInput.trim(), description = descInput.trim(), type = typeInput,
                            serverUrl = if (typeInput == "conch") urlInput.trim() else "",
                            apiKey = if (typeInput == "conch") keyInput.trim() else "",
                            sshHost = if (typeInput == "ssh") sshHostInput.trim() else "",
                            sshPort = sshPortInput.toIntOrNull() ?: 22,
                            sshUser = if (typeInput == "ssh") sshUserInput.trim().ifBlank { "root" } else "root",
                            sshPassword = if (typeInput == "ssh") sshPwInput else "",
                            sshHostKey = if (typeInput == "ssh") sshHostKeyInput else ""
                        )
                        val onResult: (Boolean) -> Unit = { saved ->
                            saving = false
                            saveRejected = !saved
                            if (saved) {
                                expanded = false
                                onSaved(device.id)
                            }
                        }
                        if (isDraft) viewModel.settings.addShellDevice(updated, onResult)
                        else viewModel.settings.updateShellDevice(updated, onResult)
                    }, enabled = nameAvailable && !saving,
                        modifier = Modifier.weight(1f)) { Text(stringResource(R.string.save)) }
                }
            }
        }

        // ── Host-key fingerprint confirmation ──
        pendingVerify?.let { (key, fingerprint) ->
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
                            Text(
                                fingerprint,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { sshHostKeyInput = key; pendingVerify = null }) {
                        Text(stringResource(R.string.shell_ssh_trust))
                    }
                },
                dismissButton = { TextButton(onClick = { pendingVerify = null }) { Text(stringResource(R.string.cancel)) } }
            )
        }
    }
}
