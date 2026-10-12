package com.newoether.agora.ui.settings

import android.annotation.SuppressLint
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.data.PredefinedVariables
import com.newoether.agora.data.PromptItemType
import com.newoether.agora.data.PromptTemplateItem
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareModalBottomSheet as ModalBottomSheet
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.sheetOptionClickable
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.motion.AnimatedContent

private fun variableDisplayName(key: String): String = when (key) {
    PredefinedVariables.TIME -> "Current Time"
    PredefinedVariables.DATE -> "Current Date"
    PredefinedVariables.SENT_TIME -> "Send Time"
    PredefinedVariables.SENT_DATE -> "Send Date"
    PredefinedVariables.ACTIVE_MEMORY -> "Active Memory"
    PredefinedVariables.SKILL_CATALOG -> "Skill Catalog"
    PredefinedVariables.CURRENT_MODEL_ID -> "Current Model ID"
    PredefinedVariables.MESSAGE_MODEL_ID -> "Message Model ID"
    PredefinedVariables.MODEL_ID -> "Current Model ID (Legacy)"
    PredefinedVariables.PROMPT -> "Prompt"
    else -> key
}

private fun variableIcon(key: String): ImageVector = when (key) {
    PredefinedVariables.TIME -> Icons.Default.Schedule
    PredefinedVariables.DATE -> Icons.Default.CalendarMonth
    PredefinedVariables.SENT_TIME -> Icons.Default.History
    PredefinedVariables.SENT_DATE -> Icons.Default.CalendarMonth
    PredefinedVariables.ACTIVE_MEMORY -> Icons.Default.Memory
    PredefinedVariables.SKILL_CATALOG -> Icons.Default.Extension
    PredefinedVariables.CURRENT_MODEL_ID,
    PredefinedVariables.MESSAGE_MODEL_ID,
    PredefinedVariables.MODEL_ID -> Icons.Default.Info
    PredefinedVariables.PROMPT -> Icons.Default.TextFields
    else -> Icons.Default.Info
}


