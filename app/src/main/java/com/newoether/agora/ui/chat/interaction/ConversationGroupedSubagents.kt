package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.model.SubagentSummary
import com.newoether.agora.ui.components.OPTION_HIGHLIGHT_SHAPE
import com.newoether.agora.ui.components.SmoothBottomSheet
import com.newoether.agora.ui.components.rememberSmoothBottomSheetState

/** The shared row keeps this entry's family key at both sides of the single/count threshold. */
@Composable
internal fun ConversationGroupedSubagentCapsule(
    agents: List<SubagentSummary>,
    conversationId: String?,
    active: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
    onOpen: () -> Unit,
) {
    val first = agents.firstOrNull() ?: return
    val single = agents.singleOrNull()
    val runningCount = agents.count { it.status == "running" }
    val title = if (single != null) {
        single.title
    } else if (runningCount > 0) {
        pluralStringResource(R.plurals.subagents_running_count, runningCount, runningCount)
    } else {
        pluralStringResource(R.plurals.subagents_count, agents.size, agents.size)
    }
    val status = single?.status ?: if (runningCount > 0) "running" else "idle"
    SubagentNavigationCapsule(
        title = title, status = status, selected = agents.any { it.id == conversationId }, active = active,
        identity = "group:${first.parentConversationId}",
        onClick = { if (single != null) onSelect(single.id) else onOpen() },
        modifier = modifier.testTag("subagents-capsule:${first.parentConversationId}"),
    )
}

/** Selection and list content only; SmoothBottomSheet owns all dialog and drag motion. */
@Composable
internal fun ConversationSubagentsSheet(
    agents: List<SubagentSummary>,
    conversationId: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val sheet = rememberSmoothBottomSheetState()
    val list = rememberLazyListState()
    val currentAgents by rememberUpdatedState(agents)
    val currentSelect by rememberUpdatedState(onSelect)
    var pendingSelection by remember { mutableStateOf<String?>(null) }
    SmoothBottomSheet(
        state = sheet,
        onDismissRequest = {
            val selected = pendingSelection?.takeIf { id -> currentAgents.any { it.id == id } }
            onDismiss()
            if (selected != null) currentSelect(selected)
        },
        contentAtTop = { !list.canScrollBackward },
        header = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_subagents),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
                    ),
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    agents.size.toString(), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )
        },
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().navigationBarsPadding().testTag("subagents-sheet-list"),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(agents, key = { it.id }) { agent ->
                val selected = agent.id == conversationId
                Surface(
                    onClick = {
                        if (pendingSelection == null && currentAgents.any { it.id == agent.id }) {
                            pendingSelection = agent.id
                            sheet.requestDismiss()
                        }
                    },
                    enabled = pendingSelection == null,
                    shape = OPTION_HIGHLIGHT_SHAPE,
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag("subagents-sheet-agent:${agent.id}").semantics { this.selected = selected },
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SubagentStatus(agent.status, "subagents-sheet-status:${agent.id}")
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(agent.title, style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
                            ))
                            Text(
                                subagentStatusLabel(agent.status), style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
