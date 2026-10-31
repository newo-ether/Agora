package com.newoether.agora.viewmodel

import com.newoether.agora.automation.AutomationRuntimeAdmission
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.ProviderContextTopologySnapshot
import com.newoether.agora.data.local.RunGraphCommit
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentQueueHandoffTest {
    @Test
    fun queuedSuccessorCannotHoldUpWorkerSettlement_whenDurableBarrierSettlesFirst() =
        verifyHandoff(durableFirst = true)

    @Test
    fun queuedSuccessorCannotHoldUpWorkerSettlement_whenJobBarrierSettlesFirst() =
        verifyHandoff(durableFirst = false)

    private fun verifyHandoff(durableFirst: Boolean) = runBlocking<Unit> {
        withTimeout(10_000) {
            val coordinator = ConversationExecutionCoordinator({ "parent" }, MutableStateFlow(1))
            val conversations = mockk<ConversationRepository>()
            val settings = mockk<SettingsRepository>()
            val boundLauncher = mockk<BoundRunGenerationLauncher>()
            val state = ConversationGenerationState("child", reclaimQueuedAttachments = {})
            val claimCompleted = CompletableDeferred<Unit>()
            val nextRunEntered = CompletableDeferred<Unit>()
            val snapshot = testGenerationAdmissionSnapshot(
                conversationId = "child", runId = "next-run", selectedModelId = "provider:model",
            )
            coEvery { conversations.recoverConversationRuntime(any(), any()) } returns 0
            coEvery { conversations.getProviderContextTopologySnapshot("child") } returns
                ProviderContextTopologySnapshot(null, emptyList())
            coEvery { conversations.createRunWithMessages(any(), any(), any(), any(), any(), any()) } answers {
                RunGraphCommit(secondArg(), thirdArg(), emptyMap())
            }
            coEvery { settings.incrementMessagesSent() } just Runs
            coEvery { boundLauncher.launch(any(), any()) } coAnswers {
                val request = firstArg<BoundRunGenerationRequest>()
                val effect = checkNotNull(state.commands.requestRunFinalization(
                    RunEffectIdentity("child", request.uiToken, request.runId, 0, "next-terminal"),
                    RunStatus.COMPLETED, RunEndReason.MODEL_COMPLETED, false,
                ))
                state.finishRunFinalization(effect.identity, true)
                nextRunEntered.complete(Unit)
                Unit
            }
            val ids = ArrayDeque(listOf("next-run", "next-message"))
            val executor = QueuedGuidanceDrainExecutor(
                conversations = conversations,
                settings = settings,
                requestBuilder = mockk(),
                executionCoordinator = coordinator,
                terminalSettlement = mockk(),
                boundRunGenerationLauncher = boundLauncher,
                toUiMessage = ::toUiMessage,
                isConversationOpen = { false },
                projectGraph = { _, _, _, _ -> Unit },
                onScrollToAbsoluteBottomAfter = { _, _ -> Unit },
                onUserMessagePersisted = { _, _ -> Unit },
                idFactory = ids::removeFirst,
                clock = { 100L },
            )
            state.onQueueDrainRequested = { settled ->
                settled.scope.launch {
                    executor.drainAfterSettlement(settled)
                    claimCompleted.complete(Unit)
                }
            }
            try {
                coordinator.withGenerationAdmission("child") {
                    coordinator.withAutomationConversationLock("child") {
                        val input = checkNotNull(AutomationRuntimeAdmission.request(
                            state, "original-run", "original-input",
                        ) as? AutomationRuntimeAdmission.Decision.Accepted).inputEffect
                        val token = input.identity.ownerToken
                        val owner = Job()
                        assertTrue(state.attachGenerationJob(token, owner))
                        assertTrue(state.acquireGenerationAdmission(token, coordinator))
                        state.finishInputPersistence(input.identity)
                        state.enqueueSend(QueuedSend(
                            id = "queued-input", text = "follow-up", modelId = "provider:model",
                            attachments = emptyList(), runId = "prepared-run", generationSnapshot = snapshot,
                        ))
                        val effect = checkNotNull(state.commands.requestRunFinalization(
                            RunEffectIdentity("child", token, "original-run", 0, "original-terminal"),
                            RunStatus.COMPLETED, RunEndReason.CONTINUATION_PENDING, false,
                        ))
                        if (durableFirst) state.finishRunFinalization(effect.identity, true)
                        owner.complete()
                        owner.join()
                        if (!durableFirst) state.finishRunFinalization(effect.identity, true)
                        claimCompleted.await()

                        // Force the failing schedule: the queue owns Preparing before the worker waits.
                        assertTrue(owner.isCompleted)
                        assertTrue(state.generating.value)
                        assertNull(state.currentRunId())
                        assertEquals(1L, state.guidanceClaimRevision())
                        state.awaitGenerationSettled(token)
                        assertTrue(state.generating.value)
                        assertFalse(nextRunEntered.isCompleted)
                        coVerify(exactly = 0) {
                            conversations.createRunWithMessages(any(), any(), any(), any(), any(), any())
                        }
                    }
                    nextRunEntered.await()
                    state.awaitSendAvailable()
                    assertEquals(1, coordinator.admittedChildCount("parent"))
                }
                // Slot publication precedes the completion hook's admission release.
                coordinator.admittedGenerationIds.first { "child" !in it }
                assertEquals(0, coordinator.admittedChildCount("parent"))
                coVerify(exactly = 1) {
                    conversations.createRunWithMessages(any(), any(), any(), any(), any(), any())
                }
                coVerify(exactly = 1) { boundLauncher.launch(any(), state) }
                assertTrue(state.queuedSends.value.isEmpty())
                assertNull(state.claimQueuedSends())
            } finally {
                state.dispose()
            }
        }
    }

    private fun toUiMessage(entity: MessageEntity) = ChatMessage(
        id = entity.id, parentId = entity.parentId, text = entity.text,
        participant = entity.participant, status = entity.status, timestamp = entity.timestamp,
        modelName = entity.modelName, runId = entity.runId, runSequence = entity.runSequence,
    )
}
