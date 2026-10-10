package com.newoether.agora.webui

import android.content.res.Resources
import com.newoether.agora.R
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.message.AssistantContentPresentation
import com.newoether.agora.ui.chat.message.TimelineBlock
import com.newoether.agora.ui.chat.message.assistantContentPresentation
import com.newoether.agora.ui.chat.message.compactSegmentIcon
import com.newoether.agora.ui.chat.message.compactSegmentTitleState
import com.newoether.agora.ui.chat.message.compactSegmentUsesLiveStatus
import com.newoether.agora.ui.chat.message.segmentDetailTitle
import com.newoether.agora.ui.chat.message.timelineBlocks
import com.newoether.agora.ui.chat.message.toolSummary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What shapes a message on the web besides its data: the phone's appearance settings and its
 * [resources] in the app language, so card text matches the phone and not the browser.
 */
internal data class WebDisplayContext(
    val resources: Resources,
    val toolCallDisplayMode: String,
    val thinkingSegmentDisplayMode: String,
    val autoExpandActiveGroup: Boolean,
    val parseInlineDollarMath: Boolean,
    val autoWrapCodeBlocks: Boolean,
    val blurEffectsEnabled: Boolean = true,
    val reduceMotion: Boolean = false,
) {
    /** Settings plus the live timer templates; the browser formats the running seconds. */
    fun toEvent() = WebSyncEvent.Display(
        toolCallDisplayMode = toolCallDisplayMode,
        thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
        autoExpandActiveGroup = autoExpandActiveGroup,
        autoWrapCodeBlocks = autoWrapCodeBlocks,
        blurEffectsEnabled = blurEffectsEnabled,
        reduceMotion = reduceMotion,
        liveThinking = WebLiveTimerStrings(
            seconds = resources.getString(R.string.thinking_for_seconds_ellipsis),
            minutes = resources.getString(R.string.thinking_for_minutes_ellipsis),
            hours = resources.getString(R.string.thinking_for_hours_ellipsis),
        ),
    )
}

/**
 * The app's own [assistantContentPresentation] and [timelineBlocks] for [message], with every
 * title and summary already in the phone's language. Null for user messages.
 */
internal fun webPresentation(
    message: ChatMessage,
    isStreaming: Boolean,
    display: WebDisplayContext,
): WebPresentation? {
    if (message.participant != Participant.MODEL && message.participant != Participant.ERROR) {
        return null
    }
    val resources = display.resources
    val presentation = assistantContentPresentation(
        message = message,
        isStreaming = isStreaming,
        toolCallDisplayMode = display.toolCallDisplayMode,
        thinkingSegmentDisplayMode = display.thinkingSegmentDisplayMode,
        failedToGenerateText = resources.getString(R.string.failed_to_generate),
    )
    val projector = WebPresentationProjector(message, isStreaming, presentation, display)
    val answerBody = presentation.answerBodyText.orEmpty()
    return WebPresentation(
        useTimeline = presentation.useTimelineSegments,
        useThinkingSheet = presentation.useThinkingSheet,
        blocks = if (presentation.useTimelineSegments) {
            timelineBlocks(
                segments = presentation.orderedSegments,
                isStreaming = isStreaming,
                groupAdjacentBlocks = presentation.groupOrderedInfoBlocks,
            ).map(projector::block)
        } else {
            emptyList()
        },
        compact = if (presentation.compactVisible) projector.compact() else null,
        answer = answerBody
            .takeIf { !presentation.useTimelineSegments && it.isNotEmpty() }
            ?.toWebText(display.parseInlineDollarMath),
        inlineTerminalText = presentation.inlineTerminalText(
            message = message,
            isStreaming = isStreaming,
            stoppedText = resources.getString(R.string.generation_stopped),
        ),
        terminalFollowsCard = presentation.terminalImmediatelyFollowsCard(answerBody),
        errorBarText = presentation.errorContent?.errorText?.takeIf { presentation.showsErrorBar },
        stoppedBar = presentation.showsStoppedBar(message, isStreaming),
    )
}

