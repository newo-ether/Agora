package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes

/**
 * Which parts an assistant message shows and in what arrangement.
 *
 * The single owner of these decisions for every surface: [AssistantMessageContent] draws it in
 * the app and the WebUI sync sends it to the browser. It holds no UI state; expansion, motion
 * and appearance keys stay with the Composables.
 */
internal data class AssistantContentPresentation(
    /** Citations and hidden tools removed, adjacent answer/thought text merged. */
    val mergedSegments: List<MessageSegment>,
    val generationActive: Boolean,
    val hasAnswerContent: Boolean,
    val errorContent: AssistantErrorContent?,
    /** [mergedSegments] plus a trailing answer when an image boundary would hide the text. */
    val orderedSegments: List<MessageSegment>,
    /** Info segments shown by the shared Thinking card and the detail sheet. */
    val detailSegments: List<MessageSegment>,
    val normalizedToolCallDisplayMode: String,
    val useThinkingSheet: Boolean,
    val groupAdjacentTimelineTools: Boolean,
    val groupOrderedInfoBlocks: Boolean,
    val useTimelineSegments: Boolean,
    val compactVisible: Boolean,
    /** The answer shown below the Thinking card, or null when there is none. */
    val answerBodyText: String?,
) {
    /** True when the terminal bar sits directly under an info card. */
    fun terminalImmediatelyFollowsCard(answerContent: String): Boolean =
        if (useTimelineSegments) {
            val lastVisibleTerminalPredecessor = mergedSegments.lastOrNull { segment ->
                segment.isVisibleAnswerSegment() || segment.isInfoSegment()
            }
            lastVisibleTerminalPredecessor?.isInfoSegment() == true
        } else {
            compactVisible && answerContent.isEmpty()
        }

    /** Inline terminal text for a message with no answer: the error, or [stoppedText]. */
    fun inlineTerminalText(
        message: ChatMessage,
        isStreaming: Boolean,
        stoppedText: String,
    ): String? = when {
        hasAnswerContent -> null
        errorContent != null -> errorContent.errorText
        !isStreaming && message.status == MessageStatus.STOPPED -> stoppedText
        else -> null
    }

    /** The error bar under an answer; without an answer the error is inline instead. */
    val showsErrorBar: Boolean get() = hasAnswerContent && errorContent != null

    fun showsStoppedBar(message: ChatMessage, isStreaming: Boolean): Boolean =
        hasAnswerContent && !isStreaming && message.status == MessageStatus.STOPPED
}

internal fun assistantContentPresentation(
    message: ChatMessage,
    isStreaming: Boolean,
    toolCallDisplayMode: String,
    thinkingSegmentDisplayMode: String,
    failedToGenerateText: String,
): AssistantContentPresentation {
    val mergedSegments = mergeAdjacentSegments(message.segments.orEmpty())
    val generationActive = message.participant == Participant.MODEL &&
        (
            isStreaming ||
                message.status == MessageStatus.SENDING ||
                message.status == MessageStatus.THINKING ||
                message.status == MessageStatus.TOOL_CALLING ||
                message.status == MessageStatus.TRANSCRIBING
            )
    val hasAnswerContent =
        message.text.isNotBlank() || mergedSegments.any { it.isVisibleAnswerSegment() }
    val renderedText = message.text
    val isError = message.status == MessageStatus.ERROR || message.participant == Participant.ERROR
    val errorContent = assistantErrorContent(message, mergedSegments, failedToGenerateText)
    val hasImageGenerationBoundary = mergedSegments.any { it.isImageGenerationSegment() }
    val orderedFallbackAnswerText =
        if (hasImageGenerationBoundary && mergedSegments.none { it.isVisibleAnswerSegment() }) {
            errorContent?.answerText ?: renderedText.takeIf { !isError }
        } else {
            null
        }
    val orderedSegments = orderedFallbackAnswerText
        ?.takeIf { it.isNotBlank() }
        ?.let { fallback -> mergedSegments + MessageSegment(type = "answer", content = fallback) }
        ?: mergedSegments
    val normalizedToolCallDisplayMode = ToolCallDisplayModes.normalize(toolCallDisplayMode)
    val useThinkingSheet = ThinkingSegmentDisplayModes.effectiveMode(
        thinkingSegmentDisplayMode,
        normalizedToolCallDisplayMode,
    ) == ThinkingSegmentDisplayModes.BOTTOM_SHEET
    val groupAdjacentTimelineTools =
        normalizedToolCallDisplayMode == ToolCallDisplayModes.GROUPED_TIMELINE
    val groupOrderedInfoBlocks = groupAdjacentTimelineTools
    val useTimelineSegments = hasImageGenerationBoundary ||
        (
            !useThinkingSheet &&
                (
                    mergedSegments.any { it.type == "answer" } ||
                        (groupAdjacentTimelineTools && mergedSegments.any { it.isInfoSegment() })
                    )
            )
    val detailSegments = mergedSegments.filter { it.type != "answer" && it.type != "error" }
    return AssistantContentPresentation(
        mergedSegments = mergedSegments,
        generationActive = generationActive,
        hasAnswerContent = hasAnswerContent,
        errorContent = errorContent,
        orderedSegments = orderedSegments,
        detailSegments = detailSegments,
        normalizedToolCallDisplayMode = normalizedToolCallDisplayMode,
        useThinkingSheet = useThinkingSheet,
        groupAdjacentTimelineTools = groupAdjacentTimelineTools,
        groupOrderedInfoBlocks = groupOrderedInfoBlocks,
        useTimelineSegments = useTimelineSegments,
        compactVisible = !useTimelineSegments && detailSegments.isNotEmpty(),
        answerBodyText = errorContent?.answerText ?: renderedText.takeIf { !isError },
    )
}

