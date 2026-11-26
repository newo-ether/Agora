package com.newoether.agora.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.tool.SessionToolGroup
import com.newoether.agora.ui.components.SmoothBottomSheet
import com.newoether.agora.ui.components.SmoothBottomSheetValue
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.settings.SettingsGroupLabel
import com.newoether.agora.ui.settings.settingsGroupRowShape
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.viewmodel.SessionToolCatalog
import com.newoether.agora.viewmodel.SessionToolEntry
import com.newoether.agora.viewmodel.SessionToolGroupTools
import com.newoether.agora.viewmodel.SessionToolUnavailable

/**
 * Per-session tool selector. Group switches decide what the next request sends; individual rows
 * only describe a tool, so one switch never hides a tool the model can still call.
 *
 * The panel reuses the settings page's grouped cards: one card stack of groups this request
 * carries, one of groups it does not, so an unavailable group stays explained instead of looking
 * like a switch that failed to turn on. Every group starts collapsed; the whole sheet opens
 * expanded because the list is the point of the panel.
 */
@Composable
internal fun SessionToolsDialog(
    catalog: SessionToolCatalog,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val switchable = remember(catalog) {
        catalog.groups.filter { it.unavailable == null }.mapTo(mutableSetOf()) { it.group }
    }
    var enabled by remember(catalog) {
        mutableStateOf<Set<SessionToolGroup>>(catalog.enabledGroups intersect switchable)
    }
    var expandedGroups by remember(catalog) { mutableStateOf(emptySet<SessionToolGroup>()) }
    var expandedTools by remember(catalog) { mutableStateOf(emptySet<String>()) }
    val carried = catalog.groups.filter { it.unavailable == null }
    val leftOut = catalog.groups.filter { it.unavailable != null }
    val enabledTools = catalog.groups.filter { it.group in enabled }.sumOf { it.tools.size }
    val enabledTokens = catalog.groups.filter { it.group in enabled }.sumOf { it.tokens }
    val listState = rememberLazyListState()

    SmoothBottomSheet(
        initialValue = SmoothBottomSheetValue.Expanded,
        onDismissRequest = onDismiss,
        contentAtTop = { !listState.canScrollBackward },
        header = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 12.dp),
            ) {
                Text(stringResource(R.string.tools), style = ChatType.detailTitle)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.session_tools_summary,
                        enabledTools,
                        catalog.totalToolCount,
                        enabledTokens,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.session_tools_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (carried.isNotEmpty()) {
                    item(key = "carried_label") {
                        SettingsGroupLabel(stringResource(R.string.session_tools_sent_here))
                    }
                    itemsIndexed(
                        items = carried,
                        key = { _, groupTools -> groupTools.group.id },
                    ) { index, groupTools ->
                        SessionToolGroupCard(
                            groupTools = groupTools,
                            enabled = groupTools.group in enabled,
                            expanded = groupTools.group in expandedGroups,
                            shape = settingsGroupRowShape(index, carried.size),
                            isToolExpanded = { name -> name in expandedTools },
                            onToggle = { on ->
                                enabled = if (on) {
                                    enabled + groupTools.group
                                } else {
                                    enabled - groupTools.group
                                }
                            },
                            onExpand = {
                                expandedGroups = if (groupTools.group in expandedGroups) {
                                    expandedGroups - groupTools.group
                                } else {
                                    expandedGroups + groupTools.group
                                }
                            },
                            onToolClick = { name ->
                                expandedTools = if (name in expandedTools) {
                                    expandedTools - name
                                } else {
                                    expandedTools + name
                                }
                            },
                        )
                    }
                }
                if (leftOut.isNotEmpty()) {
                    item(key = "left_out_label") {
                        if (carried.isNotEmpty()) Spacer(Modifier.height(10.dp))
                        SettingsGroupLabel(stringResource(R.string.session_tools_not_sent))
                    }
                    itemsIndexed(
                        items = leftOut,
                        key = { _, groupTools -> "off_${groupTools.group.id}" },
                    ) { index, groupTools ->
                        SessionToolUnavailableCard(
                            groupTools = groupTools,
                            shape = settingsGroupRowShape(index, leftOut.size),
                        )
                    }
                }
            }
            SessionToolsFooter(
                canEnableAll = enabled.size < switchable.size,
                onEnableAll = { enabled = switchable },
                onCancel = onDismiss,
                onSave = { onSave(catalog.disabledIds(enabled)) },
            )
        }
    }
}

