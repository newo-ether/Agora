package com.newoether.agora.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.apiModelName
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.components.providerIcon
import com.newoether.agora.util.Constants
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel

// Shape constants matching SettingsGroup's per-position rounding.
// Each encodes top-corners / bottom-corners for its place in the group.
private val FullRounded   = RoundedCornerShape(24.dp)
private val TopRounded    = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 5.dp, bottomEnd = 5.dp)
private val BottomRounded = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
private val MidRounded    = RoundedCornerShape(5.dp)
private val FlatShape     = RoundedCornerShape(0.dp)
private val FlatToBottom  = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
private val FiveTop       = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)
private val FiveBottom    = RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsModelsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val availableModels by viewModel.settings.availableModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val selectedModel by viewModel.settings.selectedModel.collectAsState()
    var showActiveModelDialog by remember { mutableStateOf(false) }
    var showModelAliasDialog by remember { mutableStateOf<String?>(null) }
    val expandedProviders = remember { mutableStateMapOf<String, Boolean>() }
    var searchQuery by remember { mutableStateOf("") }
    var selectedProviderFilter by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    val lastFingerprint by viewModel.settings.lastModelsFetchFingerprint.collectAsState()

    val availableProvidersList = remember(availableModels) {
        availableModels.filter { it.value.isNotEmpty() }.keys.toList().sorted()
    }

    val providers = remember(availableModels, searchQuery, modelAliases, selectedProviderFilter) {
        val parsedList = availableModels.entries.filter { it.value.isNotEmpty() }.map { (name, modelsList) ->
            name to modelsList.map { model ->
                val parsed = com.newoether.agora.model.ModelId.parse(model)
                val alias = modelAliases[model]
                val displayName = alias ?: parsed.apiModelName
                ParsedModel(model, parsed, displayName)
            }
        }
        val providerFiltered = if (selectedProviderFilter == null) {
            parsedList
        } else {
            parsedList.filter { it.first == selectedProviderFilter }
        }
        if (searchQuery.isBlank()) {
            providerFiltered
        } else {
            providerFiltered.map { (name, parsedModels) ->
                val filtered = parsedModels.filter {
                    it.displayName.contains(searchQuery, ignoreCase = true) || it.rawId.contains(searchQuery, ignoreCase = true)
                }
                name to filtered
            }.filter { it.second.isNotEmpty() }
        }
    }

    // Auto-fetch models when entering the page if provider config has changed
    LaunchedEffect(Unit) {
        val current = viewModel.computeProviderFingerprint()
        if (current != lastFingerprint) {
            viewModel.fetchAvailableModels()
        }
    }

    // Scroll to search bar on text query or provider filter changes
    LaunchedEffect(searchQuery, selectedProviderFilter) {
        if (searchQuery.isNotEmpty() || selectedProviderFilter != null) {
            listState.animateScrollToItem(4)
        }
    }

    CollapsingSettingsLazyScaffold(
        title = stringResource(R.string.models_title),
        onBack = onBack,
        listState = listState,
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
                val activeAlias = modelAliases[selectedModel]
                val activeParsed = com.newoether.agora.model.ModelId.parse(selectedModel)
                val providerName = activeParsed.providerName
                val activeDisplayName = activeAlias ?: activeParsed.apiModelName
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
                            { Text(providerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) }
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

            // ── Available Models section ──
            item(key = "section_available_title") {
                SectionLabel(
                    text = stringResource(R.string.models_available),
                    firstInPage = false
                )
            }

            item(key = "search_bar") {
                CardSurface(
                    shape = FullRounded,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(stringResource(R.string.models_search_placeholder)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                    )
                }
            }

            if (availableProvidersList.size > 1) {
                item(key = "provider_filters") {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = selectedProviderFilter == null,
                                onClick = { selectedProviderFilter = null },
                                label = { Text(stringResource(R.string.models_filter_all_providers)) }
                            )
                        }
                        items(availableProvidersList) { name ->
                            val isSelected = selectedProviderFilter == name
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedProviderFilter = if (isSelected) null else name },
                                label = { Text(name) },
                                leadingIcon = {
                                    val iconRes = providerIcon(name)
                                    val isLocal = name.equals(Constants.PROVIDER_LOCAL, ignoreCase = true)
                                    when {
                                        isLocal -> Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                                        iconRes != 0 -> Icon(painterResource(iconRes), null, modifier = Modifier.size(18.dp))
                                        else -> Icon(Icons.Default.Cloud, null, modifier = Modifier.size(18.dp))
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Sync button – always first in the Available card
            val hasProviders = providers.isNotEmpty()
            item(key = "sync") {
                CardSurface(
                    shape = if (hasProviders) TopRounded else FullRounded
                ) {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.models_sync)) },
                        supportingContent = { Text(stringResource(R.string.models_sync_desc)) },
                        leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { viewModel.fetchAvailableModels() }
                    )
                }
            }

            // Providers
            for ((providerIndex, entry) in providers.withIndex()) {
                val (name, models) = entry
                val isExpanded = searchQuery.isNotBlank() || (expandedProviders[name] ?: false)
                val isLastProvider = providerIndex == providers.lastIndex

                // ── Provider header ──
                item(key = "hdr_$name") {
                    val collapsedRadiusDp = if (isLastProvider) 24f else 5f
                    val targetBottomRadius = if (isExpanded) 0.dp else collapsedRadiusDp.dp
                    val bottomRadius by animateDpAsState(
                        targetValue = targetBottomRadius,
                        label = "radius_$name"
                    )
                    val headerShape = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = bottomRadius, bottomEnd = bottomRadius)

                    CardSurface(shape = headerShape, addTopGap = true) {
                        val headerIconRes = providerIcon(name)
                        val isLocalHeader = name.equals(Constants.PROVIDER_LOCAL, ignoreCase = true)
                        SettingsItem(
                            headlineContent = { Text(name) },
                            supportingContent = { Text(stringResource(R.string.models_count, models.size)) },
                            leadingContent = {
                                when {
                                    isLocalHeader -> Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                    headerIconRes != 0 -> Icon(painterResource(headerIconRes), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                    else -> Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                }
                            },
                            trailingContent = {
                                Icon(
                                    if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier.clickable {
                                expandedProviders[name] = !isExpanded
                            }
                        )
                    }
                }

                // ── Model block items (inlined dynamically directly into LazyColumn) ──
                if (isExpanded) {
                    itemsIndexed(
                        items = models,
                        key = { _, model -> "model_${name}_${model.rawId}" }
                    ) { modelIndex, model ->
                        val isLastModel = modelIndex == models.lastIndex
                        val modelShape = when {
                            isLastModel && isLastProvider -> FlatToBottom
                            isLastModel -> FiveBottom
                            else -> FlatShape
                        }

                        ModelItemRow(
                            parsedModel = model,
                            isEnabled = enabledModels.contains(model.rawId),
                            modelShape = modelShape,
                            onRenameClick = { showModelAliasDialog = model.rawId },
                            onCheckedChange = { isChecked ->
                                viewModel.settings.setEnabledModels(
                                    if (isChecked) enabledModels + model.rawId else enabledModels - model.rawId
                                )
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
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
                    items(enabledModels.toList()) { model ->
                        val alias = modelAliases[model]
                        val parsed = com.newoether.agora.model.ModelId.parse(model)
                        val displayName = alias ?: parsed.apiModelName
                        val providerName = parsed.providerName

                        SettingsItem(
                            headlineContent = {
                                Text(displayName, fontWeight = if (model == selectedModel) FontWeight.Bold else FontWeight.Normal)
                            },
                            supportingContent = {
                                Text(providerName, style = MaterialTheme.typography.bodySmall)
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

    // ── Model Alias Dialog ──
    showModelAliasDialog?.let { model ->
        val aliasState = rememberTextFieldState(modelAliases[model] ?: "")

        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showModelAliasDialog = null },
            title = { Text(stringResource(R.string.models_rename), fontWeight = FontWeight.Bold) },
            text = {
                val parsed = com.newoether.agora.model.ModelId.parse(model)
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.models_rename_current, parsed.apiModelName), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.noOpBringIntoView()) {
                        OutlinedTextField(
                            state = aliasState,
                            label = { Text(stringResource(R.string.models_alias_hint)) },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(parsed.apiModelName) }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.settings.updateModelAlias(model, aliasState.text.toString())
                    showModelAliasDialog = null
                }) { Text(stringResource(R.string.provider_save)) }
            },
            dismissButton = { TextButton(onClick = { showModelAliasDialog = null }) { Text(stringResource(R.string.provider_cancel)) } }
        )
    }
}

/**
 * Section title matching SettingsGroup's label style.
 * [firstInPage] = true for the first section on the page (no extra top gap);
 * subsequent sections get a 24dp gap above to match SettingsGroup's bottom padding.
 */
@Composable
private fun SectionLabel(text: String, firstInPage: Boolean) {
    val topPadding = if (firstInPage) 12.dp else 36.dp
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = topPadding, bottom = 12.dp)
    )
}

/**
 * A single Surface card matching SettingsGroup's style.
 * [addTopGap] adds a 2dp gap above when true (for items after the first in a group).
 */
@Composable
private fun CardSurface(
    shape: Shape,
    addTopGap: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(if (addTopGap) Modifier.padding(top = 2.dp) else Modifier)
    ) {
        content()
    }
}

private data class ParsedModel(
    val rawId: String,
    val parsedId: com.newoether.agora.model.ModelId,
    val displayName: String
)

@Composable
private fun ModelItemRow(
    parsedModel: ParsedModel,
    isEnabled: Boolean,
    modelShape: Shape,
    onRenameClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    CardSurface(
        shape = modelShape,
        addTopGap = false,
        modifier = modifier
    ) {
        SettingsItem(
            headlineContent = { Text(parsedModel.displayName) },
            supportingContent = if (parsedModel.displayName != parsedModel.parsedId.apiModelName) {
                { Text(parsedModel.parsedId.apiModelName) }
            } else null,
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRenameClick) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.models_rename),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Checkbox(checked = isEnabled, onCheckedChange = onCheckedChange)
                }
            },
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}
