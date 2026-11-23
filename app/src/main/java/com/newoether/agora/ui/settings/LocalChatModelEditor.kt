package com.newoether.agora.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import com.newoether.agora.ui.icons.LocalModelIcon
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.ui.components.rememberUnsavedChangesBack
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LocalChatModelEditor(
    initial: LocalChatModelConfig?,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    showDocFab: Boolean,
) {
    val start = remember(initial?.id) { LocalChatModelDraft.from(initial) }
    var draft by remember(initial?.id) { mutableStateOf(start) }
    val imports = remember(initial?.id) { LocalChatModelImports() }
    var closed by remember { mutableStateOf(false) }
    var pickingProjector by remember { mutableStateOf<Boolean?>(null) }
    var importingProjector by remember { mutableStateOf<Boolean?>(null) }
    var showGgufError by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<LocalChatModelDraftError?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = pickingProjector != null || importingProjector != null

    DisposableEffect(imports) {
        onDispose {
            closed = true
            imports.close()
        }
    }

    val leave = {
        closed = true
        imports.close()
        onBack()
    }
    val guardedBack = rememberUnsavedChangesBack(
        hasUnsavedChanges = draft != start || busy,
        onBack = leave,
    )
    BackHandler(enabled = !closed) { guardedBack() }

    fun importFile(uri: Uri, projector: Boolean) {
        importingProjector = projector
        scope.launch {
            try {
                val file = importLocalChatGguf(context, uri, projector, imports)
                if (!closed) {
                    if (file == null) {
                        showGgufError = true
                    } else if (projector) {
                        imports.discard(draft.projectorPath)
                        draft = draft.copy(projectorPath = file.path)
                        error = null
                    } else {
                        imports.discard(draft.modelPath)
                        draft = draft.copy(modelPath = file.path)
                        error = null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DebugLog.e("LocalChatModelEditor", "GGUF import failed", failure)
                if (!closed) showGgufError = true
            } finally {
                if (!closed) importingProjector = null
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val projector = pickingProjector
        pickingProjector = null
        if (!closed && projector != null && uri != null) importFile(uri, projector)
    }
    fun pick(projector: Boolean) {
        if (closed || pickingProjector != null || importingProjector != null) return
        pickingProjector = projector
        picker.launch(arrayOf("*/*"))
    }

    fun save() {
        if (closed || pickingProjector != null || importingProjector != null) return
        error = draft.validate { id ->
            viewModel.modelManager.isLocalModelIdTaken(id, excludeId = initial?.id)
        }
        if (error != null) return
        val config = draft.toConfig(initial)
        if (initial == null) {
            viewModel.modelManager.addLocalChatModel(config)
        } else {
            viewModel.modelManager.updateLocalChatModel(
                uuid = initial.id,
                newModelId = config.modelId,
                newAlias = config.alias,
                nCtx = config.nCtx,
                temperature = config.temperature,
                topP = config.topP,
                maxTokens = config.maxTokens,
                mmprojPath = config.mmprojPath,
            )
        }
        // ModelManager now owns these paths; disposal must never unlink a saved model.
        imports.transfer(config.localFilePath, config.mmprojPath)
        leave()
    }

    val errorText = error?.let { stringResource(it.messageResource()) }
    val idError = error in setOf(
        LocalChatModelDraftError.ID_REQUIRED,
        LocalChatModelDraftError.ID_INVALID,
        LocalChatModelDraftError.ID_TAKEN,
    )
    fun update(next: LocalChatModelDraft) {
        draft = next
        error = null
    }

    CollapsingSettingsScaffold(
        title = stringResource(
            if (initial == null) R.string.add_local_chat_model else R.string.embedding_edit_model,
        ),
        onBack = guardedBack,
        actions = {
            IconButton(onClick = ::save, enabled = !busy && !closed) {
                Icon(Icons.Rounded.Save, stringResource(R.string.save))
            }
        },
        floatingActionButton = { if (showDocFab) DocumentationFab("provider.md") },
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.embedding_general),
                items = buildList {
                    if (initial == null) {
                        add {
                            ModelImportItem(
                                title = stringResource(R.string.local_chat_import_title),
                                filePath = draft.modelPath,
                                description = stringResource(R.string.local_chat_import_desc),
                                icon = LocalModelIcon,
                                importing = importingProjector == false,
                                enabled = !busy && !closed,
                                onClick = { pick(false) },
                            )
                        }
                    }
                    add {
                        SettingsIconContent(icon = Icons.Rounded.Tag) {
                            McpLabeledField(
                                label = stringResource(R.string.model_id_label),
                                value = draft.modelId,
                                onValueChange = { update(draft.copy(modelId = it)) },
                                isError = idError,
                                supportingText = errorText.takeIf { idError },
                            )
                        }
                    }
                    add {
                        SettingsIconContent(icon = Icons.Rounded.Label) {
                            McpLabeledField(
                                label = stringResource(R.string.model_alias_label),
                                value = draft.alias,
                                onValueChange = { update(draft.copy(alias = it)) },
                            )
                        }
                    }
                    add {
                        ModelImportItem(
                            title = stringResource(R.string.local_projector_title),
                            filePath = draft.projectorPath,
                            description = stringResource(R.string.local_projector_desc),
                            icon = Icons.Rounded.Visibility,
                            emptyIcon = Icons.Rounded.VisibilityOff,
                            importedEffect = stringResource(R.string.local_projector_enabled),
                            importing = importingProjector == true,
                            enabled = !busy && !closed,
                            onClick = { pick(true) },
                            onRemove = {
                                imports.discard(draft.projectorPath)
                                update(draft.copy(projectorPath = ""))
                            },
                        )
                    }
                },
            )
            SettingsGroup(
                title = stringResource(R.string.advanced_title),
                items = listOf(
                    {
                        SettingsIconContent(icon = Icons.Rounded.Memory) {
                            McpLabeledField(
                                label = stringResource(R.string.local_ctx_size),
                                value = draft.nCtx,
                                onValueChange = { update(draft.copy(nCtx = it)) },
                                keyboardType = KeyboardType.Number,
                                isError = error == LocalChatModelDraftError.CONTEXT,
                            )
                        }
                    },
                    {
                        SettingsIconContent(icon = Icons.Rounded.Tune) {
                            McpLabeledField(
                                label = stringResource(R.string.local_temperature),
                                value = draft.temperature,
                                onValueChange = { update(draft.copy(temperature = it)) },
                                keyboardType = KeyboardType.Decimal,
                                isError = error == LocalChatModelDraftError.TEMPERATURE,
                            )
                        }
                    },
                    {
                        SettingsIconContent(icon = Icons.Rounded.Tune) {
                            McpLabeledField(
                                label = stringResource(R.string.local_top_p),
                                value = draft.topP,
                                onValueChange = { update(draft.copy(topP = it)) },
                                keyboardType = KeyboardType.Decimal,
                                isError = error == LocalChatModelDraftError.TOP_P,
                            )
                        }
                    },
                    {
                        SettingsIconContent(icon = Icons.Rounded.Tune) {
                            McpLabeledField(
                                label = stringResource(R.string.local_max_tokens),
                                value = draft.maxTokens,
                                onValueChange = { update(draft.copy(maxTokens = it)) },
                                keyboardType = KeyboardType.Number,
                                isError = error == LocalChatModelDraftError.MAX_TOKENS ||
                                    error == LocalChatModelDraftError.OUTPUT_EXCEEDS_CONTEXT,
                            )
                        }
                    },
                ),
            )
            if (errorText != null && !idError) {
                Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (showDocFab) Spacer(Modifier.height(80.dp))
    }

    if (showGgufError && !closed) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showGgufError = false },
            title = { Text(stringResource(R.string.import_invalid_gguf_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.import_invalid_gguf_desc)) },
            confirmButton = {
                TextButton(onClick = { showGgufError = false }) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}

private fun LocalChatModelDraftError.messageResource(): Int = when (this) {
    LocalChatModelDraftError.ID_REQUIRED -> R.string.local_model_error_id_required
    LocalChatModelDraftError.ID_INVALID -> R.string.local_model_error_id_invalid
    LocalChatModelDraftError.ID_TAKEN -> R.string.local_model_error_id_taken
    LocalChatModelDraftError.MODEL_REQUIRED -> R.string.local_model_error_file_required
    LocalChatModelDraftError.CONTEXT -> R.string.local_model_error_context
    LocalChatModelDraftError.TEMPERATURE -> R.string.local_model_error_temperature
    LocalChatModelDraftError.TOP_P -> R.string.local_model_error_top_p
    LocalChatModelDraftError.MAX_TOKENS -> R.string.local_model_error_max_tokens
    LocalChatModelDraftError.OUTPUT_EXCEEDS_CONTEXT -> R.string.local_model_error_output
}