@Composable
private fun SessionToolGroupCard(
    groupTools: SessionToolGroupTools,
    enabled: Boolean,
    expanded: Boolean,
    shape: Shape,
    isToolExpanded: (String) -> Boolean,
    onToggle: (Boolean) -> Unit,
    onExpand: () -> Unit,
    onToolClick: (String) -> Unit,
) {
    val motion = LocalAgoraMotionPolicy.current
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (motion.allowSpatialTransitions) tween(220) else snap(),
        label = "sessionToolsChevron",
    )
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = groupIcon(groupTools.group),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = groupLabel(groupTools.group),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = groupMeta(groupTools),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).rotate(chevron),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(if (motion.allowSpatialTransitions) 220 else 0),
                    expandFrom = Alignment.Top,
                ) + fadeIn(animationSpec = tween(if (motion.allowSpatialTransitions) 180 else 0)),
                exit = shrinkVertically(
                    animationSpec = tween(if (motion.allowSpatialTransitions) 160 else 0),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(animationSpec = tween(if (motion.allowSpatialTransitions) 120 else 0)),
            ) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )
                    groupTools.tools.forEach { tool ->
                        SessionToolRow(
                            tool = tool,
                            expanded = isToolExpanded(tool.name),
                            onToggle = { onToolClick(tool.name) },
                        )
                    }
                }
            }
        }
    }
}

/** One tool inside an expanded group: name and price on top, a clamped description below. */
@Composable
private fun SessionToolRow(
    tool: SessionToolEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val motion = LocalAgoraMotionPolicy.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 54.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tool.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "\u2248 ${tool.tokens}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = tool.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = if (motion.allowSpatialTransitions) {
                Modifier.animateContentSize()
            } else {
                Modifier
            },
        )
    }
}

/** A group this request does not carry: the reason replaces the switch, so nothing looks broken. */
@Composable
private fun SessionToolUnavailableCard(
    groupTools: SessionToolGroupTools,
    shape: Shape,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = groupIcon(groupTools.group),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = groupLabel(groupTools.group),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = groupMeta(groupTools),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SessionToolsFooter(
    canEnableAll: Boolean,
    onEnableAll: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onEnableAll, enabled = canEnableAll) {
                Text(stringResource(R.string.session_tools_enable_all))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
            TextButton(onClick = onSave) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@Composable
private fun groupMeta(groupTools: SessionToolGroupTools): String = when (groupTools.unavailable) {
    SessionToolUnavailable.SWITCH_OFF -> stringResource(R.string.session_tools_off_switch)
    SessionToolUnavailable.LOW_CONTEXT -> stringResource(R.string.session_tools_off_low_context)
    SessionToolUnavailable.NO_TOOLS -> stringResource(R.string.session_tools_off_empty)
    null -> stringResource(
        R.string.session_tools_group_meta,
        groupTools.tools.size,
        groupTools.tokens,
    )
}

private fun groupIcon(group: SessionToolGroup): ImageVector = when (group) {
    SessionToolGroup.MEMORY -> Icons.Rounded.Psychology
    SessionToolGroup.SKILLS -> Icons.Rounded.Extension
    SessionToolGroup.PAST_CONVERSATIONS -> Icons.Rounded.History
    SessionToolGroup.WEB -> Icons.Rounded.Language
    SessionToolGroup.TERMINAL -> Icons.Rounded.Terminal
    SessionToolGroup.IMAGE -> Icons.Rounded.Image
    SessionToolGroup.TODO -> Icons.Rounded.Checklist
    SessionToolGroup.PHONE -> Icons.Rounded.PhoneAndroid
    SessionToolGroup.SUBAGENT -> Icons.Rounded.AccountTree
    SessionToolGroup.AUTOMATION -> Icons.Rounded.Schedule
    SessionToolGroup.ASK_USER -> Icons.AutoMirrored.Rounded.HelpOutline
    SessionToolGroup.MCP -> Icons.Rounded.Lan
}

@Composable
private fun groupLabel(group: SessionToolGroup): String = stringResource(
    when (group) {
        SessionToolGroup.MEMORY -> R.string.session_tools_group_memory
        SessionToolGroup.SKILLS -> R.string.session_tools_group_skills
        SessionToolGroup.PAST_CONVERSATIONS -> R.string.session_tools_group_history
        SessionToolGroup.WEB -> R.string.session_tools_group_web
        SessionToolGroup.TERMINAL -> R.string.session_tools_group_terminal
        SessionToolGroup.IMAGE -> R.string.session_tools_group_image
        SessionToolGroup.TODO -> R.string.session_tools_group_todo
        SessionToolGroup.PHONE -> R.string.session_tools_group_phone
        SessionToolGroup.SUBAGENT -> R.string.session_tools_group_subagent
        SessionToolGroup.AUTOMATION -> R.string.session_tools_group_automation
        SessionToolGroup.ASK_USER -> R.string.session_tools_group_ask_user
        SessionToolGroup.MCP -> R.string.session_tools_group_mcp
    },
)
