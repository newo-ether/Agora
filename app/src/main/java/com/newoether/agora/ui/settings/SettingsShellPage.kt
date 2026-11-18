package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.data.repository.setSandboxMirror
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.viewmodel.ChatViewModel

/** A fresh route owns an immutable opening snapshot, independently of persisted list emissions. */
internal class ShellDeviceEditorRoute(val initial: ShellDeviceConfig, val isNew: Boolean)

@Composable
fun SettingsShellPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val shellEnabled by viewModel.settings.shellEnabled.collectAsState()
    val shellConfirmEnabled by viewModel.settings.shellConfirmEnabled.collectAsState()
    val shellDevices by viewModel.settings.shellDevices.collectAsState()
    val sandboxEnabled by viewModel.settings.sandboxEnabled.collectAsState()
    val sandboxSharedStorageEnabled by viewModel.settings.sandboxSharedStorageEnabled.collectAsState()
    val sandboxMirror by viewModel.settings.sandboxMirror.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    var editorRoute by remember { mutableStateOf<ShellDeviceEditorRoute?>(null) }
    var deleteTarget by remember { mutableStateOf<ShellDeviceConfig?>(null) }
    var showSandboxMgmt by remember { mutableStateOf(false) }
    var sandboxEntryCount by remember { mutableIntStateOf(0) }
    val pageActive = LocalSettingsScrollRetentionActive.current
    val latestPageActive by rememberUpdatedState(pageActive)
    fun ownsDeviceList() = latestPageActive && editorRoute == null && !showSandboxMgmt
    SideEffect {
        if (!pageActive) {
            editorRoute = null
            deleteTarget = null
        }
    }
    BackHandler(enabled = pageActive && showSandboxMgmt) { showSandboxMgmt = false }

    GuardedAnimatedContent(targetState = showSandboxMgmt, forward = showSandboxMgmt) { isMgmt ->
        if (isMgmt && viewModel.sandboxManager != null) {
            SettingsSecondaryPane {
                key(sandboxEntryCount) {
                    SettingsSandboxPage(
                        sandboxManager = viewModel.sandboxManager!!,
                        onBack = { showSandboxMgmt = false },
                        showDocFab = showDocFab,
                        sharedStorageEnabled = sandboxSharedStorageEnabled,
                        onSharedStorageEnabledChange = viewModel.settings::setSandboxSharedStorageEnabled,
                        mirror = sandboxMirror,
                        onMirrorChange = { viewModel.settings.setSandboxMirror(it) },
                        onSnackbar = { viewModel.emitSnackbar(it) },
                    )
                }
            }
        } else {
            GuardedAnimatedContent(
                targetState = editorRoute,
                forward = editorRoute != null,
                contentKey = { route -> route?.initial?.id ?: "devices" },
            ) { route ->
                if (route != null) {
                    SettingsSecondaryPane {
                        SettingsShellDeviceEditor(
                            initial = route.initial,
                            isNew = route.isNew,
                            viewModel = viewModel,
                            existingDevices = shellDevices,
                            showDocFab = showDocFab,
                            isCurrent = { pageActive && !showSandboxMgmt && editorRoute === route },
                            onBack = { if (editorRoute === route) editorRoute = null },
                        )
                    }
                } else {
                    CollapsingSettingsScaffold(
                        title = stringResource(R.string.shell_title),
                        onBack = onBack,
                    ) {
                        SettingsGroupColumn {
                            SettingsGroup(
                                title = stringResource(R.string.shell_title),
                                items = buildList {
                                    add {
                                        SettingsItem(
                                            headlineContent = { Text(stringResource(R.string.shell_enable)) },
                                            supportingContent = { Text(stringResource(R.string.shell_enable_desc)) },
                                            leadingContent = {
                                                Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                                            },
                                            trailingContent = {
                                                Switch(shellEnabled, onCheckedChange = viewModel.settings::setShellEnabled)
                                            },
                                            modifier = Modifier.clickable { viewModel.settings.setShellEnabled(!shellEnabled) },
                                        )
                                    }
                                    if (shellEnabled) {
                                        add {
                                            SettingsItem(
                                                headlineContent = { Text(stringResource(R.string.shell_confirm_setting)) },
                                                supportingContent = { Text(stringResource(R.string.shell_confirm_setting_desc)) },
                                                leadingContent = {
                                                    Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary)
                                                },
                                                trailingContent = {
                                                    Switch(shellConfirmEnabled, onCheckedChange = { viewModel.shellConfirmation.setEnabled(it) })
                                                },
                                                modifier = Modifier.clickable {
                                                    viewModel.shellConfirmation.setEnabled(!shellConfirmEnabled)
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                            if (shellEnabled) {
                                if (viewModel.isSandboxFlavor) {
                                    SandboxSection(viewModel, sandboxEnabled) {
                                        sandboxEntryCount++
                                        showSandboxMgmt = true
                                    }
                                } else {
                                    SandboxNotSupportedSection()
                                }
                                SettingsGroup(
                                    title = stringResource(R.string.shell_devices),
                                    items = buildList {
                                        if (shellDevices.isEmpty()) {
                                            add {
                                                SettingsItem(
                                                    headlineContent = {
                                                        Text(stringResource(R.string.shell_no_devices), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    },
                                                    leadingContent = {
                                                        Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    },
                                                    modifier = Modifier.heightIn(min = 64.dp),
                                                )
                                            }
                                        }
                                        shellDevices.forEach { device ->
                                            add {
                                                key(device.id) {
                                                    ShellDeviceRow(
                                                        device = device,
                                                        active = ownsDeviceList(),
                                                        onEdit = {
                                                            if (ownsDeviceList()) editorRoute = ShellDeviceEditorRoute(device, isNew = false)
                                                        },
                                                        onDelete = { if (ownsDeviceList()) deleteTarget = device },
                                                    )
                                                }
                                            }
                                        }
                                        add {
                                            SettingsAddItem(
                                                label = stringResource(R.string.shell_add_device),
                                                onClick = {
                                                    if (ownsDeviceList()) {
                                                        editorRoute = ShellDeviceEditorRoute(
                                                            ShellDeviceConfig(name = ""), isNew = true,
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    deleteTarget?.let { target ->
        if (ownsDeviceList()) {
            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onDismissRequest = { deleteTarget = null },
                title = { Text(stringResource(R.string.shell_delete_confirm_title), fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.shell_delete_confirm_message, target.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (pageActive && deleteTarget === target) {
                                viewModel.settings.removeShellDevice(target.id)
                                deleteTarget = null
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}

@Composable
private fun ShellDeviceRow(
    device: ShellDeviceConfig,
    active: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(device.id) { mutableStateOf(false) }
    SideEffect { if (!active) menuExpanded = false }
    val type = if (device.type == "ssh") "SSH" else "Conch"
    SettingsItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.name.ifBlank { stringResource(R.string.search_untitled) },
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.testTag("shell-device-type:${device.id}"),
                ) {
                    Text(
                        type,
                        Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
        supportingContent = if (device.description.isNotBlank()) ({
            Text(
                device.description,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }) else null,
        leadingContent = { Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Box(Modifier.testTag("shell-device-menu-anchor:${device.id}")) {
                IconButton(
                    onClick = { if (active) menuExpanded = true },
                    enabled = active,
                    modifier = Modifier.testTag("shell-device-menu:${device.id}"),
                ) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.options), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AgoraDropdownMenu(
                    expanded = active && menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.testTag("shell-device-dropdown:${device.id}"),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 16.dp,
                ) {
                    AgoraDropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { if (active) { menuExpanded = false; onEdit() } },
                    )
                    AgoraDropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { if (active) { menuExpanded = false; onDelete() } },
                    )
                }
            }
        },
        modifier = Modifier
            .testTag("shell-device-row:${device.id}")
            .clickable(enabled = active) { menuExpanded = true },
        endPadding = 6.dp,
    )
}

@Composable
private fun SandboxSection(viewModel: ChatViewModel, sandboxEnabled: Boolean, onManage: () -> Unit) {
    SettingsGroup(title = stringResource(R.string.sandbox_title), items = buildList {
        add {
            SettingsItem(
                headlineContent = { Text(stringResource(R.string.sandbox_enable)) },
                supportingContent = { Text(stringResource(R.string.sandbox_enable_desc)) },
                leadingContent = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = {
                    Switch(checked = sandboxEnabled, onCheckedChange = viewModel.settings::setSandboxEnabled)
                },
                modifier = Modifier.clickable { viewModel.settings.setSandboxEnabled(!sandboxEnabled) },
            )
        }
        if (sandboxEnabled) {
            add {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.sandbox_manage)) },
                    supportingContent = { Text(stringResource(R.string.sandbox_manage_desc)) },
                    leadingContent = { Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { onManage() },
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
                Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
        )
    })
}
