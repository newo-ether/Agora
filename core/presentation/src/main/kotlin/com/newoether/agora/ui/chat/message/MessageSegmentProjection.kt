package com.newoether.agora.ui.chat.message

import com.newoether.agora.api.LOCAL_CONTEXT_CAPACITY_ERROR_CODE
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment

internal fun MessageSegment.isHiddenFromMessagePresentation(): Boolean =
    type == "tool" && toolName == "google_search"

fun mergeAdjacentSegments(segs: List<MessageSegment>): List<MessageSegment> {
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

fun MessageSegment.isVisibleAnswerSegment(): Boolean =
    type == "answer" && content.isNotBlank()

fun MessageSegment.isInfoSegment(): Boolean =
    (type == "thought" && content.isNotBlank()) || type == "tool" || type == "transcription"

fun MessageSegment.isImageGenerationSegment(): Boolean =
    type == "tool" && remoteToolSource == null &&
        com.newoether.agora.tool.BuiltInToolNames.canonical(toolName.orEmpty()) == "image_generate"

fun groupedInfoBlockEndExclusive(
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

enum class SegmentGroupPosition {
    SINGLE,
    FIRST,
    MIDDLE,
    LAST,
}

fun segmentGroupPosition(
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

data class AssistantErrorContent(
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
fun assistantErrorContent(
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
