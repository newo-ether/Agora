package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.data.McpTransportType
import com.newoether.agora.mcp.McpConnectionStatus
import com.newoether.agora.mcp.McpServerSnapshot
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import java.util.UUID
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.SecretVisibilityToggle
import com.newoether.agora.ui.components.rememberSecretVisible
import com.newoether.agora.ui.components.secretVisualTransformation
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.Switch
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton

private data class McpEditorRoute(
    val initial: McpServerConfig,
    val isNew: Boolean,
)

private data class McpHeaderDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val value: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMcpPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val servers by viewModel.settings.mcpServers.collectAsState()
    val snapshots by viewModel.mcpServerSnapshots.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    var editorRoute by remember { mutableStateOf<McpEditorRoute?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshMcpServersOnPageEntry()
    }

    BackHandler(enabled = editorRoute != null) {
        editorRoute = null
    }

    GuardedAnimatedContent(
        targetState = editorRoute,
        forward = editorRoute != null,
    ) { route ->
        if (route != null) {
            SettingsSecondaryPane {
                val target = route.initial
                McpServerEditor(
                    initial = target,
                    snapshot = snapshots[target.id],
                    isNew = route.isNew,
                    onBack = { editorRoute = null },
                    onSave = { saved ->
                        if (route.isNew) {
                            viewModel.settings.addMcpServer(saved)
                        } else {
                            viewModel.settings.updateMcpServer(saved)
                        }
                        editorRoute = null
                    },
                    onRefresh = { viewModel.refreshMcpServer(target.id) },
                    showDocFab = showDocFab,
                )
            }
        } else {
            val scrollState = rememberScrollState()
            CollapsingSettingsScaffold(
                title = stringResource(R.string.mcp_title),
                onBack = onBack,
                scrollState = scrollState,
            ) {
                SettingsGroupColumn {
                    SettingsGroup(
                        title = stringResource(R.string.mcp_servers),
                        items = buildList {
                            if (servers.isEmpty()) {
                                add {
                                    SettingsItem(
                                        headlineContent = {
                                            Text(
                                                stringResource(R.string.mcp_no_servers),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        supportingContent = {
                                            Text(stringResource(R.string.mcp_no_servers_desc))
                                        },
                                        leadingContent = {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_mcp),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                    )
                                }
                            } else {
                                servers.forEach { server ->
                                    add {
                                        val snapshot = snapshots[server.id]
                                        var menuExpanded by remember(server.id) {
                                            mutableStateOf(false)
                                        }
                                        SettingsItem(
                                            headlineContent = {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = server.name.ifBlank { server.url },
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f, fill = false),
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    McpStatusDot(
                                                        status = if (server.enabled) {
                                                            snapshot?.status ?: McpConnectionStatus.IDLE
                                                        } else {
                                                            McpConnectionStatus.IDLE
                                                        },
                                                    )
                                                }
                                            },
                                            supportingContent = {
                                                Text(
                                                    stringResource(
                                                        R.string.mcp_tools_enabled,
                                                        snapshot?.tools?.count { it.enabled } ?: 0,
                                                    ),
                                                )
                                            },
                                            leadingContent = {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_mcp),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
                                            trailingContent = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Switch(
                                                        checked = server.enabled,
                                                        onCheckedChange = {
                                                            viewModel.settings.updateMcpServer(
                                                                server.copy(enabled = it),
                                                            )
                                                        },
                                                        modifier = Modifier.padding(end = 2.dp),
                                                    )
                                                    Box {
                                                        IconButton(
                                                            onClick = { menuExpanded = true },
                                                        ) {
                                                            Icon(
                                                                Icons.Default.MoreVert,
                                                                stringResource(R.string.options),
                                                            )
                                                        }
                                                        AgoraDropdownMenu(
                                                            expanded = menuExpanded,
                                                            onDismissRequest = {
                                                                menuExpanded = false
                                                            },
                                                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                            tonalElevation = 16.dp,
                                                        ) {
                                                            AgoraDropdownMenuItem(
                                                                text = {
                                                                    Text(stringResource(R.string.mcp_refresh))
                                                                },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        Icons.Default.Refresh,
                                                                        null,
                                                                    )
                                                                },
                                                                onClick = {
                                                                    menuExpanded = false
                                                                    viewModel.refreshMcpServer(server.id)
                                                                },
                                                            )
                                                            AgoraDropdownMenuItem(
                                                                text = {
                                                                    Text(
                                                                        stringResource(R.string.delete),
                                                                        color = MaterialTheme.colorScheme.error,
                                                                    )
                                                                },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        Icons.Default.Delete,
                                                                        null,
                                                                        tint = MaterialTheme.colorScheme.error,
                                                                    )
                                                                },
                                                                onClick = {
                                                                    menuExpanded = false
                                                                    deleteId = server.id
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier.clickable {
                                                editorRoute = McpEditorRoute(
                                                    initial = server,
                                                    isNew = false,
                                                )
                                            },
                                            endPadding = 6.dp,
                                        )
                                    }
                                }
                            }
                            add {
                                SettingsAddItem(
                                    label = stringResource(R.string.mcp_add_server),
                                    onClick = {
                                        editorRoute = McpEditorRoute(
                                            initial = McpServerConfig(),
                                            isNew = true,
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    deleteId?.let { id ->
        val server = servers.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = {
                Text(
                    text = stringResource(R.string.mcp_delete_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    stringResource(
                        R.string.mcp_delete_message,
                        server?.name?.ifBlank { server.url }.orEmpty(),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.settings.removeMcpServer(id)
                        deleteId = null
                    },
                ) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerEditor(
    initial: McpServerConfig,
    snapshot: McpServerSnapshot?,
    isNew: Boolean,
    onBack: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
    onRefresh: () -> Unit,
    showDocFab: Boolean,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var headerRows by remember(initial.id) {
        mutableStateOf(
            initial.headers.map { (name, value) ->
                McpHeaderDraft(name = name, value = value)
            },
        )
    }
    val parsedHeaders = remember(headerRows) { buildMcpHeaders(headerRows) }
    val validUrl = remember(draft.url) { isValidMcpUrl(draft.url) }
    val canSave = draft.name.isNotBlank() && validUrl
    val scrollState = rememberScrollState()
    fun save() {
        if (!canSave) return
        onSave(
            draft.copy(
                name = draft.name.trim(),
                url = draft.url.trim(),
                headers = parsedHeaders,
            ),
        )
    }
    fun updateHeader(updated: McpHeaderDraft) {
        headerRows = headerRows.map { current ->
            if (current.id == updated.id) updated else current
        }
    }

    CollapsingSettingsScaffold(
        title = stringResource(if (isNew) R.string.mcp_add_server else R.string.mcp_edit_server),
        onBack = onBack,
        scrollState = scrollState,
        floatingActionButton = { if (showDocFab) DocumentationFab("mcp.md") },
        actions = {
            IconButton(
                onClick = ::save,
                enabled = canSave,
            ) {
                Icon(Icons.Default.Save, stringResource(R.string.save))
            }
        },
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.mcp_connection),
                items = listOf(
                    {
                        SettingsIconContent(icon = Icons.Default.SwapHoriz) {
                            Text(
                                stringResource(R.string.mcp_transport),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(10.dp))
                            val transports = McpTransportType.entries
                            PillTabSwitcher(
                                tabs = listOf(
                                    stringResource(R.string.mcp_transport_streamable_http),
                                    stringResource(R.string.mcp_transport_sse),
                                ),
                                selectedIndex = transports.indexOf(draft.transport).coerceAtLeast(0),
                                onSelect = { index ->
                                    transports.getOrNull(index)?.let { selected ->
                                        draft = draft.copy(transport = selected)
                                    }
                                },
                                allowLabelOverflow = true,
                            )
                        }
                    },
                    {
                        SettingsIconContent(icon = Icons.Default.Label) {
                            McpLabeledField(
                                label = stringResource(R.string.mcp_name),
                                value = draft.name,
                                onValueChange = { draft = draft.copy(name = it) },
                            )
                        }
                    },
                    {
                        SettingsIconContent(icon = Icons.Default.Link) {
                            McpLabeledField(
                                label = stringResource(R.string.mcp_url),
                                value = draft.url,
                                onValueChange = { draft = draft.copy(url = it) },
                                isError = draft.url.isNotBlank() && !validUrl,
                                supportingText = if (draft.url.isNotBlank() && !validUrl) {
                                    stringResource(R.string.mcp_url_error)
                                } else {
                                    null
                                },
                                keyboardType = KeyboardType.Uri,
                            )
                        }
                    },
                ),
            )
            SettingsGroup(
                title = stringResource(R.string.mcp_headers),
                items = buildList {
                    if (headerRows.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = {
                                    Text(
                                        stringResource(R.string.mcp_no_headers),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                supportingContent = {
                                    Text(stringResource(R.string.mcp_headers_desc))
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Code,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    } else {
                        headerRows.forEach { header ->
                            add {
                                key(header.id) {
                                    McpHeaderItem(
                                        header = header,
                                        onHeaderChange = ::updateHeader,
                                        onDelete = {
                                            headerRows = headerRows.filterNot {
                                                it.id == header.id
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    add {
                        SettingsAddItem(
                            label = stringResource(R.string.mcp_add_header),
                            onClick = {
                                headerRows = headerRows + McpHeaderDraft()
                            },
                        )
                    }
                },
            )
            if (!isNew) {
                SettingsGroup(
                    title = stringResource(R.string.mcp_status),
                    items = listOf {
                        SettingsItem(
                            headlineContent = {
                                McpStatusText(
                                    snapshot = snapshot,
                                    includeError = true,
                                )
                            },
                            leadingContent = {
                                McpStatusIcon(snapshot?.status ?: McpConnectionStatus.IDLE)
                            },
                            trailingContent = {
                                IconButton(onClick = onRefresh) {
                                    Icon(Icons.Default.Refresh, stringResource(R.string.mcp_refresh))
                                }
                            },
                        )
                    },
                )
            }
            if (snapshot?.tools?.isNotEmpty() == true) {
                SettingsGroup(
                    title = stringResource(R.string.mcp_tools_count, snapshot.tools.size),
                    items = snapshot.tools.sortedBy { it.remote.name }.map { tool ->
                        {
                            val enabled = tool.remote.name !in draft.disabledTools
                            fun setEnabled(checked: Boolean) {
                                draft = draft.copy(
                                    disabledTools = if (checked) {
                                        draft.disabledTools - tool.remote.name
                                    } else {
                                        draft.disabledTools + tool.remote.name
                                    },
                                )
                            }
                            SettingsItem(
                                headlineContent = { Text(tool.remote.name) },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Build,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = enabled,
                                        onCheckedChange = ::setEnabled,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    setEnabled(!enabled)
                                },
                            )
                        }
                    },
                )
            }
        }
        if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun McpHeaderItem(
    header: McpHeaderDraft,
    onHeaderChange: (McpHeaderDraft) -> Unit,
    onDelete: () -> Unit,
) {
    SettingsItem(
        headlineContent = {
            Column(modifier = Modifier.fillMaxWidth()) {
                McpHeaderField(
                    label = stringResource(R.string.mcp_header_name),
                    value = header.name,
                    onValueChange = { onHeaderChange(header.copy(name = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                McpHeaderField(
                    label = stringResource(R.string.mcp_header_value),
                    value = header.value,
                    onValueChange = { onHeaderChange(header.copy(value = it)) },
                    password = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    stringResource(R.string.mcp_delete_header),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

@Composable
private fun McpHeaderField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    var secretVisible by rememberSecretVisible()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.noOpBringIntoView(),
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        visualTransformation = if (password) secretVisualTransformation(secretVisible) else VisualTransformation.None,
        trailingIcon = if (password) {
            { SecretVisibilityToggle(secretVisible) { secretVisible = !secretVisible } }
        } else {
            trailingContent
        },
        shape = RoundedCornerShape(16.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
internal fun McpLabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    placeholder: String? = null,
) {
    var secretVisible by rememberSecretVisible()
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = isError,
                supportingText = supportingText?.let { text -> { Text(text) } },
                placeholder = placeholder?.let { text -> {
                    Text(text)
                } },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (password) secretVisualTransformation(secretVisible) else VisualTransformation.None,
                trailingIcon = if (password) {
                    { SecretVisibilityToggle(secretVisible) { secretVisible = !secretVisible } }
                } else {
                    trailingContent
                },
                shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

private fun buildMcpHeaders(headers: List<McpHeaderDraft>): Map<String, String> {
    return buildMap {
        headers
            .filterNot { it.name.isBlank() && it.value.isBlank() }
            .forEach { header ->
                put(header.name.trim(), header.value.trim())
            }
    }
}

private fun isValidMcpUrl(value: String): Boolean {
    val uri = runCatching { java.net.URI(value.trim()) }.getOrNull() ?: return false
    return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
        uri.host != null &&
        uri.userInfo == null &&
        uri.fragment == null
}
