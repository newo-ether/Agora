package com.newoether.agora.ui.chat.message

import android.content.res.Resources
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.api.LOCAL_CONTEXT_CAPACITY_ERROR_CODE
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.ui.motion.animateDpAsState

internal fun MessageSegment.isHiddenFromMessagePresentation(): Boolean =
    type == "tool" && toolName == "google_search"

internal fun mergeAdjacentSegments(segs: List<MessageSegment>): List<MessageSegment> {
    val merged = mutableListOf<MessageSegment>()
    for (seg in segs) {
        if (seg.type == "citation" || seg.isHiddenFromMessagePresentation()) continue
        val last = merged.lastOrNull()
        // Only continuous answer/reasoning text is merged into one flowing block.
        // Transcriptions stay separate: each describes a distinct image, so a
        // 1:1 image↔block correspondence must be preserved.
        if (last != null && last.type == seg.type && (seg.type == "answer" || seg.type == "thought")) {
            merged[merged.lastIndex] = last.copy(
                content = last.content + seg.content,
                durationMs = mergeDurationMs(last.durationMs, seg.durationMs),
                streamingTextDeltas = last.streamingTextDeltas + seg.streamingTextDeltas,
            )
        } else {
            merged.add(seg)
        }
    }
    // Drop content-less thought segments AFTER merging (a streamed thought arrives as blank
    // fragments that concatenate into real content). Models that signal thinking without
    // emitting a summary would otherwise render an empty "Thinking" block — and because every
    // renderer funnels through here, dropping them keeps the block keys, timeline dots, and
    // detail sheet indices all consistent with what is actually drawn.
    return merged.filterNot { it.type == "thought" && it.content.isBlank() }
}

private fun mergeDurationMs(first: Long?, second: Long?): Long? {
    val merged = (first ?: 0L) + (second ?: 0L)
    return merged.takeIf { it > 0L }
}

internal fun thoughtDurationMs(
    segs: List<MessageSegment>,
    fallbackMs: Long? = null,
): Long? {
    val thoughtSegments = segs.filter { it.type == "thought" }
    if (thoughtSegments.isEmpty()) return null
    return thoughtSegments.sumOf { it.durationMs ?: 0L }
        .takeIf { it > 0L }
        ?: fallbackMs?.takeIf { it > 0L }
}

private fun MessageSegment.isBlankAnswerSegment(): Boolean =
    type == "answer" && content.isBlank()

internal fun MessageSegment.isVisibleAnswerSegment(): Boolean =
    type == "answer" && content.isNotBlank()

internal fun MessageSegment.isInfoSegment(): Boolean =
    (type == "thought" && content.isNotBlank()) || type == "tool" || type == "transcription"

internal fun MessageSegment.isImageGenerationSegment(): Boolean =
    type == "tool" && toolName == "generate_image"

internal fun groupedInfoBlockEndExclusive(
    segments: List<MessageSegment>,
    startIndex: Int,
): Int {
    if (startIndex !in segments.indices) return startIndex
    var endIndex = startIndex
    while (endIndex < segments.size && !segments[endIndex].isVisibleAnswerSegment()) {
        val segment = segments[endIndex]
        endIndex++
        if (segment.isImageGenerationSegment()) break
    }
    return endIndex
}

internal fun generatedImageAppearanceKey(
    messageId: String,
    detailIndex: Int,
): String = "$messageId:generated-image:$detailIndex"

internal enum class SegmentGroupPosition {
    SINGLE,
    FIRST,
    MIDDLE,
    LAST,
}

private const val SEGMENT_GROUP_INNER_RADIUS_DP = 5
private const val SEGMENT_GROUP_OUTER_RADIUS_DP = 24