/** One entry of the timeline arrangement, in display order. */
internal sealed interface TimelineBlock {
    /** A non-blank answer slice; [answerOffset] is its start in the whole answer text. */
    data class Answer(
        val index: Int,
        val segment: MessageSegment,
        val answerOffset: Int,
        val isStreaming: Boolean,
    ) : TimelineBlock

    /** Adjacent info segments drawn as one collapsible card. */
    data class InfoGroup(
        val startIndex: Int,
        val segments: List<MessageSegment>,
        val detailIndices: List<Int>,
        val endExclusive: Int,
        val useLiveStatus: Boolean,
        val isCurrentCard: Boolean,
        val autoExpansionActive: Boolean,
        val precededByAnswer: Boolean,
        /** A trailing image-generation segment whose thumbnail follows the card. */
        val imageBoundary: MessageSegment?,
        val imageDetailIndex: Int?,
    ) : TimelineBlock

    /** One info segment drawn as its own card (ungrouped timeline). */
    data class InfoCard(
        val index: Int,
        val segment: MessageSegment,
        val detailIndex: Int,
        val isStreamingContent: Boolean,
        val precededByAnswer: Boolean,
        val groupPosition: SegmentGroupPosition,
    ) : TimelineBlock
}

/** The timeline arrangement of [segments]; detail indices count info segments in order. */
internal fun timelineBlocks(
    segments: List<MessageSegment>,
    isStreaming: Boolean,
    groupAdjacentBlocks: Boolean,
): List<TimelineBlock> {
    val blocks = mutableListOf<TimelineBlock>()
    var detailIndex = 0
    var answerOffset = 0
    var index = 0
    var previousVisibleWasAnswer = false
    val lastVisibleSegmentIndex = segments.indexOfLast { segment ->
        segment.isVisibleAnswerSegment() || segment.isInfoSegment()
    }
    while (index < segments.size) {
        val seg = segments[index]
        when (seg.type) {
            "answer" -> {
                if (seg.content.isNotBlank()) {
                    blocks += TimelineBlock.Answer(
                        index = index,
                        segment = seg,
                        answerOffset = answerOffset,
                        isStreaming = isStreaming && index == lastVisibleSegmentIndex,
                    )
                    previousVisibleWasAnswer = true
                }
                answerOffset += seg.content.length
                index++
            }
            "thought", "tool", "transcription" -> {
                if (groupAdjacentBlocks) {
                    val blockSegments = mutableListOf<MessageSegment>()
                    val blockDetailIndices = mutableListOf<Int>()
                    val blockEnd = groupedInfoBlockEndExclusive(segments, index)
                    for (cursor in index until blockEnd) {
                        if (segments[cursor].isInfoSegment()) {
                            blockSegments += segments[cursor]
                            blockDetailIndices += detailIndex
                            detailIndex++
                        }
                    }
                    val imageBoundary =
                        blockSegments.lastOrNull()?.takeIf { it.isImageGenerationSegment() }
                    blocks += TimelineBlock.InfoGroup(
                        startIndex = index,
                        segments = blockSegments,
                        detailIndices = blockDetailIndices,
                        endExclusive = blockEnd,
                        useLiveStatus = isStreaming && blockEnd > lastVisibleSegmentIndex,
                        isCurrentCard = blockEnd > lastVisibleSegmentIndex,
                        autoExpansionActive = isStreaming && blockEnd == segments.size,
                        precededByAnswer = previousVisibleWasAnswer,
                        imageBoundary = imageBoundary,
                        imageDetailIndex = blockDetailIndices.lastOrNull().takeIf { imageBoundary != null },
                    )
                    previousVisibleWasAnswer = false
                    index = blockEnd
                } else {
                    blocks += TimelineBlock.InfoCard(
                        index = index,
                        segment = seg,
                        detailIndex = detailIndex,
                        isStreamingContent = isStreaming && index == lastVisibleSegmentIndex,
                        precededByAnswer = previousVisibleWasAnswer,
                        groupPosition = timelineSegmentGroupPosition(segments, index),
                    )
                    detailIndex++
                    previousVisibleWasAnswer = false
                    index++
                }
            }
            else -> index++
        }
    }
    return blocks
}
