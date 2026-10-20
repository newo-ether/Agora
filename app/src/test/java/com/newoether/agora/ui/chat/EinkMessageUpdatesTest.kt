package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EinkMessageUpdatesTest {
    private fun message(text: String, status: MessageStatus = MessageStatus.SENDING, run: String = "run") =
        ChatMessage(id = "answer", text = text, participant = Participant.MODEL, status = status, runId = run)

    @Test
    fun sustainedTextGrowthPublishesLatestOncePerSecondWithoutChangingInput() = runTest {
        val source = MutableSharedFlow<List<ChatMessage>>()
        val shown = mutableListOf<List<ChatMessage>>()
        val job = backgroundScope.launch { source.coalesceEinkMessages().collect { shown += it } }
        runCurrent()
        source.emit(listOf(message(""))); runCurrent()
        for (index in 1..9) {
            source.emit(listOf(message("token $index"))); runCurrent(); advanceTimeBy(100)
        }
        assertEquals(1, shown.size)
        advanceTimeBy(100); runCurrent()
        assertEquals("token 9", shown.last().single().text)
        val final = listOf(message("complete and persisted", MessageStatus.SUCCESS))
        source.emit(final); runCurrent()
        assertEquals(final, shown.last())
        assertEquals("complete and persisted", final.single().text)
        job.cancel()
    }

    @Test
    fun stopErrorAndRunReplacementFlushImmediatelyAndCancelOldPendingText() = runTest {
        for (terminal in listOf(MessageStatus.SUCCESS, MessageStatus.STOPPED, MessageStatus.ERROR)) {
            val source = MutableSharedFlow<List<ChatMessage>>()
            val shown = mutableListOf<List<ChatMessage>>()
            val job = backgroundScope.launch { source.coalesceEinkMessages().collect { shown += it } }
            runCurrent()
            source.emit(listOf(message("initial"))); runCurrent()
            source.emit(listOf(message("deferred"))); runCurrent()
            source.emit(listOf(message("terminal", terminal))); runCurrent()
            assertEquals("terminal", shown.last().single().text)
            advanceTimeBy(2_000); runCurrent()
            assertEquals(2, shown.size)
            source.emit(listOf(message("replacement", run = "new-run"))); runCurrent()
            assertEquals("replacement", shown.last().single().text)
            job.cancel()
        }
    }

    @Test
    fun cancellationDropsPendingSnapshotBeforeNewConversationCollection() = runTest {
        val source = MutableSharedFlow<List<ChatMessage>>()
        val shown = mutableListOf<List<ChatMessage>>()
        val job = backgroundScope.launch { source.coalesceEinkMessages().collect { shown += it } }
        runCurrent()
        source.emit(listOf(message("initial"))); runCurrent()
        source.emit(listOf(message("old conversation pending"))); runCurrent()
        job.cancel(); runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf("initial"), shown.map { it.single().text })
    }

    @Test
    fun completedPresentationCollectionFlushesItsLastAccumulatedText() = runTest {
        val initial = listOf(message("initial"))
        val complete = listOf(message("all accumulated text"))
        val result = flow { emit(initial); emit(complete) }.coalesceEinkMessages().toList()
        assertEquals(complete, result.last())
    }

    @Test
    fun branchAndToolLifecycleChangesAreImmediate() {
        val before = message("a")
        assertFalse(sameEinkStreamingUpdate(listOf(before), listOf(before.copy(parentId = "new-branch"))))
        assertFalse(sameEinkStreamingUpdate(listOf(before), listOf(before.copy(status = MessageStatus.TOOL_CALLING))))
        assertTrue(sameEinkStreamingUpdate(listOf(before), listOf(before.copy(text = "b"))))
    }
}
