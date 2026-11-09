package com.newoether.agora.ui.chat.message

import com.newoether.agora.api.LOCAL_CONTEXT_CAPACITY_ERROR_CODE
import com.newoether.agora.model.MessageSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageItemSegmentsTest {
    @Test
    fun uiSegmentPreparationHidesOnlyExactGeminiGoogleSearchTool() {
        val merged = mergeAdjacentSegments(
            listOf(
                MessageSegment(type = "answer", content = "Answer"),
                MessageSegment(type = "tool", toolName = "google_search", toolResult = "{}"),
                MessageSegment(type = "tool", toolName = "web_search", toolResult = "{}"),
                MessageSegment(type = "tool", toolName = "openai_search", toolResult = "{}"),
                MessageSegment(type = "tool", toolName = "code_execution", toolResult = "{}"),
                MessageSegment(type = "tool", toolName = "Google_Search", toolResult = "{}"),
            ),
        )

        assertEquals(
            listOf("web_search", "openai_search", "code_execution", "Google_Search"),
            merged.filter { it.type == "tool" }.map { it.toolName },
        )
        assertEquals("Answer", merged.first().content)
    }

    @Test
    fun settingsStyleSegmentGroupPositionsCoverSingleFirstMiddleAndLast() {
        assertEquals(
            SegmentGroupPosition.SINGLE,
            segmentGroupPosition(hasPrevious = false, hasNext = false),
        )
        assertEquals(
            SegmentGroupPosition.FIRST,
            segmentGroupPosition(hasPrevious = false, hasNext = true),
        )
        assertEquals(
            SegmentGroupPosition.MIDDLE,
            segmentGroupPosition(hasPrevious = true, hasNext = true),
        )
        assertEquals(
            SegmentGroupPosition.LAST,
            segmentGroupPosition(hasPrevious = true, hasNext = false),
        )
    }

    @Test
    fun timelineSegmentGroupsBreakAtVisibleAnswers() {
        val segments = listOf(
            MessageSegment(type = "thought", content = "Thinking"),
            MessageSegment(type = "tool"),
            MessageSegment(type = "answer", content = "Answer"),
            MessageSegment(type = "transcription"),
        )

        assertEquals(SegmentGroupPosition.FIRST, timelineSegmentGroupPosition(segments, 0))
        assertEquals(SegmentGroupPosition.LAST, timelineSegmentGroupPosition(segments, 1))
        assertEquals(SegmentGroupPosition.SINGLE, timelineSegmentGroupPosition(segments, 3))
    }

    @Test
    fun timelineSegmentGroupsFollowRenderedOrderAcrossSkippedSegments() {
        val segments = listOf(
            MessageSegment(type = "error", content = "Earlier error"),
            MessageSegment(type = "thought", content = "Thinking"),
            MessageSegment(type = "answer"),
            MessageSegment(type = "error", content = "Skipped error"),
            MessageSegment(type = "tool"),
            MessageSegment(type = "answer"),
            MessageSegment(type = "transcription"),
            MessageSegment(type = "error", content = "Later error"),
        )

        assertEquals(SegmentGroupPosition.FIRST, timelineSegmentGroupPosition(segments, 1))
        assertEquals(SegmentGroupPosition.MIDDLE, timelineSegmentGroupPosition(segments, 4))
        assertEquals(SegmentGroupPosition.LAST, timelineSegmentGroupPosition(segments, 6))
    }

    @Test
    fun timelineSegmentGroupInvalidIndicesFailClosed() {
        val segments = listOf(MessageSegment(type = "tool"))

        assertEquals(SegmentGroupPosition.SINGLE, timelineSegmentGroupPosition(segments, -1))
        assertEquals(SegmentGroupPosition.SINGLE, timelineSegmentGroupPosition(segments, 1))
    }

    @Test
    fun localContextHelpRequiresStableCodeAndEmbeddedLocalModel() {
        assertTrue(
            shouldShowLocalContextHelp(
                errorCode = LOCAL_CONTEXT_CAPACITY_ERROR_CODE,
                modelName = "Local: model.gguf",
            ),
        )
        listOf("Remote: model", "Ollama: model", null).forEach { modelName ->
            assertFalse(
                shouldShowLocalContextHelp(
                    errorCode = LOCAL_CONTEXT_CAPACITY_ERROR_CODE,
                    modelName = modelName,
                ),
            )
        }
        assertFalse(
            shouldShowLocalContextHelp(
                errorCode = "different_error",
                modelName = "Local: model.gguf",
            ),
        )
        assertFalse(shouldShowLocalContextHelp(errorCode = null, modelName = "Local: model.gguf"))
    }
}
