package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.SpeechEngineType
import com.newoether.agora.data.SpeechRecognitionConfig
import com.newoether.agora.speech.LocalSpeechModelStatus
import com.newoether.agora.speech.LocalSpeechModels
import com.newoether.agora.speech.LocalSpeechSource
import com.newoether.agora.speech.SpeechCloudPreset
import com.newoether.agora.speech.SpeechCloudPresets
import com.newoether.agora.speech.SpeechLanguages
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.rememberUnsavedChangesBack
import com.newoether.agora.viewmodel.ChatViewModel

/** Everything editable on the page; compared with its start value to guard back navigation. */
private data class SpeechDraft(
    val presetId: String,
    val baseUrl: String,
    val model: String,
    val useSavedKey: Boolean,
    val apiKey: String,
    val name: String,
    val language: String,
    val vocabulary: String,
    val preferOffline: Boolean,
    val downloadSource: String,
)

private fun savedKeyFor(viewModel: ChatViewModel, preset: SpeechCloudPreset): String? {
    val provider = preset.savedKeyProvider ?: return null
    return viewModel.settings.apiKeys.value
        .firstOrNull { it.provider.equals(provider, ignoreCase = true) }
        ?.key?.takeIf { it.isNotBlank() }
}

/**
 * Full-page editor for one recognizer. Cloud entries pick a vendor preset, endpoint, key and model;
 * system entries only choose language and the on-device preference; local entries pick and
 * download an ONNX model, keeping its id in [SpeechRecognitionConfig.presetId].
 */
