package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GenerationTranscriptionStageTest {
    @Test
    fun `disabled transcription returns without touching the manager`() = runTest {
        val manager = mockk<TranscriptionManager>()
        val execution = GenerationTranscriptionStage(manager).newExecution()

        val outcome = execution.execute(request(GenerationContext())) { _, _ ->
            fail("No snapshot expected")
        }

        assertFalse(outcome.performed)
        assertNull(execution.incompleteSnapshot())
        coVerify(exactly = 0) { manager.collectTargets(any(), any()) }
    }

    @Test
    fun `completed transcription publishes progress then seals the last snapshot`() = runTest {
        val manager = mockk<TranscriptionManager>()
        val target = TranscriptionManager.TranscriptionTarget("user", "image", 0)
        val snapshot = snapshot("progress")
        val transcriptionSegment = MessageSegment(type = "transcription", content = "done")
        coEvery { manager.collectTargets("conversation", "user") } returns listOf(target)
        coEvery {
            manager.transcribe(
                listOf(target),
                "conversation",
                "run",
                2,
                "provider",
                "model",
                "key",
                null,
                "prompt",
                null,
                "assistant",
                10L,
                false,
                "5m",
                any(),
            )
        } coAnswers {
            val progress = arg<suspend (ChatMessage) -> Unit>(14)
            progress(snapshot)
            listOf(transcriptionSegment) to null
        }
        val publications = mutableListOf<Pair<ChatMessage, Boolean>>()
        val execution = GenerationTranscriptionStage(manager).newExecution()

        val outcome = execution.execute(request(enabledContext())) { message, force ->
            publications += message to force
        }

        assertTrue(outcome.performed)
        assertEquals(listOf(transcriptionSegment), outcome.segments)
        assertNull(outcome.error)
        assertEquals(listOf(snapshot to false, snapshot to true), publications)
        assertNull(execution.incompleteSnapshot())
    }

    @Test
    fun `cancelled transcription exposes only its own latest incomplete snapshot`() = runTest {
        val manager = mockk<TranscriptionManager>()
        val target = TranscriptionManager.TranscriptionTarget("user", "image", 0)
        val snapshot = snapshot("partial")
        val expected = CancellationException("cancel")
        coEvery { manager.collectTargets("conversation", "user") } returns listOf(target)
        coEvery {
            manager.transcribe(
                listOf(target),
                "conversation",
                "run",
                2,
                "provider",
                "model",
                "key",
                null,
                "prompt",
                null,
                "assistant",
                10L,
                false,
                "5m",
                any(),
            )
        } coAnswers {
            arg<suspend (ChatMessage) -> Unit>(14)(snapshot)
            throw expected
        }
        val execution = GenerationTranscriptionStage(manager).newExecution()

        try {
            execution.execute(request(enabledContext())) { _, _ -> }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }

        assertEquals(snapshot, execution.incompleteSnapshot())
        assertNull(GenerationTranscriptionStage(manager).newExecution().incompleteSnapshot())
    }

    @Test
    fun `enabled transcription with image targets and no model reports a configuration failure`() = runTest {
        val manager = mockk<TranscriptionManager>()
        coEvery { manager.collectTargets("conversation", "user") } returns listOf(
            TranscriptionManager.TranscriptionTarget("user", "image", 0),
        )
        val outcome = GenerationTranscriptionStage(manager).newExecution().execute(
            request(enabledContext().copy(transcriptionModelId = "")),
        ) { _, _ -> fail("No transcription request is configured") }
        assertFalse(outcome.performed)
        assertTrue(outcome.error!!.contains("no transcription model"))
    }

    private fun request(context: GenerationContext) = GenerationTranscriptionStageRequest(
        conversationId = "conversation",
        runId = "run",
        pass = 2,
        parentId = "user",
        context = context,
        generationJob = null,
        modelMessageId = "assistant",
        startTime = 10L,
    )

    private fun enabledContext() = GenerationContext(
        imageTranscriptionEnabled = true,
        transcriptionProviderName = "provider",
        transcriptionModelId = "model",
        transcriptionApiKey = "key",
        imageTranscriptionPrompt = "prompt",
        transcriptionAnthropicCacheEnabled = false,
        transcriptionAnthropicCacheTtl = "5m",
    )

    private fun snapshot(text: String) = ChatMessage(
        id = "assistant",
        text = text,
        participant = Participant.MODEL,
        status = MessageStatus.TRANSCRIBING,
    )
}
