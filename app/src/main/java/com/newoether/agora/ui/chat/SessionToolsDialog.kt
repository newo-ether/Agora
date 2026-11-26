package com.newoether.agora.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.tool.SessionToolGroup
import com.newoether.agora.ui.components.OPTION_HIGHLIGHT_SHAPE
import com.newoether.agora.phone.PhoneUseIcon
import com.newoether.agora.ui.components.SmoothBottomSheet
import com.newoether.agora.ui.components.smoothSheetBottomPadding
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.SessionToolCatalog
import com.newoether.agora.viewmodel.SessionToolEntry
import com.newoether.agora.viewmodel.SessionToolGroupTools
import com.newoether.agora.viewmodel.SessionToolUnavailable

/** Row geometry shared by group rows and the tool rows under them, so their text columns line up. */
private val ROW_OUTER_PADDING = 12.dp
private val ROW_INNER_PADDING = 12.dp
private val ICON_SIZE = 24.dp
private val ICON_GAP = 16.dp
private val TEXT_START = ROW_INNER_PADDING + ICON_SIZE + ICON_GAP

/**
 * Per-session tool selector, laid out like the other chat sheets: a title row, a divider and plain
 * option rows. A switch applies at once. Web and Terminal switch the same conversation setting as
 * the composer menu, so both places always agree. Groups this request cannot carry sit dimmed
 * below the rest with the reason in place of a switch.
 *
 * The caller opens the sheet only once [catalog] is read, or with [failed] set, which replaces the
 * list with an error line. There is no loading state.
 */
@Composable
internal fun SessionToolsDialog(
    catalog: SessionToolCatalog?,
    failed: Boolean,
    onDismiss: () -> Unit,
    onToggle: (group: SessionToolGroup, enabled: Set<SessionToolGroup>) -> Unit,
) {
    var enabled by remember(catalog) { mutableStateOf(catalog?.enabledGroups.orEmpty()) }
    var expandedGroups by remember(catalog) { mutableStateOf(emptySet<SessionToolGroup>()) }
    var expandedTools by remember(catalog) { mutableStateOf(emptySet<String>()) }
    val groups = catalog?.groups.orEmpty()
    val carried = groups.filter { it.unavailable == null }
    val leftOut = groups.filter { it.unavailable != null }
    val listState = rememberLazyListState()

    SmoothBottomSheet(
        onDismissRequest = onDismiss,
        contentAtTop = { !listState.canScrollBackward },
        header = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.tools),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
                    ),
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (catalog != null) {
                    val on = carried.filter { it.group in enabled }
                    Text(
                        stringResource(
                            R.string.session_tools_header,
                            on.sumOf { it.tools.size }, catalog.totalToolCount, on.sumOf { it.tokens },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )
        },
    ) {
        when {
            failed || catalog == null ->
                SheetMessage { Text(stringResource(R.string.session_tools_failed)) }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = smoothSheetBottomPadding()),
            ) {
                items(carried, key = { it.group.id }) { groupTools ->
                    val group = groupTools.group
                    GroupRow(
                        groupTools = groupTools,
                        enabled = group in enabled,
                        expanded = group in expandedGroups,
                        isToolExpanded = { name -> name in expandedTools },
                        onToggle = { on ->
                            enabled = if (on) enabled + group else enabled - group
                            onToggle(group, enabled)
                        },
                        onExpand = {
                            expandedGroups = if (group in expandedGroups) expandedGroups - group
                                else expandedGroups + group
                        },
                        onToolClick = { name ->
                            expandedTools = if (name in expandedTools) expandedTools - name else expandedTools + name
                        },
                    )
                }
                if (leftOut.isNotEmpty()) {
                    if (carried.isNotEmpty()) {
                        item(key = "left_out_divider") {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            )
                        }
                    }
                    items(leftOut, key = { "off_${it.group.id}" }) { groupTools ->
                        UnavailableRow(groupTools)
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetMessage(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyMedium,
        ) { content() }
    }
}

@Composable
private fun GroupRow(
    groupTools: SessionToolGroupTools,
    enabled: Boolean,
    expanded: Boolean,
    isToolExpanded: (String) -> Boolean,
    onToggle: (Boolean) -> Unit,
    onExpand: () -> Unit,
    onToolClick: (String) -> Unit,
) {
    val motion = LocalAgoraMotionPolicy.current
    val spatial = motion.allowSpatialTransitions
    // Same curves as the thinking card's expand and collapse (MessageItemTimeline).
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (spatial) tween(EXPAND_MS, easing = LinearOutSlowInEasing) else snap(),
        label = "sessionToolsChevron",
    )
    Column(Modifier.fillMaxWidth()) {
        Surface(
            onClick = onExpand,
            shape = OPTION_HIGHLIGHT_SHAPE,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_OUTER_PADDING, vertical = 2.dp),
        ) {
            Row(
                Modifier.padding(horizontal = ROW_INNER_PADDING, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GroupIcon(groupTools.group, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(ICON_GAP))
                GroupText(groupTools, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Switch(checked = enabled, onCheckedChange = onToggle)
                Spacer(Modifier.width(16.dp))
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp).rotate(chevron),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (spatial) fadeIn(tween(EXPAND_MS)) + expandVertically(tween(EXPAND_MS))
                else fadeIn(tween(EXPAND_MS)),
            exit = if (spatial) fadeOut(tween(EXPAND_MS)) + shrinkVertically(tween(EXPAND_MS))
                else fadeOut(tween(EXPAND_MS)),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                groupTools.tools.forEach { tool ->
                    ToolRow(tool, expanded = isToolExpanded(tool.name), onClick = { onToolClick(tool.name) })
                }
            }
        }
    }
}

