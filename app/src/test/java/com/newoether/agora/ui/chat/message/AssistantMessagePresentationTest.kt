package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantMessagePresentationTest {
    private val thought = MessageSegment(type = "thought", content = "plan")
    private val tool = MessageSegment(type = "tool", toolName = "file_read")
    private val answer = MessageSegment(type = "answer", content = "Done.")

    private fun message(
        segments: List<MessageSegment>,
        text: String = segments.filter { it.type == "answer" }.joinToString("") { it.content },
        status: MessageStatus = MessageStatus.SUCCESS,
    ) = ChatMessage(id = "m", text = text, participant = Participant.MODEL, status = status, segments = segments)

    private fun present(
        message: ChatMessage,
        toolMode: String = ToolCallDisplayModes.DEFAULT,
        thinkingMode: String = ThinkingSegmentDisplayModes.DEFAULT,
        isStreaming: Boolean = false,
    ) = assistantContentPresentation(message, isStreaming, toolMode, thinkingMode, "Failed")

    @Test
    fun groupedTimelineGroupsAdjacentInfoSegmentsBeforeTheAnswer() {
        val presentation = present(message(listOf(thought, tool, answer)))
        assertTrue(presentation.useTimelineSegments)
        assertFalse(presentation.compactVisible)
        val blocks = timelineBlocks(presentation.orderedSegments, isStreaming = false, presentation.groupOrderedInfoBlocks)
        val group = blocks[0] as TimelineBlock.InfoGroup
        assertEquals(listOf(thought, tool), group.segments)
        assertEquals(listOf(0, 1), group.detailIndices)
        assertEquals(0, group.startIndex)
        assertFalse(group.isCurrentCard)
        val body = blocks[1] as TimelineBlock.Answer
        assertEquals(2, body.index)
        assertTrue(presentation.terminalImmediatelyFollowsCard("Done.").not())
    }

    @Test
    fun streamingGroupAtTheEndIsLiveAndAutoExpands() {
        val presentation = present(message(listOf(answer, thought), status = MessageStatus.THINKING), isStreaming = true)
        val blocks = timelineBlocks(presentation.orderedSegments, isStreaming = true, presentation.groupOrderedInfoBlocks)
        val group = blocks[1] as TimelineBlock.InfoGroup
        assertTrue(group.useLiveStatus)
        assertTrue(group.isCurrentCard)
        assertTrue(group.autoExpansionActive)
        assertTrue(group.precededByAnswer)
        assertFalse((blocks[0] as TimelineBlock.Answer).isStreaming)
    }

    @Test
    fun ungroupedTimelineGivesOneCardPerInfoSegmentWithGroupPositions() {
        val presentation = present(message(listOf(thought, tool, answer)), toolMode = ToolCallDisplayModes.TIMELINE)
        val cards = timelineBlocks(presentation.orderedSegments, false, presentation.groupOrderedInfoBlocks)
            .filterIsInstance<TimelineBlock.InfoCard>()
        assertEquals(listOf(0, 1), cards.map { it.detailIndex })
        assertEquals(listOf(SegmentGroupPosition.FIRST, SegmentGroupPosition.LAST), cards.map { it.groupPosition })
    }

    @Test
    fun legacyCompactUsesGroupedTimelineAndSheetKeepsTheSharedThinkingCard() {
        val grouped = present(message(listOf(thought, tool, answer)))
        val legacy = present(message(listOf(thought, tool, answer)), toolMode = "compact")
        assertEquals(grouped, legacy)
        assertTrue(legacy.useTimelineSegments)
        assertFalse(legacy.compactVisible)
        val sheet = present(message(listOf(thought, answer)), thinkingMode = ThinkingSegmentDisplayModes.BOTTOM_SHEET)
        assertTrue(sheet.useThinkingSheet)
        assertTrue(sheet.compactVisible)
    }

    @Test
    fun errorAfterAnAnswerIsABarAndWithoutOneIsInline() {
        val error = MessageSegment(type = "error", content = "Rate limited")
        val withAnswer = present(message(listOf(answer, error), text = "Done.", status = MessageStatus.ERROR))
        assertTrue(withAnswer.showsErrorBar)
        assertEquals("Done.", withAnswer.answerBodyText)
        val m = message(listOf(error), text = "", status = MessageStatus.ERROR)
        val alone = present(m)
        assertFalse(alone.showsErrorBar)
        assertEquals("Rate limited", alone.inlineTerminalText(m, isStreaming = false, stoppedText = "Stopped"))
    }

    @Test
    fun stoppedWithoutAnAnswerShowsInlineTextNotABar() {
        val m = message(listOf(thought), text = "", status = MessageStatus.STOPPED)
        val presentation = present(m)
        assertEquals("Stopped", presentation.inlineTerminalText(m, isStreaming = false, stoppedText = "Stopped"))
        assertFalse(presentation.showsStoppedBar(m, isStreaming = false))
        assertNull(presentation.inlineTerminalText(m, isStreaming = true, stoppedText = "Stopped"))
        assertTrue(presentation.terminalImmediatelyFollowsCard(""))
    }

    @Test
    fun imageBoundaryAppendsTheTextAsTheFinalAnswer() {
        val image = MessageSegment(type = "tool", toolName = "generate_image")
        val presentation = present(message(listOf(thought, image), text = "Here it is."))
        assertTrue(presentation.useTimelineSegments)
        assertEquals("Here it is.", presentation.orderedSegments.last().content)
        val group = timelineBlocks(presentation.orderedSegments, false, presentation.groupOrderedInfoBlocks)
            .first() as TimelineBlock.InfoGroup
        assertEquals(image, group.imageBoundary)
        assertEquals(1, group.imageDetailIndex)
    }
}
