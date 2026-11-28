package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunGraphCommit
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardGenerationContinuationLauncherTest {
    @Test
    fun compactUsesItsOwnModelWithoutWritingTheConversationModel() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val parent = MessageEntity(
            id = "parent", conversationId = "conversation", text = "source",
            participant = Participant.MODEL, status = MessageStatus.SUCCESS,
            timestamp = 100L, runId = "origin-run", runSequence = 0,
        )
        val snapshot = testGenerationAdmissionSnapshot(selectedModelId = "provider:compact")
        val persistedModels = mutableListOf<String?>()
        val requests = mutableListOf<BoundRunGenerationRequest>()
        coEvery { conversations.getMessage(parent.id) } returns parent
        coEvery { conversations.restoreBranchSelections("conversation") } returns emptyMap()
        coEvery {
            conversations.createRunWithMessages(any(), any(), any(), any(), any(), any())
        } answers {
            persistedModels += arg<String?>(3)
            RunGraphCommit(arg(1), arg(2), emptyMap())
        }
        coEvery { boundLauncher.launch(capture(requests), state) } returns Unit
        val launcher = StandardGenerationContinuationLauncher(
            conversations = conversations,
            executionCoordinator = ConversationExecutionCoordinator(),
            terminalSettlement = mockk(),
            boundRunGenerationLauncher = { boundLauncher },
            toUiMessage = ::toUiMessage,
            isConversationOpen = { false },
            projectGraph = { _, _, _, _ -> },
        )
        val launch = requireNotNull(launcher.launch(
            StandardGenerationContinuationRequest(
                conversationId = "conversation", parentMessageId = parent.id,
                snapshot = snapshot, requestKind = "compact",
                conversationModelId = null,
                modelMessageId = "compact_summary", touchConversationOnAdmission = true,
            ),
            state,
        ))
        launch.job.join()
        assertTrue(launch.started.await())
        assertEquals(listOf<String?>(null), persistedModels)
        assertEquals("provider:compact", requests.single().snapshot.selectedModelId)
        assertEquals("compact", requests.single().requestKind)
        state.dispose()
        Unit
    }

    @Test
    fun callerHeldAutomationLeaseLetsCompactStartWithoutReacquiringTheLock() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val coordinator = ConversationExecutionCoordinator()
        val parent = MessageEntity(
            id = "parent", conversationId = "conversation", text = "source",
            participant = Participant.MODEL, status = MessageStatus.SUCCESS,
            timestamp = 100L, runId = "origin-run", runSequence = 0,
        )
        coEvery { conversations.getMessage(parent.id) } returns parent
        coEvery { conversations.restoreBranchSelections("conversation") } returns emptyMap()
        coEvery {
            conversations.createRunWithMessages(any(), any(), any(), any(), any(), any())
        } answers { RunGraphCommit(arg(1), arg(2), emptyMap()) }
        // The blocked launch is cancelled before it can commit any Run.
        coEvery { conversations.getRun(any()) } returns null
        coEvery { boundLauncher.launch(any(), state) } returns Unit
        val launcher = StandardGenerationContinuationLauncher(
            conversations = conversations,
            executionCoordinator = coordinator,
            terminalSettlement = mockk(),
            boundRunGenerationLauncher = { boundLauncher },
            toUiMessage = ::toUiMessage,
            isConversationOpen = { false },
            projectGraph = { _, _, _, _ -> },
        )
        fun request(alreadyHoldsLock: Boolean) = StandardGenerationContinuationRequest(
            conversationId = "conversation", parentMessageId = parent.id,
            snapshot = testGenerationAdmissionSnapshot(selectedModelId = "provider:compact"),
            requestKind = "compact", conversationModelId = null,
            modelMessageId = "compact_summary", touchConversationOnAdmission = false,
            alreadyHoldsConversationLock = alreadyHoldsLock,
        )
        coordinator.withAutomationConversationLock("conversation") {
            // Re-acquiring the non-reentrant lease inside the holder never reaches the boundary.
            val blocked = requireNotNull(launcher.launch(request(false), state))
            assertNull(withTimeoutOrNull(300L) { blocked.started.await() })
            blocked.job.cancel()
            blocked.job.join()
            state.awaitSendAvailable()
            // The Task path declares the held lease and starts immediately.
            val launch = requireNotNull(launcher.launch(request(true), state))
            assertTrue(withTimeout(5_000L) { launch.started.await() })
            launch.job.join()
        }
        state.dispose()
        Unit
    }
    @Test
    fun createsFreshRunAndAssistantUnderDurableBoundary() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val terminalSettlement = mockk<GenerationTerminalSettlementController>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val parent = MessageEntity(
            id = "compact-boundary",
            conversationId = "conversation",
            parentId = "tool-result",
            text = "summary",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 100L,
            runId = "compact-run",
            runSequence = 0,
        )
        val createdRun = slot<com.newoether.agora.data.local.RunEntity>()
        val createdMessages = slot<List<MessageEntity>>()
        val conversationModel = slot<String>()
        val touchConversationOnAdmission = slot<Boolean>()
        val launchedRequest = slot<BoundRunGenerationRequest>()
        val launched = CompletableDeferred<Unit>()

        coEvery { conversations.getMessage(parent.id) } returns parent
        coEvery {
            conversations.restoreBranchSelections("conversation")
        } returns emptyMap()
        coEvery {
            conversations.createRunWithMessages(
                run = capture(createdRun),
                messages = capture(createdMessages),
                messageSelectionUpdates = any(),
                conversationModelId = capture(conversationModel),
                at = any(),
                touchConversationOnAdmission = capture(touchConversationOnAdmission),
            )
        } answers {
            RunGraphCommit(
                messages = createdMessages.captured,
                messageSelections = mapOf(parent.id to createdMessages.captured.single().id),
                runSelections = mapOf(parent.runId to createdRun.captured.id),
            )
        }
        coEvery { boundLauncher.launch(capture(launchedRequest), state) } answers {
            launched.complete(Unit)
        }

        val ids = ArrayDeque(listOf("continuation-run", "continuation-message"))
        val launcher = StandardGenerationContinuationLauncher(
            conversations = conversations,
            executionCoordinator = ConversationExecutionCoordinator(),
            terminalSettlement = terminalSettlement,
            boundRunGenerationLauncher = { boundLauncher },
            toUiMessage = ::toUiMessage,
            isConversationOpen = { false },
            projectGraph = { _, _, _, _ -> },
            idFactory = { ids.removeFirst() },
            clock = { 200L },
        )

        assertNotNull(
            launcher.launch(
                StandardGenerationContinuationRequest(
                    conversationId = "conversation",
                    parentMessageId = parent.id,
                    snapshot = testGenerationAdmissionSnapshot(
                        conversationId = "conversation",
                        runId = "origin-run",
                    ),
                    touchConversationOnAdmission = false,
                ),
                state,
            )
        )
        launched.await()

        assertEquals("continuation-run", createdRun.captured.id)
        assertEquals("compact-run", createdRun.captured.parentRunId)
        assertEquals(parent.id, createdMessages.captured.single().parentId)
        assertEquals(MessageStatus.SENDING, createdMessages.captured.single().status)
        assertFalse(touchConversationOnAdmission.captured)
        assertEquals("provider:model", conversationModel.captured)
        assertEquals("continuation-run", launchedRequest.captured.runId)
        assertEquals("continuation-message", launchedRequest.captured.modelMessageId)
        coVerify(exactly = 1) { boundLauncher.launch(any(), state) }
        state.dispose()
        Unit
    }

    @Test
    fun cancellationAfterRoomCommitReconcilesTheDurableRunBeforeRelease() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val terminalSettlement = mockk<GenerationTerminalSettlementController>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val parent = MessageEntity(
            id = "parent",
            conversationId = "conversation",
            text = "result",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 100L,
            runId = "origin-run",
            runSequence = 0,
        )
        coEvery { conversations.getMessage(parent.id) } returns parent
        coEvery { conversations.restoreBranchSelections("conversation") } returns emptyMap()
        coEvery {
            conversations.createRunWithMessages(any(), any(), any(), any(), any(), any())
        } coAnswers {
            throw CancellationException("cancelled after Room committed")
        }
        coEvery { conversations.getRun("continuation-run") } returns
            com.newoether.agora.data.local.RunEntity(
                id = "continuation-run",
                conversationId = "conversation",
                parentRunId = "origin-run",
                status = com.newoether.agora.model.RunStatus.ACTIVE,
                activeSlot = 1,
                startedAt = 200L,
                lastCheckpointAt = 200L,
            )
        coEvery { terminalSettlement.settleCancelledDurableRun(state, any()) } returns true
        val ids = ArrayDeque(listOf("continuation-run", "continuation-message"))
        val launcher = StandardGenerationContinuationLauncher(
            conversations = conversations,
            executionCoordinator = ConversationExecutionCoordinator(),
            terminalSettlement = terminalSettlement,
            boundRunGenerationLauncher = { mockk() },
            toUiMessage = ::toUiMessage,
            isConversationOpen = { false },
            projectGraph = { _, _, _, _ -> },
            idFactory = { ids.removeFirst() },
            clock = { 200L },
        )

        val launch = requireNotNull(
            launcher.launch(
                StandardGenerationContinuationRequest(
                    conversationId = "conversation",
                    parentMessageId = parent.id,
                    snapshot = testGenerationAdmissionSnapshot(
                        conversationId = "conversation",
                        runId = "origin-run",
                    ),
                    touchConversationOnAdmission = false,
                ),
                state,
            ),
        )
        launch.job.join()

        coVerify(exactly = 1) { conversations.getRun("continuation-run") }
        coVerify(exactly = 1) { terminalSettlement.settleCancelledDurableRun(state, any()) }
        state.dispose()
        Unit
    }

    @Test
    fun selectedChildSupersedesAutomaticContinuation() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val parent = MessageEntity(
            id = "tool-result",
            conversationId = "conversation",
            text = "result",
            participant = Participant.USER,
            status = MessageStatus.SUCCESS,
            timestamp = 100L,
            runId = "origin-run",
            runSequence = 2,
        )
        coEvery { conversations.getMessage(parent.id) } returns parent
        coEvery {
            conversations.restoreBranchSelections("conversation")
        } returns mapOf(parent.id to "queued-user")

        val launcher = StandardGenerationContinuationLauncher(
            conversations = conversations,
            executionCoordinator = ConversationExecutionCoordinator(),
            terminalSettlement = mockk(),
            boundRunGenerationLauncher = { boundLauncher },
            toUiMessage = ::toUiMessage,
            isConversationOpen = { false },
            projectGraph = { _, _, _, _ -> },
            idFactory = { "unused" },
        )

        assertNotNull(
            launcher.launch(
                StandardGenerationContinuationRequest(
                    conversationId = "conversation",
                    parentMessageId = parent.id,
                    snapshot = testGenerationAdmissionSnapshot(
                        conversationId = "conversation",
                        runId = "origin-run",
                    ),
                    touchConversationOnAdmission = false,
                ),
                state,
            )
        )
        state.awaitSendAvailable()

        coVerify(exactly = 0) {
            conversations.createRunWithMessages(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { boundLauncher.launch(any(), any()) }
        state.dispose()
        Unit
    }

    @Test
    fun recompactCreatesAFreshRunAndStreamsTheSameRowWithoutCreatingABranch() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val terminalSettlement = mockk<GenerationTerminalSettlementController>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val parent = MessageEntity(
            id = "parent",
            conversationId = "conversation",
            text = "answer",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 100L,
            runId = "parent-run",
            runSequence = 0,
        )
        val target = MessageEntity(
            id = "compact_target",
            conversationId = "conversation",
            parentId = parent.id,
            text = "old summary",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 101L,
            runId = "compact-run",
            runSequence = 0,
        )
        val suffix = MessageEntity(
            id = "suffix",
            conversationId = "conversation",
            parentId = target.id,
            text = "later answer",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 102L,
            runId = "suffix-run",
            runSequence = 0,
        )
        val selected = mapOf<String?, String>(
            null to parent.id,
            parent.id to target.id,
            target.id to suffix.id,
        )
        val replacementRun = slot<com.newoether.agora.data.local.RunEntity>()
        val restarted = target.copy(
            runId = "fresh-compact-run",
            runSequence = 0,
            text = "",
            status = MessageStatus.SENDING,
            modelName = "provider:model",
        )
        val launchedRequest = slot<BoundRunGenerationRequest>()
        val launched = CompletableDeferred<Unit>()
        coEvery { conversations.getMessage(parent.id) } returns parent
        coEvery { conversations.getMessage(target.id) } returns target
        coEvery { conversations.restoreBranchSelections("conversation") } returns selected
        coEvery {
            conversations.beginRecompactContextCompact(
                replacementRun = capture(replacementRun),
                messageId = target.id,
                modelName = "provider:model",
                expectedSelections = selected,
            )
        } returns restarted
        coEvery { boundLauncher.launch(capture(launchedRequest), state) } answers {
            launched.complete(Unit)
        }

        val launcher = StandardGenerationContinuationLauncher(
            conversations = conversations,
            executionCoordinator = ConversationExecutionCoordinator(),
            terminalSettlement = terminalSettlement,
            boundRunGenerationLauncher = { boundLauncher },
            toUiMessage = ::toUiMessage,
            isConversationOpen = { false },
            projectGraph = { _, _, _, _ -> },
            idFactory = { "fresh-compact-run" },
            clock = { 200L },
        )
        val launch = checkNotNull(
            launcher.launch(
                StandardGenerationContinuationRequest(
                    conversationId = "conversation",
                    parentMessageId = parent.id,
                    snapshot = testGenerationAdmissionSnapshot(
                        conversationId = "conversation",
                        runId = target.runId,
                    ),
                    modelMessageId = target.id,
                    replacementMessageId = target.id,
                    requestKind = "compact",
                    touchConversationOnAdmission = true,
                ),
                state,
            ),
        )

        assertTrue(launch.started.await())
        launched.await()
        assertEquals(target.id, launch.modelMessageId)
        assertEquals(target.id, launchedRequest.captured.modelMessageId)
        assertEquals("fresh-compact-run", launchedRequest.captured.runId)
        assertEquals("fresh-compact-run", replacementRun.captured.id)
        assertEquals(parent.runId, replacementRun.captured.parentRunId)
        assertEquals(target.timestamp, launchedRequest.captured.startTime)
        assertEquals("compact", launchedRequest.captured.requestKind)
        coVerify(exactly = 1) {
            conversations.beginRecompactContextCompact(
                any(),
                target.id,
                "provider:model",
                selected,
            )
        }
        coVerify(exactly = 0) {
            conversations.createRunWithMessages(any(), any(), any(), any(), any(), any())
        }
        assertEquals(suffix, listOf(parent, target, suffix).last())
        state.dispose()
        Unit
    }

    @Test
    fun successGatedContinuationAllowsQueueDrainOnlyAfterDurableSuccess() = runBlocking {
        assertTrue(queueDrainPermissionAfterTerminal(MessageStatus.SUCCESS))
    }

    @Test
    fun successGatedContinuationSuppressesQueueDrainAfterDurableError() = runBlocking {
        assertFalse(queueDrainPermissionAfterTerminal(MessageStatus.ERROR))
    }

    @Test
    fun explicitContinuationRetainsHandoffBeforeJobWorkAndCleansItAfterSuccess() = runBlocking {
        assertTrue(queueDrainPermissionAfterTerminal(MessageStatus.SUCCESS, continuesAfterSuccess = true))
    }

    @Test
    fun explicitContinuationRetainsHandoffBeforeJobWorkAndCleansItAfterFailure() = runBlocking {
        assertFalse(queueDrainPermissionAfterTerminal(MessageStatus.ERROR, continuesAfterSuccess = true))
    }

    @Test
    fun explicitContinuationRetainsHandoffBeforeJobWorkAndCleansItAfterCancellation() = runBlocking {
        assertFalse(queueDrainPermissionAfterTerminal(MessageStatus.STOPPED, continuesAfterSuccess = true))
    }

    @Test
    fun queuedGuidanceAtomicallyWinsBeforeNoInputLoopContinuation() = runBlocking {
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val revision = state.guidanceClaimRevision()
        state.enqueueSend(
            QueuedSend(
                id = "queued",
                text = "steer",
                modelId = "provider:model",
                attachments = emptyList(),
                runId = "queued-run",
            ),
        )
        var launches = 0

        val result = launchStandardContinuationAfterGuidance(state, revision) {
            launches += 1
            error("Queued guidance must prevent loop admission")
        }

        assertNull(result)
        assertEquals(0, launches)
        state.dispose()
        Unit
    }

    @Test
    fun claimedGuidanceStillWinsBeforeNoInputLoopContinuation() = runBlocking {
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val revision = state.guidanceClaimRevision()
        state.enqueueSend(
            QueuedSend(
                id = "queued",
                text = "steer",
                modelId = "provider:model",
                attachments = emptyList(),
                runId = "queued-run",
            ),
        )
        val lease = requireNotNull(state.claimQueuedSends())
        var launches = 0

        val result = launchStandardContinuationAfterGuidance(state, revision) {
            launches += 1
            error("Claimed guidance must prevent loop admission")
        }

        assertNull(result)
        assertEquals(0, launches)
        assertTrue(state.settleGuidanceClaim(lease.id, durable = false))
        state.dispose()
        Unit
    }

    @Test
    fun noGuidanceAdmitsTheLoopWhileHoldingTheQueueFence() = runBlocking {
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val revision = state.guidanceClaimRevision()
        var launches = 0

        launchStandardContinuationAfterGuidance(state, revision) {
            launches += 1
            null
        }

        assertEquals(1, launches)
        state.dispose()
        Unit
    }

    private suspend fun queueDrainPermissionAfterTerminal(
        status: MessageStatus,
        continuesAfterSuccess: Boolean = false,
    ): Boolean {
        val conversations = mockk<ConversationRepository>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val finishedHandoffs = mutableListOf<Long>()
        val beforeJobWork = CompletableDeferred<List<Long>>()
        val boundStarted = CompletableDeferred<BoundRunGenerationRequest>()
        val state = ConversationGenerationState(
            "conversation", reclaimQueuedAttachments = {},
            onRunHandoffFinished = { _, token -> finishedHandoffs += token },
        )
        val coordinator = ConversationExecutionCoordinator(parentConversationId = {
            // The first external call from the installed Job must already see retention.
            state.finishRunHandoff(state.captureUiToken(), queuedDrainOnly = true)
            beforeJobWork.complete(finishedHandoffs.toList())
            null
        })
        val parent = MessageEntity(
            id = "parent",
            conversationId = "conversation",
            text = "answer",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 100L,
            runId = "parent-run",
            runSequence = 0,
        )
        val createdMessages = slot<List<MessageEntity>>()
        val terminal = MessageEntity(
            id = "compact-message",
            conversationId = "conversation",
            parentId = parent.id,
            text = if (status == MessageStatus.SUCCESS) "summary" else "failure",
            participant = Participant.MODEL,
            status = status,
            timestamp = 200L,
            runId = "compact-run",
            runSequence = 0,
        )
        coEvery { conversations.getMessage(any()) } returnsMany listOf(parent, terminal)
        coEvery { conversations.restoreBranchSelections("conversation") } returns emptyMap()
        coEvery {
            conversations.createRunWithMessages(
                run = any(),
                messages = capture(createdMessages),
                messageSelectionUpdates = any(),
                conversationModelId = any(),
                at = any(),
                touchConversationOnAdmission = false,
            )
        } answers {
            RunGraphCommit(
                messages = createdMessages.captured,
                messageSelections = mapOf(parent.id to createdMessages.captured.single().id),
                runSelections = mapOf(parent.runId to "compact-run"),
            )
        }
        coEvery { boundLauncher.launch(any(), state) } coAnswers {
            val request = firstArg<BoundRunGenerationRequest>()
            boundStarted.complete(request)
            try {
                if (status == MessageStatus.STOPPED) awaitCancellation()
            } finally {
                val identity = RunEffectIdentity("conversation", request.uiToken, request.runId, 0, "finalize")
                val runStatus = when (status) {
                    MessageStatus.SUCCESS -> RunStatus.COMPLETED
                    MessageStatus.ERROR -> RunStatus.FAILED
                    MessageStatus.STOPPED -> RunStatus.STOPPED
                    else -> error("Expected terminal fixture status")
                }
                val reason = when (status) {
                    MessageStatus.SUCCESS -> if (continuesAfterSuccess) {
                        RunEndReason.CONTINUATION_PENDING
                    } else RunEndReason.MODEL_COMPLETED
                    MessageStatus.STOPPED -> RunEndReason.USER_STOPPED
                    else -> RunEndReason.PROVIDER_ERROR
                }
                checkNotNull(state.commands.requestRunFinalization(identity, runStatus, reason, false))
                check(state.finishRunFinalization(identity, true).accepted)
            }
        }
        val ids = ArrayDeque(listOf("compact-run", "compact-message"))
        val launcher = StandardGenerationContinuationLauncher(
            conversations = conversations,
            executionCoordinator = coordinator,
            terminalSettlement = mockk(),
            boundRunGenerationLauncher = { boundLauncher },
            toUiMessage = ::toUiMessage,
            isConversationOpen = { false },
            projectGraph = { _, _, _, _ -> },
            idFactory = { ids.removeFirst() },
            clock = { 200L },
        )

        val launch = requireNotNull(
            launcher.launch(
                StandardGenerationContinuationRequest(
                    conversationId = "conversation",
                    parentMessageId = parent.id,
                    snapshot = testGenerationAdmissionSnapshot(
                        conversationId = "conversation",
                        runId = "origin-run",
                    ),
                    touchConversationOnAdmission = false,
                    queueDrainRequiresSuccess = true,
                    continuesAfterSuccess = continuesAfterSuccess,
                ),
                state,
            ),
        )
        try {
            assertEquals(
                if (continuesAfterSuccess) emptyList<Long>() else listOf(launch.uiToken),
                withTimeout(5_000L) { beforeJobWork.await() },
            )
            val boundRequest = withTimeout(5_000L) { boundStarted.await() }
            assertEquals(continuesAfterSuccess, boundRequest.continuesAfterSuccess)
            if (status == MessageStatus.STOPPED) launch.job.cancel()
            withTimeout(5_000L) {
                launch.job.join()
                state.awaitGenerationSettled(launch.uiToken)
            }
            assertTrue(launch.started.await())
            assertFalse(state.generating.value)
            assertEquals(status == MessageStatus.STOPPED, launch.job.isCancelled)
            if (continuesAfterSuccess) {
                state.finishRunHandoff(launch.uiToken, queuedDrainOnly = true)
                assertTrue(finishedHandoffs.isEmpty())
                val otherToken = launch.uiToken + 1
                state.finishRunHandoff(otherToken)
                state.finishRunHandoff(launch.uiToken, queuedDrainOnly = true)
                assertEquals(listOf(otherToken), finishedHandoffs)
                state.finishRunHandoff(launch.uiToken)
                assertEquals(listOf(otherToken, launch.uiToken), finishedHandoffs)
                state.finishRunHandoff(launch.uiToken, queuedDrainOnly = true)
                assertEquals(listOf(otherToken, launch.uiToken, launch.uiToken), finishedHandoffs)
            }
            return state.consumeQueueDrainPermission()
        } finally {
            state.dispose()
        }
    }

    private companion object {
        fun toUiMessage(entity: MessageEntity) = ChatMessage(
            id = entity.id,
            parentId = entity.parentId,
            text = entity.text,
            participant = entity.participant,
            status = entity.status,
            runId = entity.runId,
            runSequence = entity.runSequence,
        )
    }
}
