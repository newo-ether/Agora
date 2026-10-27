package com.newoether.agora.phone

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolExecutionStates
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PhoneActionStatusTest {
    private fun activity(name: String, id: String = name, running: Boolean = true) =
        PhoneActionStatus.Activity("run", id, name, name, running)

    @Test fun completionRetainsTheActionForExactlyOneSecond() = runTest {
        val status = PhoneActionStatus(backgroundScope) { testScheduler.currentTime }
        val tap = activity("phone_tap")
        status.present(tap)
        status.present(tap.copy(running = false))
        runCurrent()
        advanceTimeBy(999)
        assertEquals("phone_tap", status.name.value)
        advanceTimeBy(1)
        runCurrent()
        assertNull(status.name.value)
        status.present(tap.copy(running = false))
        assertNull(status.name.value)
    }

    @Test fun nextActionCancelsThinkingDeadlineAndWaitsOnlyForMinimumDwell() = runTest {
        val status = PhoneActionStatus(backgroundScope) { testScheduler.currentTime }
        status.present(activity("phone_tap"))
        status.present(null)
        runCurrent()
        advanceTimeBy(50)
        val next = async { status.awaitVisible(activity("phone_swipe")) }
        runCurrent()
        advanceTimeBy(149)
        runCurrent()
        assertFalse(next.isCompleted)
        assertEquals("phone_tap", status.name.value)
        advanceTimeBy(1)
        runCurrent()
        next.await()
        assertEquals("phone_swipe", status.name.value)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals("phone_swipe", status.name.value)
        status.present(null)
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertNull(status.name.value)
    }

    @Test fun repeatedNamesHaveDistinctCallsAndProgressDoesNotRenewDwell() = runTest {
        val status = PhoneActionStatus(backgroundScope) { testScheduler.currentTime }
        status.present(activity("phone_tap", "first"))
        advanceTimeBy(50)
        status.present(activity("phone_tap", "first"))
        val second = async { status.awaitVisible(activity("phone_tap", "second")) }
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        second.await()
        val third = async { status.awaitVisible(activity("phone_type")) }
        runCurrent()
        advanceTimeBy(199)
        runCurrent()
        assertFalse(third.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        third.await()
        assertEquals("phone_type", status.name.value)
    }

    @Test fun latestTargetCoalescesRapidToolsWithoutBuildingAQueue() = runTest {
        val status = PhoneActionStatus(backgroundScope) { testScheduler.currentTime }
        status.present(activity("shell_exec"))
        advanceTimeBy(20)
        status.present(activity("web_search"))
        advanceTimeBy(20)
        status.present(activity("mcp_call_tool"))
        advanceTimeBy(159)
        runCurrent()
        assertEquals("shell_exec", status.name.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("mcp_call_tool", status.name.value)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals("mcp_call_tool", status.name.value)
    }

    @Test fun resetCancelsOldIdleAndRejectsAnOldDelayedAction() = runTest {
        val status = PhoneActionStatus(backgroundScope) { testScheduler.currentTime }
        status.present(activity("phone_tap"))
        val old = async { runCatching { status.awaitVisible(activity("phone_swipe")) }.exceptionOrNull() }
        runCurrent()
        status.reset()
        status.present(activity("shell_exec").copy(runId = "successor"))
        advanceTimeBy(1200)
        runCurrent()
        assertTrue(old.await() is PhoneFailure)
        assertEquals("shell_exec", status.name.value)
    }

    @Test fun completedWhilePendingStillGetsDwellAndItsOriginalGraceDeadline() = runTest {
        val status = PhoneActionStatus(backgroundScope) { testScheduler.currentTime }
        status.present(activity("shell_exec"))
        advanceTimeBy(50)
        status.present(activity("web_search"))
        advanceTimeBy(10)
        status.present(activity("web_search", running = false))
        advanceTimeBy(140)
        runCurrent()
        assertEquals("web_search", status.name.value)
        advanceTimeBy(859)
        runCurrent()
        assertEquals("web_search", status.name.value)
        advanceTimeBy(1)
        runCurrent()
        assertNull(status.name.value)
    }

    @Test fun displayReplacementRewaitsWithoutRejectingTheLiveCall() = runTest {
        val status = PhoneActionStatus(backgroundScope) { testScheduler.currentTime }
        status.present(activity("shell_exec"))
        advanceTimeBy(50)
        val phone = activity("phone_tap")
        var current = true
        val waiting = async { status.awaitVisible(phone) { current } }
        runCurrent()
        status.present(activity("web_search"))
        runCurrent()
        advanceTimeBy(149)
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertEquals("shell_exec", status.name.value)
        advanceTimeBy(1)
        runCurrent()
        waiting.await()
        assertEquals("phone_tap", status.name.value)

        val withdrawn = async {
            runCatching { status.awaitVisible(activity("phone_swipe")) { current } }.exceptionOrNull()
        }
        runCurrent()
        current = false
        advanceTimeBy(200)
        runCurrent()
        assertTrue(withdrawn.await() is PhoneFailure)
    }

    @Test fun phoneSelectionAndValidationIgnoreAnotherRunningDisplayTarget() {
        val phone = MessageSegment("tool", toolName = "phone_tap", toolCallId = "phone",
            toolState = ToolExecutionStates.RUNNING)
        val message = ChatMessage(id = "message", text = "", participant = Participant.MODEL, runId = "run",
            segments = listOf(phone, MessageSegment("tool", toolName = "web_search", toolCallId = "other",
                toolState = ToolExecutionStates.RUNNING), phone.copy(toolCallId = "queued",
                toolState = ToolExecutionStates.CALLING)))
        assertEquals("other", PhoneActionStatus.activity(message, "run")?.callId)
        val selected = checkNotNull(PhoneActionStatus.activity(message, "run", "phone_tap"))
        assertEquals("phone", selected.callId)
        assertTrue(PhoneActionStatus.isRunning(message, selected))
        assertFalse(PhoneActionStatus.isRunning(message.copy(runId = "successor"), selected))
        assertFalse(PhoneActionStatus.isRunning(message.copy(segments = listOf(
            phone.copy(toolState = ToolExecutionStates.SUCCEEDED),
        )), selected))
        assertFalse(PhoneActionStatus.isRunning(message.copy(segments = listOf(
            phone.copy(toolCallId = "replacement"),
        )), selected))
    }

    @Test fun realSegmentsCoverGenericMcpAndHostedToolsWithExactRunIsolation() {
        fun tool(name: String, id: String, state: String = ToolExecutionStates.RUNNING, title: String? = null) =
            MessageSegment("tool", toolName = name, toolCallId = id, toolState = state, toolDisplayName = title)
        fun message(vararg segments: MessageSegment) = ChatMessage(
            id = "message", text = "", participant = Participant.MODEL, runId = "run", segments = segments.toList(),
        )
        for (name in listOf("shell_exec", "web_search", "memory_read", "phone_tap", "openai_search", "google_search", "code_execution")) {
            assertEquals(name, PhoneActionStatus.activity(message(tool(name, "call")), "run")?.label)
        }
        val mcp = message(tool("mcp_call_tool", "mcp", title = "Read document"))
        assertEquals("Read document", PhoneActionStatus.activity(mcp, "run")?.label)
        assertNull(PhoneActionStatus.activity(mcp, "other-run"))
        val concurrent = message(tool("shell_exec", "first"), tool("web_search", "second"))
        assertEquals("second", PhoneActionStatus.activity(concurrent, "run")?.callId)
        val queued = message(tool("phone_tap", "active"), tool("web_search", "queued", ToolExecutionStates.CALLING))
        assertEquals("active", PhoneActionStatus.activity(queued, "run")?.callId)
        val remaining = message(tool("shell_exec", "first"), tool("web_search", "second", ToolExecutionStates.SUCCEEDED))
        assertEquals("first", PhoneActionStatus.activity(remaining, "run")?.callId)
        assertEquals(false, PhoneActionStatus.activity(message(tool("shell_exec", "bg", ToolExecutionStates.BACKGROUND_RUNNING)), "run")?.running)
        assertNull(PhoneActionStatus.activity(message(MessageSegment("answer", "answer")), "run"))
    }
}