@Composable
internal fun rememberAnimatedSegmentGroupShape(
    position: SegmentGroupPosition,
): RoundedCornerShape {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val innerCorner = SEGMENT_GROUP_INNER_RADIUS_DP.dp
    val outerCorner = SEGMENT_GROUP_OUTER_RADIUS_DP.dp
    val targetTopStart =
        if (position == SegmentGroupPosition.SINGLE || position == SegmentGroupPosition.FIRST) {
            outerCorner
        } else {
            innerCorner
        }
    val targetTopEnd = targetTopStart
    val targetBottomStart =
        if (position == SegmentGroupPosition.SINGLE || position == SegmentGroupPosition.LAST) {
            outerCorner
        } else {
            innerCorner
        }
    val targetBottomEnd = targetBottomStart
    val animationSpec: AnimationSpec<Dp> =
        if (motionPolicy.allowSpatialTransitions) {
            tween(
                durationMillis = 240,
                easing = FastOutSlowInEasing,
            )
        } else {
            snap()
        }
    val topStart by animateDpAsState(
        targetValue = targetTopStart,
        animationSpec = animationSpec,
        label = "segmentGroupTopStart",
    )
    val topEnd by animateDpAsState(
        targetValue = targetTopEnd,
        animationSpec = animationSpec,
        label = "segmentGroupTopEnd",
    )
    val bottomStart by animateDpAsState(
        targetValue = targetBottomStart,
        animationSpec = animationSpec,
        label = "segmentGroupBottomStart",
    )
    val bottomEnd by animateDpAsState(
        targetValue = targetBottomEnd,
        animationSpec = animationSpec,
        label = "segmentGroupBottomEnd",
    )
    return RoundedCornerShape(
        topStart = topStart.coerceIn(innerCorner, outerCorner),
        topEnd = topEnd.coerceIn(innerCorner, outerCorner),
        bottomStart = bottomStart.coerceIn(innerCorner, outerCorner),
        bottomEnd = bottomEnd.coerceIn(innerCorner, outerCorner),
    )
}

internal fun segmentGroupPosition(
    hasPrevious: Boolean,
    hasNext: Boolean,
): SegmentGroupPosition = when {
    !hasPrevious && !hasNext -> SegmentGroupPosition.SINGLE
    !hasPrevious -> SegmentGroupPosition.FIRST
    hasNext -> SegmentGroupPosition.MIDDLE
    else -> SegmentGroupPosition.LAST
}

private fun List<MessageSegment>.hasTimelineInfoNeighbor(
    index: Int,
    direction: Int,
): Boolean {
    if (direction > 0 && this[index].isImageGenerationSegment()) return false
    var cursor = index + direction
    while (cursor in indices) {
        val candidate = this[cursor]
        when {
            candidate.isVisibleAnswerSegment() -> return false
            candidate.isInfoSegment() ->
                return direction > 0 || !candidate.isImageGenerationSegment()
        }
        cursor += direction
    }
    return false
}

internal fun timelineSegmentGroupPosition(
    segments: List<MessageSegment>,
    index: Int,
): SegmentGroupPosition {
    if (index !in segments.indices || !segments[index].isInfoSegment()) {
        return SegmentGroupPosition.SINGLE
    }
    return segmentGroupPosition(
        hasPrevious = segments.hasTimelineInfoNeighbor(index, direction = -1),
        hasNext = segments.hasTimelineInfoNeighbor(index, direction = 1),
    )
}

internal fun ChatMessage.hasActiveAnswerSegment(): Boolean {
    // Citations annotate prior output; tools and thoughts still delimit the active phase.
    val lastVisibleSegment = segments?.lastOrNull {
        it.type != "citation" && !it.isBlankAnswerSegment()
    }
    return if (lastVisibleSegment != null) {
        lastVisibleSegment.isVisibleAnswerSegment()
    } else {
        text.isNotBlank()
    }
}

internal data class AssistantErrorContent(
    val answerText: String?,
    val errorText: String,
    val showLocalContextHelp: Boolean,
)

internal fun shouldShowLocalContextHelp(
    errorCode: String?,
    modelName: String?,
): Boolean = errorCode == LOCAL_CONTEXT_CAPACITY_ERROR_CODE &&
    modelName?.startsWith("Local:") == true

/**
 * Keeps already-generated assistant content separate from the terminal failure detail. Rows
 * written before explicit error segments are recoverable when their persisted answer segments
 * reproduce [ChatMessage.text]; legacy error-only rows continue to treat text as the error.
 */
