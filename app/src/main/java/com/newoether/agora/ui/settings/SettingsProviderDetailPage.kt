package com.newoether.agora.ui.settings

import com.newoether.agora.data.repository.setAnthropicCacheEnabled
import com.newoether.agora.data.repository.setAnthropicCacheTtl

import com.newoether.agora.util.DebugLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomProviderNamePolicy
import com.newoether.agora.data.isAnthropicProtocolProvider
import com.newoether.agora.data.isPromptCacheSettingEnabledForProvider
import com.newoether.agora.data.supportsPromptCacheSetting
import com.newoether.agora.data.anthropicCacheTtlForProvider
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.ui.components.CustomEndpointProtocolSelector
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.util.Constants
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
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
import com.newoether.agora.ui.components.Surface

@Composable
fun SettingsProviderDetailPage(
    providerName: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    var editorRoute by remember(providerName) { mutableStateOf<LocalChatEditorRoute?>(null) }
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    BackHandler(enabled = editorRoute != null) { editorRoute = null }
    GuardedAnimatedContent(
        targetState = editorRoute,
        forward = editorRoute != null,
        contentKey = { route -> route?.initial?.id ?: if (route != null) "add" else null },
    ) { route ->
        if (route != null) {
            SettingsSecondaryPane {
                LocalChatModelEditor(
                    initial = route.initial,
                    viewModel = viewModel,
                    onBack = { editorRoute = null },
                    showDocFab = showDocFab,
                )
            }
        } else {
            ProviderDetailSettingsList(
                providerName = providerName,
                viewModel = viewModel,
                onBack = onBack,
                onAddModel = { editorRoute = LocalChatEditorRoute(null) },
                onEditModel = { editorRoute = LocalChatEditorRoute(it) },
            )
        }
    }
}

