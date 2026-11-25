package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.SpeechEngineType
import com.newoether.agora.data.SpeechRecognitionConfig
import com.newoether.agora.speech.LocalSpeechModels
import com.newoether.agora.speech.SpeechCloudPresets
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.ui.components.sheetOptionClickable
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareModalBottomSheet as ModalBottomSheet
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/** Which recognizer the editor shows; a null route is the list page itself. */
internal data class SpeechEditorRoute(val initial: SpeechRecognitionConfig, val isNew: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSpeechRecognitionPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    var editorRoute by remember { mutableStateOf<SpeechEditorRoute?>(null) }
    var pendingDelete by remember { mutableStateOf<SpeechRecognitionConfig?>(null) }
    var showTypePicker by remember { mutableStateOf(false) }
    var typeActionInFlight by remember { mutableStateOf(false) }
    var typeActionGeneration by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val motionPolicy = LocalAgoraMotionPolicy.current
    val typeSheetState = rememberModalBottomSheetState()

    // Same contract as the embedding type picker: the route changes on tap while the sheet
    // stays composed until its hide animation finishes.
    val pickType: (SpeechEngineType) -> Unit = { type ->
        if (!typeActionInFlight) {
            typeActionInFlight = true
            val generation = ++typeActionGeneration
            editorRoute = SpeechEditorRoute(newSpeechConfig(type), isNew = true)
            if (motionPolicy.allowSpatialTransitions) {
                scope.launch {
                    try {
                        typeSheetState.hide()
                    } finally {
                        if (typeActionGeneration == generation) {
                            showTypePicker = false
                            typeActionInFlight = false
                        }
                    }
                }
            } else {
                showTypePicker = false
                typeActionInFlight = false
            }
        }
    }

    BackHandler(enabled = editorRoute != null) { editorRoute = null }

    GuardedAnimatedContent(
        targetState = editorRoute,
        forward = editorRoute != null,
        contentKey = { route -> route?.initial?.id },
    ) { route ->
        if (route != null) {
            SettingsSecondaryPane {
                SpeechRecognitionEditor(
                    initial = route.initial,
                    isNew = route.isNew,
                    viewModel = viewModel,
                    onBack = { editorRoute = null },
                )
            }
        } else {
            SpeechRecognitionList(
                viewModel = viewModel,
                onBack = onBack,
                onAdd = {
                    typeActionGeneration += 1
                    typeActionInFlight = false
                    showTypePicker = true
                },
                onEdit = { editorRoute = SpeechEditorRoute(it, isNew = false) },
                onDelete = { pendingDelete = it },
            )
        }
    }

    if (showTypePicker) {
        ModalBottomSheet(
            onDismissRequest = {
                typeActionGeneration += 1
                typeActionInFlight = false
                showTypePicker = false
            },
            sheetState = typeSheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            DialogWindowEdgeToEdge()
            Text(
                text = stringResource(R.string.speech_choose_type),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            listOf(
                Triple(SpeechEngineType.CLOUD, R.string.speech_type_cloud, R.string.speech_type_cloud_desc),
                Triple(SpeechEngineType.LOCAL, R.string.speech_type_local, R.string.speech_type_local_desc),
                Triple(SpeechEngineType.SYSTEM, R.string.speech_type_system, R.string.speech_type_system_desc),
            ).forEach { (type, titleRes, descRes) ->
                SettingsItem(
                    headlineContent = { Text(stringResource(titleRes), fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Text(stringResource(descRes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    leadingContent = {
                        Icon(speechTypeIcon(type), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    startPadding = SETTINGS_ITEM_SHEET_PADDING,
                    endPadding = SETTINGS_ITEM_SHEET_PADDING,
                    modifier = Modifier.fillMaxWidth().sheetOptionClickable(enabled = !typeActionInFlight) {
                        pickType(type)
                    },
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    pendingDelete?.let { config ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.speech_delete_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.speech_delete_confirm, config.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.speechInput.delete(config.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

internal fun newSpeechConfig(type: SpeechEngineType): SpeechRecognitionConfig = when (type) {
    SpeechEngineType.CLOUD -> {
        val preset = SpeechCloudPresets.all.first()
        SpeechRecognitionConfig(
            name = "",
            type = type,
            protocol = preset.protocol,
            presetId = preset.id,
            baseUrl = preset.baseUrl,
            model = preset.models.firstOrNull().orEmpty(),
        )
    }
    SpeechEngineType.LOCAL -> {
        val model = LocalSpeechModels.all.first()
        SpeechRecognitionConfig(name = "", type = type, presetId = model.id, model = model.displayName)
    }
    SpeechEngineType.SYSTEM -> SpeechRecognitionConfig(name = "", type = type)
}

internal fun speechTypeIcon(type: SpeechEngineType) = when (type) {
    SpeechEngineType.CLOUD -> Icons.Rounded.Cloud
    SpeechEngineType.SYSTEM -> Icons.Rounded.PhoneAndroid
    SpeechEngineType.LOCAL -> Icons.Rounded.Memory
}

@Composable
private fun SpeechRecognitionList(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (SpeechRecognitionConfig) -> Unit,
    onDelete: (SpeechRecognitionConfig) -> Unit,
) {
    val settings by viewModel.speechInput.settings.collectAsState()
    var menuFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.configs.size) { menuFor = null }
    val activeId = settings.active?.id

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_speech_recognition),
        onBack = onBack,
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.speech_general),
                items = listOf {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.speech_enable)) },
                        supportingContent = { Text(stringResource(R.string.speech_enable_desc)) },
                        leadingContent = { Icon(Icons.Rounded.Mic, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = settings.enabled, onCheckedChange = viewModel.speechInput::setEnabled)
                        },
                        modifier = Modifier.clickable { viewModel.speechInput.setEnabled(!settings.enabled) },
                    )
                },
            )
            SettingsGroup(
                title = stringResource(R.string.speech_recognizers),
                items = buildList {
                    if (settings.configs.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = {
                                    Text(
                                        stringResource(R.string.speech_no_recognizers),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Rounded.MicNone,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    )
                                },
                                modifier = Modifier.heightIn(min = 64.dp),
                            )
                        }
                    }
                    settings.configs.forEach { config ->
                        add {
                            SettingsItem(
                                headlineContent = { Text(config.name) },
                                supportingContent = { Text(speechConfigSummary(config)) },
                                leadingContent = {
                                    RadioButton(
                                        selected = config.id == activeId,
                                        onClick = { viewModel.speechInput.setActive(config.id) },
                                    )
                                },
                                trailingContent = {
                                    Box {
                                        IconButton(onClick = { menuFor = config.id }) {
                                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.options))
                                        }
                                        AgoraDropdownMenu(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                            tonalElevation = 16.dp,
                                            expanded = menuFor == config.id,
                                            onDismissRequest = { menuFor = null },
                                        ) {
                                            AgoraDropdownMenuItem(
                                                text = { Text(stringResource(R.string.edit)) },
                                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                                onClick = {
                                                    menuFor = null
                                                    onEdit(config)
                                                },
                                            )
                                            AgoraDropdownMenuItem(
                                                text = {
                                                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                                                },
                                                onClick = {
                                                    menuFor = null
                                                    onDelete(config)
                                                },
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.clickable { viewModel.speechInput.setActive(config.id) },
                            )
                        }
                    }
                    add { SettingsAddItem(label = stringResource(R.string.speech_add), onClick = onAdd) }
                },
            )
        }
    }
}

@Composable
private fun speechConfigSummary(config: SpeechRecognitionConfig): String = when (config.type) {
    SpeechEngineType.CLOUD -> {
        val preset = SpeechCloudPresets.byId(config.presetId)
        val vendor = if (preset.custom) stringResource(R.string.speech_preset_custom) else preset.displayName
        listOf(vendor, config.model).filter { it.isNotBlank() }.joinToString(" · ")
    }
    SpeechEngineType.SYSTEM -> stringResource(
        if (config.preferOffline) R.string.speech_system_summary_offline else R.string.speech_type_system,
    )
    SpeechEngineType.LOCAL -> listOf(stringResource(R.string.speech_type_local), config.model)
        .filter { it.isNotBlank() }.joinToString(" · ")
}