internal fun assistantErrorContent(
    message: ChatMessage,
    mergedSegments: List<MessageSegment>,
    fallbackErrorText: String,
): AssistantErrorContent? {
    if (message.status != com.newoether.agora.model.MessageStatus.ERROR &&
        message.participant != com.newoether.agora.model.Participant.ERROR
    ) {
        return null
    }
    val persistedErrorSegment = mergedSegments
        .lastOrNull { it.type == "error" && it.content.isNotBlank() }
    val persistedError = persistedErrorSegment?.content
    val hasPersistedAnswer = mergedSegments.any { it.isVisibleAnswerSegment() }
    return AssistantErrorContent(
        answerText = message.text.takeIf {
            it.isNotBlank() && (persistedError != null || hasPersistedAnswer)
        },
        errorText = persistedError
            ?: message.text.takeIf { it.isNotBlank() && !hasPersistedAnswer }
            ?: fallbackErrorText,
        showLocalContextHelp = shouldShowLocalContextHelp(
            errorCode = persistedErrorSegment?.errorCode,
            modelName = message.modelName,
        ),
    )
}

/**
 * Stable render identity for one merged message segment.
 *
 * Segment content, tool arguments/results, and lifecycle state are deliberately excluded: those
 * fields grow in place while streaming and must never restart the one-shot entrance animation.
 * The append-only merged index plus type changes only when a genuinely new visible segment enters
 * the message timeline.
 */
internal fun segmentAppearanceKey(
    messageId: String,
    mergedIndex: Int,
    segment: MessageSegment,
): String = "$messageId:segment:$mergedIndex:${segment.type}"

/**
 * Stable identity for one non-answer segment across compact, grouped, and timeline layouts.
 *
 * The detail index is append-only within a message. Keeping layout mode and payload fields out of
 * this key prevents a settings change or a streaming payload update from replaying the entrance.
 */
internal fun detailSegmentAppearanceKey(
    messageId: String,
    detailIndex: Int,
    segment: MessageSegment,
): String = "$messageId:detail-segment:$detailIndex:${segment.type}"

internal fun compactSegmentBlockAppearanceKey(messageId: String): String =
    "$messageId:compact"

internal fun groupedSegmentBlockAppearanceKey(
    messageId: String,
    firstDetailIndex: Int,
): String = "$messageId:group:$firstDetailIndex"

internal fun buildTimelineBlockKeys(
    messageId: String,
    segments: List<MessageSegment>,
    groupAdjacentBlocks: Boolean
): Set<String> {
    val keys = linkedSetOf<String>()
    var detailIndex = 0
    var index = 0
    while (index < segments.size) {
        val seg = segments[index]
        when {
            seg.type == "answer" -> {
                index++
            }
            seg.isInfoSegment() -> {
                if (groupAdjacentBlocks) {
                    var blockEnd = index
                    var firstDetailIndex: Int? = null
                    while (blockEnd < segments.size && !segments[blockEnd].isVisibleAnswerSegment()) {
                        val blockSeg = segments[blockEnd]
                        if (blockSeg.isInfoSegment()) {
                            if (firstDetailIndex == null) firstDetailIndex = detailIndex
                            detailIndex++
                        }
                        blockEnd++
                        if (blockSeg.isImageGenerationSegment()) break
                    }
                    keys += "$messageId:group:${firstDetailIndex ?: index}"
                    index = blockEnd
                } else {
                    keys += "$messageId:timeline:$detailIndex"
                    detailIndex++
                    index++
                }
            }
            else -> {
                index++
            }
        }
    }
    return keys
}

/**
 * Session-scoped first-appearance memory for every rendered message segment.
 *
 * This deliberately lives above LazyColumn items: local remember state is lost when an off-screen
 * message is disposed, which would replay the entrance when it is composed again.
 */
@Stable
internal class SegmentAppearanceRegistry {
    private val seenKeys = HashSet<String>()
    private val streamingFadeTrackers = HashMap<String, StreamingTailFadeTracker>()

    fun streamingFadeTracker(key: String): StreamingTailFadeTracker =
        streamingFadeTrackers.getOrPut(key) { StreamingTailFadeTracker() }

    fun shouldAnimate(key: String, isStreaming: Boolean): Boolean =
        isStreaming && key !in seenKeys

    fun markSeen(keys: Iterable<String>) {
        seenKeys.addAll(keys)
    }

    fun markSeen(key: String) {
        seenKeys += key
    }
}

internal enum class GroupedSegmentAutoExpansionAction {
    NONE,
    EXPAND,
    COLLAPSE,
}

internal fun groupedSegmentExpandedState(
    persistedExpanded: Boolean?,
    initiallyAutoExpanded: Boolean,
    collapseForImageBoundary: Boolean = false,
): Boolean = !collapseForImageBoundary &&
    (initiallyAutoExpanded || persistedExpanded == true)

