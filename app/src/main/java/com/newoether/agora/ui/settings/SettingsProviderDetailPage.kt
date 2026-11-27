package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.newoether.agora.ui.icons.LocalModelIcon
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.newoether.agora.util.rememberContentTextDirection
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem

/** Provider detail route: the Local model page, or the explicit-save remote Provider editor. */
@Composable
fun SettingsProviderDetailPage(
    providerName: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit = onBack,
) {
    if (providerName != Constants.PROVIDER_LOCAL) {
        ProviderRemoteEditor(
            providerName = providerName,
            viewModel = viewModel,
            onBack = onBack,
            onSaved = onSaved,
        )
        return
    }
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
            LocalProviderSettingsList(
                viewModel = viewModel,
                onBack = onBack,
                onAddModel = { editorRoute = LocalChatEditorRoute(null) },
                onEditModel = { editorRoute = LocalChatEditorRoute(it) },
            )
        }
    }
}

private data class LocalChatEditorRoute(val initial: LocalChatModelConfig?)

@Composable
private fun LocalProviderSettingsList(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onAddModel: () -> Unit,
    onEditModel: (LocalChatModelConfig) -> Unit,
) {
    val localChatModels by viewModel.settings.localChatModels.collectAsState()
    val localModelIdleRetentionMinutes by
        viewModel.settings.localModelIdleRetentionMinutes.collectAsState()
    val localLowContextModeEnabled by
        viewModel.settings.localLowContextModeEnabled.collectAsState()
    val localInferenceThreads by viewModel.settings.localInferenceThreads.collectAsState()
    val localKvCacheType by viewModel.settings.localKvCacheType.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    var showDeleteModelConfirm by remember { mutableStateOf<LocalChatModelConfig?>(null) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.local_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("provider.md") },
    ) {
            SettingsGroupColumn {
                SettingsGroup(
                    title = stringResource(R.string.local_models_title),
                    items = buildList {
                        if (localChatModels.isEmpty()) {
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.provider_no_local_models), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    supportingContent = { Text(stringResource(R.string.provider_no_local_models_desc), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                                    leadingContent = { Icon(Icons.Rounded.Layers, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                                    modifier = Modifier.heightIn(min = 64.dp)
                                )
                            }
                        }
                        localChatModels.forEach { model ->
                            var showMenu by remember { mutableStateOf(false) }
                            add {
                                SettingsItem(
                                    modifier = Modifier.clickable { showMenu = true },
                                    headlineContent = { Text(model.alias, fontWeight = FontWeight.Medium, style = LocalTextStyle.current.copy(textDirection = rememberContentTextDirection(model.alias))) },
                                    leadingContent = {
                                        Icon(
                                            LocalModelIcon,
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
                                                        Icon(Icons.Rounded.Visibility, null, modifier = Modifier.size(10.dp))
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
                                            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.options), modifier = Modifier.size(18.dp)) }
                                            AgoraDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 16.dp) {
                                                AgoraDropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { showMenu = false; onEditModel(model) })
                                                AgoraDropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; showDeleteModelConfirm = model })
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
                                        Icons.Rounded.Memory,
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
                        {
                            LocalInferenceThreadsSlider(
                                value = localInferenceThreads,
                                onValueChange = viewModel.settings::setLocalInferenceThreads,
                            )
                        },
                        {
                            LocalKvCacheTypeItem(
                                value = localKvCacheType,
                                onValueChange = viewModel.settings::setLocalKvCacheType,
                            )
                        },
                    ),
                )
            }
            if (showDocFab) Spacer(modifier = Modifier.height(80.dp))
    }

    showDeleteModelConfirm?.let { model ->
        AlertDialog(containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showDeleteModelConfirm = null }, title = { Text(stringResource(R.string.local_chat_delete_title), fontWeight = FontWeight.Bold) }, text = { Text(stringResource(R.string.local_chat_delete_text, model.alias)) }, confirmButton = { TextButton(onClick = { viewModel.modelManager.deleteLocalChatModel(model.id); showDeleteModelConfirm = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton(onClick = { showDeleteModelConfirm = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
