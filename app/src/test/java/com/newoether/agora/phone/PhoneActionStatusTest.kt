package com.newoether.agora.phone

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.util.appLanguageResources
import com.newoether.agora.ui.chat.message.toolDisplayName
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneActionStatusTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val resources get() = app.appLanguageResources("en")
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
        assertEquals("other", PhoneActionStatus.activity(message, "run", resources)?.callId)
        val selected = checkNotNull(PhoneActionStatus.activity(message, "run", resources, "phone_tap"))
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

    @Test fun capsuleUsesCardNamesInEveryAppLocaleAndRetainsTypingStatus() {
        val names = listOf("shell_exec", "web_search", "memory_read", "openai_search",
            "google_search", "code_execution", "mcp_server_read_document_a1b2c3", "unknown_action") + PHONE_TOOL_NAMES
        for (locale in listOf("en", "ar", "de", "es", "fr", "ja", "ko", "pt-BR", "ru", "vi", "zh", "zh-TW")) {
            val localized = app.appLanguageResources(locale)
            for (name in names) {
                val segment = MessageSegment("tool", toolName = name, toolCallId = "call",
                    toolState = ToolExecutionStates.RUNNING)
                val message = ChatMessage(id = "message", text = "", participant = Participant.MODEL,
                    runId = "run", segments = listOf(segment))
                val selected = checkNotNull(PhoneActionStatus.activity(message, "run", localized))
                val expected = if (name == "phone_type") localized.getString(R.string.phone_typing)
                    else localized.toolDisplayName(segment)
                assertEquals("$locale $name", expected, selected.label)
                assertEquals(name, selected.toolName)
                assertTrue(PhoneActionStatus.isRunning(message, selected))
            }
        }
        val shell = MessageSegment("tool", toolName = "shell_exec", toolCallId = "shell",
            toolState = ToolExecutionStates.RUNNING, toolDisplayName = "shell_exec")
        val message = ChatMessage(id = "message", text = "", participant = Participant.MODEL,
            runId = "run", segments = listOf(shell))
        assertEquals("Shell", PhoneActionStatus.activity(message, "run", app.appLanguageResources("en"))?.label)
        assertEquals("终端", PhoneActionStatus.activity(message, "run", app.appLanguageResources("zh"))?.label)
    }

    @Test fun resolvedMcpNameChangesOnlyPresentationForTheSameCall() = runTest {
        val segment = MessageSegment("tool", toolName = "mcp_call_tool", toolCallId = "call",
            toolState = ToolExecutionStates.CALLING)
        val message = ChatMessage(id = "message", text = "", participant = Participant.MODEL,
            runId = "run", segments = listOf(segment))
        val status = PhoneActionStatus(backgroundScope) { testScheduler.currentTime }
        val initial = checkNotNull(PhoneActionStatus.activity(message, "run", resources))
        status.present(initial)
        assertEquals("MCP", status.name.value)
        val resolved = checkNotNull(PhoneActionStatus.activity(message.copy(segments = listOf(
            segment.copy(toolDisplayName = "read_document", toolState = ToolExecutionStates.RUNNING),
        )), "run", resources))
        assertTrue(initial.sameCall(resolved))
        status.present(resolved)
        assertEquals("Read Document", status.name.value)
        advanceTimeBy(50)
        val next = async { status.awaitVisible(activity("phone_tap")) }
        runCurrent()
        advanceTimeBy(149)
        runCurrent()
        assertFalse(next.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        next.await()
    }

    @Test fun realSegmentsCoverGenericMcpAndHostedToolsWithExactRunIsolation() {
        fun tool(name: String, id: String, state: String = ToolExecutionStates.RUNNING, title: String? = null) =
            MessageSegment("tool", toolName = name, toolCallId = id, toolState = state, toolDisplayName = title)
        fun message(vararg segments: MessageSegment) = ChatMessage(
            id = "message", text = "", participant = Participant.MODEL, runId = "run", segments = segments.toList(),
        )
        for (name in listOf("shell_exec", "web_search", "memory_read", "phone_tap", "openai_search", "google_search", "code_execution")) {
            val segment = tool(name, "call")
            assertEquals(resources.toolDisplayName(segment),
                PhoneActionStatus.activity(message(segment), "run", resources)?.label)
        }
        val mcp = message(tool("mcp_call_tool", "mcp", title = "Read document"))
        assertEquals("Read Document", PhoneActionStatus.activity(mcp, "run", resources)?.label)
        assertNull(PhoneActionStatus.activity(mcp, "other-run", resources))
        val concurrent = message(tool("shell_exec", "first"), tool("web_search", "second"))
        assertEquals("second", PhoneActionStatus.activity(concurrent, "run", resources)?.callId)
        val queued = message(tool("phone_tap", "active"), tool("web_search", "queued", ToolExecutionStates.CALLING))
        assertEquals("active", PhoneActionStatus.activity(queued, "run", resources)?.callId)
        val remaining = message(tool("shell_exec", "first"), tool("web_search", "second", ToolExecutionStates.SUCCEEDED))
        assertEquals("first", PhoneActionStatus.activity(remaining, "run", resources)?.callId)
        assertEquals(false, PhoneActionStatus.activity(message(tool("shell_exec", "bg", ToolExecutionStates.BACKGROUND_RUNNING)), "run", resources)?.running)
        assertNull(PhoneActionStatus.activity(message(MessageSegment("answer", "answer")), "run", resources))
    }
}
