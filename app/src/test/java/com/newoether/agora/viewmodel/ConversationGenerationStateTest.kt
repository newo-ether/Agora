package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ConversationCommand
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationGenerationStateTest {

    @Test
    fun replacementClaim_isIdleOnlyAndAtomic() = runBlocking {
        val state = ConversationGenerationState("conversation")

        val token = state.tryAcquireForReplacement()

        assertTrue(token != null)
        assertTrue(state.generating.value)
        assertNull(state.tryAcquireForReplacement())
        assertTrue(state.endGeneration(token!!))
        assertFalse(state.generating.value)
    }

    @Test
    fun normalCompletion_doesNotSuppressQueueDrain() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")

        assertTrue(finalizeBoundRun(state, token, "run"))

        assertTrue(state.consumeQueueDrainPermission())
        assertTrue(state.consumeQueueDrainPermission())
    }

    @Test
    fun stop_waitsForCoroutineAndFinalizerBeforeReleasing() = runBlocking {
        val active = activeStateWithStreamingMessage()
        val state = active.state

        val stopped = state.stop()

        assertTrue(state.generating.value)
        assertTrue(state.isLoading.value)
        assertTrue(state.stopping.value)
        active.unwind.complete(Unit)
        active.job.join()
        assertTrue(state.generating.value)
        assertTrue(state.stopping.value)
        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.SETTLED,
            state.finishStopFinalization(stopped.completion(success = true)),
        )
        assertFalse(state.generating.value)
        assertFalse(state.isLoading.value)
        assertFalse(state.stopping.value)
    }

    @Test
    fun stopFinalizer_neverReleasesAnOccupiedCoroutineSlot() = runBlocking {
        val active = activeStateWithStreamingMessage()
        val state = active.state
        val settled = CompletableDeferred<Unit>()
        state.onQueueDrainRequested = { settled.complete(Unit) }

        val stopped = state.stop()

        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.RECORDED,
            state.finishStopFinalization(stopped.completion(success = true)),
        )
        assertTrue(state.generating.value)
        assertTrue(state.stopping.value)
        active.unwind.complete(Unit)
        active.job.join()
        settled.await()
        assertFalse(state.generating.value)
        assertFalse(state.isLoading.value)
        assertFalse(state.stopping.value)
    }

    @Test
    fun failedStopFinalization_keepsSlotOccupied() = runBlocking {
        val active = activeStateWithStreamingMessage()
        val state = active.state

        val stopped = state.stop()
        active.unwind.complete(Unit)
        active.job.join()

        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.FAILED,
            state.finishStopFinalization(stopped.completion(success = false)),
        )
        assertTrue(state.generating.value)
        assertTrue(state.stopping.value)
        assertNull(state.acquireForSend())
    }

    @Test
    fun failedStopFinalization_repeatedStopReissuesTheSameEffectAndCanSettle() = runBlocking {
        val active = activeStateWithStreamingMessage()
        val state = active.state
        val first = state.stop()
        active.unwind.complete(Unit)
        active.job.join()

        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.FAILED,
            state.finishStopFinalization(first.completion(success = false)),
        )

        val retry = state.stop()

        assertEquals(first.finalizationEffect, retry.finalizationEffect)
        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.SETTLED,
            state.finishStopFinalization(retry.completion(success = true)),
        )
        assertFalse(state.generating.value)
        assertFalse(state.stopping.value)
    }

    @Test
    fun mailboxStop_cancelsEveryRegisteredGenerationHandleBeforeReturning() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")
        var firstCancelCount = 0
        var secondCancelCount = 0
        state.streamScope.register(GenerationCancelHandle { firstCancelCount += 1 })
        state.streamScope.register(GenerationCancelHandle { secondCancelCount += 1 })

        state.stop()
        state.streamScope.cancelAll()

        assertEquals(1, firstCancelCount)
        assertEquals(1, secondCancelCount)
    }

    @Test
    fun cancelledStopSubmitter_cannotDropAnAcceptedCutoffOrItsResult() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")
        val result = CompletableDeferred<ConversationGenerationState.StopResult>()

        val request = state.requestStop { result.complete(it) }
        request.cancel()
        val stopped = result.await()

        assertEquals("run", stopped.runId)
        assertTrue(state.stopping.value)
        assertFalse(state.isCurrentToken(token))
    }

    @Test
    fun mailboxSend_claimsPreparingAndBindsOnlyItsExactPersistenceResult() = runBlocking {
        val state = ConversationGenerationState("conversation")

        val requested = state.commands.requestSend(
            proposedRunId = "run",
            effectId = "send",
            directOnly = false,
            hasPendingGuidance = false,
        )
        val effect = requested.effects.single() as RunEffect.PersistAcceptedInput

        assertTrue(state.generating.value)
        assertNull(state.currentRunId())
        assertTrue(state.inputPersisted(effect.identity))
        assertEquals("run", state.currentRunId())
        assertTrue(finalizeBoundRun(state, effect.identity.ownerToken, "run"))
        assertFalse(state.generating.value)
    }

    @Test
    fun StopBeforeInputPersistence_rejectsTheLateMailboxResult() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val requested = state.commands.requestSend(
            proposedRunId = "run",
            effectId = "send",
            directOnly = false,
            hasPendingGuidance = false,
        )
        val effect = requested.effects.single() as RunEffect.PersistAcceptedInput

        val stopped = state.stop()

        assertNull(stopped.finalizationEffect)
        assertFalse(state.inputPersisted(effect.identity))
        assertFalse(state.generating.value)
        assertNull(state.currentRunId())
    }

    @Test
    fun StopDuringRoomCommit_bindsTheDurableRunAndReturnsItsExactStopEffect() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val requested = state.commands.requestSend(
            proposedRunId = "run",
            effectId = "send",
            directOnly = false,
            hasPendingGuidance = false,
        )
        val inputEffect = requested.effects.single() as RunEffect.PersistAcceptedInput
        val unwind = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            withContext(NonCancellable) {
                started.complete(Unit)
                unwind.await()
            }
        }
        started.await()
        assertTrue(state.attachGenerationJob(inputEffect.identity.ownerToken, job))
        val settled = CompletableDeferred<Unit>()
        state.onQueueDrainRequested = { settled.complete(Unit) }

        val initialStop = state.stop()
        val binding = state.finishInputPersistence(inputEffect.identity)

        assertNull(initialStop.finalizationEffect)
        assertTrue(binding is ConversationGenerationState.RunBindingOutcome.Stopping)
        binding as ConversationGenerationState.RunBindingOutcome.Stopping
        assertEquals("run", binding.finalizationEffect.identity.runId)
        assertEquals(
            "stop-${inputEffect.identity.ownerToken}",
            binding.finalizationEffect.identity.effectId,
        )
        assertEquals("run", state.currentRunId())
        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.RECORDED,
            state.finishStopFinalization(
                ConversationCommand.PersistenceSettled(
                    identity = binding.finalizationEffect.identity,
                    success = true,
                ),
            ),
        )

        unwind.complete(Unit)
        job.join()
        settled.await()
        assertFalse(state.generating.value)
        assertFalse(state.stopping.value)
    }

    @Test
    fun mailboxInputFailure_remainsOwnedUntilTheGenerationCoroutineSettles() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val requested = state.commands.requestSend(
            proposedRunId = "run",
            effectId = "send",
            directOnly = false,
            hasPendingGuidance = false,
        )
        val effect = requested.effects.single() as RunEffect.PersistAcceptedInput

        assertTrue(state.commands.inputPersistenceFailed(effect.identity))
        assertTrue(state.generating.value)
        assertNull(state.acquireForSend())
        assertTrue(state.endGeneration(effect.identity.ownerToken))
        assertFalse(state.generating.value)
    }

    @Test
    fun activeMailboxSend_returnsGuidanceForTheBoundRunWithoutChangingOwner() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "active-run", pass = 2)

        val requested = state.commands.requestSend(
            proposedRunId = "unused-run",
            effectId = "guidance",
            directOnly = false,
            hasPendingGuidance = false,
        )

        val guidance = requested.effects.single() as RunEffect.AcceptGuidance
        assertEquals("active-run", guidance.identity.runId)
        assertEquals(2, guidance.identity.pass)
        assertEquals(token, guidance.identity.ownerToken)
        assertEquals("active-run", state.currentRunId())
        assertTrue(finalizeBoundRun(state, token, "active-run", pass = 2))
    }

    @Test
    fun preparingSendAcceptsMemoryGuidanceForItsProposedFreshRun() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val first = state.commands.requestSend(
            proposedRunId = "preparing-run",
            effectId = "first",
            directOnly = false,
            hasPendingGuidance = false,
        )
        val firstEffect = first.effects.single() as RunEffect.PersistAcceptedInput

        val second = state.commands.requestSend(
            proposedRunId = "unused-second-run",
            effectId = "guidance",
            directOnly = false,
            hasPendingGuidance = false,
        )
        val guidance = second.effects.single() as RunEffect.AcceptGuidance

        assertEquals(firstEffect.identity.ownerToken, guidance.identity.ownerToken)
        assertEquals("preparing-run", guidance.identity.runId)
        assertTrue(state.inputPersisted(firstEffect.identity))
        assertTrue(finalizeBoundRun(state, firstEffect.identity.ownerToken, "preparing-run"))
    }

    @Test
    fun stopCancelsAnExternallyOwnedBackgroundGenerationJob() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        val started = CompletableDeferred<Unit>()
        val externalJob = launch {
            started.complete(Unit)
            awaitCancellation()
        }
        assertTrue(state.attachGenerationJob(token, externalJob))
        state.bindRun(token, "background-run")
        started.await()

        val stopped = state.stop()
        externalJob.join()

        assertTrue(externalJob.isCancelled)
        assertEquals("background-run", stopped.runId)
        // The coroutine half settled, but the durable Run half still owns STOPPING.
        assertTrue(state.generating.value)
        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.SETTLED,
            state.finishStopFinalization(stopped.completion(success = true)),
        )
        assertFalse(state.generating.value)
    }

    @Test
    fun normalExternalCompletionRequestsQueueDrainExactlyOnce() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        val externalJob = Job()
        var drainRequests = 0
        val drained = CompletableDeferred<Unit>()
        state.onQueueDrainRequested = {
            drainRequests += 1
            drained.complete(Unit)
        }

        assertTrue(state.attachGenerationJob(token, externalJob))
        externalJob.complete()
        externalJob.join()
        drained.await()

        assertEquals(1, drainRequests)
        assertFalse(state.generating.value)
    }

    @Test
    fun alreadyCompletedExternalJob_cannotStrandAnInstalledSlot() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        val completedJob = Job().apply { complete() }
        val released = CompletableDeferred<Unit>()
        state.onQueueDrainRequested = { released.complete(Unit) }

        assertTrue(state.attachGenerationJob(token, completedJob))
        released.await()

        assertFalse(state.generating.value)
    }

    @Test
    fun boundJobCompletionWithoutTerminalResultRemainsOccupiedForStopRecovery() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")
        val released = CompletableDeferred<Unit>()
        state.onQueueDrainRequested = { released.complete(Unit) }
        val job = checkNotNull(state.launchGenerationJob(token) { awaitCancellation() })

        try {
            state.endGeneration(token)
            throw AssertionError("Expected early CoroutineSettled to be rejected")
        } catch (actual: IllegalStateException) {
            assertTrue(actual.message.orEmpty().contains("completed"))
        }
        assertTrue(state.generating.value)

        job.cancel()
        job.join()
        assertFalse(released.isCompleted)
        assertTrue(state.generating.value)

        val stopped = state.stop()
        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.SETTLED,
            state.finishStopFinalization(stopped.completion(success = true)),
        )
        assertFalse(state.generating.value)
    }

    @Test
    fun stopPreservesQueueDrainPermission() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")

        val stopped = state.stop()

        assertEquals("run", stopped.runId)
        // Stop no longer suppresses queue drain. The existing queue consumption logic
        // naturally takes over after the stop state settles.
        assertTrue(state.consumeQueueDrainPermission())
        assertTrue(state.consumeQueueDrainPermission())
    }

    @Test
    fun compactStopKeepsAutomaticHandoffSuppressed() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "compact-run")
        state.deferNextQueueDrain()

        val stopped = state.stop()

        assertEquals("compact-run", stopped.runId)
        assertFalse(state.consumeQueueDrainPermission())
        assertTrue(state.consumeQueueDrainPermission())
    }

    /**
     * The Stop barrier has two halves (coroutine unwind, durable terminal write) that can complete
     * in either order, and whichever finishes LAST performs the release.
     *
     * These two tests pin both orders against the same requirement: the releaser must announce the
     * settle through onQueueDrainRequested, because that is the only path which migrates the still-pending
     * queued inputs onto a fresh Run. A release that instead just reported "you may drain" would
     * look correct in isolation while handing the drain a terminalized Run, which fails deep inside
     * and strands durably-accepted user messages with no answer.
     */
    @Test
    fun stopSettledByDurableWriteLast_announcesSettle() = runBlocking {
        val active = activeStateWithStreamingMessage()
        val state = active.state
        var settledCount = 0
        state.onQueueDrainRequested = { settledCount += 1 }

        val stopped = state.stop()
        active.unwind.complete(Unit)
        active.job.join()
        // Coroutine unwound first, so it must not have settled anything on its own.
        assertEquals(0, settledCount)

        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.SETTLED,
            state.finishStopFinalization(stopped.completion(success = true)),
        )

        assertEquals(1, settledCount)
        assertFalse(state.generating.value)
        assertFalse(state.stopping.value)
    }

    @Test
    fun stopSettledByCoroutineUnwindLast_announcesSettle() = runBlocking {
        val active = activeStateWithStreamingMessage()
        val state = active.state
        val settled = CompletableDeferred<Unit>()
        state.onQueueDrainRequested = { settled.complete(Unit) }

        val stopped = state.stop()
        // Durable half lands first; it cannot release while the coroutine still owns the slot.
        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.RECORDED,
            state.finishStopFinalization(stopped.completion(success = true)),
        )
        assertFalse(settled.isCompleted)

        active.unwind.complete(Unit)
        active.job.join()

        // The coroutine released, so it owes the announcement.
        settled.await()
        assertFalse(state.generating.value)
        assertFalse(state.stopping.value)
    }

    @Test
    fun stopAndBothSettlements_areSerializedInMailboxOrder() = runBlocking {
        val active = activeStateWithStreamingMessage()
        val state = active.state
        val settled = CompletableDeferred<Unit>()
        state.onQueueDrainRequested = { settled.complete(Unit) }

        val stopped = state.stop()
        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.RECORDED,
            state.finishStopFinalization(stopped.completion(success = true)),
        )
        active.unwind.complete(Unit)
        active.job.join()
        settled.await()

        assertEquals(
            listOf(
                "AcquireSlot",
                "BindRun",
                "StopRequested",
                "PersistenceSettled",
                "CoroutineSettled",
            ),
            state.runtimeTraceSnapshot().map { it.commandType },
        )
    }

    @Test
    fun staleStopFinalizerCallback_cannotReleaseLaterStoppingRun() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val firstToken = state.acquireForSend()!!
        state.bindRun(firstToken, "first-run", pass = 2)
        state.streamUpdate(
            firstToken,
            ChatMessage(
                id = "first-model",
                text = "first",
                participant = Participant.MODEL,
                status = MessageStatus.SENDING,
            ),
        )
        val firstStop = state.stop()
        val firstCompletion = firstStop.completion(success = true)
        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.SETTLED,
            state.finishStopFinalization(firstCompletion),
        )

        val secondToken = state.acquireForSend()!!
        state.bindRun(secondToken, "second-run", pass = 5)
        state.streamUpdate(
            secondToken,
            ChatMessage(
                id = "second-model",
                text = "second",
                participant = Participant.MODEL,
                status = MessageStatus.SENDING,
            ),
        )
        val secondStop = state.stop()

        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.REJECTED,
            state.finishStopFinalization(firstCompletion),
        )
        assertTrue(state.generating.value)
        assertTrue(state.stopping.value)
        assertEquals("second-run", state.currentRunId())

        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.SETTLED,
            state.finishStopFinalization(secondStop.completion(success = true)),
        )
        assertFalse(state.generating.value)
    }

    @Test
    fun stopDuringQueuedPassClaim_rejectsTheNewPassBinding() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run", pass = 2)

        val stopped = state.stop()

        // The durable claim may finish after Stop, but it must not reopen the stopped owner for
        // pass 3. The controller treats this result as a hard boundary and terminalizes the rows.
        assertFalse(state.tryBindRun(token, "run", pass = 3))
        assertTrue(state.generating.value)
        assertTrue(state.stopping.value)
        assertEquals(
            ConversationGenerationState.StopFinalizationOutcome.SETTLED,
            state.finishStopFinalization(stopped.completion(success = true)),
        )
        assertFalse(state.generating.value)
    }

    @Test
    fun runtimeTrace_excludesStreamingMessageContent() = runBlocking {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run", pass = 4)
        state.streamUpdate(
            token,
            ChatMessage(
                id = "model",
                text = "STREAM_CONTENT_SENTINEL",
                participant = Participant.MODEL,
                status = MessageStatus.SENDING,
            ),
        )

        state.stop()

        val trace = state.runtimeTraceSnapshot()
        assertEquals(listOf("AcquireSlot", "BindRun", "StopRequested"), trace.map { it.commandType })
        assertFalse(trace.toString().contains("STREAM_CONTENT_SENTINEL"))
    }

    @Test
    fun guidanceLeaseUsesNormalSendContractForAFreshRun() = runBlocking {
        val state = ConversationGenerationState("conversation")
        state.enqueueSend(
            QueuedSend("guidance", "text", "model", emptyList(), "stopped-run"),
        )
        val lease = state.claimQueuedSends()!!

        val requested = state.commands.requestSend(
            proposedRunId = "fresh-run",
            effectId = "guidance-fresh-run",
            directOnly = false,
            hasPendingGuidance = false,
        )
        val effect = requested.effects.filterIsInstance<RunEffect.PersistAcceptedInput>().single()

        assertEquals("fresh-run", effect.identity.runId)
        assertFalse(lease.batch.any { it.runId == effect.identity.runId })
        assertTrue(state.inputPersisted(effect.identity))
        assertEquals("fresh-run", state.currentRunId())
        assertTrue(state.settleGuidanceClaim(lease.id, durable = true))
        assertTrue(finalizeBoundRun(state, effect.identity.ownerToken, "fresh-run"))
    }

    @Test
    fun removingQueuedGuidanceTransfersOwnershipExactlyOnce() {
        val state = ConversationGenerationState("conversation")
        val queued = QueuedSend("one", "first", "model", emptyList(), "run")
        state.enqueueSend(queued)

        assertEquals(queued, state.removeQueuedSend("one"))
        assertNull(state.removeQueuedSend("one"))
        assertTrue(state.queuedSends.value.isEmpty())
    }

    @Test
    fun failedBoundaryDrainDefersOnlyImmediateAutomaticRetry() {
        val state = ConversationGenerationState("conversation")

        state.deferNextQueueDrain()

        assertFalse(state.consumeQueueDrainPermission())
        assertTrue(state.consumeQueueDrainPermission())
    }

    @Test
    fun consecutiveOriginAndCompactDrainDeferralsCannotConsumeEachOther() {
        val state = ConversationGenerationState("conversation")

        state.deferNextQueueDrain()
        state.deferNextQueueDrain()

        assertFalse(state.consumeQueueDrainPermission())
        assertFalse(state.consumeQueueDrainPermission())
        assertTrue(state.consumeQueueDrainPermission())
    }

    @Test
    fun staleDrainDeferralCannotSuppressTheNextRunsDrain() {
        val state = ConversationGenerationState("conversation")
        // A run armed a deferral but never released its slot, so nothing consumed it.
        state.deferNextQueueDrain()
        assertNotNull(state.acquireForSend())
        assertTrue(state.consumeQueueDrainPermission())
    }

    @Test
    fun successfulCompactRemovesOnlyItsOwnDrainDeferral() {
        val state = ConversationGenerationState("conversation")

        state.deferNextQueueDrain()
        state.deferNextQueueDrain()
        state.cancelDeferredQueueDrain()

        assertFalse(state.consumeQueueDrainPermission())
        assertTrue(state.consumeQueueDrainPermission())
    }

    @Test
    fun successfulCompactRemovesItsDeferralAfterOriginSettlement() {
        val state = ConversationGenerationState("conversation")

        state.deferNextQueueDrain()
        state.deferNextQueueDrain()
        assertFalse(state.consumeQueueDrainPermission())
        state.cancelDeferredQueueDrain()

        assertTrue(state.consumeQueueDrainPermission())
    }

    private fun activeStateWithStreamingMessage(): ActiveGeneration {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")
        val unwind = CompletableDeferred<Unit>()
        val job = checkNotNull(
            state.launchGenerationJob(token) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { unwind.await() }
                }
            }
        )
        state.streamUpdate(
            token,
            ChatMessage(
                id = "model",
                text = "partial",
                participant = Participant.MODEL,
                status = MessageStatus.SENDING,
            )
        )
        return ActiveGeneration(state, token, job, unwind)
    }

    private data class ActiveGeneration(
        val state: ConversationGenerationState,
        val token: Long,
        val job: Job,
        val unwind: CompletableDeferred<Unit>,
    )
}