private data class LocalChatEditorRoute(val initial: LocalChatModelConfig?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDetailSettingsList(
    providerName: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onAddModel: () -> Unit,
    onEditModel: (LocalChatModelConfig) -> Unit,
) {
    val apiKeys by viewModel.settings.apiKeys.collectAsState()
    val activeApiKeyIds by viewModel.settings.activeApiKeyIds.collectAsState()
    val providerBaseUrls by viewModel.settings.providerBaseUrls.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val openAiResponsesApiEnabled by viewModel.settings.openAiResponsesApiEnabled.collectAsState()
    val anthropicCacheEnabled by viewModel.settings.anthropicCacheEnabled.collectAsState()
    val anthropicCacheTtl by viewModel.settings.anthropicCacheTtl.collectAsState()
    val localChatModels by viewModel.settings.localChatModels.collectAsState()
    val localModelIdleRetentionMinutes by
        viewModel.settings.localModelIdleRetentionMinutes.collectAsState()
    val localLowContextModeEnabled by
        viewModel.settings.localLowContextModeEnabled.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    var currentName by rememberSaveable(providerName) { mutableStateOf(providerName) }

    val isLocal = currentName == Constants.PROVIDER_LOCAL
    val customConfig = customProviders.firstOrNull { it.name == currentName }
    val isCustom = customConfig != null

    // Dialogs
    var showKeyDialog by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var showDeleteKeyConfirm by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var showDeleteModelConfirm by remember { mutableStateOf<LocalChatModelConfig?>(null) }
    var showRenameProvider by remember { mutableStateOf(false) }
    var showDeleteProvider by remember { mutableStateOf(false) }
    var providerMenuExpanded by remember { mutableStateOf(false) }

    CollapsingSettingsScaffold(
        title = if (isLocal) stringResource(R.string.local_title) else currentName,
        onBack = onBack,
        actions = {
            if (isCustom) {
                Box {
                    IconButton(onClick = { providerMenuExpanded = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.options)) }
                    AgoraDropdownMenu(expanded = providerMenuExpanded, onDismissRequest = { providerMenuExpanded = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 16.dp) {
                        AgoraDropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { providerMenuExpanded = false; showRenameProvider = true })
                        AgoraDropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { providerMenuExpanded = false; showDeleteProvider = true })
                    }
                }
            }
        },
        floatingActionButton = { if (showDocFab) DocumentationFab("provider.md") },
    ) {
            SettingsGroupColumn {
                // Base URL (non-Local only)
                if (!isLocal) {
                // Nullable: after deleting a custom provider this page recomposes once more
                // before navigation pops it — render with an empty placeholder, don't crash.
                val providerInstance = viewModel.getProviderInstanceOrNull(currentName)
                val savedUrl = providerBaseUrls[currentName]
                val defaultUrl = providerInstance?.defaultBaseUrl.orEmpty()
                val placeholderUrl = providerInstance?.baseUrlPlaceholder.orEmpty()
                val displayedUrl = savedUrl?.takeIf(String::isNotBlank) ?: defaultUrl
                // Don't key remember on savedUrl — that causes TextFieldState to be recreated
                // every time the debounced save writes back to DataStore, overwriting user input.
                val baseUrlState = remember(providerName) { TextFieldState(displayedUrl) }
                // Sync external changes (e.g. import) and a late provider registration back into
                // the field. A missing/blank override visibly resolves to the provider default.
                LaunchedEffect(savedUrl, defaultUrl) {
                    val ext = savedUrl?.takeIf(String::isNotBlank) ?: defaultUrl
                    val cur = baseUrlState.text.toString()
                    if (ext.isNotEmpty() && ext != cur) {
                        baseUrlState.edit { replace(0, length, ext) }
                    }
                }
                // Save user input with 500ms debounce — but only on a *real* edit.
                // On first composition the field is initialized to `savedUrl ?: ""`, and a DataStore
                // cold load can deliver savedUrl=null momentarily; writing that "" back would poison
                // the persisted map (see SettingsManager.saveProviderBaseUrl). Skipping when the text
                // already equals the persisted (or absent) value eliminates that race and also avoids
                // re-writing the same URL the LaunchedEffect(savedUrl) sync just applied.
                LaunchedEffect(baseUrlState.text) {
                    delay(500)
                    val text = baseUrlState.text.toString()
                    val stored = providerBaseUrls[currentName] ?: ""
                    val isUneditedImplicitDefault =
                        stored.isBlank() && text == defaultUrl
                    if (!isUneditedImplicitDefault && text != stored) {
                        viewModel.settings.setProviderBaseUrl(currentName, text)
                    }
                }
                SettingsGroup(
                    title = stringResource(
                        if (customConfig != null) R.string.provider_connection
                        else R.string.provider_base_url
                    ),
                    items = buildList {
                        add {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Icon(painterResource(R.drawable.link_24), null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.provider_base_url), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                                        Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                                            OutlinedTextField(
                                                state = baseUrlState,
                                                placeholder = { Text(placeholderUrl, style = MaterialTheme.typography.bodyMedium) },
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        customConfig?.let { config ->
                            add {
                                SettingsIconContent(icon = Icons.Default.DataObject) {
                                    Text(
                                        stringResource(R.string.custom_provider_protocol_label),
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    CustomEndpointProtocolSelector(
                                        selected = config.protocol,
                                        onSelected = { protocol ->
                                            if (protocol != config.protocol) {
                                                viewModel.customModelConfiguration.updateProviderProtocol(currentName, protocol)
                                            }
                                        },
                                    )
                                }
                            }
                            if (config.protocol == CustomEndpointProtocol.OPENAI) {
                                add {
                                    SettingsItem(
                                        modifier = Modifier.toggleable(
                                            value = config.responsesApiEnabled,
                                            onValueChange = {
                                                viewModel.settings.setCustomProviderResponsesApiEnabled(
                                                    currentName,
                                                    it,
                                                )
                                            },
                                        ),
                                        headlineContent = { Text(stringResource(R.string.responses_api)) },
                                        supportingContent = { Text(stringResource(R.string.responses_api_desc)) },
                                        leadingContent = {
                                            Icon(
                                                Icons.Default.DataObject,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        },
                                        trailingContent = {
                                            Switch(
                                                checked = config.responsesApiEnabled,
                                                onCheckedChange = null,
                                            )
                                        },
                                    )
                                }
                            }
                        } ?: if (currentName == Constants.PROVIDER_OPENAI) {
                            add {
                                SettingsItem(
                                    modifier = Modifier.toggleable(
                                        value = openAiResponsesApiEnabled,
                                        onValueChange = viewModel.settings::setOpenAiResponsesApiEnabled,
                                    ),
                                    headlineContent = { Text(stringResource(R.string.responses_api)) },
                                    supportingContent = { Text(stringResource(R.string.responses_api_desc)) },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.DataObject,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = openAiResponsesApiEnabled,
                                            onCheckedChange = null,
                                        )
                                    },
                                )
                            }
                        } else Unit
                    },
                )
            }

            // Local models
            if (isLocal) {
                SettingsGroup(
                    title = stringResource(R.string.local_models_title),
                    items = buildList {
                        if (localChatModels.isEmpty()) {
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.provider_no_local_models), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    supportingContent = { Text(stringResource(R.string.provider_no_local_models_desc), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                                    leadingContent = { Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                                    modifier = Modifier.heightIn(min = 64.dp)
                                )
                            }
                        }
                        localChatModels.forEach { model ->
                            var showMenu by remember { mutableStateOf(false) }
                            add {
                                SettingsItem(
                                    modifier = Modifier.clickable { showMenu = true },
                                    headlineContent = { Text(model.alias, fontWeight = FontWeight.Medium) },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    },
                                    supportingContent = {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(top = 1.dp)
                                        ) {
                                            if (model.mmprojPath.isNotBlank()) {
                                                Surface(
                                                    shape = RoundedCornerShape(5.dp),
                                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                                ) {
                                                    Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.Visibility, null, modifier = Modifier.size(10.dp))
                                                        Spacer(Modifier.width(3.dp))
                                                        Text(stringResource(R.string.provider_vision_badge), style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(5.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            ) {
                                                Text(
                                                    "Context=${model.nCtx}",
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(5.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            ) {
                                                Text(
                                                    "T=${model.temperature}",
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        Box {
                                            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.MoreVert, stringResource(R.string.options), modifier = Modifier.size(18.dp)) }
                                            AgoraDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 16.dp) {
                                                AgoraDropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; onEditModel(model) })
                                                AgoraDropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; showDeleteModelConfirm = model })
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        add {
                            SettingsAddItem(
                                label = stringResource(R.string.import_model_chat),
                                onClick = onAddModel,
                            )
                        }
                    }
                )
                SettingsGroup(
                    title = stringResource(R.string.advanced_title),
                    items = listOf(
                        {
                            SettingsItem(
                                modifier = Modifier.toggleable(
                                    value = localLowContextModeEnabled,
                                    onValueChange =
                                        viewModel.settings::setLocalLowContextModeEnabled,
                                ),
                                headlineContent = {
                                    Text(stringResource(R.string.low_context_mode))
                                },
                                supportingContent = {
                                    Text(stringResource(R.string.low_context_mode_default_desc))
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Memory,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = localLowContextModeEnabled,
                                        onCheckedChange = null,
                                    )
                                },
                            )
                        },
                        {
                            LocalModelIdleRetentionSlider(
                                value = localModelIdleRetentionMinutes,
                                onValueChange =
                                    viewModel.settings::setLocalModelIdleRetentionMinutes,
                            )
                        },
                    ),
                )
            }

            // API Keys (non-Local)
            if (!isLocal) {
                ProviderApiKeysSettings(
                    apiKeys = apiKeys,
                    currentName = currentName,
                    activeApiKeyIds = activeApiKeyIds,
                    onActivateKey = viewModel.settings::setActiveApiKey,
                    onEditKey = { showKeyDialog = it },
                    onDeleteKey = { showDeleteKeyConfirm = it },
                )
                if (supportsPromptCacheSetting(currentName, customProviders)) {
                    val providerId = customConfig?.providerId ?: currentName
                    // OpenAI-protocol endpoints share the switch (prompt_cache_key) but have no TTL.
                    val cacheTtlSupported = isAnthropicProtocolProvider(currentName, customProviders)
                    val cacheEnabled = isPromptCacheSettingEnabledForProvider(
                        providerId, anthropicCacheEnabled, customProviders,
                    )
                    val cacheTtl = anthropicCacheTtlForProvider(
                        providerId, anthropicCacheTtl, customProviders,
                    )
                    SettingsGroup(
                        title = stringResource(R.string.advanced_title),
                        items = buildList {
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.provider_cache)) },
                                    supportingContent = { Text(stringResource(R.string.provider_cache_desc)) },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.Cached,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    trailingContent = {
                                        Switch(checked = cacheEnabled, onCheckedChange = null)
                                    },
                                    modifier = Modifier.toggleable(
                                        value = cacheEnabled,
                                        role = Role.Switch,
                                        onValueChange = { viewModel.settings.setAnthropicCacheEnabled(providerId, it) },
                                    ),
                                )
                            }
                            if (cacheEnabled && cacheTtlSupported) {
                                add {
                                    var expanded by remember(providerId) { mutableStateOf(false) }
                                    SettingsItem(
                                        headlineContent = { Text(stringResource(R.string.provider_cache_duration)) },
                                        supportingContent = { Text(stringResource(R.string.provider_cache_duration_desc)) },
                                        leadingContent = {
                                            Icon(
                                                Icons.Default.Schedule,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        },
                                        trailingContent = {
                                            Box {
                                                Text(
                                                    cacheTtl,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(end = 4.dp),
                                                )
                                                AgoraDropdownMenu(
                                                    expanded = expanded,
                                                    onDismissRequest = { expanded = false },
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                    tonalElevation = 16.dp,
                                                ) {
                                                    listOf("5m", "1h").forEach { ttl ->
                                                        AgoraDropdownMenuItem(
                                                            text = { Text(ttl) },
                                                            leadingIcon = {
                                                                if (cacheTtl == ttl) {
                                                                    Icon(
                                                                        Icons.Default.Check,
                                                                        contentDescription = null,
                                                                        tint = MaterialTheme.colorScheme.primary,
                                                                    )
                                                                }
                                                            },
                                                            onClick = {
                                                                viewModel.settings.setAnthropicCacheTtl(providerId, ttl)
                                                                expanded = false
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.clickable { expanded = true },
                                    )
                                }
                            }
                        },
                    )
                }
            }
            }
            if (showDocFab) Spacer(modifier = Modifier.height(80.dp))
    }

    // --- Dialogs ---

    // Delete model confirm
    showDeleteModelConfirm?.let { model ->
        AlertDialog(containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showDeleteModelConfirm = null }, title = { Text(stringResource(R.string.local_chat_delete_title), fontWeight = FontWeight.Bold) }, text = { Text(stringResource(R.string.local_chat_delete_text, model.alias)) }, confirmButton = { TextButton(onClick = { viewModel.modelManager.deleteLocalChatModel(model.id); showDeleteModelConfirm = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton(onClick = { showDeleteModelConfirm = null }) { Text(stringResource(R.string.cancel)) } })
    }

    // API Key dialog
    showKeyDialog?.let { entry ->
        var name by remember { mutableStateOf(entry.name) }; var key by remember { mutableStateOf(entry.key) }
        val isEdit = apiKeys.any { it.id == entry.id }
        AlertDialog(modifier = Modifier.clearFocusOnTap(), containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showKeyDialog = null }, title = { Text(if (isEdit) stringResource(R.string.provider_edit_key) else stringResource(R.string.provider_add_key_title), fontWeight = FontWeight.Bold) }, text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.provider_key_name_hint)) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().noOpBringIntoView())
                Spacer(modifier = Modifier.height(8.dp))
                var keyVisible by rememberSecretVisible()
                Box(modifier = Modifier.noOpBringIntoView()) { OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("${currentName} API Key") }, visualTransformation = secretVisualTransformation(keyVisible), trailingIcon = { SecretVisibilityToggle(keyVisible) { keyVisible = !keyVisible } }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) }
            }
        }, confirmButton = { TextButton(onClick = { if (name.isNotBlank() && key.isNotBlank()) { if (isEdit) viewModel.settings.updateApiKey(entry.id, name, key) else viewModel.settings.addApiKey(name, key, currentName); showKeyDialog = null } }) { Text(if (isEdit) stringResource(R.string.provider_save) else stringResource(R.string.provider_add)) } }, dismissButton = { TextButton(onClick = { showKeyDialog = null }) { Text(stringResource(R.string.cancel)) } })
    }

    // Delete key confirm
    showDeleteKeyConfirm?.let { entry ->
        AlertDialog(containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showDeleteKeyConfirm = null }, title = { Text(stringResource(R.string.provider_delete_key_title), fontWeight = FontWeight.Bold) }, text = { Text(stringResource(R.string.provider_delete_key_text, entry.name)) }, confirmButton = { TextButton(onClick = { viewModel.settings.deleteApiKey(entry.id); showDeleteKeyConfirm = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.provider_delete)) } }, dismissButton = { TextButton(onClick = { showDeleteKeyConfirm = null }) { Text(stringResource(R.string.cancel)) } })
    }

    // Rename custom provider
    if (showRenameProvider) {
        var renameValue by remember(currentName) { mutableStateOf(currentName) }
        var renameError by remember { mutableStateOf(false) }
        AlertDialog(modifier = Modifier.clearFocusOnTap(), containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showRenameProvider = false }, title = { Text(stringResource(R.string.custom_provider_rename_title), fontWeight = FontWeight.Bold) }, text = {
            OutlinedTextField(value = renameValue, onValueChange = { renameValue = it; renameError = false }, label = { Text(stringResource(R.string.custom_provider_name_label)) }, isError = renameError, supportingText = if (renameError) {{ Text(stringResource(R.string.custom_provider_name_error)) }} else null, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), singleLine = true)
        }, confirmButton = { TextButton(onClick = {
            val trimmed = renameValue.trim()
            renameError = CustomProviderNamePolicy.hasConflict(
                name = trimmed,
                existingNames = customProviders.map { it.name },
                currentName = currentName,
            )
            if (!renameError) {
                viewModel.customModelConfiguration.renameProvider(currentName, trimmed)
                showRenameProvider = false
                if (trimmed != currentName) currentName = trimmed
            }
        }) { Text(stringResource(R.string.custom_provider_rename)) } }, dismissButton = { TextButton(onClick = { showRenameProvider = false }) { Text(stringResource(R.string.cancel)) } })
    }

    // Delete custom provider
    if (showDeleteProvider) {
        AlertDialog(containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showDeleteProvider = false }, title = { Text(stringResource(R.string.custom_provider_delete_title), fontWeight = FontWeight.Bold) }, text = { Text(stringResource(R.string.custom_provider_delete_text, currentName)) }, confirmButton = { TextButton(onClick = { viewModel.customModelConfiguration.deleteProvider(currentName); showDeleteProvider = false; onBack() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.provider_delete)) } }, dismissButton = { TextButton(onClick = { showDeleteProvider = false }) { Text(stringResource(R.string.cancel)) } })
    }
}
