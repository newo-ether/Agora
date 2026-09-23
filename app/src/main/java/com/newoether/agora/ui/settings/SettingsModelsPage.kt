package com.newoether.agora.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.modelAliasDisplayName
import com.newoether.agora.data.modelApiDisplayName
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.model.ModelId
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.components.providerIcon
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.util.Constants
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel

// Shape constants matching SettingsGroup's per-position rounding.
// Each encodes top-corners / bottom-corners for its place in the group.
private val FullRounded   = RoundedCornerShape(24.dp)
private val TopRounded    = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 5.dp, bottomEnd = 5.dp)
private val BottomRounded = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
private val MidRounded    = RoundedCornerShape(5.dp)
internal val FlatShape     = RoundedCornerShape(0.dp)
internal val FlatToBottom  = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
internal val FiveBottom    = RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp)

private val RemoteModelProviders = listOf(
    Constants.PROVIDER_GOOGLE,
    Constants.PROVIDER_OPENAI,
    Constants.PROVIDER_ANTHROPIC,
    Constants.PROVIDER_DEEPSEEK,
    Constants.PROVIDER_QWEN,
    Constants.PROVIDER_GROQ,
    Constants.PROVIDER_OLLAMA,
    Constants.PROVIDER_OPEN_ROUTER,
    Constants.PROVIDER_OPENCODE_GO, Constants.PROVIDER_REQUESTY,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsModelsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val availableModels by viewModel.settings.availableModels.collectAsState()
    val customModels by viewModel.settings.customModels.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val modelProviderNames by viewModel.settings.modelProviderNames.collectAsState()
    val selectedModel by viewModel.settings.selectedModel.collectAsState()
    val isSyncingModels by viewModel.isSyncingModels.collectAsState()
    var showActiveModelDialog by remember { mutableStateOf(false) }
    var showModelAliasDialog by remember { mutableStateOf<String?>(null) }
    var showCustomModelDialog by remember { mutableStateOf(false) }
    var editingCustomModel by remember { mutableStateOf<String?>(null) }
    var deletingCustomModel by remember { mutableStateOf<String?>(null) }
    var customModelProvider by rememberSaveable { mutableStateOf("") }
    var customModelId by rememberSaveable { mutableStateOf("") }
    var customModelAlias by rememberSaveable { mutableStateOf("") }
    var customModelRawAlias by rememberSaveable { mutableStateOf("") }
    var customModelProviderMenuExpanded by remember { mutableStateOf(false) }
    var modelSearchQuery by rememberSaveable { mutableStateOf("") }
    val expandedProviders = remember { mutableStateMapOf<String, MutableTransitionState<Boolean>>() }
    val modelBlockHeights = remember { mutableStateMapOf<String, Float>() }

    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    val lastFingerprint by viewModel.settings.lastModelsFetchFingerprint.collectAsState()
    val providerChoices = remember(customProviders) {
        (RemoteModelProviders + customProviders.map { it.name }).distinct()
    }
    val manualProviderOrder = remember(customProviders) {
        RemoteModelProviders + customProviders.map(CustomProviderConfig::providerId)
    }
    val manualModelGroups = remember(customModels, manualProviderOrder) {
        customModelGroups(customModels, manualProviderOrder)
    }
    val autoFetchedModelGroups = remember(
        availableModels,
        customModels,
        modelAliases,
        modelSearchQuery,
    ) {
        fetchedModelGroups(
            availableModels = availableModels,
            customModels = customModels,
            modelAliases = modelAliases,
            query = modelSearchQuery,
        )
    }
    val normalizedSearchQuery = modelSearchQuery.trim()
    val searchActive = normalizedSearchQuery.isNotEmpty()
    val autoProviderStateKeys = remember(availableModels) {
        availableModels.keys.mapTo(linkedSetOf()) { providerName -> "auto:$providerName" }
    }

    LaunchedEffect(providerChoices) {
        if (customModelProvider !in providerChoices) {
            customModelProvider = providerChoices.firstOrNull().orEmpty()
        }
    }

    // Search-driven bulk changes are intentional jump cuts. Replacing the transition-state
    // identity lets the content start at its destination, while header taps keep mutating the
    // same state object and therefore retain their normal expand/collapse animation.
    LaunchedEffect(searchActive) {
        val knownAutoProviders = expandedProviders.keys
            .filterTo(linkedSetOf()) { it.startsWith("auto:") }
            .apply { addAll(autoProviderStateKeys) }
        knownAutoProviders.forEach { providerStateKey ->
            expandedProviders[providerStateKey] = MutableTransitionState(searchActive)
            if (!searchActive) modelBlockHeights[providerStateKey] = 0f
        }
    }

    // A provider fetched during an active search joins expanded without replaying the bulk
    // transition or reopening a provider that the user manually collapsed.
    LaunchedEffect(searchActive, autoProviderStateKeys) {
        if (searchActive) {
            autoProviderStateKeys.forEach { providerStateKey ->
                if (providerStateKey !in expandedProviders) {
                    expandedProviders[providerStateKey] = MutableTransitionState(true)
                }
            }
        }
    }

    // Auto-fetch models when entering the page if provider config has changed
    LaunchedEffect(Unit) {
        val current = viewModel.computeProviderFingerprint()
        if (current != lastFingerprint) {
            viewModel.fetchAvailableModels()
        }
    }

    CollapsingSettingsLazyScaffold(
        title = stringResource(R.string.models_title),
        onBack = onBack,
        contentHorizontalPadding = 0.dp,
        floatingActionButton = { if (showDocFab) DocumentationFab("models.md") }
    ) {
            // ── Default Model section ──
            item(key = "section_default_title") {
                SectionLabel(
                    text = stringResource(R.string.models_default),
                    firstInPage = true
                )
            }

            item(key = "default_model") {
                val activeParsed = com.newoether.agora.model.ModelId.parse(selectedModel)
                val providerName = providerDisplayName(
                    activeParsed.providerName,
                    customProviders,
                )
                val activeDisplayName = modelAliasDisplayName(
                    selectedModel,
                    modelAliases,
                    customProviders,
                )
                val activeIconRes = providerIcon(providerName)
                val isActiveLocal = providerName.equals(Constants.PROVIDER_LOCAL, ignoreCase = true)
                val hasEnabledModels = enabledModels.isNotEmpty()

                CardSurface(shape = FullRounded) {
                    SettingsItem(
                        headlineContent = {
                            Text(
                                if (!hasEnabledModels) stringResource(R.string.models_no_models) else activeDisplayName,
                                color = if (!hasEnabledModels) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        supportingContent = if (hasEnabledModels) {
                            {
                                Text(
                                    providerName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = 0.6f,
                                    ),
                                )
                            }
                        } else null,
                        leadingContent = {
                            val tint = if (hasEnabledModels) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            when {
                                !hasEnabledModels -> Icon(Icons.Default.Chat, null, tint = tint, modifier = Modifier.size(24.dp))
                                isActiveLocal -> Icon(Icons.Default.AutoAwesome, null, tint = tint, modifier = Modifier.size(24.dp))
                                activeIconRes != 0 -> Icon(painterResource(activeIconRes), null, tint = tint, modifier = Modifier.size(24.dp))
                                else -> Icon(Icons.Default.Chat, null, tint = tint, modifier = Modifier.size(24.dp))
                            }
                        },
                        modifier = Modifier.heightIn(min = 66.dp).clickable(enabled = hasEnabledModels) { showActiveModelDialog = true }
                    )
                }
            }

            // ── Manually added models ──
            item(key = "section_manual_title") {
                SectionLabel(
                    text = stringResource(R.string.models_manual),
                    firstInPage = false
                )
            }

            if (manualModelGroups.isEmpty()) {
                item(key = "manual_empty") {
                    CardSurface(shape = TopRounded) {
                        SettingsItem(
                            headlineContent = {
                                Text(
                                    stringResource(R.string.models_manual_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Chat,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                            },
                            modifier = Modifier.heightIn(min = 64.dp),
                        )
                    }
                }
            } else {
                modelProviderGroups(
                    keyPrefix = "manual",
                    groups = manualModelGroups,
                    firstHeaderStartsSection = true,
                    lastGroupClosesSection = false,
                    allowSpatialTransitions = allowSpatialTransitions,
                    searchActive = false,
                    enabledModels = enabledModels,
                    modelAliases = modelAliases,
                    customProviders = customProviders,
                    expandedProviders = expandedProviders,
                    modelBlockHeights = modelBlockHeights,
                    onAliasClick = null,
                    onDetailsClick = { model ->
                        val parsed = ModelId.parse(model)
                        editingCustomModel = model
                        customModelProvider = providerDisplayName(
                            parsed.providerName,
                            customProviders,
                        )
                        customModelId = parsed.modelName
                        customModelRawAlias = modelAliases[model].orEmpty()
                        customModelAlias = modelAliasDisplayName(
                            model,
                            modelAliases,
                            customProviders,
                        )
                        showCustomModelDialog = true
                    },
                    onEnabledChange = { model, enabled ->
                        viewModel.settings.setEnabledModels(
                            if (enabled) enabledModels + model else enabledModels - model
                        )
                    },
                )
            }

            item(key = "manual_add") {
                CardSurface(shape = BottomRounded, addTopGap = true) {
                    SettingsAddItem(
                        label = stringResource(R.string.models_add_custom),
                        onClick = {
                            editingCustomModel = null
                            customModelId = ""
                            customModelAlias = ""
                            customModelRawAlias = ""
                            if (customModelProvider !in providerChoices) {
                                customModelProvider = providerChoices.firstOrNull().orEmpty()
                            }
                            showCustomModelDialog = true
                        },
                    )
                }
            }

            // ── Automatically fetched models ──
            item(key = "section_auto_fetch_title") {
                SectionLabel(
                    text = stringResource(R.string.models_auto_fetch),
                    firstInPage = false
                )
            }

            item(key = "sync") {
                CardSurface(shape = TopRounded) {
                    SettingsItem(
                        headlineContent = {
                            Crossfade(
                                targetState = isSyncingModels,
                                animationSpec = tween(durationMillis = 250),
                                label = "modelSyncHeadline",
                            ) { syncing ->
                                Text(
                                    stringResource(
                                        if (syncing) R.string.models_syncing else R.string.models_sync
                                    )
                                )
                            }
                        },
                        supportingContent = { Text(stringResource(R.string.models_sync_desc)) },
                        leadingContent = {
                            Crossfade(
                                targetState = isSyncingModels,
                                animationSpec = tween(durationMillis = 250),
                                label = "modelSyncLeading",
                            ) { syncing ->
                                if (syncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.5.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.clickable { viewModel.fetchAvailableModels() }
                    )
                }
            }

            item(key = "auto_search") {
                CardSurface(shape = MidRounded, addTopGap = true) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        OutlinedTextField(
                            value = modelSearchQuery,
                            onValueChange = { modelSearchQuery = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.models_search_hint)) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null)
                            },
                            trailingIcon = if (modelSearchQuery.isNotEmpty()) {
                                {
                                    IconButton(onClick = { modelSearchQuery = "" }) {
                                        Icon(
                                            Icons.Default.Clear,
                                            contentDescription =
                                                stringResource(R.string.models_clear_search),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                disabledBorderColor = Color.Transparent,
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (autoFetchedModelGroups.isEmpty()) {
                item(key = "auto_empty") {
                    CardSurface(shape = BottomRounded, addTopGap = true) {
                        SettingsItem(
                            headlineContent = {
                                Text(
                                    stringResource(
                                        if (normalizedSearchQuery.isEmpty()) {
                                            R.string.models_auto_empty
                                        } else {
                                            R.string.models_search_empty
                                        }
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                            },
                            modifier = Modifier.heightIn(min = 64.dp),
                        )
                    }
                }
            } else {
                modelProviderGroups(
                    keyPrefix = "auto",
                    groups = autoFetchedModelGroups,
                    firstHeaderStartsSection = false,
                    lastGroupClosesSection = true,
                    allowSpatialTransitions = allowSpatialTransitions,
                    searchActive = searchActive,
                    enabledModels = enabledModels,
                    modelAliases = modelAliases,
                    customProviders = customProviders,
                    expandedProviders = expandedProviders,
                    modelBlockHeights = modelBlockHeights,
                    onAliasClick = { showModelAliasDialog = it },
                    onDetailsClick = null,
                    onEnabledChange = { model, enabled ->
                        viewModel.settings.setEnabledModels(
                            if (enabled) enabledModels + model else enabledModels - model
                        )
                    },
                )
            }

            if (showDocFab) {
                item(key = "doc_spacer") { Spacer(modifier = Modifier.height(80.dp)) }
            }
    }

    // ── Active Model Dialog ──
    if (showActiveModelDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showActiveModelDialog = false },
            title = { Text(stringResource(R.string.models_select_default), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(enabledModels.toList(), key = { it }) { model ->
                        val parsed = com.newoether.agora.model.ModelId.parse(model)
                        val displayName = modelAliasDisplayName(
                            model,
                            modelAliases,
                            customProviders,
                        )
                        val providerName = providerDisplayName(
                            parsed.providerName,
                            customProviders,
                        )

                        SettingsItem(
                            headlineContent = {
                                Text(displayName, fontWeight = if (model == selectedModel) FontWeight.Bold else FontWeight.Normal)
                            },
                            supportingContent = {
                                Text(
                                    providerName,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = model == selectedModel,
                                    onClick = {
                                        viewModel.settings.setSelectedModel(model)
                                        showActiveModelDialog = false
                                    }
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setSelectedModel(model)
                                showActiveModelDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showActiveModelDialog = false }) { Text(stringResource(R.string.provider_close)) } }
        )
    }

    // ── Add / edit Custom Model Dialog ──
    if (showCustomModelDialog) {
        val originalModelId = editingCustomModel
        val normalizedProvider = customModelProvider.trim()
        val normalizedModelId = customModelId.trim()
        val normalizedAlias = customModelAlias.trim()
        val unchangedDisplayAlias = originalModelId?.let { model ->
            modelAliasDisplayName(model, modelAliases, customProviders)
        }.orEmpty()
        var showProviderName by remember(originalModelId) {
            mutableStateOf(modelProviderNames[originalModelId] != false)
        }
        val aliasToPersist = if (originalModelId != null) {
            modelAliasToPersist(
                rawAlias = customModelRawAlias,
                initialDisplayAlias = unchangedDisplayAlias,
                editedAlias = customModelAlias,
            )
        } else {
            normalizedAlias
        }
        val pendingModelId = if (
            normalizedProvider.isNotEmpty() &&
            normalizedModelId.isNotEmpty()
        ) {
            ModelId(normalizedProvider, normalizedModelId).prefixed
        } else {
            ""
        }
        val modelAlreadyExists =
            pendingModelId in customModels && pendingModelId != originalModelId
        val canSaveModel = pendingModelId.isNotEmpty() && !modelAlreadyExists

        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                customModelProviderMenuExpanded = false
                showCustomModelDialog = false
            },
            title = {
                Text(
                    stringResource(
                        if (originalModelId == null) {
                            R.string.models_add_custom
                        } else {
                            R.string.models_edit_custom
                        }
                    ),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CustomModelProviderPicker(
                        customModelProvider = customModelProvider,
                        customModelProviderMenuExpanded = customModelProviderMenuExpanded,
                        providerChoices = providerChoices,
                        onExpandedChange = { customModelProviderMenuExpanded = it },
                        onProviderChange = { customModelProvider = it },
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(modifier = Modifier.noOpBringIntoView()) {
                        OutlinedTextField(
                            value = customModelId,
                            onValueChange = { customModelId = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.model_id_label)) },
                            isError = modelAlreadyExists,
                            supportingText = if (modelAlreadyExists) {
                                { Text(stringResource(R.string.models_custom_exists)) }
                            } else {
                                null
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(modifier = Modifier.noOpBringIntoView()) {
                        OutlinedTextField(
                            value = customModelAlias,
                            onValueChange = { customModelAlias = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.models_alias_hint)) },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    ModelProviderNameSwitch(showProviderName) { showProviderName = it }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (originalModelId != null) {
                        TextButton(
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            onClick = {
                                customModelProviderMenuExpanded = false
                                showCustomModelDialog = false
                                deletingCustomModel = originalModelId
                            },
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(
                        onClick = {
                            customModelProviderMenuExpanded = false
                            showCustomModelDialog = false
                        }
                    ) {
                        Text(stringResource(R.string.provider_cancel))
                    }

                    TextButton(
                        enabled = canSaveModel,
                        onClick = {
                            if (originalModelId == null) {
                                viewModel.settings.addCustomModel(
                                    provider = normalizedProvider,
                                    modelName = normalizedModelId,
                                    alias = aliasToPersist,
                                    showProviderName = showProviderName,
                                )
                            } else {
                                viewModel.customModelConfiguration.updateModel(
                                    oldModelId = originalModelId,
                                    provider = normalizedProvider,
                                    modelId = normalizedModelId,
                                    alias = aliasToPersist,
                                    showProviderName = showProviderName,
                                )
                            }
                            customModelProviderMenuExpanded = false
                            showCustomModelDialog = false
                        },
                    ) {
                        Text(
                            stringResource(
                                if (originalModelId == null) {
                                    R.string.add
                                } else {
                                    R.string.save
                                }
                            )
                        )
                    }
                }
            },
        )
    }

    deletingCustomModel?.let { model ->
        val displayName = modelAliasDisplayName(model, modelAliases, customProviders)
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { deletingCustomModel = null },
            title = {
                Text(
                    stringResource(R.string.models_delete_custom_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    stringResource(
                        R.string.models_delete_custom_text,
                        displayName,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    onClick = {
                        viewModel.customModelConfiguration.deleteModel(model)
                        deletingCustomModel = null
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingCustomModel = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Model Alias Dialog ──
    showModelAliasDialog?.let { model ->
        val rawAlias = modelAliases[model].orEmpty()
        val displayAlias = modelAliasDisplayName(model, modelAliases, customProviders)
        val aliasState = rememberTextFieldState(displayAlias)
        var showProviderName by remember(model) { mutableStateOf(modelProviderNames[model] != false) }

        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showModelAliasDialog = null },
            title = { Text(stringResource(R.string.models_rename), fontWeight = FontWeight.Bold) },
            text = {
                val displayApiModelName = modelApiDisplayName(model, customProviders)
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.models_rename_current, displayApiModelName), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.noOpBringIntoView()) {
                        OutlinedTextField(
                            state = aliasState,
                            label = { Text(stringResource(R.string.models_alias_hint)) },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(displayApiModelName) }
                        )
                    }
                    ModelProviderNameSwitch(showProviderName) { showProviderName = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val editedAlias = aliasState.text.toString()
                    viewModel.settings.updateModelAlias(
                        model,
                        modelAliasToPersist(
                            rawAlias = rawAlias,
                            initialDisplayAlias = displayAlias,
                            editedAlias = editedAlias,
                        ),
                        showProviderName = showProviderName,
                    )
                    showModelAliasDialog = null
                }) { Text(stringResource(R.string.provider_save)) }
            },
            dismissButton = { TextButton(onClick = { showModelAliasDialog = null }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }
}
@Composable
private fun ModelProviderNameSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val rippleOutset = 8.dp
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(top = 8.dp)
            .layout { measurable, constraints ->
                val outset = rippleOutset.roundToPx()
                val placeable = measurable.measure(constraints.offset(horizontal = outset * 2))
                layout(placeable.width - outset * 2, placeable.height) {
                    placeable.placeRelative(-outset, 0)
                }
            }
            .clip(RoundedCornerShape(16.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = rippleOutset, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp, end = 12.dp)) {
            Text(
                stringResource(R.string.models_show_provider_name),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            Text(
                stringResource(R.string.models_show_provider_name_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
