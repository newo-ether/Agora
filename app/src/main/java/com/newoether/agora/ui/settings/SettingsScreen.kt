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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
import com.newoether.agora.PHONE_USE_SUPPORTED
import com.newoether.agora.R
import com.newoether.agora.phone.PhoneUseIcon
import com.newoether.agora.ui.common.LocalSnackbarMessage
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
        SettingsGroupLabel(title)
        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                }
                val shape = settingsGroupRowShape(index, items.size)
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

/** Group heading above a list of [SettingsGroup] rows. */
@Composable
internal fun SettingsGroupLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/** Corner shape of row [index] in a group of [count] rows, for lists that place the rows themselves
 *  into a LazyColumn instead of handing the whole group to [SettingsGroup]. */
internal fun settingsGroupRowShape(index: Int, count: Int): Shape = when {
    count == 1 -> RoundedCornerShape(24.dp)
    index == 0 -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 5.dp, bottomEnd = 5.dp)
    index == count - 1 -> RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
    else -> RoundedCornerShape(5.dp)
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

/** SettingsItem side padding for rows using sheetOptionClickable: 16dp less the 8dp sheet inset. */
internal val SETTINGS_ITEM_SHEET_PADDING = 16.dp - com.newoether.agora.ui.components.SHEET_OPTION_INSET

@Composable
fun SettingsItem(
    modifier: Modifier = Modifier,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    leadingSpacing: Dp = 16.dp,
    startPadding: Dp = 16.dp,
    endPadding: Dp = 16.dp,
) {
    val verticalPadding = if (supportingContent == null) 12.dp else 16.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = startPadding,
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
                imageVector = Icons.Rounded.Add,
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
        SettingsCategory("provider", R.string.settings_provider, R.string.settings_provider_desc, Icons.Rounded.Api),
        SettingsCategory("models", R.string.settings_models, R.string.settings_models_desc, Icons.Rounded.Layers),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_responses, items = listOf(
        SettingsCategory("prompts", R.string.settings_prompts, R.string.settings_prompts_desc, Icons.Rounded.Psychology),
        SettingsCategory("generation", R.string.settings_generation, R.string.settings_generation_desc, Icons.Rounded.Tune),
        SettingsCategory("context", R.string.context_title, R.string.context_desc, Icons.Rounded.Memory),
        SettingsCategory("titlegen", R.string.settings_title_gen, R.string.settings_title_gen_desc, Icons.Rounded.Edit),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_multimodal, items = listOf(
        SettingsCategory("transcription", R.string.settings_transcription, R.string.settings_transcription_desc, Icons.Rounded.ImageSearch),
        SettingsCategory("imagegen", R.string.settings_image_gen, R.string.settings_image_gen_desc, Icons.Rounded.AddPhotoAlternate),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_tools, items = listOf(
        SettingsCategory("websearch", R.string.settings_web_search, R.string.settings_web_search_desc, Icons.Rounded.Language),
        SettingsCategory("search", R.string.search_title, R.string.search_desc, Icons.Rounded.Search),
        SettingsCategory("shell", R.string.shell_title, R.string.shell_desc, Icons.Rounded.Terminal),
        SettingsCategory(
            "mcp",
            R.string.mcp_title,
            R.string.mcp_desc,
            iconRes = R.drawable.ic_mcp,
        ),
        SettingsCategory("phoneuse", R.string.phone_title, R.string.phone_description, PhoneUseIcon),
        SettingsCategory("automation", R.string.settings_automation, R.string.settings_automation_desc, Icons.Rounded.Repeat),
        SettingsCategory("todo", R.string.settings_todo, R.string.settings_todo_desc, Icons.Rounded.Checklist),
        SettingsCategory("subagents", R.string.settings_subagents, R.string.settings_subagents_desc, Icons.Rounded.AccountTree),
        SettingsCategory("interaction", R.string.settings_interaction, R.string.settings_interaction_desc, Icons.Rounded.QuestionAnswer),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_network, items = listOf(
        SettingsCategory("proxy", R.string.settings_proxy, R.string.settings_proxy_desc, Icons.Rounded.Lan),
        SettingsCategory("webui", R.string.settings_webui, R.string.settings_webui_desc, Icons.Rounded.Web),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_memory_data, items = listOf(
        SettingsCategory("memory", R.string.settings_memory, R.string.settings_memory_desc, Icons.Rounded.Description),
        SettingsCategory("skills", R.string.settings_skills, R.string.settings_skills_desc, Icons.Rounded.Extension),
        SettingsCategory("datacontrol", R.string.settings_data_control, R.string.settings_data_control_desc, Icons.Rounded.Storage),
        SettingsCategory("usage", R.string.settings_usage, R.string.settings_usage_desc, Icons.Rounded.BarChart),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_appearance_language, items = listOf(
        SettingsCategory("appearance", R.string.settings_appearance, R.string.settings_appearance_desc, Icons.Rounded.Palette),
        SettingsCategory("language", R.string.language_title, R.string.language_desc, Icons.Rounded.Translate),
    )),
)

internal val developerSettingsGroup = SettingsGroupData(
    titleRes = R.string.settings_group_developer,
    items = listOf(
        SettingsCategory(
            "developer",
            R.string.settings_developer,
            R.string.settings_developer_desc,
            Icons.Rounded.BugReport,
        ),
    ),
)

internal val aboutSettingsGroup = SettingsGroupData(
    titleRes = R.string.settings_group_about,
    items = listOf(
        SettingsCategory("about", R.string.settings_about, R.string.settings_about_desc, Icons.Rounded.Info),
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
            addAll(baseSettingsGroups.map { group ->
                if (PHONE_USE_SUPPORTED) group
                else group.copy(items = group.items.filterNot { it.key == "phoneuse" })
            })
            if (developerOptionsEnabled) add(developerSettingsGroup)
            add(aboutSettingsGroup)
        }
    }
    val listState = rememberLazyListState()
    val navigationListState = rememberLazyListState()
    val scrollRetention = remember { SettingsScrollRetention() }
    CompositionLocalProvider(
        LocalSettingsScrollRetention provides scrollRetention,
        LocalSnackbarMessage provides { message -> viewModel.emitSnackbar(message) },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val twoPane = maxWidth >= SettingsTwoPaneMinWidth
            BackHandler {
                if (twoPane || selectedCategory == null) onBack() else selectedCategory = null
            }
            if (twoPane) {
                SettingsTwoPaneScreen(
                    viewModel = viewModel,
                    settingsGroups = settingsGroups,
                    navigationListState = navigationListState,
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
}
