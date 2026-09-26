package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.newoether.agora.R
import com.newoether.agora.ui.settings.datacontrol.SettingsDataControlPage
import com.newoether.agora.viewmodel.ChatViewModel

/** When true, [SettingsGroup] inside a [SettingsGroupColumn] suppresses its own bottom padding
 *  (spacing is handled by the column's [Arrangement.spacedBy] instead). */
val LocalSettingsGroupSpacing = staticCompositionLocalOf { false }
internal val LocalSettingsPaneBackButtonVisible = staticCompositionLocalOf { true }

@Composable
internal fun SettingsSecondaryPane(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettingsPaneBackButtonVisible provides true, content = content)
}

/** Settings page content container: uniform 24dp spacing between groups (and any other elements),
 *  with zero trailing after the last element. */
@Composable
fun SettingsGroupColumn(
    modifier: Modifier = Modifier,
    spacing: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    CompositionLocalProvider(LocalSettingsGroupSpacing provides true) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
    }
}

@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 24.dp,
    items: List<@Composable () -> Unit>
) {
    val effectiveBottom = if (LocalSettingsGroupSpacing.current) 0.dp else bottomPadding
    Column(modifier = modifier.fillMaxWidth().padding(bottom = effectiveBottom)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                }
                val isFirst = index == 0
                val isLast = index == items.lastIndex
                val shape = when {
                    items.size == 1 -> RoundedCornerShape(24.dp)
                    isFirst -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 5.dp, bottomEnd = 5.dp)
                    isLast -> RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
                    else -> RoundedCornerShape(5.dp)
                }
                Surface(
                    shape = shape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item()
                }
            }
        }
    }
}

/** Shared body for a [SettingsGroup] item with a primary-tinted leading icon and content column. */
@Composable
fun SettingsIconContent(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), content = content)
        }
    }
}

@Composable
fun SettingsItem(
    modifier: Modifier = Modifier,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    leadingSpacing: Dp = 16.dp,
    endPadding: Dp = 16.dp,
) {
    val verticalPadding = if (supportingContent == null) 12.dp else 16.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                end = endPadding,
                top = verticalPadding,
                bottom = verticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingContent != null) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                leadingContent()
            }
            Spacer(modifier = Modifier.width(leadingSpacing))
        }
        Column(modifier = Modifier.weight(1f)) {
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                LocalContentColor provides MaterialTheme.colorScheme.onSurface
            ) {
                headlineContent()
            }
            if (supportingContent != null) {
                Spacer(modifier = Modifier.height(3.dp))
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    supportingContent()
                }
            }
        }
        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(16.dp))
            trailingContent()
        }
    }
}

/**
 * Canonical centered add action for settings groups.
 *
 * Keep this aligned with the settings row contract instead of recreating its
 * dimensions in individual pages.
 */
@Composable
fun SettingsAddItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

internal data class SettingsCategory(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector? = null,
    @DrawableRes val iconRes: Int? = null,
)

internal data class SettingsGroupData(
    val titleRes: Int? = null,
    val items: List<SettingsCategory>
)

