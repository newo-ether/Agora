package com.newoether.agora.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageGenerationBoundaryResolver
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.util.Constants
import kotlin.math.roundToInt

/** Page fragments keep their content origin fixed when an older page is prepended.
 * Put the original adjacent-message spacing after its predecessor, with no gap inside
 * a native assistant turn split only for paging. Ordinary ChatApp messages opt out.
 */
internal fun messageListPageLeadingSpacing(message: ChatMessage?): Int = when (message?.participant) {
    null -> 0
    Participant.USER -> 8
    else -> 14
}

internal fun messageListPageTrailingSpacing(messages: List<ChatMessage>): Map<String, Int> = buildMap {
    messages.forEachIndexed { index, message ->
        if (message.displayPageId == null) return@forEachIndexed
        val next = messages.getOrNull(index + 1)
        val continues = next?.displayPageId != null && next.displayPageId != message.displayPageId &&
            message.participant == Participant.MODEL && next.participant == Participant.MODEL &&
            !message.runId.isNullOrBlank() && message.runId == next.runId
        put(message.id, if (continues) 0 else
            (if (message.participant == Participant.USER) 8 else 24) + messageListPageLeadingSpacing(next))
    }
}

internal enum class MessageListLayoutMode {
    STABLE,
    ACTIVE_SCROLL,
    COVERED_TRANSITION,
}

internal fun messageListLayoutMode(
    isSwitching: Boolean,
    isScrollInProgress: Boolean,
    isUserDragging: Boolean = false,
): MessageListLayoutMode = when {
    isSwitching -> MessageListLayoutMode.COVERED_TRANSITION
    isScrollInProgress || isUserDragging -> MessageListLayoutMode.ACTIVE_SCROLL
    else -> MessageListLayoutMode.STABLE
}

internal fun calculateTailMinHeightPx(
    viewportHeightPx: Int,
    targetTopPx: Int,
    bottomObstructionPx: Int,
): Int = (viewportHeightPx - targetTopPx - bottomObstructionPx).coerceAtLeast(0)

internal fun calculateTailLayoutHeightPx(
    minimumHeightPx: Int,
    contentHeightPx: Int,
): Int = maxOf(minimumHeightPx, contentHeightPx)

internal fun calculateTailHolderMinHeightPx(
    turns: List<MessageListTurn>,
    semanticAnchorKey: String?,
    baseMinimumHeightPx: Int,
    messageHeights: Map<String, Int>,
): Int {
    val anchorIndex = turns.indexOfFirst { turn -> turn.key == semanticAnchorKey }
    if (anchorIndex < 0 || turns.isEmpty()) return 0
    val precedingTailHeightPx = turns
        .subList(anchorIndex, turns.lastIndex)
        .sumOf { turn -> turn.messages.sumOf { message -> messageHeights[message.id] ?: 0 } }
    return (baseMinimumHeightPx - precedingTailHeightPx).coerceAtLeast(0)
}

/**
 * One stable LazyColumn item per ordinary conversation turn or Compact message.
 *
 * A real USER starts an ordinary turn and every following non-USER message remains in that turn
 * until the next real USER or Compact. Each Compact is a singleton item and ends the preceding
 * turn. Ordinary turn identity must not change when a new turn is appended: otherwise the
 * previous assistant is disposed from the tail item and recreated as a standalone item, producing
 * a visible blank/reparse frame on Send.
 */
internal data class MessageListTurn(
    val key: String,
    val messages: List<ChatMessage>,
) {
    /**
     * HTML previews host WebViews through AndroidView. Reusing such a slot for another turn
     * removes the WebView subtree inside a deactivated item, after which Compose can place a
     * detached child ("LayoutNode should be attached to an owner"). These turns get a
     * per-turn content type so LazyColumn never recycles them into a different turn.
     */
    val hostsHtmlPreview: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        messages.any { message -> message.text.contains("```html", ignoreCase = true) }
    }
}

internal const val MessageTurnContentType = "turn"

internal fun MessageListTurn.lazyContentType(): Any =
    if (hostsHtmlPreview) "html:$key" else MessageTurnContentType

internal fun branchReplacementExitMessageIds(
    messages: List<ChatMessage>,
    oldMessageId: String?,
): Set<String> = branchReplacementExitMessages(messages, oldMessageId)
    .mapTo(linkedSetOf()) { message -> message.id }

internal fun branchReplacementExitMessages(
    messages: List<ChatMessage>,
    oldMessageId: String?,
): List<ChatMessage> {
    oldMessageId ?: return emptyList()
    val firstExitIndex = messages.indexOfFirst { message -> message.id == oldMessageId }
    if (firstExitIndex < 0) return emptyList()
    return messages.subList(firstExitIndex, messages.size).toList()
}

