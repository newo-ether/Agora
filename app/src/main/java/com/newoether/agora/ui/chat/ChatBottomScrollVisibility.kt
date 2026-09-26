package com.newoether.agora.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Directional travel a scroll must cover before it counts as a change of intent. */
private val ScrollDirectionThreshold = 64.dp

/**
 * Latches the direction the user is scrolling, but only after they have travelled far enough for the
 * direction to mean something.
 *
 * Flipping on the first pixel of every gesture makes the scroll-to-bottom button blink during the
 * small corrections that happen while reading, so travel is accumulated per direction and the latch
 * only moves once one direction has covered the threshold. Reversing direction restarts the run
 * instead of cancelling out against it, otherwise a long scroll one way would leave the opposite
 * direction needing to undo all of it first.
 */
internal class ScrollDirectionLatch(private val thresholdPx: Float) {
    var headingToTail: Boolean = false
        private set
    private var travel = 0f

    fun onDelta(delta: Float) {
        if (delta == 0f) return
        travel = if (travel != 0f && (travel > 0f) == (delta > 0f)) travel + delta else delta
        when {
            travel >= thresholdPx -> {
                headingToTail = true
                travel = 0f
            }
            travel <= -thresholdPx -> {
                headingToTail = false
                travel = 0f
            }
        }
    }
}

/**
 * Visibility of the scroll-to-bottom button: availability plus the latched scroll direction.
 *
 * Availability alone reveals the button whenever the open conversation is taller than the viewport,
 * including the moment it opens, so the button competes with the first screen the user reads.
 * Direction decides the rest: scrolling up means the user is walking back through history and the
 * button gets out of the way, scrolling down means they are heading for the tail, which is the only
 * moment the jump helps. The list is not reversed, so a downward scroll increases the scroll offset.
 */
@Composable
internal fun rememberAbsoluteBottomButtonVisible(
    conversationId: String?,
    loadedMessagesConversationId: String?,
    isNewChatMode: Boolean,
    isSwitching: Boolean,
    shareSelectionActive: Boolean,
    isNearAbsoluteBottom: Boolean,
    absoluteBottomScrollPhase: AbsoluteBottomScrollPhase,
    listState: LazyListState,
    streamingTailController: StreamingTailController,
    regenerationScrollActive: Boolean,
    imeBottomAnchorActive: Boolean,
): State<Boolean> {
    val thresholdPx = with(LocalDensity.current) { ScrollDirectionThreshold.toPx() }
    // Starts at false for every conversation, which is what keeps the button off the first screen.
    var headingToTail by remember(conversationId) { mutableStateOf(false) }
    LaunchedEffect(conversationId, listState, thresholdPx) {
        val latch = ScrollDirectionLatch(thresholdPx)
        var lastIndex = listState.firstVisibleItemIndex
        var lastOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                // Item offsets only compare within one item, so crossing an item boundary counts as
                // a full-threshold move: passing a whole message is never an accidental nudge.
                val delta = when {
                    index == lastIndex -> (offset - lastOffset).toFloat()
                    index > lastIndex -> thresholdPx
                    else -> -thresholdPx
                }
                lastIndex = index
                lastOffset = offset
                latch.onDelta(delta)
                headingToTail = latch.headingToTail
            }
    }
    return remember(
        conversationId,
        loadedMessagesConversationId,
        isNewChatMode,
        isSwitching,
        shareSelectionActive,
        isNearAbsoluteBottom,
        absoluteBottomScrollPhase,
        listState,
        streamingTailController,
        regenerationScrollActive,
        imeBottomAnchorActive,
    ) {
        derivedStateOf {
            headingToTail &&
                shouldShowAbsoluteBottomButton(
                    isNewChatMode = isNewChatMode,
                    isSwitching = isSwitching,
                    conversationContentReady = conversationId != null &&
                        loadedMessagesConversationId == conversationId,
                    shareSelectionActive = shareSelectionActive,
                    hasItems = listState.layoutInfo.totalItemsCount > 1,
                    canScrollForward = listState.canScrollForward,
                    isNearBottom = isNearAbsoluteBottom,
                    isStreamingAutoFollowing = streamingTailController.isAutoFollowing,
                    scrollPhase = absoluteBottomScrollPhase,
                    competingProgrammaticScrollActive =
                        regenerationScrollActive || imeBottomAnchorActive,
                )
        }
    }
}
