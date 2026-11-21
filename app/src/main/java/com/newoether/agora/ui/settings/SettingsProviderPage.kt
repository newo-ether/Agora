package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.BUILT_IN_REMOTE_PROVIDERS
import com.newoether.agora.data.CustomProviderNamePolicy
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.components.displayName
import com.newoether.agora.ui.components.providerIcon
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ChatViewModel

/** 0 = no add flow, 1 = Provider picker, 2 = custom Provider form. */
private const val ADD_NONE = 0
private const val ADD_PICKER = 1
private const val ADD_CUSTOM = 2

@Composable
fun SettingsProviderPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val providerList by viewModel.settings.providerList.collectAsState()

    var selectedProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var addStage by rememberSaveable { mutableIntStateOf(ADD_NONE) }
    val scrollState = rememberSettingsScrollState()

    // The picker, the custom form and the remote editor register their own back handlers later,
    // so they take precedence; this one covers the root list and the Local page.
    BackHandler(enabled = addStage == ADD_NONE) {
        if (selectedProvider != null) selectedProvider = null else onBack()
    }

    val depth = when {
        addStage != ADD_NONE -> addStage
        selectedProvider != null -> 1
        else -> 0
    }
    var previousDepth by remember { mutableIntStateOf(depth) }
    val forward = depth >= previousDepth
    SideEffect { previousDepth = depth }

    Box(modifier = Modifier.fillMaxSize()) {
        GuardedAnimatedContent(
            targetState = Triple(selectedProvider, addStage, depth),
            forward = forward,
        ) { (provider, stage, _) ->
            when {
                stage == ADD_CUSTOM -> SettingsSecondaryPane {
                    AddCustomProviderPage(
                        viewModel = viewModel,
                        onBack = { addStage = ADD_PICKER },
                        onSaved = { addStage = ADD_NONE },
                    )
                }
                stage == ADD_PICKER -> SettingsSecondaryPane {
                    val apiKeys by viewModel.settings.apiKeys.collectAsState()
                    val providerBaseUrls by viewModel.settings.providerBaseUrls.collectAsState()
                    ProviderAddPage(
                        listed = providerList,
                        apiKeys = apiKeys,
                        baseUrls = providerBaseUrls,
                        onBack = { addStage = ADD_NONE },
                        onAddCustom = { addStage = ADD_CUSTOM },
                        onPickBuiltIn = { name ->
                            viewModel.settings.addBuiltInProvider(name)
                            selectedProvider = name
                            addStage = ADD_NONE
                        },
                    )
                }
                provider != null -> SettingsSecondaryPane {
                    SettingsProviderDetailPage(
                        providerName = provider,
                        viewModel = viewModel,
                        onBack = { selectedProvider = null },
                    )
                }
                else -> ProviderListPage(
                    viewModel = viewModel,
                    providerList = providerList,
                    scrollState = scrollState,
                    onBack = onBack,
                    onOpen = { selectedProvider = it },
                    onAdd = { addStage = ADD_PICKER },
                )
            }
        }
    }
}

internal data class ProviderStatus(val configured: Boolean, val summary: String)

/**
 * Configuration summary shared by the Provider list and the add picker. Custom Providers and
 * Ollama are configured by their Base URL; other built-ins by their API keys.
 */