private class WebPresentationProjector(
    private val message: ChatMessage,
    private val isStreaming: Boolean,
    private val presentation: AssistantContentPresentation,
    private val display: WebDisplayContext,
) {
    private val resources = display.resources

    fun block(block: TimelineBlock): WebTimelineBlock = when (block) {
        is TimelineBlock.Answer -> WebTimelineBlock.Answer(
            index = block.index,
            text = block.segment.content.toWebText(display.parseInlineDollarMath),
            streaming = block.isStreaming,
        )
        is TimelineBlock.InfoGroup -> WebTimelineBlock.Group(
            group(
                key = "group:${block.detailIndices.firstOrNull() ?: block.startIndex}",
                segs = block.segments,
                detailIndices = block.detailIndices,
                useLiveStatus = block.useLiveStatus,
                isCurrentCard = block.isCurrentCard,
                autoExpansionActive = block.autoExpansionActive,
                precededByAnswer = block.precededByAnswer,
                imageDetailIndex = block.imageDetailIndex,
            ),
        )
        is TimelineBlock.InfoCard -> WebTimelineBlock.Card(
            item = item(
                seg = block.segment,
                titleSegments = presentation.detailSegments,
                titleIndex = block.detailIndex,
                detailIndex = block.detailIndex,
                streaming = block.isStreamingContent,
            ),
            precededByAnswer = block.precededByAnswer,
            groupPosition = block.groupPosition.name,
        )
    }

    /** The single block compact mode shows above the answer. */
    fun compact(): WebInfoGroup = group(
        key = "compact",
        segs = presentation.detailSegments,
        detailIndices = presentation.detailSegments.indices.toList(),
        useLiveStatus = true,
        isCurrentCard = !presentation.hasAnswerContent,
        autoExpansionActive = false,
        precededByAnswer = false,
        imageDetailIndex = null,
    )

    private fun group(
        key: String,
        segs: List<MessageSegment>,
        detailIndices: List<Int>,
        useLiveStatus: Boolean,
        isCurrentCard: Boolean,
        autoExpansionActive: Boolean,
        precededByAnswer: Boolean,
        imageDetailIndex: Int?,
    ): WebInfoGroup {
        val cardUsesLiveStatus = compactSegmentUsesLiveStatus(
            generationActive = presentation.generationActive,
            isCurrentCard = isCurrentCard,
            useLiveStatus = useLiveStatus,
        )
        val title = resources.compactSegmentTitleState(segs, message, cardUsesLiveStatus)
        return WebInfoGroup(
            key = key,
            title = title.title,
            liveBaseMs = title.liveBaseMs,
            icon = compactSegmentIcon(
                segs = segs,
                message = message,
                generationActive = presentation.generationActive,
                isCurrentCard = isCurrentCard,
                cardUsesLiveStatus = cardUsesLiveStatus,
                collapsedTitle = title.title,
            ).name,
            autoExpansionActive = autoExpansionActive,
            precededByAnswer = precededByAnswer,
            imageDetailIndex = imageDetailIndex,
            items = segs.mapIndexed { index, seg ->
                item(
                    seg = seg,
                    titleSegments = segs,
                    titleIndex = index,
                    detailIndex = detailIndices.getOrElse(index) { index },
                    streaming = isStreaming && useLiveStatus && index == segs.lastIndex,
                )
            },
        )
    }

    private fun item(
        seg: MessageSegment,
        titleSegments: List<MessageSegment>,
        titleIndex: Int,
        detailIndex: Int,
        streaming: Boolean,
    ) = WebInfoItem(
        detailIndex = detailIndex,
        type = seg.type,
        title = resources.segmentDetailTitle(seg, titleSegments, titleIndex),
        summary = if (seg.type == "tool") resources.toolSummary(seg) else null,
        content = seg.content.toWebText(display.parseInlineDollarMath),
        streaming = streaming,
        toolState = seg.toolState,
        toolDetail = if (seg.type == "tool") resources.webToolDetail(seg) else null,
    )
}

@Serializable
internal data class WebLiveTimerStrings(val seconds: String, val minutes: String, val hours: String)

/** Mirrors [AssistantContentPresentation]: which parts show and in what order. */
@Serializable
internal data class WebPresentation(
    val useTimeline: Boolean,
    val useThinkingSheet: Boolean,
    val blocks: List<WebTimelineBlock>,
    /** The compact-mode block above the answer. */
    val compact: WebInfoGroup?,
    /** The compact-mode answer body. */
    val answer: WebText?,
    val inlineTerminalText: String?,
    val terminalFollowsCard: Boolean,
    val errorBarText: String?,
    val stoppedBar: Boolean,
)

@Serializable
internal sealed interface WebTimelineBlock {
    @Serializable @SerialName("answer")
    data class Answer(val index: Int, val text: WebText, val streaming: Boolean) : WebTimelineBlock

    @Serializable @SerialName("group")
    data class Group(val group: WebInfoGroup) : WebTimelineBlock

    @Serializable @SerialName("card")
    data class Card(
        val item: WebInfoItem,
        val precededByAnswer: Boolean,
        val groupPosition: String,
    ) : WebTimelineBlock
}

/**
 * A collapsible info block. When [liveBaseMs] is set the title is a running timer: the browser
 * counts up from it with [WebLiveTimerStrings], as the app does every second.
 */
@Serializable
internal data class WebInfoGroup(
    val key: String,
    val title: String,
    val liveBaseMs: Long?,
    val icon: String,
    val autoExpansionActive: Boolean,
    val precededByAnswer: Boolean,
    /** Detail index of the generated image that ends this block, if any. */
    val imageDetailIndex: Int?,
    val items: List<WebInfoItem>,
)

@Serializable
internal data class WebInfoItem(
    val detailIndex: Int,
    val type: String,
    val title: String,
    val summary: String?,
    val content: WebText,
    val streaming: Boolean,
    val toolState: String?,
    val toolDetail: WebToolDetail? = null,
)

internal fun ChatConversation.toWeb(generating: Boolean) = WebConversation(
    id = id,
    title = title,
    generating = generating,
    unread = hasUnreadGeneration,
)

internal fun ChatMessage.toWebPathEntry() = WebPathEntry(
    id = id,
    parentId = parentId,
    participant = participant.name,
    status = status.name,
)

internal fun ChatMessage.toWeb(inlineDollarMath: Boolean, presentation: WebPresentation?) = WebMessage(
    id = id,
    parentId = parentId,
    participant = participant.name,
    status = status.name,
    timestamp = timestamp,
    modelName = modelName,
    // The user bubble shows plain text, so its math is not split out.
    text = if (participant == Participant.USER) WebText(text) else text.toWebText(inlineDollarMath),
    thoughts = thoughts?.toWebText(inlineDollarMath),
    thoughtTitle = thoughtTitle,
    thoughtTimeMs = thoughtTimeMs,
    segments = segments.orEmpty().map { it.toWeb(inlineDollarMath) },
    presentation = presentation,
)

internal fun MessageSegment.toWeb(inlineDollarMath: Boolean) = WebSegment(
    type = type,
    content = content.toWebText(inlineDollarMath),
    durationMs = durationMs,
    toolName = toolName,
    toolDisplayName = toolDisplayName,
    toolState = toolState,
    errorCode = errorCode,
)
