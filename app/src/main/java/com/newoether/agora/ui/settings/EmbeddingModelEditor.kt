package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.api.ProviderDefaults
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.rememberUnsavedChangesBack
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.EmbeddingCacheRowSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A preset (provider name, base URL, known model ids) for the remote embedding editor. */
internal data class EmbeddingProviderPreset(
    val name: String,
    val baseUrl: String,
    val models: List<String>,
    /** Users may need a non-default host (custom gateways, Ollama on another machine). */
    val editableUrl: Boolean = false,
    val needsKey: Boolean = true,
    val custom: Boolean = false,
)

internal fun embeddingProviderPresets(): List<EmbeddingProviderPreset> = listOf(
    EmbeddingProviderPreset(
        Constants.PROVIDER_OPENAI,
        ProviderDefaults.embeddingBaseUrl(Constants.PROVIDER_OPENAI),
        listOf("text-embedding-3-small", "text-embedding-3-large", "text-embedding-ada-002"),
    ),
    EmbeddingProviderPreset("Mistral", ProviderDefaults.embeddingBaseUrl("Mistral"), listOf("mistral-embed")),
    EmbeddingProviderPreset(
        "Voyage AI",
        ProviderDefaults.embeddingBaseUrl("Voyage AI"),
        listOf("voyage-3-large", "voyage-3-lite", "voyage-code-3"),
    ),
    EmbeddingProviderPreset(
        "SiliconFlow",
        ProviderDefaults.embeddingBaseUrl("SiliconFlow"),
        listOf("BAAI/bge-m3", "BAAI/bge-large-en-v1.5"),
    ),
    EmbeddingProviderPreset(
        Constants.PROVIDER_OPEN_ROUTER,
        ProviderDefaults.embeddingBaseUrl(Constants.PROVIDER_OPEN_ROUTER),
        listOf("openai/text-embedding-3-small", "openai/text-embedding-3-large"),
    ),
    EmbeddingProviderPreset(
        Constants.PROVIDER_OLLAMA,
        ProviderDefaults.embeddingBaseUrl(Constants.PROVIDER_OLLAMA),
        emptyList(),
        editableUrl = true,
        needsKey = false,
    ),
    EmbeddingProviderPreset("Custom", "", emptyList(), editableUrl = true, custom = true),
)

/** Which model the editor page is showing; `null` route means the search page itself. */
internal data class EmbeddingEditorRoute(
    val initial: EmbeddingModelConfig,
    val isNew: Boolean,
)

internal fun newEmbeddingRoute(type: EmbeddingModelType): EmbeddingEditorRoute =
    EmbeddingEditorRoute(EmbeddingModelConfig(name = "", type = type), isNew = true)

/** Everything the user can change on the page; compared against its start value for back guarding. */
private data class EmbeddingDraft(
    val name: String,
    val batchSize: String,
    val providerIndex: Int,
    val baseUrl: String,
    val modelName: String,
    val useSavedKey: Boolean,
    val apiKey: String,
    val localFilePath: String,
    val localFileName: String,
)

private fun initialDraft(initial: EmbeddingModelConfig, isNew: Boolean, preset: EmbeddingProviderPreset) =
    EmbeddingDraft(
        name = initial.name,
        batchSize = initial.batchSize.toString(),
        providerIndex = 0,
        baseUrl = if (isNew) preset.baseUrl else initial.remoteBaseUrl,
        modelName = if (isNew) preset.models.firstOrNull().orEmpty() else initial.remoteModelName,
        useSavedKey = isNew,
        apiKey = initial.remoteApiKey,
        localFilePath = initial.localFilePath,
        localFileName = initial.localFilePath.substringAfterLast('/'),
    )

/**
 * Full-page editor for one embedding model. New remote models pick a provider, key and model id;
 * new local models import a GGUF file. Existing models keep their model id, endpoint and file,
 * because cached vectors belong to that exact model; only name, batch size and key change.
 */