@Composable
internal fun providerStatus(
    name: String,
    isUrlConfigured: Boolean,
    apiKeys: List<ApiKeyEntry>,
    baseUrls: Map<String, String>,
): ProviderStatus {
    val notConfigured = stringResource(R.string.not_configured)
    if (isUrlConfigured || name == Constants.PROVIDER_OLLAMA) {
        val url = baseUrls[name]?.takeIf(String::isNotBlank)
        return ProviderStatus(url != null, url ?: notConfigured)
    }
    val keyCount = apiKeys.count { it.provider == name }
    return if (keyCount > 0) {
        ProviderStatus(true, stringResource(R.string.provider_keys_summary, keyCount))
    } else {
        ProviderStatus(false, notConfigured)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProviderListPage(
    viewModel: ChatViewModel,
    providerList: List<String>,
    scrollState: androidx.compose.foundation.ScrollState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val apiKeys by viewModel.settings.apiKeys.collectAsState()
    val providerBaseUrls by viewModel.settings.providerBaseUrls.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val localChatModels by viewModel.settings.localChatModels.collectAsState()

    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var removeTarget by remember { mutableStateOf<String?>(null) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_provider),
        onBack = onBack,
        scrollState = scrollState,
    ) {
        SettingsGroupColumn {
            val addItem: @Composable () -> Unit = {
                SettingsAddItem(label = stringResource(R.string.custom_provider_add_title), onClick = onAdd)
            }
            if (providerList.isEmpty()) {
                SettingsGroup(
                    title = stringResource(R.string.settings_provider),
                    items = listOf(
                        {
                            SettingsItem(
                                headlineContent = {
                                    Text(stringResource(R.string.provider_list_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                leadingContent = {
                                    Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(24.dp))
                                },
                                modifier = Modifier.heightIn(min = 64.dp),
                            )
                        },
                        addItem,
                    ),
                )
            } else {
                ReorderableSettingsGroup(
                    title = stringResource(R.string.settings_provider),
                    items = providerList,
                    keyOf = { it },
                    onMove = { viewModel.settings.reorderProviders(it) },
                    footer = listOf(addItem),
                ) { name, dragHandle ->
                    val custom = customProviders.firstOrNull { it.name == name }
                    val isBuiltIn = name in BUILT_IN_REMOTE_PROVIDERS
                    val status = providerStatus(name, isUrlConfigured = custom != null, apiKeys, providerBaseUrls)
                    val configured = status.configured
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        SettingsItem(
                            headlineContent = { Text(name) },
                            supportingContent = { Text(status.summary) },
                            leadingContent = {
                                val tint = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                val iconRes = if (isBuiltIn) providerIcon(name) else 0
                                if (iconRes != 0) Icon(painterResource(iconRes), null, tint = tint, modifier = Modifier.size(24.dp))
                                else Icon(Icons.Default.Cloud, null, tint = tint, modifier = Modifier.size(24.dp))
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (custom != null) {
                                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                            Text(custom.protocol.displayName(), modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        }
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    Icon(
                                        Icons.Default.DragHandle,
                                        contentDescription = stringResource(R.string.provider_reorder),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = dragHandle.size(24.dp),
                                    )
                                }
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = { onOpen(name) },
                                onLongClick = { menuExpanded = true },
                            ),
                        )
                        AgoraDropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 16.dp,
                        ) {
                            if (custom != null) {
                                AgoraDropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { menuExpanded = false; renameTarget = name },
                                )
                                AgoraDropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menuExpanded = false; deleteTarget = name },
                                )
                            } else {
                                AgoraDropdownMenuItem(
                                    text = { Text(stringResource(R.string.provider_remove), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menuExpanded = false; removeTarget = name },
                                )
                            }
                        }
                    }
                }
            }

            val localConfigured = localChatModels.isNotEmpty()
            SettingsGroup(title = stringResource(R.string.local_models_title), items = listOf {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.local_title)) },
                    supportingContent = { Text(if (localConfigured) stringResource(R.string.provider_local_models_summary, localChatModels.size) else stringResource(R.string.not_configured)) },
                    leadingContent = { Icon(Icons.Default.AutoAwesome, null, tint = if (localConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                    modifier = Modifier.clickable { onOpen(Constants.PROVIDER_LOCAL) },
                )
            })
        }
    }

    renameTarget?.let { target ->
        var value by remember(target) { mutableStateOf(target) }
        var error by remember(target) { mutableStateOf(false) }
        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.custom_provider_rename_title), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = false },
                    label = { Text(stringResource(R.string.custom_provider_name_label)) },
                    isError = error,
                    supportingText = if (error) {{ Text(stringResource(R.string.custom_provider_name_error)) }} else null,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = value.trim()
                    error = CustomProviderNamePolicy.hasConflict(
                        name = trimmed,
                        existingNames = customProviders.map { it.name },
                        currentName = target,
                    )
                    if (!error) {
                        if (trimmed != target) viewModel.customModelConfiguration.renameProvider(target, trimmed)
                        renameTarget = null
                    }
                }) { Text(stringResource(R.string.custom_provider_rename)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.custom_provider_delete_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.custom_provider_delete_text, target)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.customModelConfiguration.deleteProvider(target); deleteTarget = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.provider_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    removeTarget?.let { target ->
        RemoveBuiltInProviderDialog(
            providerName = target,
            onDismiss = { removeTarget = null },
            onConfirm = { viewModel.settings.removeBuiltInProvider(target); removeTarget = null },
        )
    }
}
