package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.StableMessageList
import com.newoether.agora.model.StableModelAliases
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.ui.chat.message.MessageItem
import com.newoether.agora.util.Constants

@Composable
fun MessageList(
    messages: StableMessageList,
    allMessages: StableMessageList = StableMessageList(),
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    state: LazyListState = rememberLazyListState(),
    userScrollEnabled: Boolean = true,
    isLoading: Boolean = false,
    isSwitching: Boolean = false,
    visualizeContextRollout: Boolean = false,
    toolCallDisplayMode: String = ToolCallDisplayModes.DEFAULT,
    maxContextWindow: Int = 20,
    modelAliases: StableModelAliases = StableModelAliases(),
    bottomBarHeight: androidx.compose.ui.unit.Dp = 0.dp,
    viewportHeight: Int = 0,
    messageHeights: SnapshotStateMap<String, Int> = remember { mutableStateMapOf() },
    onEditMessage: (String, String) -> Unit = { _, _ -> },
    onSwitchBranch: (String?, String, Int) -> Unit = { _, _, _ -> },
    onRegenerate: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    thoughtExpandedStates: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
) {
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isLoading) { if (isLoading) editingMessageId = null }
    val density = androidx.compose.ui.platform.LocalDensity.current

    val currentPath = messages.list.filter { it.participant != Participant.ERROR }
    val contextStartIndex = if (currentPath.size > maxContextWindow) currentPath.size - maxContextWindow else 0
    val inContextIds = currentPath.drop(contextStartIndex).map { it.id }.toSet()

    val lastUserMessageIndex = messages.list.indexOfLast { it.participant == Participant.USER }

    // Precompute branch siblings grouped by parent once per allMessages change.
    // Previously this filter+sort ran per visible item (O(n²) and re-run on every
    // streaming-token recomposition of the active message).
    val siblingsByParent = remember(allMessages) {
        allMessages.list
            .filter { !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) }
            .groupBy { it.parentId }
            .mapValues { (_, v) -> v.sortedBy { it.timestamp } }
    }

    val extraPadding = if (lastUserMessageIndex == -1 || viewportHeight == 0) {
        0.dp
    } else {
        with(density) {
            val vDp = viewportHeight.toDp()
            val targetTopDp = 140.dp
            val availableSpaceDp = vDp - targetTopDp - (bottomBarHeight + 8.dp)
            var contentHeightPx = 0
            for (i in lastUserMessageIndex until messages.list.size) {
                contentHeightPx += messageHeights[messages.list[i].id] ?: 0
            }
            val contentHeightDp = contentHeightPx.toDp()
            (availableSpaceDp - contentHeightDp).coerceAtLeast(0.dp)
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            reverseLayout = false,
            state = state,
            userScrollEnabled = userScrollEnabled
        ) {
            items(messages.list, key = { it.id }) { message ->
                val isLastMessage = messages.list.lastOrNull()?.id == message.id
                val isInContext = inContextIds.contains(message.id)
                val siblings = siblingsByParent[message.parentId].orEmpty()
                val branchIndex = siblings.indexOfFirst { it.id == message.id }
                val totalBranches = siblings.size

                // Fade newly-appended messages in. Placement/fade-out left off so this
                // doesn't fight the manual height/scroll padding management below.
                Box(modifier = Modifier.animateItem(fadeInSpec = tween(400), placementSpec = null, fadeOutSpec = null)) {
                MessageItem(
                    message = message,
                    onEdit = { id, text ->
                        onEditMessage(id, text)
                        editingMessageId = null
                    },
                    // isStreaming driven by message status, not isLoading flag
                    isStreaming = isLastMessage && message.participant == Participant.MODEL
                        && message.status in setOf(MessageStatus.SENDING, MessageStatus.THINKING, MessageStatus.TOOL_CALLING, MessageStatus.TRANSCRIBING),
                    isLoading = isLoading,
                    isEditingAllowed = (editingMessageId == null || editingMessageId == message.id) && !isLoading,
                    isEditing = editingMessageId == message.id,
                    isSwitching = isSwitching,
                    isInContext = isInContext,
                    modelAliases = modelAliases,
                    visualizeContextRollout = visualizeContextRollout,
                    toolCallDisplayMode = toolCallDisplayMode,
                    onStartEdit = { editingMessageId = message.id },
                    onCancelEdit = { editingMessageId = null },
                    branchIndex = branchIndex,
                    totalBranches = totalBranches,
                    onSwitchBranch = { direction -> onSwitchBranch(message.parentId, message.id, direction) },
                    onRegenerate = onRegenerate,
                    onDelete = onDelete,
                    onMediaClick = onMediaClick,
                    onFileContentClick = onFileContentClick,
                    onPdfPagesClick = onPdfPagesClick,
                    onHeightChanged = { height -> messageHeights[message.id] = height },
                    thoughtExpandedStates = thoughtExpandedStates
                )
                }
            }
            item {
                Spacer(modifier = Modifier.height(extraPadding))
            }
        }
    }
}
