package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.newoether.agora.R
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.BUILT_IN_REMOTE_PROVIDERS
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.ProviderEditorCommitResult
import com.newoether.agora.data.ProviderEditorDraft
import com.newoether.agora.data.anthropicCacheTtlForProvider
import com.newoether.agora.data.isAnthropicProtocolProvider
import com.newoether.agora.data.isPromptCacheSettingEnabledForProvider
import com.newoether.agora.data.supportsPromptCacheSetting
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.CustomEndpointProtocolSelector
import com.newoether.agora.ui.components.SecretVisibilityToggle
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.components.rememberSecretVisible
import com.newoether.agora.ui.components.rememberUnsavedChangesBack
import com.newoether.agora.ui.components.secretVisualTransformation
import com.newoether.agora.util.Constants
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

private val CACHE_TTLS = listOf("5m", "1h")

/**
 * Explicit-save detail page of one remote Provider, built-in or custom.
 *
 * Every connection, credential and cache field edits a draft that only Save commits, in one
 * DataStore edit. Back discards the draft behind the unsaved-changes guard. Deleting a custom
 * Provider or removing a built-in from the list acts immediately and leaves the page.
 * The draft lives in memory only, so API keys never enter saved instance state.
 */
@Composable
internal fun ProviderRemoteEditor(
    providerName: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val apiKeys by viewModel.settings.apiKeys.collectAsState()
    val activeApiKeyIds by viewModel.settings.activeApiKeyIds.collectAsState()
    val providerBaseUrls by viewModel.settings.providerBaseUrls.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val openAiResponsesApiEnabled by viewModel.settings.openAiResponsesApiEnabled.collectAsState()
    val anthropicCacheEnabled by viewModel.settings.anthropicCacheEnabled.collectAsState()
    val anthropicCacheTtl by viewModel.settings.anthropicCacheTtl.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    val customConfig = customProviders.firstOrNull { it.name == providerName }
    val isBuiltIn = providerName in BUILT_IN_REMOTE_PROVIDERS
    // Nullable: after deleting a custom Provider this page can recompose once before it pops.
    val providerInstance = viewModel.getProviderInstanceOrNull(providerName)
    val defaultUrl = providerInstance?.defaultBaseUrl.orEmpty()
    val storedUrl = providerBaseUrls[providerName].orEmpty()

    val stored = ProviderEditorDraft(
        name = providerName,
        // A blank override visibly resolves to the Provider default, as before explicit save.
        baseUrl = storedUrl.ifBlank { defaultUrl },
        protocol = customConfig?.protocol,
        responsesApiEnabled = customConfig?.responsesApiEnabled ?: openAiResponsesApiEnabled,
        cacheEnabled = isPromptCacheSettingEnabledForProvider(
            customConfig?.providerId ?: providerName, anthropicCacheEnabled, customProviders,
        ),
        cacheTtl = anthropicCacheTtlForProvider(
            customConfig?.providerId ?: providerName, anthropicCacheTtl, customProviders,
        ).takeIf { it in CACHE_TTLS } ?: "1h",
        apiKeys = apiKeys.filter { it.provider == providerName },
        activeKeyId = activeApiKeyIds[providerName],
    )
    var start by remember(providerName) { mutableStateOf(stored) }
    var draft by remember(providerName) { mutableStateOf(stored) }
    // An untouched draft follows external writes (cold load, import); a dirty one is kept.
    LaunchedEffect(stored) {
        if (draft == start) {
            start = stored
            draft = stored
        }
    }

    var nameError by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var showDeleteKeyConfirm by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var showDeleteProvider by remember { mutableStateOf(false) }
    var showRemoveProvider by remember { mutableStateOf(false) }
    var providerMenuExpanded by remember { mutableStateOf(false) }

    val guardedBack = rememberUnsavedChangesBack(
        hasUnsavedChanges = draft != start,
        onBack = onBack,
    )
    BackHandler { guardedBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveFailed = stringResource(R.string.provider_save_failed)
    fun save() {
        if (saving) return
        // An unedited implicit default is not an override: keep the stored URL absent.
        val url = draft.baseUrl.trim()
            .takeUnless { isBuiltIn && storedUrl.isBlank() && it == defaultUrl }
            .orEmpty()
        saving = true
        scope.launch {
            val result = try {
                viewModel.customModelConfiguration.commitProviderEditor(
                    providerName,
                    draft.copy(baseUrl = url),
                )
            } finally {
                saving = false
            }
            when (result) {
                ProviderEditorCommitResult.SAVED -> onBack()
                ProviderEditorCommitResult.NAME_CONFLICT -> nameError = true
                ProviderEditorCommitResult.URL_REQUIRED -> urlError = true
                ProviderEditorCommitResult.MISSING,
                ProviderEditorCommitResult.FAILED ->
                    Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Cache and Responses rows follow the draft protocol, not the stored one.
    val draftCustoms: List<CustomProviderConfig> = customProviders.map { config ->
        if (config.name == providerName && draft.protocol != null) config.copy(protocol = draft.protocol!!)
        else config
    }

    CollapsingSettingsScaffold(
        title = if (customConfig != null) draft.name.ifBlank { providerName } else providerName,
        onBack = guardedBack,
        scrollState = rememberSettingsScrollState(),
        actions = {
            IconButton(onClick = ::save, enabled = !saving) {
                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
            }
            Box {
                IconButton(onClick = { providerMenuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.options))
                }
                AgoraDropdownMenu(
                    expanded = providerMenuExpanded,
                    onDismissRequest = { providerMenuExpanded = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 16.dp,
                ) {
                    if (customConfig != null) {
                        AgoraDropdownMenuItem(
                            text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { providerMenuExpanded = false; showDeleteProvider = true },
                        )
                    } else {
                        AgoraDropdownMenuItem(
                            text = { Text(stringResource(R.string.provider_remove_from_list), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { providerMenuExpanded = false; showRemoveProvider = true },
                        )
                    }
                }
            }
        },
        floatingActionButton = { if (showDocFab) DocumentationFab("provider.md") },
    ) {
        SettingsGroupColumn {
            if (customConfig != null) {
                SettingsGroup(
                    title = stringResource(R.string.embedding_general),
                    items = listOf {
                        SettingsIconContent(Icons.Default.Label) {
                            McpLabeledField(
                                label = stringResource(R.string.custom_provider_name_label),
                                value = draft.name,
                                onValueChange = { draft = draft.copy(name = it); nameError = false },
                                isError = nameError,
                                supportingText = if (nameError) stringResource(R.string.custom_provider_name_error) else null,
                            )
                        }
                    },
                )
            }
            SettingsGroup(
                title = stringResource(
                    if (customConfig != null) R.string.provider_connection else R.string.provider_base_url,
                ),
                items = buildList {
                    add {
                        SettingsIconContent(Icons.Default.Link) {
                            McpLabeledField(
                                label = stringResource(R.string.provider_base_url),
                                value = draft.baseUrl,
                                onValueChange = { draft = draft.copy(baseUrl = it); urlError = false },
                                isError = urlError,
                                supportingText = if (urlError) stringResource(R.string.custom_provider_url_error) else null,
                                keyboardType = KeyboardType.Uri,
                                placeholder = providerInstance?.baseUrlPlaceholder,
                            )
                        }
                    }
                    draft.protocol?.let { protocol ->
                        add {
                            SettingsIconContent(icon = Icons.Default.DataObject) {
                                Text(
                                    stringResource(R.string.custom_provider_protocol_label),
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(10.dp))
                                CustomEndpointProtocolSelector(
                                    selected = protocol,
                                    onSelected = { draft = draft.copy(protocol = it) },
                                )
                            }
                        }
                    }
                    val showResponses = draft.protocol == CustomEndpointProtocol.OPENAI ||
                        (customConfig == null && providerName == Constants.PROVIDER_OPENAI)
                    if (showResponses) {
                        add {
                            SettingsItem(
                                modifier = Modifier.toggleable(
                                    value = draft.responsesApiEnabled,
                                    role = Role.Switch,
                                    onValueChange = { draft = draft.copy(responsesApiEnabled = it) },
                                ),
                                headlineContent = { Text(stringResource(R.string.responses_api)) },
                                supportingContent = { Text(stringResource(R.string.responses_api_desc)) },
                                leadingContent = {
                                    Icon(Icons.Default.DataObject, null, tint = MaterialTheme.colorScheme.primary)
                                },
                                trailingContent = { Switch(checked = draft.responsesApiEnabled, onCheckedChange = null) },
                            )
                        }
                    }
                },
            )

            ProviderApiKeysSettings(
                apiKeys = draft.apiKeys,
                currentName = providerName,
                activeApiKeyIds = draft.activeKeyId?.let { mapOf(providerName to it) }.orEmpty(),
                onActivateKey = { _, id -> draft = draft.copy(activeKeyId = id) },
                onEditKey = { showKeyDialog = it },
                onDeleteKey = { showDeleteKeyConfirm = it },
            )

            if (supportsPromptCacheSetting(providerName, draftCustoms)) {
                // OpenAI-protocol endpoints share the switch (prompt_cache_key) but have no TTL.
                val ttlSupported = isAnthropicProtocolProvider(providerName, draftCustoms)
                SettingsGroup(
                    title = stringResource(R.string.advanced_title),
                    items = buildList {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.provider_cache)) },
                                supportingContent = { Text(stringResource(R.string.provider_cache_desc)) },
                                leadingContent = {
                                    Icon(Icons.Default.Cached, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                },
                                trailingContent = { Switch(checked = draft.cacheEnabled, onCheckedChange = null) },
                                modifier = Modifier.toggleable(
                                    value = draft.cacheEnabled,
                                    role = Role.Switch,
                                    onValueChange = { draft = draft.copy(cacheEnabled = it) },
                                ),
                            )
                        }
                        if (draft.cacheEnabled && ttlSupported) {
                            add { CacheTtlRow(selected = draft.cacheTtl, onSelected = { draft = draft.copy(cacheTtl = it) }) }
                        }
                    },
                )
            }
        }
        if (showDocFab) Spacer(Modifier.height(80.dp))
    }

    showKeyDialog?.let { entry ->
        ApiKeyDialog(
            entry = entry,
            providerName = providerName,
            isEdit = draft.apiKeys.any { it.id == entry.id },
            onDismiss = { showKeyDialog = null },
            onConfirm = { updated ->
                val exists = draft.apiKeys.any { it.id == updated.id }
                val keys = if (exists) draft.apiKeys.map { if (it.id == updated.id) updated else it }
                else draft.apiKeys + updated
                draft = draft.copy(apiKeys = keys, activeKeyId = draft.activeKeyId ?: updated.id)
                showKeyDialog = null
            },
        )
    }

    showDeleteKeyConfirm?.let { entry ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showDeleteKeyConfirm = null },
            title = { Text(stringResource(R.string.provider_delete_key_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.provider_delete_key_text, entry.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val keys = draft.apiKeys.filter { it.id != entry.id }
                        val active = draft.activeKeyId?.takeIf { id -> keys.any { it.id == id } }
                            ?: keys.firstOrNull()?.id
                        draft = draft.copy(apiKeys = keys, activeKeyId = active)
                        showDeleteKeyConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.provider_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteKeyConfirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showDeleteProvider) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showDeleteProvider = false },
            title = { Text(stringResource(R.string.custom_provider_delete_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.custom_provider_delete_text, providerName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.customModelConfiguration.deleteProvider(providerName)
                        showDeleteProvider = false
                        onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.provider_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteProvider = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showRemoveProvider) {
        RemoveBuiltInProviderDialog(
            providerName = providerName,
            onDismiss = { showRemoveProvider = false },
            onConfirm = {
                viewModel.settings.removeBuiltInProvider(providerName)
                showRemoveProvider = false
                onBack()
            },
        )
    }
}

/** Confirmation shared by the detail-page menu and the Provider list long-press menu. */
@Composable
internal fun RemoveBuiltInProviderDialog(
    providerName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.provider_remove_title, providerName), fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.provider_remove_text)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.provider_remove)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CacheTtlRow(selected: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    SettingsItem(
        headlineContent = { Text(stringResource(R.string.provider_cache_duration)) },
        supportingContent = { Text(stringResource(R.string.provider_cache_duration_desc)) },
        leadingContent = {
            Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        },
        trailingContent = {
            Box {
                Text(
                    selected,
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
                    CACHE_TTLS.forEach { ttl ->
                        AgoraDropdownMenuItem(
                            text = { Text(ttl) },
                            leadingIcon = {
                                if (selected == ttl) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            onClick = { onSelected(ttl); expanded = false },
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable { expanded = true },
    )
}

@Composable
private fun ApiKeyDialog(
    entry: ApiKeyEntry,
    providerName: String,
    isEdit: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (ApiKeyEntry) -> Unit,
) {
    var name by remember { mutableStateOf(entry.name) }
    var key by remember { mutableStateOf(entry.key) }
    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (isEdit) R.string.provider_edit_key else R.string.provider_add_key_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.provider_key_name_hint)) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().noOpBringIntoView(),
                )
                Spacer(Modifier.height(8.dp))
                var keyVisible by rememberSecretVisible()
                Box(modifier = Modifier.noOpBringIntoView()) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("$providerName API Key") },
                        visualTransformation = secretVisualTransformation(keyVisible),
                        trailingIcon = { SecretVisibilityToggle(keyVisible) { keyVisible = !keyVisible } },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && key.isNotBlank()) onConfirm(entry.copy(name = name, key = key))
            }) {
                Text(stringResource(if (isEdit) R.string.provider_save else R.string.provider_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
