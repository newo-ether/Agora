package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentGenerationLifecycleTest {
    @Test
    fun durableTerminalCannotReleaseBeforeInstalledJobAndNonCancellableCleanup() = runBlocking<Unit> {
        withTimeout(5_000) {
            val coordinator = ConversationExecutionCoordinator({ "parent" }, MutableStateFlow(1))
            val state = ConversationGenerationState("child", reclaimQueuedAttachments = {})
            val token = checkNotNull(state.tryAcquireForReplacement())
            val entered = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val job = checkNotNull(state.launchGenerationJob(token) {
                assertTrue(state.acquireGenerationAdmission(token, coordinator))
                state.bindRun(token, "run")
                val effect = checkNotNull(state.commands.requestRunFinalization(
                    RunEffectIdentity("child", token, "run", 0, "terminal"),
                    RunStatus.COMPLETED, RunEndReason.MODEL_COMPLETED, false,
                ))
                assertEquals(ConversationGenerationState.RunFinalizationOutcome.RECORDED,
                    state.finishRunFinalization(effect.identity, true))
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            })
            entered.await()
            val waiterStarted = CompletableDeferred<Unit>()
            val sibling = async {
                waiterStarted.complete(Unit)
                coordinator.acquireGenerationAdmission("sibling")
            }
            waiterStarted.await()
            job.cancel()
            assertEquals(1, coordinator.admittedChildCount("parent"))
            assertFalse(sibling.isCompleted)
            cleanup.complete(Unit)
            job.join()
            state.awaitSendAvailable()
            sibling.await().invoke()
            assertEquals(0, coordinator.admittedChildCount("parent"))
            state.dispose()
        }
    }

    @Test
    fun completedJobCannotReleaseBeforeDurableTerminal_andFailedFinalizationRetainsAllowance() = runBlocking<Unit> {
        withTimeout(5_000) {
            val coordinator = ConversationExecutionCoordinator({ "parent" }, MutableStateFlow(1))
            val state = ConversationGenerationState("child", reclaimQueuedAttachments = {})
            val token = checkNotNull(state.tryAcquireForReplacement())
            val ready = CompletableDeferred<RunEffectIdentity>()
            val job = checkNotNull(state.launchGenerationJob(token) {
                assertTrue(state.acquireGenerationAdmission(token, coordinator))
                state.bindRun(token, "run")
                val effect = checkNotNull(state.commands.requestRunFinalization(
                    RunEffectIdentity("child", token, "run", 0, "terminal"),
                    RunStatus.COMPLETED, RunEndReason.MODEL_COMPLETED, false,
                ))
                ready.complete(effect.identity)
            })
            job.join()
            val effect = ready.await()
            state.endGeneration(token)
            assertEquals(1, coordinator.admittedChildCount("parent"))
            assertEquals(ConversationGenerationState.RunFinalizationOutcome.FAILED,
                state.finishRunFinalization(effect, false))
            assertEquals(1, coordinator.admittedChildCount("parent"))
            assertEquals(ConversationGenerationState.RunFinalizationOutcome.SETTLED,
                state.finishRunFinalization(effect, true))
            coordinator.withGenerationAdmission("sibling") {
                assertEquals(1, coordinator.admittedChildCount("parent"))
            }
            state.dispose()
        }
    }

    @Test
    fun externalOwnerJobAndWorkerShareAllowanceAcrossCompactAndFreshOrdinaryRuns() = runBlocking<Unit> {
        withTimeout(5_000) {
            val coordinator = ConversationExecutionCoordinator({ "parent" }, MutableStateFlow(1))
            val state = ConversationGenerationState("child", reclaimQueuedAttachments = {})
            coordinator.withGenerationAdmission("child") {
                repeat(2) { index ->
                    val token = checkNotNull(state.tryAcquireForReplacement())
                    val owner = Job()
                    assertTrue(state.attachGenerationJob(token, owner))
                    assertTrue(state.acquireGenerationAdmission(token, coordinator))
                    state.bindRun(token, "run-$index")
                    val effect = checkNotNull(state.commands.requestRunFinalization(
                        RunEffectIdentity("child", token, "run-$index", 0, "terminal-$index"),
                        RunStatus.COMPLETED, RunEndReason.MODEL_COMPLETED, false,
                    ))
                    state.finishRunFinalization(effect.identity, true)
                    owner.complete()
                    owner.join()
                    state.awaitSendAvailable()
                    assertEquals(1, coordinator.admittedChildCount("parent"))
                }
            }
            // The slot becomes available before its completion hook releases the worker lease.
            coordinator.admittedGenerationIds.first { "child" !in it }
            assertEquals(0, coordinator.admittedChildCount("parent"))
            state.dispose()
        }
    }

    @Test
    fun stopWhileWaitingForFamilyAllowanceDoesNotLeakOrStartWork() = runBlocking<Unit> {
        withTimeout(5_000) {
            val coordinator = ConversationExecutionCoordinator({ "parent" }, MutableStateFlow(1))
            val release = coordinator.acquireGenerationAdmission("active")
            val state = ConversationGenerationState("child", reclaimQueuedAttachments = {})
            val token = checkNotNull(state.tryAcquireForReplacement())
            val waiting = CompletableDeferred<Unit>()
            val job = checkNotNull(state.launchGenerationJob(token) {
                waiting.complete(Unit)
                if (state.acquireGenerationAdmission(token, coordinator)) error("must remain waiting")
            })
            waiting.await()
            state.stop()
            job.join()
            state.awaitSendAvailable()
            release()
            assertEquals(0, coordinator.admittedChildCount("parent"))
            state.dispose()
        }
    }
}
