package com.newoether.agora.remote

import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.message.assistantErrorContent
import org.junit.Assert.*
import org.junit.Test

class RemoteTurnErrorTest {
    private val error = RemoteMessage("filo-turn-error:t", "t", null, "assistant",
        "The context window was exceeded.", 1, error = true)
    private val user = RemoteMessage("u", "t", null, "user", "Question", 1)
    private val answer = RemoteMessage("a", "t", null, "assistant", "Partial answer", 1)
    private val tool = RemoteMessage("tool", "t", null, "assistant", "", 1,
        RemoteActivity("tool", "execute_shell_command", state = "succeeded"))

    @Test fun nativeFailureUsesOriginalErrorPresentationAndPreservesExistingAnswerAndTools() {
        for (records in listOf(listOf(user, error), listOf(user, tool, error), listOf(user, answer, tool, error))) {
            val messages = projectRemoteMessages(records, RemoteRuntime("active", "t"))
            val failed = messages.last()
            assertEquals(Participant.MODEL, failed.participant)
            assertEquals(MessageStatus.ERROR, failed.status)
            val original = assistantErrorContent(failed, failed.segments.orEmpty(), "Fallback")!!
            assertEquals(error.text, original.errorText)
            assertEquals(answer.text.takeIf { answer in records }, original.answerText)
            assertFalse(original.showLocalContextHelp)
            assertEquals(records.count { it.activity?.type == "tool" }, failed.segments!!.count { it.type == "tool" })
            assertEquals("error", failed.segments.last().type)
            assertFalse(messages.any { it.status in setOf(MessageStatus.THINKING, MessageStatus.TOOL_CALLING, MessageStatus.SENDING) })
        }
    }

    @Test fun errorStatusSurvivesTopologyPrependAndOriginalHydrationStatusCopy() {
        val page = bodyPage(listOf(user, answer, tool, error), "older", emptyList(), RemoteRuntime("active", "t"))
        val nodes = admitRemotePage(emptyList(), page)
        val groups = projectRemoteTopology(nodes, page.runtime)
        assertEquals(MessageStatus.ERROR, groups.last().stub.status)
        val prior = bodyPage(listOf(answer.copy(id = "prior", turnId = "old")), null, emptyList())
        val prepended = projectRemoteTopology(admitRemotePage(nodes, prior, older = true), page.runtime)
        assertEquals(groups, prepended.takeLast(groups.size))
        val body = projectRemoteMessages(page.messages).last().copy(status = groups.last().stub.status)
        assertEquals(error.text, assistantErrorContent(body, body.segments.orEmpty(), "Fallback")!!.errorText)
    }

    @Test fun retiredToolDisplayUsesTheSharedGroupedPolicyForNativeRemoteMessages() {
        val native = projectRemoteMessages(listOf(user, answer, tool, error)).last()
        fun present(mode: String) = com.newoether.agora.ui.chat.message.assistantContentPresentation(
            native, false, mode, com.newoether.agora.model.ThinkingSegmentDisplayModes.CARD, "Fallback")
        val grouped = present(com.newoether.agora.model.ToolCallDisplayModes.GROUPED_TIMELINE)
        assertEquals(grouped, present("compact"))
        assertTrue(grouped.useTimelineSegments)
        assertTrue(grouped.groupAdjacentTimelineTools)
        assertEquals(error.text, grouped.errorContent!!.errorText)
        assertEquals(listOf(answer.text), com.newoether.agora.ui.chat.message.timelineBlocks(
            grouped.orderedSegments, false, grouped.groupOrderedInfoBlocks,
        ).filterIsInstance<com.newoether.agora.ui.chat.message.TimelineBlock.Answer>().map { it.segment.content })
    }

    @Test fun unknownRuntimeDoesNotInventANativeErrorAndANewTurnCanGenerateAfterFailure() {
        assertNull(assistantErrorContent(projectRemoteMessages(listOf(answer), RemoteRuntime("notLoaded")).single(), emptyList(), "Fallback"))
        val nextUser = user.copy(id = "u2", turnId = "next")
        val messages = projectRemoteMessages(listOf(user, error, nextUser), RemoteRuntime("active", "next"))
        assertEquals(MessageStatus.ERROR, messages[1].status)
        assertEquals(MessageStatus.SENDING, messages.last().status)
        assertEquals("next", messages.last().runId)
    }
}
