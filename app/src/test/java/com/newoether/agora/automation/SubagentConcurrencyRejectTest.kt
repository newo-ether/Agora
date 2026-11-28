package com.newoether.agora.automation

import com.newoether.agora.tool.SubagentToolProvider
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SubagentConcurrencyRejectTest {
    private fun holdWorkers(f: SubagentManagerTest.Fixture) {
        coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            awaitCancellation()
        }
    }

    @Test fun fullCapacityReturnsToolErrorWithoutCreatingOrWritingAnotherChild() = runTest {
        val f = SubagentManagerTest.Fixture(backgroundScope)
        holdWorkers(f)
        repeat(4) { f.spawn("Worker $it") }
        // Admission precedes even the lazy workers' first dispatcher turn.
        assertEquals(4, f.coordinator.admittedChildCount("parent"))
        val before = f.children.toMap()
        val result = SubagentToolProvider(f.manager).executeOneShotResult("spawn_agent",
            """{"name":"Rejected","model_id":"Provider:model","task":"Task"}""", f.context)
        assertTrue(result.isError)
        assertTrue(result.text.startsWith("Error: Subagent concurrency limit reached (4/4"))
        assertTrue(result.text.contains("agent_wait"))
        assertFalse(result.text.contains("\"started\""))
        assertEquals(before, f.children)
        coVerify(exactly = 4) { f.dao.upsertConversation(any()) }
        coVerify(exactly = 4) { f.dao.upsertConversationSettingsTransfer(any()) }
        assertEquals(4, f.repository.subagentJobs.value.size)
    }

    @Test fun concurrentSpawnsContendForLastSlotBeforeFirstCreationCompletes() = runTest {
        val f = SubagentManagerTest.Fixture(backgroundScope)
        holdWorkers(f)
        f.concurrentLimit.value = 1
        val writing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        coEvery { f.dao.countSubagents("parent") } coAnswers {
            writing.complete(Unit)
            finish.await()
            0
        }
        val first = async { f.spawn("First") }
        writing.await()
        val second = async { runCatching { f.spawn("Second") } }
        assertTrue(second.await().isFailure)
        assertEquals(setOf("parent"), f.children.keys)
        finish.complete(Unit)
        val accepted = first.await()
        assertEquals(setOf("parent", accepted), f.children.keys)
        coVerify(exactly = 1) { f.dao.upsertConversation(any()) }
        coVerify(exactly = 1) { f.dao.upsertConversationSettingsTransfer(any()) }
    }

    @Test fun realParallelReservationsAdmitExactlyOneAndReleaseIdempotently() = runTest {
        val coordinator = ConversationExecutionCoordinator()
        val held = (0 until 3).map { coordinator.reserveSubagentGeneration("parent", "held-$it") }
        val results = (0 until 16).map { index ->
            async(Dispatchers.Default) {
                runCatching { coordinator.reserveSubagentGeneration("parent", "candidate-$index") }
            }
        }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(4, coordinator.admittedGenerationIds.value.size)
        (held + results.mapNotNull { it.getOrNull() }).forEach { it(); it() }
        assertEquals(0, coordinator.admittedChildCount("parent"))
        assertTrue(coordinator.admittedGenerationIds.value.isEmpty())
    }

    @Test fun failedCreationAndCancelledPreparationReleaseTheReservation() = runTest {
        val f = SubagentManagerTest.Fixture(backgroundScope)
        f.concurrentLimit.value = 1
        coEvery { f.dao.countSubagents("parent") } throws IllegalStateException("Creation failed")
        assertTrue(runCatching { f.spawn() }.isFailure)
        assertEquals(0, f.coordinator.admittedChildCount("parent"))
        assertEquals(setOf("parent"), f.children.keys)
        val entered = CompletableDeferred<Unit>()
        coEvery { f.dao.countSubagents("parent") } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = async { f.spawn() }
        entered.await()
        pending.cancel()
        pending.join()
        assertEquals(0, f.coordinator.admittedChildCount("parent"))
        coEvery { f.dao.countSubagents("parent") } returns 0
        f.spawn("After failure")
        assertEquals(1, f.coordinator.admittedChildCount("parent"))
    }

    @Test fun workerCancellationBeforeLazyBodyStartsReleasesCapacity() = runTest {
        val owner = Job()
        val f = SubagentManagerTest.Fixture(CoroutineScope(backgroundScope.coroutineContext + owner))
        f.spawn()
        owner.cancel()
        owner.join()
        assertEquals(0, f.coordinator.admittedChildCount("parent"))
        assertTrue(f.repository.subagentJobs.value.isEmpty())
    }

    @Test fun rejectionRetainsCapacityUntilCancellationCleanupFinishes() = runTest {
        val f = SubagentManagerTest.Fixture(backgroundScope)
        f.concurrentLimit.value = 1
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    cleanupStarted.complete(Unit)
                    cleanup.await()
                }
            }
        }
        val first = f.spawn()
        runCurrent()
        assertTrue(runCatching { f.spawn("Rejected") }.isFailure)
        val stop = async { f.manager.stop(first, f.context) }
        cleanupStarted.await()
        assertFalse(stop.isCompleted)
        assertEquals(1, f.coordinator.admittedChildCount("parent"))
        assertTrue(runCatching { f.spawn("During cleanup") }.isFailure)
        cleanup.complete(Unit)
        stop.await()
        assertEquals(0, f.coordinator.admittedChildCount("parent"))
        f.spawn("After cleanup")
    }

    @Test fun naturalCompletionFreesCapacityAndLoweringLimitLeavesExistingWorkIntact() = runTest {
        val f = SubagentManagerTest.Fixture(backgroundScope)
        f.concurrentLimit.value = 1
        val completed = f.spawn()
        runCurrent()
        assertEquals(0, f.coordinator.admittedChildCount("parent"))
        assertEquals("completed", f.children[completed]?.subagentResultStatus)
        holdWorkers(f)
        f.concurrentLimit.value = 2
        val first = f.spawn("First")
        val second = f.spawn("Second")
        runCurrent()
        f.concurrentLimit.value = 1
        assertTrue(runCatching { f.spawn("Rejected") }.isFailure)
        assertTrue(f.repository.subagentJobs.value.values.all { it.isActive })
        f.manager.stop(first, f.context)
        assertTrue(runCatching { f.spawn("Still full") }.isFailure)
        f.manager.stop(second, f.context)
        f.spawn("Available again")
        assertEquals(1, f.coordinator.admittedChildCount("parent"))
    }
}
