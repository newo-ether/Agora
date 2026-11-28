package com.newoether.agora.viewmodel

import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.RunState
import com.newoether.agora.model.RuntimeRunIdentity
import com.newoether.agora.phone.PhoneControlLease
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhoneRunAuthorityTest {
    @Test fun onlyExactExecutingBatchCanAuthorizePhoneEffects() = runBlocking<Unit> {
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val token = state.acquireForSend()!!
        state.bindRun(token, "run", pass = 2)
        val provider = RunEffectIdentity("conversation", token, "run", 2, "provider-2-0")
        assertFalse(state.acceptsToolEffect(provider))
        assertTrue(state.controlsRun("run"))
        val batch = state.commands.requestToolBatch(provider)!!
        assertTrue(state.acceptsToolEffect(batch.identity))
        for (stale in listOf(
            batch.identity.copy(conversationId = "other"), batch.identity.copy(ownerToken = token + 1),
            batch.identity.copy(runId = "old"), batch.identity.copy(pass = 1), batch.identity.copy(effectId = "old"),
        )) assertFalse(state.acceptsToolEffect(stale))
        state.commands.completeToolBatch(batch.identity)
        assertFalse(state.acceptsToolEffect(batch.identity))
        state.stop()
        assertFalse(state.controlsRun("run"))
        state.dispose()
    }

    @Test fun stopSettlementAndDisposalReleaseTheCanonicalRunsLease() = runBlocking<Unit> {
        for (trigger in listOf("stop", "settle", "dispose")) {
            val lease = PhoneControlLease { 0 }
            val state = state(lease)
            val token = state.acquireForSend()!!
            state.bindRun(token, "run")
            lease.claim(identity(token, "run"), "Title")
            when (trigger) {
                "stop" -> state.stop()
                "settle" -> assertTrue(finalizeBoundRun(state, token, "run"))
                "dispose" -> state.dispose()
            }
            assertNull("Lease remained after $trigger", lease.held.value)
            assertFalse(state.controlsRun("run"))
            state.dispose()
        }
    }

    @Test fun continuationKeepsOneEpochThroughEitherSettlementOrderAndBindsOnlyFreshRun() = runBlocking<Unit> {
        for (coroutineFirst in listOf(false, true)) {
            var time = 100L
            val lease = PhoneControlLease { time }
            val state = state(lease)
            val token = state.acquireForSend()!!
            state.bindRun(token, "origin")
            val oldBatch = state.commands.requestToolBatch(identity(token, "origin"))!!.identity
            val permit = (lease.claim(oldBatch, "Title", "root") as PhoneControlLease.Decision.Granted).permit
            val initial = lease.held.value!!
            time += 41_000
            lease.tick()
            val budget = lease.held.value!!.remainingMs
            val finalization = requestContinuation(state, token, "origin")
            assertFalse(state.acceptsToolEffect(oldBatch))
            assertNotNull(lease.held.value)
            if (coroutineFirst) {
                assertFalse(state.endGeneration(token))
                assertEquals(ConversationGenerationState.RunFinalizationOutcome.SETTLED,
                    state.finishRunFinalization(finalization, true))
            } else {
                assertEquals(ConversationGenerationState.RunFinalizationOutcome.RECORDED,
                    state.finishRunFinalization(finalization, true))
                assertTrue(state.endGeneration(token))
            }
            assertFalse(state.generating.value)
            assertEquals(permit.epoch, lease.held.value!!.epoch)
            val input = state.commands.requestSend("fresh", "input-fresh", false, false)
                .effects.filterIsInstance<RunEffect.PersistAcceptedInput>().single()
            assertEquals("origin", lease.held.value!!.owner.runId)
            state.finishRunHandoff(token)
            assertEquals(ConversationGenerationState.RunBindingOutcome.Rejected,
                state.finishInputPersistence(input.identity.copy(effectId = "stale")))
            assertEquals("origin", lease.held.value!!.owner.runId)
            assertEquals(ConversationGenerationState.RunBindingOutcome.Active,
                state.finishInputPersistence(input.identity))
            val held = lease.held.value!!
            assertEquals("fresh", held.owner.runId)
            assertEquals(initial.epoch, held.epoch)
            assertEquals(initial.claimedAt, held.claimedAt)
            assertEquals("root", held.mode)
            assertEquals(budget, held.remainingMs)
            assertTrue(lease.recentReleases().isEmpty())
            assertNotNull(lease.validate(permit))
            assertFalse(state.acceptsToolEffect(oldBatch))
            val freshBatch = state.commands.requestToolBatch(input.identity.copy(effectId = "provider-fresh"))!!
            assertTrue(state.acceptsToolEffect(freshBatch.identity))
            assertTrue(lease.authorize(freshBatch.identity) is PhoneControlLease.Decision.Granted)
            state.dispose()
        }
    }

    @Test fun unboundSuccessorStopFailureAbandonmentAndDisposalRetireTheRetainedEpoch() = runBlocking<Unit> {
        for (trigger in listOf("stop", "input_failure", "abandon", "coroutine", "dispose", "idle_dispose")) {
            val lease = PhoneControlLease { 0 }
            val state = state(lease)
            val token = state.acquireForSend()!!
            state.bindRun(token, "origin")
            lease.claim(identity(token, "origin"), "Title")
            settleContinuation(state, token, "origin")
            if (trigger == "idle_dispose") {
                state.dispose()
            } else if (trigger == "coroutine") {
                val successor = state.tryAcquireForReplacement()!!
                assertTrue(state.endGeneration(successor))
            } else {
                val input = state.commands.requestSend("fresh", "input-fresh", false, false)
                    .effects.filterIsInstance<RunEffect.PersistAcceptedInput>().single()
                when (trigger) {
                    "stop" -> {
                        state.stop()
                        assertEquals(ConversationGenerationState.RunBindingOutcome.Rejected,
                            state.finishInputPersistence(input.identity))
                    }
                    "input_failure" -> state.commands.inputPersistenceFailed(input.identity)
                    "abandon" -> state.commands.abandonSendLaunch(input.identity)
                    "dispose" -> state.dispose()
                }
            }
            assertNull("Lease remained after $trigger", lease.held.value)
            state.dispose()
        }
    }

    @Test fun failedTerminalPersistenceReleasesAndStaleFailureCannotReleaseSuccessor() = runBlocking<Unit> {
        val lease = PhoneControlLease { 0 }
        val state = state(lease)
        val token = state.acquireForSend()!!
        state.bindRun(token, "origin")
        lease.claim(identity(token, "origin"), "Title")
        val finalization = requestContinuation(state, token, "origin")
        assertEquals(ConversationGenerationState.RunFinalizationOutcome.REJECTED,
            state.finishRunFinalization(finalization.copy(effectId = "old"), false))
        assertNotNull(lease.held.value)
        assertEquals(ConversationGenerationState.RunFinalizationOutcome.FAILED,
            state.finishRunFinalization(finalization, false))
        assertNull(lease.held.value)
        state.dispose()
    }

    @Test fun repeatedFreshRunHandoffsRejectOldCleanupAndDisposeFromTheIdleGap() = runBlocking<Unit> {
        val lease = PhoneControlLease { 0 }
        val registry = ConversationStateRegistry(
            onRunStateChanged = lease::onRunStateChanged,
            onRunHandoffFinished = lease::finishHandoff,
            reclaimQueuedAttachments = {},
        )
        val state = registry.getOrCreate("conversation")
        val origin = state.acquireForSend()!!
        state.bindRun(origin, "origin")
        lease.claim(identity(origin, "origin"), "Title")
        val epoch = lease.held.value!!.epoch
        settleContinuation(state, origin, "origin")
        val compact = state.tryAcquireForReplacement()!!
        state.bindRun(compact, "compact")
        settleContinuation(state, compact, "compact")
        state.finishRunHandoff(origin)
        assertEquals(epoch, lease.held.value!!.epoch)
        val successor = state.tryAcquireForReplacement()!!
        state.bindRun(successor, "successor")
        state.finishRunHandoff(compact)
        assertEquals("successor", lease.held.value!!.owner.runId)
        assertEquals(ConversationGenerationState.RunFinalizationOutcome.REJECTED,
            state.finishRunFinalization(identity(origin, "origin", "finalize-origin"), false))
        assertNotNull(lease.held.value)
        settleContinuation(state, successor, "successor")
        registry.remove("conversation")
        assertNull(lease.held.value)
    }

    @Test fun noSuccessorCleanupIsScopedToTheRuntimeInstanceAndOriginToken() = runBlocking<Unit> {
        val lease = PhoneControlLease { 0 }
        val state = state(lease)
        val token = state.acquireForSend()!!
        state.bindRun(token, "origin")
        lease.claim(identity(token, "origin"), "Title")
        settleContinuation(state, token, "origin")
        val other = state(lease)
        other.finishRunHandoff(token)
        state.finishRunHandoff(token + 1)
        assertNotNull(lease.held.value)
        state.finishRunHandoff(token)
        assertNull(lease.held.value)
        state.dispose()
        other.dispose()
    }

    @Test fun idleQueueDecisionRetainsLeaseAndDirectBoundSuccessorTransfersInOneTransition() = runBlocking<Unit> {
        val lease = PhoneControlLease { 0L }
        val state = state(lease)
        val token = state.acquireForSend()!!
        state.bindRun(token, "origin")
        lease.claim(identity(token, "origin"), "Title")
        settleContinuation(state, token, "origin")
        val epoch = lease.held.value!!.epoch
        val idleDecision = state.commands.requestSend("queued", "queue-check", false, true)
        assertTrue(idleDecision.accepted)
        assertTrue(idleDecision.newState is RunState.Idle)
        assertEquals(epoch, lease.held.value!!.epoch)
        lease.onRunStateChanged(state, idleDecision.newState, RunState.Active(
            RuntimeRunIdentity("conversation", token + 1, "fresh", 0),
        ))
        assertEquals(PhoneControlLease.Owner("conversation", "fresh"), lease.held.value!!.owner)
        assertEquals(epoch, lease.held.value!!.epoch)
        state.finishRunHandoff(token)
        assertNotNull(lease.held.value)
        lease.releaseEpoch(epoch, "run_ended")
        state.dispose()
    }

    @Test fun sameEpochNotificationRevokesTheCurrentSuccessorWithoutStoppingItsRun() = runBlocking<Unit> {
        val lease = PhoneControlLease { 0L }
        val state = state(lease)
        try {
            val origin = state.acquireForSend()!!
            state.bindRun(origin, "origin")
            lease.claim(identity(origin, "origin"), "Title")
            val epoch = lease.held.value!!.epoch
            settleContinuation(state, origin, "origin")
            val successor = state.tryAcquireForReplacement()!!
            state.bindRun(successor, "successor")
            val batch = state.commands.requestToolBatch(identity(successor, "successor"))!!.identity
            val permit = (lease.authorize(batch) as PhoneControlLease.Decision.Granted).permit

            lease.revokeEpochByUser("other-conversation", epoch)
            assertNull(lease.validate(permit))
            lease.revokeEpochByUser("conversation", epoch)

            assertNull(lease.held.value)
            assertEquals("released_by_user", lease.validate(permit))
            assertEquals("released_by_user", (lease.claim(batch, "Title") as PhoneControlLease.Decision.Refused).code)
            assertTrue(state.acceptsToolEffect(batch))
            assertTrue(state.controlsRun("successor"))
            assertTrue(finalizeBoundRun(state, successor, "successor"))
            val fresh = state.acquireForSend()!!
            state.bindRun(fresh, "fresh")
            val freshPermit = (lease.claim(identity(fresh, "fresh"), "Title") as PhoneControlLease.Decision.Granted).permit
            assertNotEquals(epoch, freshPermit.epoch)
            lease.revokeEpochByUser("conversation", epoch)
            assertNull(lease.validate(freshPermit))
        } finally {
            state.dispose()
        }
    }

    @Test fun notificationRevokesTheRetainedLeaseDuringTheIdleHandoffGap() = runBlocking<Unit> {
        val lease = PhoneControlLease { 0L }
        val state = state(lease)
        try {
            val origin = state.acquireForSend()!!
            state.bindRun(origin, "origin")
            lease.claim(identity(origin, "origin"), "Title")
            val epoch = lease.held.value!!.epoch
            settleContinuation(state, origin, "origin")
            assertFalse(state.generating.value)
            assertNotNull(lease.held.value)

            lease.revokeEpochByUser("conversation", epoch)
            state.finishRunHandoff(origin)

            assertNull(lease.held.value)
            assertEquals("released_by_user", lease.recentReleases().single().reason)
        } finally {
            state.dispose()
        }
    }

    private fun state(lease: PhoneControlLease) = ConversationGenerationState(
        "conversation", onRunStateChanged = lease::onRunStateChanged,
        onRunHandoffFinished = lease::finishHandoff, reclaimQueuedAttachments = {},
    )

    private fun identity(token: Long, run: String, effect: String = "provider-$run") =
        RunEffectIdentity("conversation", token, run, 0, effect)

    private suspend fun requestContinuation(state: ConversationGenerationState, token: Long, run: String): RunEffectIdentity {
        val identity = identity(token, run, "finalize-$run")
        assertNotNull(state.commands.requestRunFinalization(
            identity, RunStatus.COMPLETED, RunEndReason.CONTINUATION_PENDING, false,
        ))
        return identity
    }

    private suspend fun settleContinuation(state: ConversationGenerationState, token: Long, run: String) {
        val finalization = requestContinuation(state, token, run)
        assertEquals(ConversationGenerationState.RunFinalizationOutcome.RECORDED,
            state.finishRunFinalization(finalization, true))
        assertTrue(state.endGeneration(token))
    }
}