@SuppressLint("UnusedContentLambdaTargetStateParameter")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptEditorPage(
    entry: SystemPromptEntry?,
    isNew: Boolean = false,
    saveEnabled: Boolean = true,
    onSave: (
        title: String,
        systemItems: List<PromptTemplateItem>,
        userItems: List<PromptTemplateItem>,
        assistantItems: List<PromptTemplateItem>,
    ) -> Unit,
    onBack: () -> Unit,
    showDocFab: Boolean = true
) {
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val isEdit = entry != null && !isNew
    var title by remember { mutableStateOf(entry?.title ?: "") }
    var selectedTab by remember { mutableIntStateOf(0) }

    val systemItems = remember {
        mutableStateListOf<PromptTemplateItem>().also { list ->
            entry?.resolvedSystemItems?.let { list.addAll(it) }
        }
    }
    val userItems = remember {
        mutableStateListOf<PromptTemplateItem>().also { list ->
            list.addAll(entry?.resolvedUserItems ?: PredefinedVariables.normalizeMessageTemplate(emptyList()))
        }
    }
    val assistantItems = remember {
        mutableStateListOf<PromptTemplateItem>().also { list ->
            list.addAll(entry?.resolvedAssistantItems ?: PredefinedVariables.normalizeMessageTemplate(emptyList()))
        }
    }

    var showVariablePicker by remember { mutableStateOf(false) }
    var insertAtIndex by remember { mutableIntStateOf(-1) }
    var titleError by remember { mutableStateOf(false) }

    val currentItems: MutableList<PromptTemplateItem> = when (selectedTab) {
        0 -> systemItems
        1 -> userItems
        else -> assistantItems
    }

    BackHandler(enabled = showVariablePicker) {
        showVariablePicker = false
    }
    BackHandler(enabled = !showVariablePicker) {
        onBack()
    }

    CollapsingSettingsScaffold(
        title = if (isEdit) stringResource(R.string.prompts_edit_title) else stringResource(R.string.prompts_add_title),
        onBack = onBack,
        actions = {
            IconButton(
                enabled = saveEnabled,
                onClick = {
                    if (title.isBlank()) {
                        titleError = true
                        return@IconButton
                    }
                    onSave(
                        title,
                        systemItems.toList(),
                        PredefinedVariables.normalizeMessageTemplate(userItems),
                        PredefinedVariables.normalizeMessageTemplate(assistantItems),
                    )
                },
            ) {
                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.provider_save))
            }
        },
        floatingActionButton = { if (showDocFab) DocumentationFab("system-prompts.md") }
    ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; titleError = false },
                label = { Text(stringResource(R.string.prompts_title_hint)) },
                isError = titleError,
                supportingText = if (titleError) {
                    { Text(stringResource(R.string.template_title_required)) }
                } else null,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            val tabLabels = listOf(
                stringResource(R.string.template_tab_system),
                stringResource(R.string.template_tab_user),
                stringResource(R.string.template_tab_assistant),
            )
            val tabDescriptions = listOf(
                stringResource(R.string.template_tab_system_desc),
                stringResource(R.string.template_tab_user_desc),
                stringResource(R.string.template_tab_assistant_desc),
            )
            PillTabSwitcher(
                tabs = tabLabels,
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
                allowLabelOverflow = true,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = tabDescriptions[selectedTab],
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Tab content
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    if (allowSpatialTransitions) {
                        (
                            fadeIn(
                                animationSpec = tween(
                                    durationMillis = 220,
                                    delayMillis = 90,
                                ),
                            ) +
                                scaleIn(
                                    initialScale = 0.92f,
                                    animationSpec = tween(
                                        durationMillis = 220,
                                        delayMillis = 90,
                                    ),
                                )
                            ).togetherWith(
                            fadeOut(animationSpec = tween(durationMillis = 90)),
                        )
                    } else {
                        fadeIn(animationSpec = tween(durationMillis = 220))
                            .togetherWith(
                                fadeOut(animationSpec = tween(durationMillis = 90)),
                            )
                            .using(
                                SizeTransform(
                                    clip = false,
                                    sizeAnimationSpec = { _, _ -> snap() },
                                ),
                            )
                    }
                },
            ) {
                Column {
                    if (currentItems.isEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                        ) {
                            Icon(
                                Icons.Default.TextFields,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.template_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }

                    for (i in currentItems.indices) {
                        val item = currentItems[i]
                        val isPrompt = PredefinedVariables.isPromptItem(item)

                        InsertBetweenButton(
                            onInsertText = {
                                currentItems.add(
                                    i,
                                    PromptTemplateItem(type = PromptItemType.CUSTOM, value = "")
                                )
                            },
                            onInsertVariable = { insertAtIndex = i; showVariablePicker = true }
                        )

                        TemplateItemRow(
                            item = item,
                            onChange = { updated -> currentItems[i] = updated },
                            onDelete = if (isPrompt) null else ({ currentItems.removeAt(i) }),
                            onMoveUp = if (!isPrompt && i > 0) {
                                {
                                    val moved = currentItems.removeAt(i)
                                    currentItems.add(i - 1, moved)
                                }
                            } else null,
                            onMoveDown = if (!isPrompt && i < currentItems.lastIndex) {
                                {
                                    val moved = currentItems.removeAt(i)
                                    currentItems.add(i + 1, moved)
                                }
                            } else null,
                        )
                    }

                    InsertBetweenButton(
                        onInsertText = {
                            currentItems.add(
                                PromptTemplateItem(
                                    type = PromptItemType.CUSTOM,
                                    value = ""
                                )
                            )
                        },
                        onInsertVariable = {
                            insertAtIndex = currentItems.size; showVariablePicker = true
                        }
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            // Preview
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.template_preview),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (allowSpatialTransitions) {
                            Modifier.animateContentSize(tween(200))
                        } else {
                            Modifier
                        },
                    )
            ) {
                val previewText = PredefinedVariables.compile(
                    items = currentItems.toList(),
                    runtimeValues = PredefinedVariables.EXAMPLE_VALUES
                )
                Text(
                    text = previewText.ifEmpty { stringResource(R.string.template_preview_empty) },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }

            if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }

    // Variable picker bottom sheet
    if (showVariablePicker) {
        val targetIndex = insertAtIndex
        ModalBottomSheet(
            onDismissRequest = { showVariablePicker = false; insertAtIndex = -1 },
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            DialogWindowEdgeToEdge()
            Text(
                text = stringResource(R.string.template_variable_picker_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            val availableVars = if (selectedTab == 0) PredefinedVariables.ALL.filter { it !in PredefinedVariables.PER_MESSAGE_VARS } else PredefinedVariables.ALL
            for (key in availableVars) {
                SettingsItem(
                    headlineContent = { Text(variableDisplayName(key)) },
                    supportingContent = { Text("{${key}}") },
                    leadingContent = {
                        Icon(variableIcon(key), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    startPadding = SETTINGS_ITEM_SHEET_PADDING,
                    endPadding = SETTINGS_ITEM_SHEET_PADDING,
                    modifier = Modifier.fillMaxWidth().sheetOptionClickable {
                        val item = PromptTemplateItem(type = PromptItemType.PREDEFINED, value = key)
                        if (targetIndex >= 0 && targetIndex <= currentItems.size) {
                            currentItems.add(targetIndex, item)
                        } else {
                            currentItems.add(item)
                        }
                        showVariablePicker = false
                        insertAtIndex = -1
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InsertBetweenButton(
    onInsertText: () -> Unit,
    onInsertVariable: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
    ) {
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        Box {
            FilledTonalIconButton(
                onClick = { expanded = true },
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.template_insert_title),
                    modifier = Modifier.size(12.dp)
                )
            }
            AgoraDropdownMenu(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 16.dp,
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                AgoraDropdownMenuItem(
                    text = { Text(stringResource(R.string.template_add_text)) },
                    leadingIcon = { Icon(Icons.Default.TextFields, null) },
                    onClick = { expanded = false; onInsertText() }
                )
                AgoraDropdownMenuItem(
                    text = { Text(stringResource(R.string.template_add_variable)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) },
                    onClick = { expanded = false; onInsertVariable() }
                )
            }

        }
    }
}

@Composable
private fun TemplateItemRow(
    item: PromptTemplateItem,
    onChange: (PromptTemplateItem) -> Unit,
    onDelete: (() -> Unit)?,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (onMoveUp != null || onMoveDown != null || onDelete != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (onMoveUp != null) {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.template_move_up), modifier = Modifier.size(18.dp))
                    }
                }
                if (onMoveDown != null) {
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.template_move_down), modifier = Modifier.size(18.dp))
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.provider_delete), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        when (item.type) {
            PromptItemType.CUSTOM -> {
                var text by remember(item.id) { mutableStateOf(item.value) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { newValue ->
                        text = newValue
                        onChange(item.copy(value = newValue))
                    },
                    label = { Text(stringResource(R.string.template_custom_text_label)) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            PromptItemType.PREDEFINED -> {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
                    ) {
                        Icon(
                            variableIcon(item.value),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = variableDisplayName(item.value),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "{${item.value}}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}