/**
 * Session-scoped lifecycle memory for Grouped cards.
 *
 * This lives above LazyColumn items so recomposition and off-screen disposal cannot replay an
 * automatic expansion during one active period. Native generation can arrive after the history
 * body or recover after reconnecting; the latest authoritative activity owns that transition.
 */
@Stable
internal class GroupedSegmentAutoExpansionController {
    private enum class State {
        ACTIVE,
        INACTIVE,
    }

    private val states = HashMap<String, State>()
    private val collapsedImageBoundaryKeys = HashSet<String>()

    fun shouldCollapseForImageBoundary(
        key: String,
        hasImageBoundary: Boolean,
    ): Boolean = hasImageBoundary && key !in collapsedImageBoundaryKeys

    fun claimImageBoundaryCollapse(
        key: String,
        hasImageBoundary: Boolean,
    ): Boolean {
        if (!hasImageBoundary || !collapsedImageBoundaryKeys.add(key)) return false
        states[key] = State.INACTIVE
        return true
    }

    fun shouldPresentInitiallyExpanded(
        key: String,
        isActive: Boolean,
        enabled: Boolean,
    ): Boolean = enabled && isActive && states[key] == null

    fun update(
        key: String,
        isActive: Boolean,
        enabled: Boolean,
    ): GroupedSegmentAutoExpansionAction {
        if (key in collapsedImageBoundaryKeys) return GroupedSegmentAutoExpansionAction.NONE
        if (!enabled) {
            if (isActive) {
                states.remove(key)
            } else {
                states[key] = State.INACTIVE
            }
            return GroupedSegmentAutoExpansionAction.NONE
        }

        return when (states[key]) {
            null, State.INACTIVE -> {
                states[key] = if (isActive) State.ACTIVE else State.INACTIVE
                if (isActive) {
                    GroupedSegmentAutoExpansionAction.EXPAND
                } else {
                    GroupedSegmentAutoExpansionAction.NONE
                }
            }
            State.ACTIVE -> {
                if (isActive) {
                    GroupedSegmentAutoExpansionAction.NONE
                } else {
                    states[key] = State.INACTIVE
                    GroupedSegmentAutoExpansionAction.COLLAPSE
                }
            }
        }
    }
}

@Composable
internal fun AnimatedTimelineBlockAppearance(
    animationKey: String,
    animate: Boolean? = null,
    appearanceRegistry: SegmentAppearanceRegistry? = null,
    isStreaming: Boolean = false,
    forceOpaque: Boolean = false,
    content: @Composable () -> Unit
) {
    key(animationKey) {
        // Claim appearance at the component that actually enters composition. A parent-side
        // bulk mark can consume a key before a conditional child is emitted, silently suppressing
        // the very first scale/fade.
        val play = remember(animationKey, appearanceRegistry) {
            animate ?: appearanceRegistry?.shouldAnimate(animationKey, isStreaming) ?: false
        }
        SideEffect {
            appearanceRegistry?.markSeen(animationKey)
        }
        val appearanceModifier = generationLifecycleAppearanceModifier(
            animationKey = animationKey,
            animate = play,
            durationMillis = SEGMENT_ENTER_DURATION_MS,
            initialScale = SEGMENT_ENTER_INITIAL_SCALE,
            forceOpaque = forceOpaque,
        )
        Box(
            modifier = appearanceModifier,
        ) {
            content()
        }
    }
}

@Composable
internal fun rememberSegmentAppearance(
    registry: SegmentAppearanceRegistry,
    animationKey: String,
    isStreaming: Boolean,
): Boolean {
    val play = remember(registry, animationKey) {
        registry.shouldAnimate(animationKey, isStreaming)
    }
    SideEffect {
        registry.markSeen(animationKey)
    }
    return play
}

// Label a transcription segment; numbers them ("Image Transcription 1/2/…") only
// when more than one is present, so a single image keeps the clean unnumbered name.
internal fun Resources.transcriptionLabel(segs: List<MessageSegment>, index: Int): String {
    val total = segs.count { it.type == "transcription" }
    if (total <= 1) return getString(R.string.transcription_label)
    val ordinal = segs.take(index + 1).count { it.type == "transcription" }
    return getString(R.string.transcription_label_numbered, ordinal)
}

@Composable
internal fun transcriptionLabel(segs: List<MessageSegment>, index: Int): String =
    currentResources().transcriptionLabel(segs, index)