@Composable
internal fun EmbeddingModelEditor(
    initial: EmbeddingModelConfig,
    isNew: Boolean,
    viewModel: ChatViewModel,
    cacheRow: EmbeddingCacheRowSnapshot?,
    onCache: () -> Unit,
    onRecache: () -> Unit,
    onBack: () -> Unit,
    showDocFab: Boolean,
) {
    val presets = remember { embeddingProviderPresets() }
    val isRemote = initial.type == EmbeddingModelType.REMOTE
    val start = remember(initial.id) { initialDraft(initial, isNew, presets[0]) }
    var draft by remember(initial.id) { mutableStateOf(start) }
    val lifecycle = remember(initial.id) { EmbeddingEditorLifecycle() }
    var isTesting by remember(initial.id) { mutableStateOf(false) }
    var isImporting by remember(initial.id) { mutableStateOf(false) }
    var showGgufError by remember(initial.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    DisposableEffect(lifecycle) {
        onDispose { lifecycle.close() }
    }

    val preset = presets[draft.providerIndex]
    val savedKey = remember(draft.providerIndex) {
        if (isNew && preset.needsKey && !preset.custom) {
            viewModel.ragManager.resolveEmbeddingKeyForProviderExact(preset.name)
        } else {
            null
        }
    }
    val usingSavedKey = savedKey != null && draft.useSavedKey
    val effectiveKey = when {
        !preset.needsKey && isNew -> ""
        usingSavedKey -> savedKey!!.key
        else -> draft.apiKey.trim()
    }
    val defaultName = if (isRemote) draft.modelName.trim() else draft.localFileName.removeSuffix(".gguf")
    val canSave = !isTesting && !isImporting && when {
        !isNew -> (draft.name.isNotBlank() || defaultName.isNotBlank())
        isRemote -> draft.modelName.isNotBlank() && draft.baseUrl.isNotBlank()
        else -> draft.localFilePath.isNotBlank()
    }

    val leave = {
        lifecycle.close()
        onBack()
    }
    val guardedBack = rememberUnsavedChangesBack(hasUnsavedChanges = draft != start || isImporting, onBack = leave)
    BackHandler { guardedBack() }

    fun save() {
        if (lifecycle.closed || !canSave) return
        val finalName = draft.name.trim().ifBlank { defaultName }
        val batch = (draft.batchSize.toIntOrNull() ?: 8).coerceIn(1, 100)
        if (!isNew) {
            val keyChanged = isRemote && effectiveKey != initial.remoteApiKey
            if (!keyChanged) {
                viewModel.ragManager.updateEmbeddingModel(initial.id, finalName, batch)
                leave()
                return
            }
        } else if (!isRemote) {
            viewModel.ragManager.addEmbeddingModel(
                EmbeddingModelConfig(
                    name = finalName,
                    type = EmbeddingModelType.LOCAL_ONNX,
                    localFilePath = draft.localFilePath,
                    batchSize = batch,
                ),
            )
            lifecycle.imports.transfer(draft.localFilePath)
            leave()
            return
        }
        // Remote add, or a key change on an existing model: only persist a working connection.
        val model = if (isNew) draft.modelName.trim() else initial.remoteModelName
        val url = if (isNew) draft.baseUrl.trim() else initial.remoteBaseUrl
        isTesting = true
        lifecycle.testConnection(
            scope = scope,
            test = { viewModel.remoteEmbeddingConnectionTester.test(model, url, effectiveKey) },
        ) { result ->
            if (lifecycle.closed) return@testConnection
            isTesting = false
            if (result == null || !result.startsWith("OK")) {
                viewModel.emitSnackbar(context.getString(R.string.embedding_test_failed, result ?: "Failed"))
                return@testConnection
            }
            if (isNew) {
                viewModel.ragManager.addEmbeddingModel(
                    EmbeddingModelConfig(
                        name = finalName,
                        type = EmbeddingModelType.REMOTE,
                        remoteModelName = model,
                        remoteBaseUrl = url,
                        remoteApiKey = effectiveKey,
                        batchSize = batch,
                    ),
                )
            } else {
                viewModel.ragManager.updateEmbeddingModel(initial.id, finalName, batch, effectiveKey)
            }
            leave()
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || lifecycle.closed || isImporting) return@rememberLauncherForActivityResult
        isImporting = true
        scope.launch {
            try {
                val imported = importEmbeddingGguf(context, uri, lifecycle.imports)
                if (lifecycle.closed) return@launch
                if (imported == null) {
                    showGgufError = true
                } else {
                    lifecycle.imports.discard(draft.localFilePath)
                    draft = draft.copy(localFilePath = imported.path, localFileName = imported.displayName)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!lifecycle.closed) showGgufError = true
            } finally {
                if (!lifecycle.closed) isImporting = false
            }
        }
    }

    CollapsingSettingsScaffold(
        title = stringResource(if (isNew) R.string.embedding_add_model else R.string.embedding_edit_model),
        onBack = guardedBack,
        scrollState = rememberSettingsScrollState(),
        floatingActionButton = { if (showDocFab) DocumentationFab("search.md") },
        actions = {
            if (isTesting) {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = ::save, enabled = canSave) {
                    Icon(Icons.Default.Save, stringResource(R.string.save))
                }
            }
        },
    ) {
        SettingsGroupColumn {
            if (isRemote) {
                SettingsGroup(
                    title = stringResource(R.string.embedding_connection),
                    items = buildList {
                        if (isNew) {
                            add {
                                var providerExpanded by remember { mutableStateOf(false) }
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.embedding_provider_label)) },
                                    supportingContent = { Text(stringResource(R.string.embedding_type_remote_desc)) },
                                    leadingContent = {
                                        Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                                    },
                                    trailingContent = {
                                        Box {
                                            Text(
                                                if (preset.custom) {
                                                    stringResource(R.string.embedding_provider_custom)
                                                } else {
                                                    preset.name
                                                },
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.width(80.dp),
                                                textAlign = TextAlign.Center,
                                            )
                                            AgoraDropdownMenu(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                tonalElevation = 16.dp,
                                                expanded = providerExpanded,
                                                onDismissRequest = { providerExpanded = false },
                                            ) {
                                                presets.forEachIndexed { index, p ->
                                                    AgoraDropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                if (p.custom) {
                                                                    stringResource(R.string.embedding_provider_custom)
                                                                } else {
                                                                    p.name
                                                                },
                                                            )
                                                        },
                                                        leadingIcon = {
                                                            if (index == draft.providerIndex) {
                                                                Icon(
                                                                    Icons.Default.Check,
                                                                    null,
                                                                    tint = MaterialTheme.colorScheme.primary,
                                                                )
                                                            }
                                                        },
                                                        onClick = {
                                                            if (index != draft.providerIndex) {
                                                                draft = draft.copy(
                                                                    providerIndex = index,
                                                                    baseUrl = p.baseUrl,
                                                                    modelName = p.models.firstOrNull().orEmpty(),
                                                                    useSavedKey = true,
                                                                    apiKey = "",
                                                                )
                                                            }
                                                            providerExpanded = false
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.clickable { providerExpanded = true },
                                )
                            }
                        }
                        if (!isNew || preset.editableUrl) {
                            add {
                                SettingsIconContent(icon = Icons.Default.Link) {
                                    if (isNew) {
                                        McpLabeledField(
                                            label = stringResource(R.string.embedding_base_url_label),
                                            value = draft.baseUrl,
                                            onValueChange = { draft = draft.copy(baseUrl = it) },
                                            keyboardType = KeyboardType.Uri,
                                            placeholder = "https://api.example.com/v1",
                                        )
                                    } else {
                                        ReadOnlyField(
                                            stringResource(R.string.embedding_base_url_label),
                                            initial.remoteBaseUrl.ifBlank { "-" },
                                        )
                                    }
                                }
                            }
                        }
                        if (!isNew || preset.needsKey) {
                            if (savedKey != null) {
                                add {
                                    SettingsItem(
                                        headlineContent = {
                                            Text(stringResource(R.string.embedding_use_saved_key, savedKey.provider))
                                        },
                                        supportingContent = {
                                            Text(stringResource(R.string.embedding_use_saved_key_desc))
                                        },
                                        leadingContent = {
                                            Icon(Icons.Default.VpnKey, null, tint = MaterialTheme.colorScheme.primary)
                                        },
                                        trailingContent = {
                                            Switch(
                                                checked = draft.useSavedKey,
                                                onCheckedChange = { draft = draft.copy(useSavedKey = it) },
                                            )
                                        },
                                        modifier = Modifier.clickable {
                                            draft = draft.copy(useSavedKey = !draft.useSavedKey)
                                        },
                                    )
                                }
                            }
                            if (!usingSavedKey) {
                                add {
                                    SettingsIconContent(icon = Icons.Default.VpnKey) {
                                        McpLabeledField(
                                            label = stringResource(R.string.embedding_api_key),
                                            value = draft.apiKey,
                                            onValueChange = { draft = draft.copy(apiKey = it) },
                                            password = true,
                                            placeholder = stringResource(R.string.embedding_api_key_hint),
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
                SettingsGroup(
                    title = stringResource(R.string.embedding_model_label),
                    items = buildList {
                        add {
                            SettingsIconContent(icon = Icons.Default.AutoAwesome) {
                                if (isNew) {
                                    McpLabeledField(
                                        label = stringResource(R.string.embedding_model_label),
                                        value = draft.modelName,
                                        onValueChange = { draft = draft.copy(modelName = it) },
                                        placeholder = stringResource(R.string.embedding_model_id_hint),
                                    )
                                } else {
                                    ReadOnlyField(
                                        stringResource(R.string.embedding_model_label),
                                        initial.remoteModelName,
                                        stringResource(R.string.embedding_model_locked_desc),
                                    )
                                }
                            }
                        }
                    },
                )
            } else if (isNew) {
                SettingsGroup(
                    title = stringResource(R.string.embedding_model_file),
                    items = listOf {
                        ModelImportItem(
                            title = draft.localFileName.ifBlank { stringResource(R.string.embedding_import_title) },
                            description = stringResource(R.string.embedding_import_desc),
                            icon = Icons.Default.AutoAwesome,
                            importing = isImporting,
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                        )
                    },
                )
            }
            SettingsGroup(
                title = stringResource(R.string.embedding_general),
                items = listOf(
                    {
                        SettingsIconContent(icon = Icons.Default.Label) {
                            McpLabeledField(
                                label = stringResource(R.string.model_name_label),
                                value = draft.name,
                                onValueChange = { draft = draft.copy(name = it) },
                                placeholder = defaultName.ifBlank { null },
                            )
                        }
                    },
                    {
                        SettingsIconContent(icon = Icons.Default.Tune) {
                            McpLabeledField(
                                label = stringResource(R.string.embedding_batch_size),
                                value = draft.batchSize,
                                onValueChange = { value -> draft = draft.copy(batchSize = value.filter(Char::isDigit)) },
                                keyboardType = KeyboardType.Number,
                                supportingText = stringResource(R.string.embedding_batch_size_desc),
                            )
                        }
                    },
                ),
            )
            if (!isNew) {
                SettingsGroup(
                    title = stringResource(R.string.embedding_cache_status),
                    items = listOf {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.embedding_cache_status)) },
                            supportingContent = { Text(embeddingCacheLabel(cacheRow) ?: "-") },
                            leadingContent = {
                                Icon(Icons.Default.Cached, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                EmbeddingCacheAction(
                                    modelId = initial.id,
                                    cacheRow = cacheRow,
                                    size = rememberEmbeddingCacheActionSize(),
                                    onCache = onCache,
                                    onRecache = onRecache,
                                )
                            },
                        )
                    },
                )
            }
        }
        if (showDocFab) Spacer(modifier = Modifier.height(80.dp))
    }

    if (showGgufError) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showGgufError = false },
            title = { Text(stringResource(R.string.import_invalid_gguf_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.import_invalid_gguf_desc)) },
            confirmButton = { TextButton(onClick = { showGgufError = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun FieldTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ReadOnlyField(label: String, value: String, note: String? = null) {
    FieldTitle(label)
    Spacer(Modifier.height(4.dp))
    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (note != null) {
        Spacer(Modifier.height(4.dp))
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
