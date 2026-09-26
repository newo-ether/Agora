package com.newoether.agora.ui.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.CircularBackButton
import com.newoether.agora.ui.settings.datacontrol.SettingsDataControlPage
import com.newoether.agora.viewmodel.ChatViewModel

internal val SettingsTwoPaneMinWidth = 840.dp
private val SettingsNavigationPaneWidth = 400.dp
private val SettingsNavigationPaneShape = RoundedCornerShape(
    topEnd = 24.dp,
    bottomEnd = 24.dp,
)

@Composable
internal fun SettingsTwoPaneScreen(
    viewModel: ChatViewModel,
    settingsGroups: List<SettingsGroupData>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        SettingsNavigationPane(
            settingsGroups = settingsGroups,
            selectedCategory = selectedCategory,
            onCategorySelected = onCategorySelected,
            onBack = onBack,
            modifier = Modifier
                .width(SettingsNavigationPaneWidth)
                .fillMaxHeight()
                .clip(SettingsNavigationPaneShape),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = SettingsContentMaxWidth)
                    .fillMaxWidth()
                    .clipToBounds(),
            ) {
                CompositionLocalProvider(LocalSettingsPaneBackButtonVisible provides false) {
                    Crossfade(
                        targetState = selectedCategory,
                        animationSpec = tween(durationMillis = 250),
                        label = "settingsCategory",
                    ) { category ->
                        SettingsDestination(
                            category = category,
                            viewModel = viewModel,
                            onBack = onBack,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsNavigationPane(
    settingsGroups: List<SettingsGroupData>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 20.dp, top = statusBarTop + 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularBackButton(
                onClick = onBack,
                contentDescription = stringResource(R.string.back),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp,
                end = 12.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            settingsGroups.forEach { group ->
                group.titleRes?.let { titleRes ->
                    item("header:$titleRes") {
                        Text(
                            text = stringResource(titleRes),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp),
                        )
                    }
                }
                group.items.forEach { category ->
                    item(category.key) {
                        SettingsNavigationItem(
                            category = category,
                            selected = category.key == selectedCategory,
                            onClick = { onCategorySelected(category.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsNavigationItem(
    category: SettingsCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 250),
        label = "settingsNavigationContainer",
    )
    val primaryContentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 250),
        label = "settingsNavigationPrimaryContent",
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(durationMillis = 250),
        label = "settingsNavigationIcon",
    )
    val supportingContentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 250),
        label = "settingsNavigationSupportingContent",
    )
    Surface(
        color = containerColor,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (category.iconRes != null) {
                Icon(
                    painter = painterResource(category.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = iconColor,
                )
            } else {
                Icon(
                    imageVector = checkNotNull(category.icon),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = iconColor,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(category.titleRes),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = primaryContentColor,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(category.descriptionRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = supportingContentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun SettingsCategoryHome(
    settingsGroups: List<SettingsGroupData>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onBack: () -> Unit,
    onCategorySelected: (String) -> Unit,
) {
    CollapsingSettingsLazyScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        listState = listState,
    ) {
        items(settingsGroups.size) { groupIndex ->
            val group = settingsGroups[groupIndex]
            Column(modifier = Modifier.fillMaxWidth()) {
                group.titleRes?.let { titleRes ->
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                group.items.forEachIndexed { index, category ->
                    if (index > 0) Spacer(modifier = Modifier.height(2.dp))
                    val shape = when {
                        group.items.size == 1 -> RoundedCornerShape(24.dp)
                        index == 0 -> RoundedCornerShape(
                            topStart = 24.dp, topEnd = 24.dp,
                            bottomStart = 5.dp, bottomEnd = 5.dp,
                        )
                        index == group.items.lastIndex -> RoundedCornerShape(
                            topStart = 5.dp, topEnd = 5.dp,
                            bottomStart = 24.dp, bottomEnd = 24.dp,
                        )
                        else -> RoundedCornerShape(5.dp)
                    }
                    Surface(
                        shape = shape,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .clickable { onCategorySelected(category.key) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (category.iconRes != null) {
                                Icon(
                                    painter = painterResource(category.iconRes),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = checkNotNull(category.icon),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(category.titleRes),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.Medium,
                                    ),
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = stringResource(category.descriptionRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (groupIndex < settingsGroups.lastIndex) {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
internal fun SettingsDestination(
    category: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    when (category) {
        "provider" -> SettingsProviderPage(viewModel, onBack)
        "prompts" -> SettingsPromptsPage(viewModel, onBack)
        "models" -> SettingsModelsPage(viewModel, onBack)
        "generation" -> SettingsGenerationPage(viewModel, onBack)
        "context" -> SettingsContextPage(viewModel, onBack)
        "websearch" -> SettingsWebSearchPage(viewModel, onBack)
        "imagegen" -> SettingsImageGenPage(viewModel, onBack)
        "shell" -> SettingsShellPage(viewModel, onBack)
        "interaction" -> SettingsInteractionPage(viewModel, onBack)
        "mcp" -> SettingsMcpPage(viewModel, onBack)
        "automation" -> SettingsAutomationPage(viewModel, onBack)
        "proxy" -> SettingsProxyPage(viewModel, onBack)
        "language" -> SettingsLanguagePage(viewModel, onBack)
        "titlegen" -> SettingsTitleGenPage(viewModel, onBack)
        "transcription" -> SettingsTranscriptionPage(viewModel, onBack)
        "search" -> SettingsSearchPage(viewModel, onBack)
        "memory" -> SettingsMemoryPage(viewModel, onBack)
        "skills" -> SettingsSkillsPage(viewModel, onBack)
        "datacontrol" -> SettingsDataControlPage(viewModel, onBack)
        "appearance" -> SettingsAppearancePage(viewModel, onBack)
        "developer" -> SettingsDeveloperPage(viewModel, onBack, onDisabled = onBack)
        "about" -> SettingsAboutPage(viewModel, onBack)
        else -> SettingsProviderPage(viewModel, onBack)
    }
}
