package com.newoether.agora.ui.chat

import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatConversation

internal enum class DrawerConversationIndicator {
    NONE,
    GENERATING,
    UNREAD,
}

internal fun resolveDrawerConversationIndicator(
    isGenerating: Boolean,
    isSelected: Boolean,
    hasUnreadGeneration: Boolean,
): DrawerConversationIndicator = when {
    isGenerating -> DrawerConversationIndicator.GENERATING
    hasUnreadGeneration && !isSelected -> DrawerConversationIndicator.UNREAD
    else -> DrawerConversationIndicator.NONE
}

internal val DrawerEdgeFadeTolerance = 2.dp

internal fun drawerConversationKeys(conversations: List<ChatConversation>): List<String> {
    val (pinned, ordinary) = conversations.partition { it.isPinned }
    return buildList {
        if (pinned.isNotEmpty()) add("section:pinned")
        pinned.forEach { add("conversation:${it.id}") }
        if (pinned.isNotEmpty() && ordinary.isNotEmpty()) add("section:conversations")
        ordinary.forEach { add("conversation:${it.id}") }
    }
}

internal fun isDrawerListAtTop(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
    tolerancePx: Int,
): Boolean =
    firstVisibleItemIndex == 0 &&
        firstVisibleItemScrollOffsetPx <= tolerancePx.coerceAtLeast(0)

internal fun isDrawerListAtBottom(
    totalItemsCount: Int,
    lastVisibleItemIndex: Int?,
    lastVisibleItemEndOffsetPx: Int?,
    viewportEndOffsetPx: Int,
    tolerancePx: Int,
): Boolean {
    if (totalItemsCount == 0) return true
    return lastVisibleItemIndex == totalItemsCount - 1 &&
        lastVisibleItemEndOffsetPx != null &&
        lastVisibleItemEndOffsetPx <= viewportEndOffsetPx + tolerancePx.coerceAtLeast(0)
}
