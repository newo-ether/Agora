package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GenerationRetryErrorTest {
    @Test fun latestRetryErrorSurvivesResetAndMailboxStopWithoutChangingStopSemantics() = runBlocking {
        val harness = Harness()
        harness.output.beginProviderRequest()
        harness.accept(StreamEvent.Retrying(1, 5, error = GenerationError.Configuration("First failure")))
        harness.accept(StreamEvent.Retrying(2, 5, resetCurrentAttempt = true,
            error = GenerationError.Configuration("Last failure")))
        assertEquals(MessageStatus.SENDING, harness.output.currentStatus)
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val token = requireNotNull(state.acquireForSend())
        state.bindRun(token, "run")
        state.streamUpdate(token, harness.snapshot())

        val stopped = state.stop()
        val message = requireNotNull(stopped.stoppedMessage)
        assertEquals(MessageStatus.STOPPED, message.status)
        assertEquals("Last failure", message.segments!!.single { it.type == "error" }.content)
        assertNull(message.retryText)
        assertTrue(state.stopping.value)
        assertFalse(state.isCurrentToken(token))
        state.streamUpdate(token, harness.snapshot().copy(text = "stale output"))
        assertEquals(message, state.streamingMessage.value)
        assertNull(state.acquireForSend())
        assertTrue(state.finishStopFinalization(stopped.completion(true)).accepted)
    }

    @Test fun ordinaryStopAndRetryWithoutDiagnosticDoNotInventAnError() = runBlocking {
        for (retry in listOf<StreamEvent.Retrying?>(null, StreamEvent.Retrying(1, 5))) {
            val harness = Harness()
            retry?.let { harness.accept(it) }
            val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
            val token = requireNotNull(state.acquireForSend())
            state.bindRun(token, "run")
            state.streamUpdate(token, harness.snapshot())
            val stopped = state.stop()
            assertEquals(MessageStatus.STOPPED, stopped.stoppedMessage!!.status)
            assertNull(stopped.stoppedMessage!!.segments?.lastOrNull { it.type == "error" })
            assertNull(stopped.stoppedMessage!!.retryText)
            assertTrue(state.finishStopFinalization(stopped.completion(true)).accepted)
        }
    }

    @Test fun recoveredAnswerThoughtAndToolOutputClearTheRetryDiagnostic() = runBlocking {
        val resumedEvents = listOf(
            StreamEvent.TextChunk("Recovered answer"),
            StreamEvent.ThoughtChunk("Recovered thought"),
            StreamEvent.ToolCallUpdate("stream", "call", "file_read", "{}"),
            StreamEvent.ToolCallRequest("call", "file_read", "{}"),
            StreamEvent.HostedToolCallUpdate("hosted", "web_search", "{}"),
        )
        for (event in resumedEvents) {
            val harness = Harness()
            harness.accept(StreamEvent.Retrying(1, 5,
                error = GenerationError.LocalModel(message = "Last failure", code = "RETRY_FAILURE")))
            assertEquals("RETRY_FAILURE", harness.output.generationErrorCode)
            harness.accept(event)
            assertNull(harness.output.generationErrorMessage)
            assertNull(harness.output.generationErrorCode)
            assertNull(harness.output.retryText)
            assertTrue(harness.snapshot().segments.orEmpty().none { it.type == "error" })
        }
    }

    @Test fun usageAndEmptyChunksKeepDiagnosticUntilRealOutputOrTerminalError() = runBlocking {
        val harness = Harness()
        harness.output.beginProviderRequest()
        harness.accept(StreamEvent.Retrying(1, 5,
            error = GenerationError.Configuration("Last retry failure")))
        harness.accept(StreamEvent.UsageUpdate(tokenCount = 3, thoughtsTokenCount = 1))
        harness.accept(StreamEvent.TextChunk(""))
        harness.accept(StreamEvent.ThoughtChunk(""))
        harness.accept(StreamEvent.Retrying(2, 5, resetCurrentAttempt = true))
        assertEquals("Last retry failure", harness.output.generationErrorMessage)
        harness.accept(StreamEvent.Error(GenerationError.Configuration("Terminal failure")))
        assertEquals(MessageStatus.ERROR, harness.output.currentStatus)
        assertEquals("Terminal failure", harness.output.generationErrorMessage)
        assertNull(harness.output.retryText)
    }

    private class Harness {
        val output = GenerationOutputAccumulator(object : GenerationToolPresentationSource {
            override fun presentationMetadata(name: String, arguments: String?) = null
        }, "provider")
        private val provider = mockk<LlmProvider> { every { name } returns "provider" }
        private val context = mockk<Context> {
            every { getString(R.string.generation_retry_attempt, any(), any()) } returns "Retrying"
        }
        suspend fun accept(event: StreamEvent) = output.handleStreamEvent(
            event, 0, provider, context, emptyMap(), StreamingUiUpdateGate(), {}, {},
        )
        fun snapshot() = ChatMessage(id = "model", text = output.totalText.toString(),
            participant = Participant.MODEL, status = output.currentStatus,
            retryText = output.retryText, runId = "run", runSequence = 0,
            segments = buildLiveSegments(output.toolOverlay.snapshot(), output.currentAnswerBuf,
                output.currentThoughtBuf, errorMessage = output.generationErrorMessage,
                errorCode = output.generationErrorCode))
    }
}
