package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffectIdentity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SubagentMessageQueueTest {
    private class Fixture {
        val dao = mockk<ChatDao>()
        val conversations = ConversationRepository(dao, null, scheduleMaintenance = {})
        val registry = ConversationStateRegistry {}
        val rows = mutableMapOf(
            "child" to ChatEntity("child", "Worker", modelId = "Provider:child", parentConversationId = "parent"),
            "other" to ChatEntity("other", "Other", modelId = "Provider:other", parentConversationId = "elsewhere"),
        )
        val identity = RunEffectIdentity("parent", 7L, "parent-run", 2, "tool-effect")
        val parentContext = GenerationContext(
            conversationId = "parent", toolEffectIdentity = identity, subagentToolsEnabled = true,
            shellEnabled = true, automationToolsEnabled = true,
        )
        val coordinator = ConversationExecutionCoordinator({ id -> rows[id]?.parentConversationId })
        var parentActive = true
        val captures = mutableListOf<Triple<String, String, String>>()
        val snapshots = mutableListOf<GenerationAdmissionSnapshot>()
        val drains = mutableListOf<String>()
        var prepare: suspend (String, String, String) -> GenerationAdmissionSnapshot = { id, run, model ->
            testGenerationAdmissionSnapshot(conversationId = id, runId = run, selectedModelId = model)
        }
        private var nextId = 0
        val queue: SubagentMessageQueue

        init {
            coEvery { dao.isSubagentToolOwnerActive(any(), any(), any()) } answers {
                parentActive && firstArg<String>() == identity.conversationId &&
                    secondArg<String>() == identity.runId && thirdArg<Int>() == identity.pass
            }
            coEvery { dao.getConversation(any()) } answers { rows[firstArg()] }
            registry.attachUiCallbacks(this) { state ->
                state.onQueueDrainRequested = { drains += it.conversationId }
            }
            queue = SubagentMessageQueue(conversations, registry, coordinator, { id, run, model ->
                captures += Triple(id, run, model)
                prepare(id, run, model).also { snapshots += it }
            }, { "queue-${++nextId}" })
        }
    }

    @Test fun childrenKeepFrozenPhoneAdmissionAndAutomationLineageFromTheirParent() = runTest {
        val f = Fixture()
        try {
            val parent = f.parentContext.copy(phoneEnabled = true, phoneReady = true, phoneMode = "root",
                phoneAllowAutomation = false, automationCaller = true)
            f.queue.enqueue("child", "Follow up", parent)
            val child = f.registry.get("child")!!.queuedSends.value.single().generationSnapshot!!.context
            assertTrue(child.phoneEnabled)
            assertTrue(child.phoneReady)
            assertEquals("root", child.phoneMode)
            assertTrue(child.automationCaller)
            assertFalse(child.phoneAllowAutomation)
            assertNull(child.toolEffectIdentity)
            assertTrue(com.newoether.agora.phone.phoneDefinitions(child).isEmpty())
        } finally { f.registry.cancelAll() }
    }

    @Test fun idleWakeupAtCapacityFailsBeforeSnapshotOrQueueAndBusyChildReusesAllowance() = runTest {
        val f = Fixture()
        val releases = (0 until 4).map { f.coordinator.reserveSubagentGeneration("parent", "busy-$it") }
        try {
            val failure = runCatching { f.queue.enqueue("child", "Rejected", f.parentContext) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("4/4"))
            assertTrue(f.captures.isEmpty())
            assertNull(f.registry.get("child"))
            assertTrue(f.drains.isEmpty())
            releases.first().invoke()
            val childOwner = f.coordinator.reserveSubagentGeneration("parent", "child")
            f.queue.enqueue("child", "Accepted follow up", f.parentContext)
            assertEquals(4, f.coordinator.admittedChildCount("parent"))
            val state = requireNotNull(f.registry.get("child"))
            childOwner()
            assertEquals(4, f.coordinator.admittedChildCount("parent"))
            state.dismissQueuedSend(state.queuedSends.value.single().id)
            assertEquals(3, f.coordinator.admittedChildCount("parent"))
        } finally {
            f.registry.cancelAll()
            releases.forEach { it() }
        }
    }

    @Test fun wakeupReservationSurvivesClaimRollbackAndTransfersToOrdinaryGeneration() = runTest {
        val f = Fixture()
        try {
            f.queue.enqueue("child", "Follow up", f.parentContext)
            val state = requireNotNull(f.registry.get("child"))
            assertEquals(setOf("child"), f.coordinator.admittedGenerationIds.value)
            val first = requireNotNull(state.claimQueuedSends())
            assertTrue(state.settleGuidanceClaim(first.id, durable = false))
            assertEquals(1, f.coordinator.admittedChildCount("parent"))
            val releaseRun = f.coordinator.acquireGenerationAdmission("child")
            val next = requireNotNull(state.claimQueuedSends())
            assertTrue(state.settleGuidanceClaim(next.id, durable = true))
            assertEquals(1, f.coordinator.admittedChildCount("parent"))
            releaseRun()
            assertTrue(f.coordinator.admittedGenerationIds.value.isEmpty())
            f.queue.enqueue("child", "Dispose me", f.parentContext)
            f.registry.remove("child")
            assertEquals(0, f.coordinator.admittedChildCount("parent"))
        } finally {
            f.registry.cancelAll()
        }
    }

    @Test fun failedOrCancelledWakeupPreparationReleasesCapacity() = runTest {
        val f = Fixture()
        try {
            f.prepare = { _, _, _ -> error("Preparation failed") }
            assertTrue(runCatching { f.queue.enqueue("child", "Rejected", f.parentContext) }.isFailure)
            assertEquals(0, f.coordinator.admittedChildCount("parent"))
            val entered = CompletableDeferred<Unit>()
            f.prepare = { _, _, _ -> entered.complete(Unit); awaitCancellation() }
            val pending = async { f.queue.enqueue("child", "Cancelled", f.parentContext) }
            entered.await()
            pending.cancel()
            pending.join()
            assertEquals(0, f.coordinator.admittedChildCount("parent"))
            assertTrue(f.registry.get("child")?.queuedSends?.value.orEmpty().isEmpty())
        } finally {
            f.registry.cancelAll()
        }
    }

    @Test fun busyChildQueuesWithoutInterruptingItsInstalledGenerationOrReadingAnotherOwner() = runTest {
        val f = Fixture()
        try {
            val state = f.registry.getOrCreate("child")
            val token = requireNotNull(state.acquireForSend())
            state.bindRun(token, "child-running")
            val entered = CompletableDeferred<Unit>()
            val job = requireNotNull(state.launchGenerationJob(token) {
                entered.complete(Unit)
                awaitCancellation()
            })
            entered.await()
            val receipt = f.queue.enqueue("child", "Follow up", f.parentContext)
            assertEquals("queue-1", receipt)
            assertEquals(receipt, state.queuedSends.value.single().id)
            assertEquals("Follow up", state.queuedSends.value.single().text)
            assertTrue(job.isActive)
            assertTrue(state.generating.value)
            assertEquals("child-running", state.currentRunId())
            assertTrue(f.drains.isEmpty())
            assertNull(f.registry.get("parent"))
            assertNull(f.registry.get("other"))
            coVerify(exactly = 0) { f.dao.getConversation("parent") }
            coVerify(exactly = 0) { f.dao.getConversation("other") }
        } finally {
            f.registry.cancelAll()
        }
    }

    @Test fun idleChildRequestsOrdinaryDrainWithItsVisibleQueueItemAlreadyPresent() = runTest {
        val f = Fixture()
        try {
            val state = f.registry.getOrCreate("child")
            var atDrain: List<String>? = null
            f.registry.attachUiCallbacks(this) { owner ->
                owner.onQueueDrainRequested = { atDrain = it.queuedSends.value.map(QueuedSend::id) }
            }
            val receipt = f.queue.enqueue("child", "Idle follow up", f.parentContext)
            assertEquals(listOf(receipt), atDrain)
            assertFalse(state.generating.value)
            assertNull(state.currentRunId())
            assertEquals("Provider:child", state.queuedSends.value.single().modelId)
        } finally {
            f.registry.cancelAll()
        }
    }

    @Test fun fifoClaimsRollbackAndRemovalStayOwnedByTheExistingGuidanceStore() = runTest {
        val f = Fixture()
        try {
            val first = f.queue.enqueue("child", "First", f.parentContext)
            val second = f.queue.enqueue("child", "Second", f.parentContext)
            val state = requireNotNull(f.registry.get("child"))
            val lease = state.queueMutationMutex.withLock { requireNotNull(state.claimQueuedSends()) }
            assertEquals(listOf(first, second), lease.batch.map(QueuedSend::id))
            assertTrue(state.queuedSends.value.isEmpty())
            val third = f.queue.enqueue("child", "Third", f.parentContext)
            assertTrue(state.settleGuidanceClaim(lease.id, durable = false))
            assertEquals(listOf(first, second, third), state.queuedSends.value.map(QueuedSend::id))
            state.queueMutationMutex.withLock { state.dismissQueuedSend(second) }
            assertEquals(listOf("First", "Third"), state.queuedSends.value.map(QueuedSend::text))
            val retained = state.queuedSends.value
            val nextLease = state.queueMutationMutex.withLock { requireNotNull(state.claimQueuedSends()) }
            assertEquals(retained, nextLease.batch)
            assertEquals(listOf(first, third), nextLease.batch.map(QueuedSend::id))
            assertEquals(listOf("First", "Third"), nextLease.batch.map(QueuedSend::text))
            assertTrue(state.queuedSends.value.isEmpty())
            assertTrue(state.settleGuidanceClaim(nextLease.id, durable = true))
            assertFalse(state.settleGuidanceClaim(nextLease.id, durable = false))
            assertEquals(3, f.captures.map { it.second }.distinct().size)
            assertTrue(f.captures.none { it.second == f.identity.runId })
        } finally {
            f.registry.cancelAll()
        }
    }

    @Test fun modelFollowupsStayMarkedAcrossFifoRollbackWhileTypedInputStaysPlain() = runTest {
        val f = Fixture()
        try {
            val first = f.queue.enqueue("child", "First", f.parentContext)
            val second = f.queue.enqueue("child", "Second", f.parentContext)
            val state = requireNotNull(f.registry.get("child"))
            val typed = QueuedSend("typed", "My own message", "Provider:child", emptyList(), "typed-run")
            state.enqueueSend(typed)
            val lease = state.queueMutationMutex.withLock { requireNotNull(state.claimQueuedSends()) }
            assertEquals(listOf(first, second, "typed"), lease.batch.map { it.id })
            assertEquals(listOf(MessageSource.Kind.SUBAGENT, MessageSource.Kind.SUBAGENT, null),
                lease.batch.map { it.source?.kind })
            lease.batch.take(2).forEach { item ->
                assertTrue(item.source?.subagentResults.orEmpty().isEmpty())
                assertEquals(item.source, MessageSource.decode(MessageSource.encode(item.source)))
            }
            assertTrue(state.settleGuidanceClaim(lease.id, durable = false))
            assertEquals(lease.batch, state.queuedSends.value)
            assertEquals(listOf("First", "Second", "My own message"), state.queuedSends.value.map { it.text })
            state.removeSubagentResults(mapOf("child" to "unrelated-result"))
            assertEquals("Instruction sources are not completion receipts", lease.batch, state.queuedSends.value)
            val messages = state.queuedSends.value.map { item ->
                ChatMessage(id = item.id, text = item.text, participant = Participant.USER, source = item.source)
            }
            val projected = projectAutomaticMessagesForApi(messages)
            assertTrue(projected[0].text.startsWith("<subagent_result>"))
            assertTrue(projected[1].text.startsWith("<subagent_result>"))
            assertEquals("My own message", projected[2].text)
            assertNull(projected[2].source)
        } finally {
            f.registry.cancelAll()
        }
    }

    @Test fun inheritedContextClearsParentIdentityAndKeepsChildRestrictionsInOrdinaryAndCompactPaths() = runTest {
        val f = Fixture()
        try {
            val keys = mutableMapOf("search" to "parent-key")
            val enabledModels = mutableListOf("Provider:parent")
            val parent = f.parentContext.copy(
                enabledModelIds = enabledModels, webSearchApiKeys = keys,
                skillReadAccess = false, skillModifyAccess = false,
                accessSavedMemories = false, accessActiveMemory = false,
                sandboxEnabled = false, sandboxSharedStorageEnabled = false,
                askUserEnabled = false, webSearchEnabled = false, imageGenEnabled = false,
                skillCatalog = "parent catalog", modelSearchMethod = "parent method", toolTimeoutMs = 123L,
                foregroundServiceManagedExternally = true,
            )
            f.prepare = { id, run, model ->
                val captured = testGenerationAdmissionSnapshot(id, run, model)
                captured.copy(context = captured.context.copy(todoEnabled = false, shellEnabled = false))
            }
            f.queue.enqueue("child", "Task only", parent)
            val item = requireNotNull(f.registry.get("child")).queuedSends.value.single()
            val snapshot = requireNotNull(item.generationSnapshot)
            val expected = parent.copy(
                conversationId = "child", toolEffectIdentity = null, subagentToolsEnabled = false,
                accessPastConversations = false, todoEnabled = false, foregroundServiceManagedExternally = false,
            )
            assertEquals(expected, snapshot.context)
            assertEquals(snapshot.context, snapshot.automaticCompact.generationContext)
            assertEquals("Task only", item.text)
            assertEquals(MessageSource.Kind.SUBAGENT, item.source?.kind)
            assertTrue(item.source?.subagentResults.orEmpty().isEmpty())
            assertTrue(item.attachments.isEmpty())
            assertNotEquals(f.identity.runId, snapshot.runId)
            assertEquals(f.snapshots.single().config, snapshot.config)
            assertEquals(f.snapshots.single().providerInstances, snapshot.providerInstances)
            keys["later"] = "not admitted"
            enabledModels += "Provider:later"
            assertEquals(mapOf("search" to "parent-key"), snapshot.context.webSearchApiKeys)
            assertEquals(listOf("Provider:parent"), snapshot.context.enabledModelIds)
        } finally {
            f.registry.cancelAll()
        }
    }

    @Test fun todoPermissionIsTheIntersectionOfParentAndNewCapture() = runTest {
        val f = Fixture()
        try {
            for (parentAllowed in listOf(false, true)) {
                for (capturedAllowed in listOf(false, true)) {
                    f.prepare = { id, run, model ->
                        val snapshot = testGenerationAdmissionSnapshot(id, run, model)
                        snapshot.copy(context = snapshot.context.copy(todoEnabled = capturedAllowed))
                    }
                    val receipt = f.queue.enqueue("child", "Follow up", f.parentContext.copy(todoEnabled = parentAllowed))
                    val item = requireNotNull(f.registry.get("child")).queuedSends.value.single { it.id == receipt }
                    assertEquals(parentAllowed && capturedAllowed, item.generationSnapshot?.context?.todoEnabled)
                }
            }
        } finally {
            f.registry.cancelAll()
        }
    }

    @Test fun invalidParentsAndForeignOrMissingChildrenNeverCaptureOrQueue() = runTest {
        val f = Fixture()
        try {
            val invalidParents = listOf(
                f.parentContext.copy(subagentToolsEnabled = false),
                f.parentContext.copy(toolEffectIdentity = null),
                f.parentContext.copy(conversationId = "elsewhere"),
                f.parentContext.copy(toolEffectIdentity = f.identity.copy(runId = "old-run")),
                f.parentContext.copy(toolEffectIdentity = f.identity.copy(pass = 1)),
            )
            invalidParents.forEach { context ->
                assertTrue(runCatching { f.queue.enqueue("child", "Task", context) }.isFailure)
            }
            coVerify(exactly = 0) { f.dao.getConversation(any()) }
            assertTrue(runCatching { f.queue.enqueue("other", "Task", f.parentContext) }.isFailure)
            assertTrue(runCatching { f.queue.enqueue("missing", "Task", f.parentContext) }.isFailure)
            assertTrue(runCatching { f.queue.enqueue("child", " ", f.parentContext) }.isFailure)
            assertTrue(f.captures.isEmpty())
            assertNull(f.registry.get("child"))
            assertNull(f.registry.get("other"))
            assertTrue(f.drains.isEmpty())
        } finally {
            f.registry.cancelAll()
        }
    }

    @Test fun parentInvalidatedDuringSnapshotPreparationDoesNotLeaveAnEntry() = runTest {
        val f = Fixture()
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.prepare = { id, run, model ->
                entered.complete(Unit)
                release.await()
                testGenerationAdmissionSnapshot(id, run, model)
            }
            val send = async { runCatching { f.queue.enqueue("child", "Task", f.parentContext) } }
            entered.await()
            f.parentActive = false
            release.complete(Unit)
            assertTrue(send.await().isFailure)
            assertTrue(f.registry.get("child")?.queuedSends?.value.orEmpty().isEmpty())
            coVerify(exactly = 1) { f.dao.getConversation("child") }
            assertTrue(f.drains.isEmpty())
        } finally {
            f.registry.cancelAll()
        }
    }

    @Test fun parentIsRevalidatedAfterWaitingForTheQueueMutationLock() = runTest {
        val f = Fixture()
        try {
            val state = f.registry.getOrCreate("child")
            state.queueMutationMutex.lock()
            val send = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { f.queue.enqueue("child", "Task", f.parentContext) }
            }
            assertFalse(send.isCompleted)
            assertEquals(1, f.captures.size)
            f.parentActive = false
            state.queueMutationMutex.unlock()
            assertTrue(send.await().isFailure)
            assertTrue(state.queuedSends.value.isEmpty())
            assertTrue(f.drains.isEmpty())
        } finally {
            f.registry.cancelAll()
        }
    }

    @Test fun childDeletionOwnershipOrModelChangeDuringPreparationDoesNotQueue() = runTest {
        val f = Fixture()
        try {
            val original = requireNotNull(f.rows["child"])
            for (replacement in listOf(null, original.copy(parentConversationId = "elsewhere"),
                original.copy(modelId = "Provider:changed"))) {
                f.rows["child"] = original
                f.prepare = { id, run, model ->
                    if (replacement == null) f.rows.remove(id) else f.rows[id] = replacement
                    testGenerationAdmissionSnapshot(id, run, model)
                }
                assertTrue(runCatching { f.queue.enqueue("child", "Task", f.parentContext) }.isFailure)
                assertTrue(f.registry.get("child")?.queuedSends?.value.orEmpty().isEmpty())
            }
            assertTrue(f.drains.isEmpty())
            assertNull(f.registry.get("other"))
        } finally {
            f.registry.cancelAll()
        }
    }

    @Test fun failedTransactionExitRemovesOnlyTheNewEntryBeforeItCanBeClaimed() = runTest {
        val f = Fixture()
        try {
            val existing = f.queue.enqueue("child", "Existing", f.parentContext)
            val state = requireNotNull(f.registry.get("child"))
            val repository = spyk(f.conversations)
            var transactions = 0
            coEvery { repository.withProviderContextSnapshot<Any?>(any()) } coAnswers {
                val result = firstArg<suspend () -> Any?>().invoke()
                if (++transactions == 2) error("Transaction exit failed")
                result
            }
            var nextId = 0
            val queue = SubagentMessageQueue(repository, f.registry, f.coordinator,
                { id, run, model -> testGenerationAdmissionSnapshot(id, run, model) }, { "failed-${++nextId}" })
            assertTrue(runCatching { queue.enqueue("child", "Rejected", f.parentContext) }.isFailure)
            assertEquals(listOf(existing), state.queuedSends.value.map(QueuedSend::id))
            assertEquals(listOf("child"), f.drains)
        } finally {
            f.registry.cancelAll()
        }
    }

    @Test fun preparationFailureCancellationAndWrongSnapshotNeverPublishAnEntry() = runTest {
        val f = Fixture()
        try {
            f.prepare = { _, _, _ -> error("Provider not configured") }
            assertTrue(runCatching { f.queue.enqueue("child", "Task", f.parentContext) }.isFailure)
            f.prepare = { _, _, _ -> throw CancellationException("Cancelled") }
            assertTrue(runCatching { f.queue.enqueue("child", "Task", f.parentContext) }.exceptionOrNull()
                is CancellationException)
            f.prepare = { _, _, _ -> testGenerationAdmissionSnapshot("other", "wrong-run") }
            assertTrue(runCatching { f.queue.enqueue("child", "Task", f.parentContext) }.isFailure)
            assertNull(f.registry.get("child"))
            assertTrue(f.drains.isEmpty())
        } finally {
            f.registry.cancelAll()
        }
    }
}