/**
 * One tool under an expanded group. Its highlight starts at the group's text column, so names sit
 * under the group title and the ripple keeps the option shape.
 */
@Composable
private fun ToolRow(tool: SessionToolEntry, expanded: Boolean, onClick: () -> Unit) {
    val spatial = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ROW_OUTER_PADDING + TEXT_START - ROW_INNER_PADDING, end = ROW_OUTER_PADDING)
            .clip(OPTION_HIGHLIGHT_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = ROW_INNER_PADDING, vertical = 8.dp),
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
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.session_tools_tokens, tool.tokens),
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
            modifier = if (spatial) Modifier.animateContentSize(tween(EXPAND_MS)) else Modifier,
        )
    }
}

/** A group this request does not carry: dimmed, with the reason where the switch would be. */
@Composable
private fun UnavailableRow(groupTools: SessionToolGroupTools) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_OUTER_PADDING, vertical = 2.dp)
            .padding(horizontal = ROW_INNER_PADDING, vertical = 10.dp)
            .alpha(0.6f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupIcon(groupTools.group, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(ICON_GAP))
        GroupText(groupTools, Modifier.weight(1f))
    }
}

/** The icon of the group's settings page, so the sheet and Settings name each family the same way. */
@Composable
private fun GroupIcon(group: SessionToolGroup, tint: Color) {
    Box(Modifier.size(ICON_SIZE), contentAlignment = Alignment.Center) {
        val modifier = Modifier.size(ICON_SIZE)
        if (group == SessionToolGroup.MCP) {
            Icon(painterResource(R.drawable.ic_mcp), contentDescription = null, tint = tint, modifier = modifier)
        } else {
            Icon(groupIcon(group), contentDescription = null, tint = tint, modifier = modifier)
        }
    }
}

@Composable
private fun GroupText(groupTools: SessionToolGroupTools, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            groupLabel(groupTools.group),
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            groupMeta(groupTools),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun groupMeta(groupTools: SessionToolGroupTools): String = when (groupTools.unavailable) {
    SessionToolUnavailable.SWITCH_OFF -> stringResource(R.string.session_tools_off_switch)
    SessionToolUnavailable.LOW_CONTEXT -> stringResource(R.string.session_tools_off_low_context)
    SessionToolUnavailable.NO_TOOLS -> stringResource(R.string.session_tools_off_empty)
    null -> stringResource(R.string.session_tools_group_meta, groupTools.tools.size, groupTools.tokens)
}

/** Mirrors `baseSettingsGroups` in SettingsScreen; MCP draws its own drawable in [GroupIcon]. */
private fun groupIcon(group: SessionToolGroup): ImageVector = when (group) {
    SessionToolGroup.MEMORY -> Icons.Rounded.Description
    SessionToolGroup.SKILLS -> Icons.Rounded.Extension
    SessionToolGroup.PAST_CONVERSATIONS -> Icons.Rounded.Search
    SessionToolGroup.WEB -> Icons.Rounded.Language
    SessionToolGroup.TERMINAL -> Icons.Rounded.Terminal
    SessionToolGroup.IMAGE -> Icons.Rounded.AddPhotoAlternate
    SessionToolGroup.TODO -> Icons.Rounded.Checklist
    SessionToolGroup.PHONE -> PhoneUseIcon
    SessionToolGroup.SUBAGENT -> Icons.Rounded.AccountTree
    SessionToolGroup.AUTOMATION -> Icons.Rounded.Repeat
    SessionToolGroup.ASK_USER -> Icons.Rounded.QuestionAnswer
    SessionToolGroup.MCP -> Icons.Rounded.Extension
}

/** The title of the group's settings page. */
@Composable
private fun groupLabel(group: SessionToolGroup): String = stringResource(
    when (group) {
        SessionToolGroup.MEMORY -> R.string.settings_memory
        SessionToolGroup.SKILLS -> R.string.settings_skills
        SessionToolGroup.PAST_CONVERSATIONS -> R.string.search_title
        SessionToolGroup.WEB -> R.string.settings_web_search
        SessionToolGroup.TERMINAL -> R.string.shell_title
        SessionToolGroup.IMAGE -> R.string.settings_image_gen
        SessionToolGroup.TODO -> R.string.settings_todo
        SessionToolGroup.PHONE -> R.string.phone_title
        SessionToolGroup.SUBAGENT -> R.string.settings_subagents
        SessionToolGroup.AUTOMATION -> R.string.settings_automation
        SessionToolGroup.ASK_USER -> R.string.settings_interaction
        SessionToolGroup.MCP -> R.string.mcp_title
    },
)

/** Thinking card expand and collapse duration. */
private const val EXPAND_MS = 400