@Composable
internal fun SpeechRecognitionEditor(
    initial: SpeechRecognitionConfig,
    isNew: Boolean,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val isCloud = initial.type == SpeechEngineType.CLOUD
    val isLocal = initial.type == SpeechEngineType.LOCAL
    val start = remember(initial.id) {
        val preset = SpeechCloudPresets.byId(initial.presetId)
        val saved = savedKeyFor(viewModel, preset)
        SpeechDraft(
            presetId = if (isLocal) {
                (LocalSpeechModels.byId(initial.presetId) ?: LocalSpeechModels.all.first()).id
            } else {
                preset.id
            },
            baseUrl = initial.baseUrl,
            model = initial.model,
            useSavedKey = saved != null && (isNew || initial.apiKey == saved),
            apiKey = initial.apiKey,
            name = initial.name,
            language = initial.language,
            vocabulary = initial.vocabulary,
            preferOffline = initial.preferOffline,
            downloadSource = LocalSpeechSource.fromId(initial.downloadSource).id,
        )
    }
    var draft by remember(initial.id) { mutableStateOf(start) }
    val preset = SpeechCloudPresets.byId(draft.presetId)
    val localModel = if (isLocal) LocalSpeechModels.byId(draft.presetId) ?: LocalSpeechModels.all.first() else null
    val localStatuses by viewModel.speechInput.localModels.status.collectAsState()
    val localInstalled = localModel != null && localStatuses[localModel.id] == LocalSpeechModelStatus.Installed
    val showLanguage = localModel == null || localModel.languages.isNotEmpty()
    val showVocabulary = isCloud || localModel?.supportsVocabulary == true
    val savedKey = remember(draft.presetId) { savedKeyFor(viewModel, preset) }
    val usingSavedKey = savedKey != null && draft.useSavedKey
    val effectiveKey = if (usingSavedKey) savedKey!! else draft.apiKey.trim()
    val systemName = stringResource(R.string.speech_type_system)
    val defaultName = when {
        isCloud -> draft.model.trim().ifBlank { preset.displayName }
        localModel != null -> localModel.displayName
        else -> systemName
    }
    val canSave = when {
        isCloud -> draft.baseUrl.isNotBlank() && draft.model.isNotBlank() &&
            (!preset.keyRequired || effectiveKey.isNotBlank())
        isLocal -> localInstalled
        else -> true
    }

    val guardedBack = rememberUnsavedChangesBack(hasUnsavedChanges = draft != start, onBack = onBack)

    fun save() {
        if (!canSave) return
        viewModel.speechInput.upsert(
            initial.copy(
                name = draft.name.trim().ifBlank { defaultName },
                protocol = if (isCloud) preset.protocol else initial.protocol,
                presetId = when {
                    isCloud -> preset.id
                    localModel != null -> localModel.id
                    else -> ""
                },
                baseUrl = if (isCloud) draft.baseUrl.trim() else "",
                model = when {
                    isCloud -> draft.model.trim()
                    localModel != null -> localModel.displayName
                    else -> ""
                },
                apiKey = if (isCloud) effectiveKey else "",
                language = if (showLanguage) draft.language else "",
                vocabulary = if (showVocabulary) draft.vocabulary.trim() else "",
                preferOffline = initial.type == SpeechEngineType.SYSTEM && draft.preferOffline,
                downloadSource = if (isLocal) draft.downloadSource else "",
            ),
        )
        onBack()
    }

    CollapsingSettingsScaffold(
        title = stringResource(if (isNew) R.string.speech_add else R.string.speech_edit),
        onBack = guardedBack,
        scrollState = rememberSettingsScrollState(),
        actions = {
            IconButton(onClick = ::save, enabled = canSave) {
                Icon(Icons.Rounded.Save, stringResource(R.string.save))
            }
        },
    ) {
        SettingsGroupColumn {
            if (localModel != null) {
                LocalSpeechModelGroup(
                    store = viewModel.speechInput.localModels,
                    model = localModel,
                    source = LocalSpeechSource.fromId(draft.downloadSource),
                    onModelChange = { next ->
                        draft = draft.copy(
                            presetId = next.id,
                            language = draft.language.takeIf { it in next.languages }.orEmpty(),
                        )
                    },
                    onSourceChange = { draft = draft.copy(downloadSource = it.id) },
                )
            }
            if (isCloud) {
                SettingsGroup(
                    title = stringResource(R.string.speech_connection),
                    items = buildList {
                        add {
                            val customLabel = stringResource(R.string.speech_preset_custom)
                            SpeechDropdownItem(
                                title = stringResource(R.string.speech_provider),
                                description = stringResource(R.string.speech_provider_desc),
                                icon = Icons.Rounded.Api,
                                selectedLabel = if (preset.custom) customLabel else preset.displayName,
                                options = SpeechCloudPresets.all.map { p ->
                                    p.id to (if (p.custom) customLabel else p.displayName)
                                },
                                selectedKey = preset.id,
                                onSelect = { id ->
                                    if (id != draft.presetId) {
                                        val next = SpeechCloudPresets.byId(id)
                                        draft = draft.copy(
                                            presetId = next.id,
                                            baseUrl = next.baseUrl,
                                            model = next.models.firstOrNull().orEmpty(),
                                            useSavedKey = true,
                                            apiKey = "",
                                        )
                                    }
                                },
                            )
                        }
                        add {
                            SettingsIconContent(icon = Icons.Rounded.Link) {
                                McpLabeledField(
                                    label = stringResource(R.string.speech_base_url),
                                    value = draft.baseUrl,
                                    onValueChange = { draft = draft.copy(baseUrl = it) },
                                    keyboardType = KeyboardType.Uri,
                                    placeholder = "https://api.example.com/v1",
                                )
                            }
                        }
                        if (savedKey != null) {
                            add {
                                SettingsItem(
                                    headlineContent = {
                                        Text(stringResource(R.string.embedding_use_saved_key, preset.savedKeyProvider.orEmpty()))
                                    },
                                    supportingContent = { Text(stringResource(R.string.speech_use_saved_key_desc)) },
                                    leadingContent = {
                                        Icon(Icons.Rounded.VpnKey, null, tint = MaterialTheme.colorScheme.primary)
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = draft.useSavedKey,
                                            onCheckedChange = { draft = draft.copy(useSavedKey = it) },
                                        )
                                    },
                                    modifier = Modifier.clickable { draft = draft.copy(useSavedKey = !draft.useSavedKey) },
                                )
                            }
                        }
                        if (!usingSavedKey) {
                            add {
                                SettingsIconContent(icon = Icons.Rounded.VpnKey) {
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
                        add {
                            SettingsIconContent(icon = Icons.Rounded.Tag) {
                                var modelMenu by remember { mutableStateOf(false) }
                                McpLabeledField(
                                    label = stringResource(R.string.model_id_label),
                                    value = draft.model,
                                    onValueChange = { draft = draft.copy(model = it) },
                                    placeholder = preset.models.firstOrNull() ?: "whisper-1",
                                    trailingContent = if (preset.models.size > 1) {
                                        {
                                            Box {
                                                IconButton(onClick = { modelMenu = true }) {
                                                    Icon(Icons.Rounded.ArrowDropDown, stringResource(R.string.speech_model_presets))
                                                }
                                                AgoraDropdownMenu(
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                    tonalElevation = 16.dp,
                                                    expanded = modelMenu,
                                                    onDismissRequest = { modelMenu = false },
                                                ) {
                                                    preset.models.forEach { model ->
                                                        AgoraDropdownMenuItem(
                                                            text = { Text(model) },
                                                            onClick = {
                                                                draft = draft.copy(model = model)
                                                                modelMenu = false
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    },
                )
            }
            SettingsGroup(
                title = stringResource(R.string.speech_recognition_options),
                items = buildList {
                    if (showLanguage) add {
                        val autoLabel = stringResource(R.string.speech_language_auto)
                        val choices = localModel?.let { m -> SpeechLanguages.choices.filter { it.first in m.languages } }
                            ?: SpeechLanguages.choices
                        val options = listOf("" to autoLabel) + choices
                        val current = options.firstOrNull { it.first == draft.language }?.second ?: draft.language
                        SpeechDropdownItem(
                            title = stringResource(R.string.speech_language),
                            description = stringResource(R.string.speech_language_desc),
                            icon = Icons.Rounded.Translate,
                            selectedLabel = current,
                            options = options,
                            selectedKey = draft.language,
                            onSelect = { draft = draft.copy(language = it) },
                        )
                    }
                    if (showVocabulary) {
                        add {
                            SettingsIconContent(icon = Icons.Rounded.Spellcheck) {
                                McpLabeledField(
                                    label = stringResource(R.string.speech_vocabulary),
                                    value = draft.vocabulary,
                                    onValueChange = { draft = draft.copy(vocabulary = it) },
                                    supportingText = stringResource(R.string.speech_vocabulary_desc),
                                    placeholder = "Agora, Kotlin, Compose",
                                )
                            }
                        }
                    }
                    if (initial.type == SpeechEngineType.SYSTEM) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.speech_prefer_offline)) },
                                supportingContent = { Text(stringResource(R.string.speech_prefer_offline_desc)) },
                                leadingContent = {
                                    Icon(Icons.Rounded.WifiOff, null, tint = MaterialTheme.colorScheme.primary)
                                },
                                trailingContent = {
                                    Switch(
                                        checked = draft.preferOffline,
                                        onCheckedChange = { draft = draft.copy(preferOffline = it) },
                                    )
                                },
                                modifier = Modifier.clickable { draft = draft.copy(preferOffline = !draft.preferOffline) },
                            )
                        }
                    }
                    add {
                        SettingsIconContent(icon = Icons.Rounded.Label) {
                            McpLabeledField(
                                label = stringResource(R.string.model_name_label),
                                value = draft.name,
                                onValueChange = { draft = draft.copy(name = it) },
                                placeholder = defaultName.ifBlank { null },
                            )
                        }
                    }
                },
            )
        }
    }
}

/** A settings row whose right-hand label opens a single-choice menu. */
@Composable
internal fun SpeechDropdownItem(
    title: String,
    description: String,
    icon: ImageVector,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Box {
                Text(
                    selectedLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
                AgoraDropdownMenu(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 16.dp,
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    options.forEach { (key, label) ->
                        AgoraDropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = {
                                if (key == selectedKey) {
                                    Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            onClick = {
                                onSelect(key)
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
