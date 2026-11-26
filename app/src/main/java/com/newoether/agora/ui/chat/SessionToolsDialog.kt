package com.newoether.agora.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.tool.SessionToolGroup
import com.newoether.agora.viewmodel.SessionToolCatalog
import com.newoether.agora.viewmodel.SessionToolGroupTools
import com.newoether.agora.viewmodel.SessionToolUnavailable

/**
 * Per-session tool selector. Group switches decide what the next request sends; individual rows
 * only describe a tool, so one switch never hides a tool the model can still call.
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
    var expandedGroups by remember(catalog) {
        mutableStateOf<Set<SessionToolGroup>>(
            catalog.groups.filter { it.unavailable == null }.map { it.group }.toSet(),
        )
    }
    var expandedTools by remember(catalog) { mutableStateOf(emptySet<String>()) }

    val enabledGroups = catalog.groups.filter { it.group in enabled }
    val enabledTools = enabledGroups.sumOf { it.tools.size }
    val enabledTokens = enabledGroups.sumOf { it.tokens }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.tools), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.session_tools_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column {
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
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(catalog.groups, key = { it.group.id }) { groupTools ->
                        SessionToolGroupBlock(
                            groupTools = groupTools,
                            enabled = groupTools.group in enabled,
                            expanded = groupTools.group in expandedGroups,
                            isToolExpanded = { name -> name in expandedTools },
                            onToggleGroup = { on ->
                                enabled = if (on) enabled + groupTools.group else enabled - groupTools.group
                            },
                            onToggleExpanded = {
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
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { enabled = switchable }) {
                    Text(stringResource(R.string.session_tools_enable_all))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onClick = {
                        onSave(
                            catalog.groups
                                .filter { it.tools.isNotEmpty() && it.group !in enabled }
                                .mapTo(mutableSetOf()) { it.group.id },
                        )
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        },
    )
}

@Composable
private fun SessionToolGroupBlock(
    groupTools: SessionToolGroupTools,
    enabled: Boolean,
    expanded: Boolean,
    isToolExpanded: (String) -> Boolean,
    onToggleGroup: (Boolean) -> Unit,
    onToggleExpanded: () -> Unit,
    onToolClick: (String) -> Unit,
) {
    val available = groupTools.unavailable == null
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = groupLabel(groupTools.group),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (available) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = groupMeta(groupTools),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled && available,
                onCheckedChange = onToggleGroup,
                enabled = available,
                modifier = Modifier.size(width = 40.dp, height = 24.dp),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded && groupTools.tools.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, bottom = 8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                groupTools.tools.forEach { tool ->
                    val descriptionExpanded = isToolExpanded(tool.name)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToolClick(tool.name) }
                            .padding(vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "≈${tool.tokens}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = tool.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun groupMeta(groupTools: SessionToolGroupTools): String = when (groupTools.unavailable) {
    SessionToolUnavailable.SWITCH_OFF -> stringResource(R.string.session_tools_off_switch)
    SessionToolUnavailable.NO_TOOLS -> stringResource(R.string.session_tools_off_empty)
    null -> stringResource(
        R.string.session_tools_group_meta,
        groupTools.tools.size,
        groupTools.tokens,
    )
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
