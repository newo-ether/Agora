package com.newoether.agora.ui.settings

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.SSH_AUTH_PASSWORD
import com.newoether.agora.data.SSH_AUTH_PRIVATE_KEY
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.util.SshPrivateKeyException
import com.newoether.agora.util.readSshPrivateKey
import com.newoether.agora.util.validateSshPrivateKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SAF results and validation belong only to this active explicit-save draft. */
@Composable
internal fun SettingsSshAuthenticationEditor(
    device: ShellDeviceConfig,
    onChange: (ShellDeviceConfig) -> Unit,
    onImportingChange: (Boolean) -> Unit,
    @StringRes saveError: Int?,
    isCurrent: () -> Boolean,
    enabled: Boolean = true,
) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val latestDevice by rememberUpdatedState(device)
    val latestOnChange by rememberUpdatedState(onChange)
    val latestIsCurrent by rememberUpdatedState(isCurrent)
    var closed by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<Int?>(null) }
    var encrypted by remember { mutableStateOf(false) }
    var requestSequence by remember { mutableIntStateOf(0) }
    var pickerSequence by remember { mutableIntStateOf(-1) }
    fun acceptsResult() = !closed && latestIsCurrent()
    fun canEdit() = acceptsResult() && enabled
    LaunchedEffect(device.sshPrivateKey) {
        val key = device.sshPrivateKey
        val result = if (key.isBlank()) false else withContext(Dispatchers.IO) {
            runCatching { validateSshPrivateKey(key) }.getOrDefault(false)
        }
        if (acceptsResult() && latestDevice.sshPrivateKey == key) encrypted = result
    }
    SideEffect {
        if (!latestIsCurrent()) closed = true
    }
    DisposableEffect(Unit) {
        onDispose { closed = true }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = pickerSequence
        if (acceptsResult() && request == requestSequence && latestDevice.sshAuthMode == SSH_AUTH_PRIVATE_KEY) {
            if (uri == null) {
                importing = false
                onImportingChange(false)
            } else {
                scope.launch {
                    try {
                        val (name, imported) = withContext(Dispatchers.IO) {
                            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use { cursor ->
                                    if (cursor.moveToFirst()) cursor.getString(0)?.take(160) else null
                                }.orEmpty()
                            val key = resolver.openInputStream(uri)?.use(::readSshPrivateKey)
                                ?: error("Private key source is unavailable")
                            name to key
                        }
                        if (acceptsResult() && request == requestSequence && latestDevice.sshAuthMode == SSH_AUTH_PRIVATE_KEY) {
                            latestOnChange(latestDevice.copy(
                                sshPassword = "", sshPrivateKey = imported.first,
                                sshPrivateKeyName = name, sshKeyPassphrase = "",
                            ))
                            encrypted = imported.second
                            importError = null
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (acceptsResult() && request == requestSequence) importError = sshPrivateKeyErrorResource(error)
                    } finally {
                        if (acceptsResult() && request == requestSequence) {
                            importing = false
                            onImportingChange(false)
                        }
                    }
                }
            }
        }
    }
    SettingsGroup(title = stringResource(R.string.shell_authentication), items = buildList {
        add {
            SettingsIconContent(
                Icons.Rounded.Key,
                modifier = Modifier.testTag("shell-ssh-authentication"),
            ) {
                McpLabeledField(
                    label = stringResource(R.string.shell_device_user),
                    modifier = Modifier.testTag("shell-field-user"),
                    value = device.sshUser,
                    onValueChange = { if (canEdit()) onChange(device.copy(sshUser = it)) },
                    placeholder = "root",
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.shell_ssh_auth_method),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                PillTabSwitcher(
                    tabs = listOf(
                        stringResource(R.string.shell_device_password),
                        stringResource(R.string.shell_ssh_auth_private_key),
                    ),
                    selectedIndex = if (device.sshAuthMode == SSH_AUTH_PRIVATE_KEY) 1 else 0,
                    modifier = Modifier.testTag("shell-auth-method"),
                    onSelect = { index ->
                        val mode = if (index == 1) SSH_AUTH_PRIVATE_KEY else SSH_AUTH_PASSWORD
                        if (canEdit() && !importing && mode != device.sshAuthMode) {
                            requestSequence++
                            importError = null
                            encrypted = false
                            onChange(device.copy(
                                sshAuthMode = mode, sshPassword = "", sshPrivateKey = "",
                                sshPrivateKeyName = "", sshKeyPassphrase = "",
                            ))
                        }
                    },
                )
            }
        }
        if (device.sshAuthMode == SSH_AUTH_PRIVATE_KEY) {
            add {
                ModelImportItem(
                    title = stringResource(R.string.shell_ssh_import_private_key),
                    importedFile = if (device.sshPrivateKey.isBlank()) null else ModelImportFileInfo(
                        device.sshPrivateKeyName.ifBlank { stringResource(R.string.shell_ssh_auth_private_key) },
                        device.sshPrivateKey.toByteArray(Charsets.UTF_8).size.toLong(),
                    ),
                    description = stringResource(R.string.shell_ssh_private_key_desc),
                    icon = Icons.Rounded.UploadFile,
                    importing = importing,
                    enabled = canEdit(),
                    onClick = {
                        if (canEdit() && !importing) {
                            requestSequence++
                            pickerSequence = requestSequence
                            importing = true
                            onImportingChange(true)
                            picker.launch(arrayOf("*/*"))
                        }
                    },
                )
            }
            if (encrypted) {
                add {
                    SettingsIconContent(Icons.Rounded.Password) {
                        McpLabeledField(
                            label = stringResource(R.string.shell_ssh_private_key_passphrase),
                            value = device.sshKeyPassphrase,
                            onValueChange = { if (canEdit()) onChange(device.copy(sshKeyPassphrase = it)) },
                            password = true,
                        )
                    }
                }
            }
        } else {
            add {
                SettingsIconContent(Icons.Rounded.Password) {
                    McpLabeledField(
                        label = stringResource(R.string.shell_device_password),
                        value = device.sshPassword,
                        onValueChange = { if (canEdit()) onChange(device.copy(sshPassword = it)) },
                        password = true,
                    )
                }
            }
        }
        (importError ?: saveError)?.let { error ->
            add {
                SettingsIconContent(Icons.Rounded.Key) {
                    Text(stringResource(error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    })
}

@StringRes
internal fun sshPrivateKeyErrorResource(error: Exception): Int = when ((error as? SshPrivateKeyException)?.reason) {
    SshPrivateKeyException.Reason.INVALID -> R.string.shell_ssh_private_key_invalid
    SshPrivateKeyException.Reason.TOO_LARGE -> R.string.shell_ssh_private_key_too_large
    SshPrivateKeyException.Reason.PASSPHRASE -> R.string.shell_ssh_private_key_passphrase_invalid
    null -> R.string.shell_ssh_private_key_read_failed
}