internal val baseSettingsGroups = listOf(
    SettingsGroupData(titleRes = R.string.settings_group_services, items = listOf(
        SettingsCategory("provider", R.string.settings_provider, R.string.settings_provider_desc, Icons.Default.Cloud),
        SettingsCategory("models", R.string.settings_models, R.string.settings_models_desc, Icons.Default.Chat),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_responses, items = listOf(
        SettingsCategory("prompts", R.string.settings_prompts, R.string.settings_prompts_desc, Icons.Default.Psychology),
        SettingsCategory("generation", R.string.settings_generation, R.string.settings_generation_desc, Icons.Default.Tune),
        SettingsCategory("context", R.string.context_title, R.string.context_desc, Icons.Default.Memory),
        SettingsCategory("titlegen", R.string.settings_title_gen, R.string.settings_title_gen_desc, Icons.Default.Edit),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_multimodal, items = listOf(
        SettingsCategory("transcription", R.string.settings_transcription, R.string.settings_transcription_desc, Icons.Default.ImageSearch),
        SettingsCategory("imagegen", R.string.settings_image_gen, R.string.settings_image_gen_desc, Icons.Default.AddPhotoAlternate),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_tools, items = listOf(
        SettingsCategory("websearch", R.string.settings_web_search, R.string.settings_web_search_desc, Icons.Default.Language),
        SettingsCategory("search", R.string.search_title, R.string.search_desc, Icons.Default.Search),
        SettingsCategory("shell", R.string.shell_title, R.string.shell_desc, Icons.Default.Terminal),
        SettingsCategory(
            "mcp",
            R.string.mcp_title,
            R.string.mcp_desc,
            iconRes = R.drawable.ic_mcp,
        ),
        SettingsCategory("automation", R.string.settings_automation, R.string.settings_automation_desc, Icons.Default.Repeat),
        SettingsCategory("interaction", R.string.settings_interaction, R.string.settings_interaction_desc, Icons.Default.QuestionAnswer),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_network, items = listOf(
        SettingsCategory("proxy", R.string.settings_proxy, R.string.settings_proxy_desc, Icons.Default.Lan),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_memory_data, items = listOf(
        SettingsCategory("memory", R.string.settings_memory, R.string.settings_memory_desc, Icons.Default.Description),
        SettingsCategory("skills", R.string.settings_skills, R.string.settings_skills_desc, Icons.Default.Extension),
        SettingsCategory("datacontrol", R.string.settings_data_control, R.string.settings_data_control_desc, Icons.Default.Storage),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_appearance_language, items = listOf(
        SettingsCategory("appearance", R.string.settings_appearance, R.string.settings_appearance_desc, Icons.Default.Palette),
        SettingsCategory("language", R.string.language_title, R.string.language_desc, Icons.Default.Translate),
    )),
)

internal val developerSettingsGroup = SettingsGroupData(
    titleRes = R.string.settings_group_developer,
    items = listOf(
        SettingsCategory(
            "developer",
            R.string.settings_developer,
            R.string.settings_developer_desc,
            Icons.Default.BugReport,
        ),
    ),
)

internal val aboutSettingsGroup = SettingsGroupData(
    titleRes = R.string.settings_group_about,
    items = listOf(
        SettingsCategory("about", R.string.settings_about, R.string.settings_about_desc, Icons.Default.Info),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    initialCategory: String? = null,
) {
    var selectedCategory by rememberSaveable { mutableStateOf(initialCategory) }
    LaunchedEffect(initialCategory) {
        if (initialCategory != null) selectedCategory = initialCategory
    }
    val developerOptionsEnabled by viewModel.settings.developerOptionsEnabled.collectAsState()
    val settingsGroups = remember(developerOptionsEnabled) {
        buildList {
            addAll(baseSettingsGroups)
            if (developerOptionsEnabled) add(developerSettingsGroup)
            add(aboutSettingsGroup)
        }
    }
    val listState = rememberLazyListState()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= SettingsTwoPaneMinWidth
        BackHandler {
            if (twoPane || selectedCategory == null) onBack() else selectedCategory = null
        }
        if (twoPane) {
            SettingsTwoPaneScreen(
                viewModel = viewModel,
                settingsGroups = settingsGroups,
                selectedCategory = selectedCategory ?: "provider",
                onCategorySelected = { selectedCategory = it },
                onBack = onBack,
            )
        } else {
            GuardedAnimatedContent(
                targetState = selectedCategory,
                forward = selectedCategory != null,
            ) { category ->
                if (category != null) {
                    SettingsDestination(
                        category = category,
                        viewModel = viewModel,
                        onBack = { selectedCategory = null },
                    )
                } else {
                    SettingsCategoryHome(
                        settingsGroups = settingsGroups,
                        listState = listState,
                        onBack = onBack,
                        onCategorySelected = { selectedCategory = it },
                    )
                }
            }
        }
    }
}
