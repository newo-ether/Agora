package com.newoether.agora.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.ui.common.PersistedSliderFeedbackGate
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Switch
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.TextButton
import com.newoether.agora.ui.motion.Crossfade
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.ui.components.sheetOptionClickable
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareModalBottomSheet as ModalBottomSheet
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

private data class SearchMethodOption(val key: String, @androidx.annotation.StringRes val labelRes: Int)

private val searchMethods = listOf(
    SearchMethodOption("keyword", R.string.search_method_keyword),
    SearchMethodOption(Constants.SEARCH_METHOD_RAG, R.string.search_method_rag)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSearchPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val embeddingModels by viewModel.settings.embeddingModels.collectAsState()
    val cacheRows by viewModel.ragManager.cacheRows.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    val embeddingModelIds = remember(embeddingModels) { embeddingModels.map(EmbeddingModelConfig::id) }
    LaunchedEffect(embeddingModelIds) { viewModel.ragManager.loadCacheCounts() }

    var editorRoute by remember { mutableStateOf<EmbeddingEditorRoute?>(null) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showRecacheConfirm by remember { mutableStateOf<String?>(null) }
    var showTypePicker by remember { mutableStateOf(false) }
    var typeActionInFlight by remember { mutableStateOf(false) }
    var typeActionGeneration by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val motionPolicy = LocalAgoraMotionPolicy.current
    val typeSheetState = rememberModalBottomSheetState()

    // Start the editor transition and the sheet dismissal from the same tap, keeping the sheet
    // composed until its hide animation completes (same contract as the prompt template picker).
    val pickType: (EmbeddingModelType) -> Unit = { type ->
        if (!typeActionInFlight) {
            typeActionInFlight = true
            val generation = ++typeActionGeneration
            editorRoute = newEmbeddingRoute(type)
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
                val modelId = route.initial.id
                EmbeddingModelEditor(
                    initial = route.initial,
                    isNew = route.isNew,
                    viewModel = viewModel,
                    cacheRow = cacheRows[modelId],
                    onCache = { viewModel.ragManager.cacheMessagesForModel(modelId) },
                    onRecache = { showRecacheConfirm = modelId },
                    onBack = { editorRoute = null },
                    showDocFab = showDocFab,
                )
            }
        } else {
            SearchSettingsList(
                viewModel = viewModel,
                showDocFab = showDocFab,
                onBack = onBack,
                onAddModel = {
                    typeActionGeneration += 1
                    typeActionInFlight = false
                    showTypePicker = true
                },
                onEditModel = { editorRoute = EmbeddingEditorRoute(it, isNew = false) },
                onDeleteModel = { showDeleteDialog = it },
                onRecacheModel = { showRecacheConfirm = it },
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
                text = stringResource(R.string.embedding_choose_type),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            listOf(
                Triple(EmbeddingModelType.REMOTE, R.string.embedding_type_remote, R.string.embedding_type_remote_desc),
                Triple(EmbeddingModelType.LOCAL, R.string.embedding_type_local, R.string.embedding_type_local_desc),
            ).forEach { (type, titleRes, descRes) ->
                SettingsItem(
                    headlineContent = { Text(stringResource(titleRes), fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Text(stringResource(descRes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    leadingContent = {
                        Icon(
                            if (type == EmbeddingModelType.REMOTE) Icons.Default.Cloud else Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
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

    showRecacheConfirm?.let { modelId ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showRecacheConfirm = null },
            title = { Text(stringResource(R.string.recache_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.recache_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.ragManager.cacheMessagesForModel(modelId, recache = true)
                    showRecacheConfirm = null
                }) { Text(stringResource(R.string.recache_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showRecacheConfirm = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    showDeleteDialog?.let { modelId ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(stringResource(R.string.delete_model_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.delete_model_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.ragManager.deleteEmbeddingModel(modelId)
                    showDeleteDialog = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun SearchSettingsList(
    viewModel: ChatViewModel,
    showDocFab: Boolean,
    onBack: () -> Unit,
    onAddModel: () -> Unit,
    onEditModel: (EmbeddingModelConfig) -> Unit,
    onDeleteModel: (String) -> Unit,
    onRecacheModel: (String) -> Unit,
) {
    val accessPastConversations by viewModel.settings.accessPastConversations.collectAsState()
    val autoCacheEnabled by viewModel.settings.autoCacheEnabled.collectAsState()
    val showUncachedNotification by viewModel.settings.showUncachedNotification.collectAsState()
    val modelSearchMethod by viewModel.settings.modelSearchMethod.collectAsState()
    val manualSearchMethod by viewModel.settings.manualSearchMethod.collectAsState()
    val embeddingModels by viewModel.settings.embeddingModels.collectAsState()
    val activeEmbeddingModelId by viewModel.settings.activeEmbeddingModelId.collectAsState()
    val cacheRows by viewModel.ragManager.cacheRows.collectAsState()
    val searchContextWindow by viewModel.settings.searchContextWindow.collectAsState()
    val searchMatchLimit by viewModel.settings.searchMatchLimit.collectAsState()
    val ragThreshold by viewModel.settings.ragThreshold.collectAsState()
    val cacheActionSize = rememberEmbeddingCacheActionSize()
    var showMenuForModel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(embeddingModels.size) { showMenuForModel = null }

    val searchContextGate = remember {
        PersistedSliderFeedbackGate(initialPersisted = searchContextWindow, toDisplay = Int::toFloat)
    }
    val searchMatchGate = remember {
        PersistedSliderFeedbackGate(initialPersisted = searchMatchLimit, toDisplay = Int::toFloat)
    }
    val ragThresholdGate = remember {
        PersistedSliderFeedbackGate(
            initialPersisted = ragThreshold,
            toDisplay = { it },
            equivalent = { left, right -> kotlin.math.abs(left - right) < 0.0001f },
        )
    }
    LaunchedEffect(searchContextWindow) { searchContextGate.reconcile(searchContextWindow) }
    LaunchedEffect(searchMatchLimit) { searchMatchGate.reconcile(searchMatchLimit) }
    LaunchedEffect(ragThreshold) { ragThresholdGate.reconcile(ragThreshold) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.search_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("search.md") }
    ) {
        SettingsGroupColumn {
            if (cacheRows.values.any { it.countFailed && it.cached == null && !it.workActive }) {
                Text(
                    text = "${stringResource(R.string.search_title)}: ${stringResource(R.string.tool_state_failed)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsGroup(
                title = stringResource(R.string.memory_access_title),
                items = listOf {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.memory_access_past)) },
                        supportingContent = { Text(stringResource(R.string.memory_access_past_desc)) },
                        leadingContent = { Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(
                                checked = accessPastConversations,
                                onCheckedChange = { viewModel.settings.setAccessPastConversations(it) },
                            )
                        },
                        modifier = Modifier.clickable {
                            viewModel.settings.setAccessPastConversations(!accessPastConversations)
                        }
                    )
                }
            )

            SettingsGroup(
                title = stringResource(R.string.auto_cache_title),
                items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.auto_cache)) },
                            supportingContent = { Text(stringResource(R.string.auto_cache_desc)) },
                            leadingContent = { Icon(Icons.Default.Cached, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(
                                    checked = autoCacheEnabled,
                                    onCheckedChange = { viewModel.ragManager.setAutoCacheEnabled(it) },
                                )
                            },
                            modifier = Modifier.clickable { viewModel.ragManager.setAutoCacheEnabled(!autoCacheEnabled) }
                        )
                    }
                    if (!autoCacheEnabled) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.show_uncached_notification)) },
                                supportingContent = { Text(stringResource(R.string.show_uncached_notification_desc)) },
                                leadingContent = {
                                    Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary)
                                },
                                trailingContent = {
                                    Switch(
                                        checked = showUncachedNotification,
                                        onCheckedChange = viewModel.settings::setShowUncachedNotification,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    viewModel.settings.setShowUncachedNotification(!showUncachedNotification)
                                }
                            )
                        }
                    }
                }
            )

            SettingsGroup(
                title = stringResource(R.string.search_methods_title),
                items = listOf(
                    {
                        SearchMethodItem(
                            titleRes = R.string.model_search_method,
                            descRes = R.string.model_search_method_desc,
                            icon = Icons.Default.AutoAwesome,
                            selected = modelSearchMethod,
                            ragAvailable = embeddingModels.isNotEmpty(),
                            onSelect = viewModel.settings::setModelSearchMethod,
                        )
                    },
                    {
                        SearchMethodItem(
                            titleRes = R.string.manual_search_method,
                            descRes = R.string.manual_search_method_desc,
                            icon = Icons.Default.ManageSearch,
                            selected = manualSearchMethod,
                            ragAvailable = embeddingModels.isNotEmpty(),
                            onSelect = viewModel.settings::setManualSearchMethod,
                        )
                    },
                )
            )

            SettingsGroup(
                title = stringResource(R.string.embedding_title),
                items = buildList {
                    if (embeddingModels.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = {
                                    Text(
                                        stringResource(R.string.no_embedding_models),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Chat,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    )
                                },
                                modifier = Modifier.heightIn(min = 64.dp)
                            )
                        }
                    }
                    embeddingModels.forEach { model ->
                        add {
                            val cacheRow = cacheRows[model.id]
                            SettingsItem(
                                headlineContent = { Text(model.name) },
                                supportingContent = { EmbeddingModelStatusText(model, cacheRow) },
                                leadingContent = {
                                    RadioButton(
                                        selected = model.id == activeEmbeddingModelId,
                                        onClick = { viewModel.ragManager.setActiveEmbeddingModel(model.id) }
                                    )
                                },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        EmbeddingCacheAction(
                                            modelId = model.id,
                                            cacheRow = cacheRow,
                                            size = cacheActionSize,
                                            onCache = { viewModel.ragManager.cacheMessagesForModel(model.id) },
                                            onRecache = { onRecacheModel(model.id) },
                                        )
                                        Box {
                                            IconButton(onClick = { showMenuForModel = model.id }) {
                                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.options))
                                            }
                                            AgoraDropdownMenu(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                tonalElevation = 16.dp,
                                                expanded = showMenuForModel == model.id,
                                                onDismissRequest = { showMenuForModel = null }
                                            ) {
                                                AgoraDropdownMenuItem(
                                                    text = { Text(stringResource(R.string.edit)) },
                                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                                    onClick = {
                                                        showMenuForModel = null
                                                        onEditModel(model)
                                                    }
                                                )
                                                AgoraDropdownMenuItem(
                                                    text = {
                                                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                                    },
                                                    onClick = {
                                                        showMenuForModel = null
                                                        onDeleteModel(model.id)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.clickable { viewModel.ragManager.setActiveEmbeddingModel(model.id) }
                            )
                        }
                    }
                    add {
                        SettingsAddItem(label = stringResource(R.string.embedding_add_model), onClick = onAddModel)
                    }
                }
            )

            SearchAdvancedSettings(
                searchContextWindow = searchContextWindow,
                searchMatchLimit = searchMatchLimit,
                ragThreshold = ragThreshold,
                searchContextGate = searchContextGate,
                searchMatchGate = searchMatchGate,
                ragThresholdGate = ragThresholdGate,
                onSearchContextWindowChange = viewModel.settings::setSearchContextWindow,
                onSearchMatchLimitChange = viewModel.settings::setSearchMatchLimit,
                onRagThresholdChange = viewModel.settings::setRagThreshold,
            )
        }
        if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

/** One "search method" row: label on the right opens a keyword / RAG menu. */
@Composable
private fun SearchMethodItem(
    @androidx.annotation.StringRes titleRes: Int,
    @androidx.annotation.StringRes descRes: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: String,
    ragAvailable: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsItem(
        headlineContent = { Text(stringResource(titleRes)) },
        supportingContent = { Text(stringResource(descRes)) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Box {
                Text(
                    searchMethods.find { it.key == selected }?.let { stringResource(it.labelRes) } ?: "",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(80.dp),
                    textAlign = TextAlign.Center
                )
                AgoraDropdownMenu(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 16.dp,
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    searchMethods.forEach { method ->
                        val disabled = method.key == Constants.SEARCH_METHOD_RAG && !ragAvailable
                        val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        AgoraDropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(method.labelRes),
                                    color = if (disabled) disabledColor else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            leadingIcon = {
                                if (selected == method.key) {
                                    Icon(
                                        Icons.Default.Check,
                                        null,
                                        tint = if (disabled) disabledColor else MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            onClick = {
                                if (!disabled) {
                                    onSelect(method.key)
                                    expanded = false
                                }
                            }
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable { expanded = true }
    )
}
