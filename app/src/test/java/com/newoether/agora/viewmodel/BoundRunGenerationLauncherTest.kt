package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundRunGenerationLauncherTest {
    @Test
    fun launchesOnePassWithLoadedKeyAndIdentifiedRuntimeCallbacks() = runBlocking {
        val fixture = Fixture()
        var generationJob: Job? = null
        val callbacks = slot<GenerationCallbacks>()
        coEvery {
            fixture.manager.generate(
                conversationId = "conversation",
                modelMessageId = "model-message",
                startTime = 100L,
                modelName = "provider:model",
                runId = "run",
                pass = 3,
                ownerToken = fixture.uiToken,
                config = fixture.config,
                ctx = fixture.generationContext,
                providerInstances = fixture.snapshot.providerInstances,
                generationJob = any(),
                callbacks = capture(callbacks),
                streamScope = fixture.state.streamScope,
                requestTrace = any(),
            )
        } coAnswers {
            generationJob = arg(10)
            GenerationExecutionResult()
        }
        mockDebugLog()
        try {
            fixture.launcher.launch(fixture.request, fixture.state)
            callbacks.captured.onToolRoundPersisted()
        } finally {
            unmockkObject(DebugLog)
        }

        assertSame(currentCoroutineContext()[Job], generationJob)
        coVerify(exactly = 1) {
            fixture.compactController.automaticNeeded(
                conversationId = "conversation",
                contextLimit = 4096,
                // The threshold measures the main request, so it carries the main model id.
                config = fixture.snapshot.automaticCompact.copy(
                    mainModelId = fixture.config.modelId,
                ),
            )
        }
        fixture.state.dispose()
        Unit
    }

    @Test
    fun explicitContinuationIsForwardedToGenerationWithoutQueuedGuidance() = runBlocking {
        for (continuesAfterSuccess in listOf(false, true)) {
            val fixture = Fixture()
            val callbacks = slot<GenerationCallbacks>()
            coEvery {
                fixture.manager.generate(
                    any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), capture(callbacks), any(), any(),
                )
            } returns GenerationExecutionResult()
            mockDebugLog()
            try {
                fixture.launcher.launch(
                    fixture.request.copy(continuesAfterSuccess = continuesAfterSuccess),
                    fixture.state,
                )
                assertEquals(continuesAfterSuccess, callbacks.captured.hasPendingContinuation)
                assertFalse(callbacks.captured.hasQueuedSends())
                assertTrue(fixture.continuationRequests.isEmpty())
            } finally {
                fixture.state.dispose()
                unmockkObject(DebugLog)
            }
        }
    }

    @Test
    fun automaticCompactCompletesTheAssistantRunBeforeSchedulingFollowUp() = runBlocking {
        val fixture = Fixture()
        coEvery {
            fixture.compactController.automaticNeeded(any(), any(), any())
        } returns true
        coEvery {
            fixture.manager.generate(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(),
            )
        } coAnswers {
            val callbacks = arg<GenerationCallbacks>(11)
            assertEquals(
                ToolRoundBoundaryDecision.CompleteForFollowUp,
                callbacks.onToolRoundPersisted(),
            )
            GenerationExecutionResult(
                followUpParentMessageId = "result-boundary",
            )
        }
        mockDebugLog()
        try {
            fixture.launcher.launch(fixture.request, fixture.state)
        } finally {
            unmockkObject(DebugLog)
        }

        val continuation = fixture.continuationRequests.single()
        assertEquals("result-boundary", continuation.parentMessageId)
        assertEquals(fixture.request, continuation.generationRequest)
        assertSame(fixture.state, fixture.continuationStates.single())
        fixture.state.dispose()
        Unit
    }

    @Test
    fun generationStartFailureTerminalizesOnlyTheBoundSendingPlaceholder() = runBlocking {
        val fixture = Fixture()
        coEvery {
            fixture.manager.generate(
                conversationId = any(),
                modelMessageId = any(),
                startTime = any(),
                modelName = any(),
                runId = any(),
                pass = any(),
                ownerToken = any(),
                config = any(),
                ctx = any(),
                providerInstances = any(),
                generationJob = any(),
                callbacks = any(),
                streamScope = any(),
                requestTrace = any(),
            )
        } throws IllegalStateException("configuration failed")
        coEvery {
            fixture.conversations.getMessage("model-message")
        } returns MESSAGE_ENTITY
        val failedMessage = slot<ChatMessage>()
        coEvery {
            fixture.terminalSettlement.finalizeBoundFailure(
                conversationId = "conversation",
                runId = "run",
                pass = 3,
                uiToken = fixture.uiToken,
                state = fixture.state,
                failedMessage = capture(failedMessage),
                effectId = "request-finalize-run-3",
                notificationText = any(),
            )
        } returns true
        mockDebugLog()
        try {
            fixture.launcher.launch(fixture.request, fixture.state)
        } finally {
            unmockkObject(DebugLog)
        }

        assertEquals(MessageStatus.ERROR, failedMessage.captured.status)
        assertEquals("Error: configuration failed", failedMessage.captured.text)
        coVerify(exactly = 1) {
            fixture.manager.generate(
                conversationId = any(),
                modelMessageId = any(),
                startTime = any(),
                modelName = any(),
                runId = any(),
                pass = any(),
                ownerToken = any(),
                config = any(),
                ctx = any(),
                providerInstances = any(),
                generationJob = any(),
                callbacks = any(),
                streamScope = any(),
                requestTrace = any(),
            )
        }
        fixture.state.dispose()
        Unit
    }

    @Test
    fun cancellationFromGenerationIsPropagatedWithoutFailureFinalization() = runBlocking {
        val fixture = Fixture()
        coEvery {
            fixture.manager.generate(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(),
            )
        } throws
            CancellationException("stop")
        mockDebugLog()
        try {
            try {
                fixture.launcher.launch(fixture.request, fixture.state)
                fail("CancellationException should propagate")
            } catch (_: CancellationException) {
                Unit
            }
        } finally {
            unmockkObject(DebugLog)
        }

        coVerify(exactly = 0) {
            fixture.terminalSettlement.finalizeBoundFailure(
                any(), any(), any(), any(), any(), any(), any()
            )
        }
        fixture.state.dispose()
        Unit
    }

    private class Fixture {
        val conversations = mockk<ConversationRepository>()
        val manager = mockk<GenerationManager>()
        val compactController = mockk<ConversationCompactController>()
        val terminalSettlement = mockk<GenerationTerminalSettlementController>()
        val continuationRequests = mutableListOf<AutomaticCompactContinuationRequest>()
        val continuationStates = mutableListOf<ConversationGenerationState>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val uiToken = requireNotNull(state.acquireForSend())
        val snapshot = testGenerationAdmissionSnapshot(
            conversationId = "conversation",
            runId = "run",
        )
        val config = snapshot.config
        val generationContext = snapshot.context
        val request = BoundRunGenerationRequest(
            conversationId = "conversation",
            modelMessageId = "model-message",
            startTime = 100L,
            snapshot = snapshot,
            uiToken = uiToken,
            persistId = 7L,
            runId = "run",
            pass = 3,
            requestKind = "test",
        )
        val launcher: BoundRunGenerationLauncher

        init {
            state.bindRun(uiToken, "run", pass = 3)
            coEvery { manager.resolvedFixedContextTokenCost(any(), any()) } returns 0
            every { manager.includesAssistantReasoning(any(), any()) } returns false
            coEvery {
                compactController.automaticNeeded(any(), any(), any())
            } returns false
            launcher = BoundRunGenerationLauncher(
                conversations = conversations,
                generationManagerProvider = { manager },
                automaticCompactNeeded = compactController::automaticNeeded,
                terminalSettlement = terminalSettlement,
                toUiMessage = ::toUiMessage,
                onAutomaticCompactContinuation = { request, state ->
                    continuationRequests += request
                    continuationStates += state
                },
                clock = { 150L },
            )
        }
    }

    private companion object {
        val MESSAGE_ENTITY = MessageEntity(
            id = "model-message",
            conversationId = "conversation",
            text = "",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            timestamp = 100L,
            runId = "run",
            runSequence = 0,
        )

        fun toUiMessage(entity: MessageEntity) = ChatMessage(
            id = entity.id,
            parentId = entity.parentId,
            text = entity.text,
            participant = entity.participant,
            status = entity.status,
            runId = entity.runId,
            runSequence = entity.runSequence,
        )

        fun mockDebugLog() {
            mockkObject(DebugLog)
            every { DebugLog.d(any(), any()) } just Runs
            every { DebugLog.e(any(), any()) } just Runs
        }
    }
}
