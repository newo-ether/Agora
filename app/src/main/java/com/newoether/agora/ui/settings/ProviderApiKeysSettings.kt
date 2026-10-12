package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.IconButton

@Composable
internal fun ProviderApiKeysSettings(
    apiKeys: List<ApiKeyEntry>,
    currentName: String,
    activeApiKeyIds: Map<String, String>,
    onActivateKey: (String, String) -> Unit,
    onEditKey: (ApiKeyEntry) -> Unit,
    onDeleteKey: (ApiKeyEntry) -> Unit,
) {
    val providerKeys = apiKeys.filter { it.provider == currentName }
    if (providerKeys.isEmpty()) {
        SettingsGroup(
            title = stringResource(R.string.provider_api_keys),
            items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.provider_no_keys, currentName), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        leadingContent = { Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                        modifier = Modifier.heightIn(min = 64.dp)
                    )
                }
                add {
                    SettingsAddItem(
                        label = stringResource(R.string.provider_add_key),
                        onClick = {
                            onEditKey(ApiKeyEntry(
                                name = "",
                                key = "",
                                provider = currentName,
                            ))
                        },
                    )
                }
            }
        )
    } else {
        SettingsGroup(
            title = stringResource(R.string.provider_api_keys),
            items = buildList {
                providerKeys.forEach { entry ->
                    var showMenu by remember { mutableStateOf(false) }
                    val isCurrentActive = entry.id == activeApiKeyIds[currentName]
                    add {
                        SettingsItem(
                            headlineContent = { Text(entry.name, fontWeight = FontWeight.Medium) },
                            supportingContent = { Text(entry.key.take(4) + "••••••••" + entry.key.takeLast(4)) },
                            leadingContent = { Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) { RadioButton(selected = isCurrentActive, onClick = { onActivateKey(currentName, entry.id) }, modifier = Modifier.size(20.dp)) } },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.MoreVert, stringResource(R.string.options), modifier = Modifier.size(18.dp)) }
                                    AgoraDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 16.dp) {
                                        AgoraDropdownMenuItem(text = { Text(stringResource(R.string.provider_edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; onEditKey(entry) })
                                        AgoraDropdownMenuItem(text = { Text(stringResource(R.string.provider_delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onDeleteKey(entry) })
                                    }
                                }
                            },
                            modifier = Modifier
                                .clickable { onActivateKey(currentName, entry.id) }
                        )
                    }
                }
                add {
                    SettingsAddItem(
                        label = stringResource(R.string.provider_add_key),
                        onClick = {
                            onEditKey(ApiKeyEntry(
                                name = "",
                                key = "",
                                provider = currentName,
                            ))
                        },
                    )
                }
            }
        )
    }
}