/**
 * Keeps a faded branch composed after the selected graph path switches to its replacement.
 * Current-path messages are ordered first so SENDING appears directly below its USER anchor;
 * retained messages keep their original stable keys after it and contribute layout height only.
 */
internal fun mergeBranchReplacementPresentationMessages(
    activeMessages: List<ChatMessage>,
    retainedExitMessages: List<ChatMessage>,
): List<ChatMessage> {
    if (retainedExitMessages.isEmpty()) return activeMessages
    val activeIds = activeMessages.mapTo(hashSetOf()) { message -> message.id }
    val retainedOnly = retainedExitMessages.filterNot { message -> message.id in activeIds }
    if (retainedOnly.isEmpty()) return activeMessages
    return buildList(activeMessages.size + retainedOnly.size) {
        addAll(activeMessages)
        addAll(retainedOnly)
    }
}

internal fun branchReplacementVisualKey(
    messageId: String,
    sourceUserMessageId: String?,
    targetUserMessageId: String?,
    aliases: Map<String, String>,
): String {
    if (messageId != targetUserMessageId || sourceUserMessageId == null) {
        return aliases[messageId] ?: messageId
    }
    return aliases[sourceUserMessageId] ?: sourceUserMessageId
}

/**
 * Reuses unchanged turn objects across immutable streaming snapshots. Only the active tail turn
 * receives a new identity, allowing Compose to skip every historical LazyColumn item.
 */
internal class MessageListTurnCache {
    private var previousByKey: Map<String, MessageListTurn> = emptyMap()

    fun update(messages: List<ChatMessage>): List<MessageListTurn> {
        val next = buildMessageListTurns(messages).map { candidate ->
            previousByKey[candidate.key]
                ?.takeIf { previous -> previous.messages == candidate.messages }
                ?: candidate
        }
        previousByKey = next.associateBy { it.key }
        return next
    }
}

/**
 * Session-scoped one-shot registry. LazyColumn disposal/recreation and conversation switches must
 * not replay an entrance for a message the user has already seen.
 */
internal class MessageLifecycleAppearanceRegistry {
    private val knownMessageIds = HashSet<String>()

    fun isKnown(messageId: String): Boolean = messageId in knownMessageIds

    fun markKnown(messageId: String) {
        knownMessageIds += messageId
    }
}

internal fun shouldAnimateMessageLifecycleEntrance(
    message: ChatMessage,
    isKnown: Boolean,
    isLoading: Boolean,
    isStreaming: Boolean,
    lastUserMessageId: String?,
    requestedTargetMessageId: String?,
): Boolean {
    if (isKnown) return false
    if (
        message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
        message.id.startsWith(Constants.RESULT_MSG_PREFIX)
    ) {
        return false
    }
    return when (message.participant) {
        Participant.USER ->
            message.id == requestedTargetMessageId ||
                (isLoading && message.id == lastUserMessageId)
        Participant.MODEL ->
            isStreaming ||
                (
                    requestedTargetMessageId != null &&
                        message.parentId == requestedTargetMessageId
                )
        Participant.ERROR -> false
    }
}

internal fun buildMessageListTurns(messages: List<ChatMessage>): List<MessageListTurn> {
    if (messages.isEmpty()) return emptyList()

    val turns = mutableListOf<MessageListTurn>()
    var activeTurn = mutableListOf<ChatMessage>()

    fun flushActiveTurn() {
        if (activeTurn.isEmpty()) return
        turns += MessageListTurn(
            key = activeTurn.first().id,
            messages = activeTurn.toList(),
        )
        activeTurn = mutableListOf()
    }

    messages.forEach { message ->
        if (activeTurn.isNotEmpty() && message.displayPageId != activeTurn.first().displayPageId) {
            flushActiveTurn()
        }
        if (message.isContextCompact()) {
            flushActiveTurn()
            turns += MessageListTurn(message.id, listOf(message))
        } else if (MessageGenerationBoundaryResolver.isRealUser(message)) {
            flushActiveTurn()
            activeTurn += message
        } else if (
            activeTurn.firstOrNull()?.let(MessageGenerationBoundaryResolver::isRealUser) == true
        ) {
            activeTurn += message
        } else {
            // Preserve leading/error-only paths as their own stable items until a USER begins a
            // normal conversation turn.
            flushActiveTurn()
            turns += MessageListTurn(message.id, listOf(message))
        }
    }
    flushActiveTurn()
    return turns
}

internal fun messageListTailAnchorKey(turns: List<MessageListTurn>): String? = turns
    .lastOrNull { turn ->
        turn.messages.firstOrNull()?.let { message ->
            (turns.lastOrNull()?.messages?.lastOrNull()?.displayPageId == null ||
                message.displayPageId == turns.last().messages.last().displayPageId) &&
                (MessageGenerationBoundaryResolver.isRealUser(message) || message.isContextCompact())
        } == true
    }
    ?.key

internal fun messageListTailHolderKey(turns: List<MessageListTurn>): String? = turns.lastOrNull()?.key

internal fun messageListTurnIndex(
    turns: List<MessageListTurn>,
    messageId: String,
): Int = turns.indexOfFirst { turn -> turn.messages.any { it.id == messageId } }

internal fun estimateMessageListTurnHeightPx(
    turn: MessageListTurn,
    messageHeights: Map<String, Int>,
    fallbackHeightPx: Float,
): Float = turn.messages.sumOf { message ->
    (messageHeights[message.id]?.toDouble() ?: fallbackHeightPx.toDouble())
}.toFloat()

internal fun estimateSearchMatchCenterInTurnPx(
    turn: MessageListTurn,
    match: ConversationSearchMatch,
    messageHeights: Map<String, Int>,
    fallbackHeightPx: Float,
): Float {
    val targetIndex = turn.messages.indexOfFirst { it.id == match.messageId }
    if (targetIndex < 0) return fallbackHeightPx / 2f
    val precedingHeight = turn.messages
        .take(targetIndex)
        .sumOf { message ->
            (messageHeights[message.id]?.toDouble() ?: fallbackHeightPx.toDouble())
        }
        .toFloat()
    val target = turn.messages[targetIndex]
    val targetHeight = messageHeights[target.id]?.toFloat() ?: fallbackHeightPx
    val characterCenter = (match.start + match.endExclusive) / 2f
    val textFraction = if (target.text.isEmpty()) {
        0.5f
    } else {
        (characterCenter / target.text.length).coerceIn(0.08f, 0.92f)
    }
    return precedingHeight + targetHeight * textFraction
}

internal fun searchMatchCenterInTurnPx(
    glyphCenterInRootPx: Float,
    listRootInRootPx: Float,
    turnOffsetInListPx: Float,
): Float = glyphCenterInRootPx - listRootInRootPx - turnOffsetInListPx
internal fun searchMatchScrollErrorPx(
    turnOffsetInListPx: Float,
    matchCenterInTurnPx: Float,
    viewportCenterInListPx: Float,
): Float = turnOffsetInListPx + matchCenterInTurnPx - viewportCenterInListPx
internal fun searchMatchScrollOffsetPx(
    matchCenterInTurnPx: Float,
    viewportCenterInListPx: Float,
): Int = (matchCenterInTurnPx - viewportCenterInListPx).roundToInt()
internal fun acceptsSearchMatchMeasurement(
    activeKey: String?,
    reportedKey: String,
    measurementEpoch: String?,
): Boolean = if (activeKey == null) {
    measurementEpoch == null
} else {
    reportedKey == activeKey && measurementEpoch == activeKey
}
internal data class MessageListViewportAnchor(
    val messageId: String,
    val scrollOffsetPx: Int,
)

internal fun LazyListState.captureMessageListViewportAnchor(
    turns: List<MessageListTurn>,
    visualKey: (String) -> String = { it },
): MessageListViewportAnchor? {
    // The measured index belongs to the old list until prepend's next measure pass.
    val measuredKey = layoutInfo.visibleItemsInfo
        .firstOrNull { it.index == firstVisibleItemIndex }?.key ?: return null
    val message = turns.firstOrNull { visualKey(it.key) == measuredKey }?.messages?.firstOrNull() ?: return null
    return MessageListViewportAnchor(message.id, firstVisibleItemScrollOffset)
}

internal fun LazyListState.restoreMessageListViewportAnchor(
    turns: List<MessageListTurn>,
    anchor: MessageListViewportAnchor,
): Boolean {
    val turnIndex = messageListTurnIndex(turns, anchor.messageId)
    if (turnIndex < 0) return false
    requestScrollToItem(turnIndex, anchor.scrollOffsetPx)
    return true
}

internal class MessageListMutationAnchorLock {
    private val activeMutationKeys = mutableSetOf<String>()

    var anchor: MessageListViewportAnchor? = null
        private set

    fun begin(
        key: String,
        candidate: MessageListViewportAnchor?,
    ): MessageListViewportAnchor? {
        activeMutationKeys += key
        if (anchor == null) anchor = candidate
        return anchor
    }

    /**
     * Returns the anchor exactly once, when the final overlapping mutation settles.
     * Repeated begin calls for the same reversing animation never replace the pre-change anchor.
     */
    fun finish(key: String): MessageListViewportAnchor? {
        if (!activeMutationKeys.remove(key) || activeMutationKeys.isNotEmpty()) return null
        return anchor.also { anchor = null }
    }

    fun isActive(key: String): Boolean = key in activeMutationKeys

    fun cancel() {
        activeMutationKeys.clear()
        anchor = null
    }

    val activeMutationCount: Int
        get() = activeMutationKeys.size
}
