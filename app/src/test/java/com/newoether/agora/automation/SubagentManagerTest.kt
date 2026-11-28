package com.newoether.agora.automation

import com.newoether.agora.data.local.*
import com.newoether.agora.data.repository.*
import com.newoether.agora.model.*
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.GenerationContext
import com.newoether.agora.viewmodel.SubagentCompletionQueueDelivery
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubagentManagerTest {
    internal class Fixture(scope: CoroutineScope) {
        val children = mutableMapOf("parent" to ChatEntity("parent", "Parent"))
        val messages = mutableMapOf<String, MessageEntity>()
        val terminalMessages = mutableMapOf<String, MessageEntity>()
        val summaries = MutableStateFlow<List<SubagentSummary>>(emptyList())
        val dao = mockk<ChatDao>(relaxed = true)
        val settings = mockk<SettingsRepository>(relaxed = true)
        val transfers = mockk<ConversationSettingsTransferCoordinator>()
        val engine = mockk<TaskExecutionEngine>()
        val registry = ConversationStateRegistry {}
        val concurrentLimit = MutableStateFlow(4)
        val coordinator = ConversationExecutionCoordinator(
            parentConversationId = { id -> children[id]?.parentConversationId },
            subagentMaxConcurrent = concurrentLimit,
        )
        val repository = ConversationRepository(dao, null, scheduleMaintenance = {},
            subagentGenerationIds = coordinator.admittedGenerationIds)
        val delivery = SubagentCompletionQueueDelivery(repository, registry) { "Provider:model" }
        val stoppedLoops = mutableListOf<String>()
        val queuedMessages = mutableListOf<Triple<String, String, GenerationContext>>()
        val deletedChildren = mutableListOf<Pair<String, String>>(); var deleteAccepted = true
        val identity = RunEffectIdentity("parent", 1L, "r-parent", 0, "effect")
        val configuredModels = MutableStateFlow<Set<String>?>(setOf("Provider:model"))
        val context = GenerationContext(conversationId = "parent", toolEffectIdentity = identity,
            subagentToolsEnabled = true, enabledModelIds = listOf("Provider:model"),
            subagentModelIds = listOf("Provider:model"), shellEnabled = true)
        val manager: SubagentManager
        init {
            every { dao.observeSubagents("parent") } returns summaries
            coEvery { dao.getConversation(any()) } answers { children[firstArg()] }
            coEvery { dao.upsertConversation(any()) } answers {
                val row = firstArg<ChatEntity>()
                children[row.id] = row
            }
            coEvery { dao.isSubagentToolOwnerActive(any(), any(), any()) } answers {
                firstArg<String>() == "parent" && secondArg<String>() == "r-parent" && thirdArg<Int>() == 0
            }
            coEvery { dao.getSubagentIds(any()) } answers {
                children.values.filter { it.parentConversationId == firstArg<String>() }.map { it.id }
            }
            coEvery { dao.countSubagents(any()) } answers {
                children.values.count { it.parentConversationId == firstArg<String>() }
            }
            coEvery { dao.getLastMessageForConversation(any()) } answers { messages[firstArg()] }
            coEvery { dao.getMessage(any()) } answers {
                val messageId = firstArg<String>()
                terminalMessages[messageId] ?: messages.values.firstOrNull { it.id == messageId }
            }
            coEvery { dao.getSubagentCompletionJson(any(), any()) } answers {
                children[firstArg<String>()]?.takeIf { it.parentConversationId == secondArg<String>() }?.subagentResultJson
            }
            coEvery { dao.getLiveRun(any()) } returns null
            coEvery { dao.getRun(any()) } answers {
                val runId = firstArg<String>()
                (terminalMessages.values + messages.values).firstOrNull { it.runId == runId }?.let { message ->
                    val status = when (message.status) {
                        MessageStatus.SUCCESS -> RunStatus.COMPLETED
                        MessageStatus.STOPPED -> RunStatus.STOPPED
                        else -> RunStatus.FAILED
                    }
                    val reason = when (status) {
                        RunStatus.COMPLETED -> RunEndReason.MODEL_COMPLETED
                        RunStatus.STOPPED -> RunEndReason.USER_STOPPED
                        else -> RunEndReason.PROVIDER_ERROR
                    }
                    RunEntity(runId, message.conversationId, null, status, null, 1L, 2L,
                        endedAt = 2L, endReason = reason)
                }
            }
            coEvery { dao.setSubagentResult(any(), any(), any(), any(), any(), any()) } answers {
                val row = children[firstArg<String>()]
                if (row == null || row.parentConversationId != secondArg<String>()) 0 else {
                    children[row.id] = row.copy(subagentResultJson = arg(2), subagentResultId = arg(3), subagentResultStatus = arg(4))
                    1
                }
            }
            coEvery { dao.pendingSubagentResults(any()) } answers {
                children.values.filter { it.parentConversationId == firstArg<String>() && it.subagentResultId != null &&
                    it.subagentResultId != it.subagentDeliveredResultId }.map {
                    SubagentOutboxRow(it.id, it.title, requireNotNull(it.subagentResultJson), requireNotNull(it.subagentResultId))
                }
            }
            every { settings.enabledModels } returns MutableStateFlow(setOf("Provider:model"))
            every { settings.subagentEnabledModels } returns configuredModels
            coEvery { settings.getSubagentModelIds() } answers {
                configuredModels.value.orEmpty().intersect(settings.enabledModels.value)
            }
            coEvery { settings.getSubagentMaxTotal() } returns 16
            every { settings.conversationSettings } returns MutableStateFlow(emptyMap())
            coEvery { transfers.complete(any()) } returns true
            coEvery { engine.stopConversationAndAwait(any()) } returns Unit
            coEvery { engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val child = firstArg<String>()
                val answer = MessageEntity(id = "answer-$child", conversationId = child, text = "Result",
                    participant = Participant.MODEL, status = MessageStatus.SUCCESS, timestamp = 2L,
                    runId = "run-$child", runSequence = 1L)
                terminalMessages[answer.id] = answer
                TaskExecutionEngine.Result.Success(answer.id, answer.text)
            }
            manager = SubagentManager(repository, settings, transfers, engine, registry, coordinator,
                AutomationExecutionGate(), scope, enqueueCompletions = delivery::deliver,
                enqueueMessage = { id, text, context ->
                    queuedMessages += Triple(id, text, context)
                    "queued-${queuedMessages.size}"
                },
                stopLoop = { stoppedLoops += it },
                deleteIdleChild = { id, parent -> deletedChildren += id to parent; deleteAccepted.also { if (it) children.remove(id) } })
        }
        fun answer(child: String, id: String = "answer", text: String = "Result") {
            terminalMessages[id] = MessageEntity(id = id, conversationId = child, text = text,
                participant = Participant.MODEL, status = MessageStatus.SUCCESS, timestamp = 2L,
                runId = "run-$child", runSequence = 1L)
        }
        suspend fun spawn(name: String = "Worker") = manager.spawn(name, "Provider:model", "Task", context)
    }
    @Test fun creationDoesNotScanOwnersAndRejectsStaleNestedAndDisabledModels() = runTest {
        val f = Fixture(backgroundScope)
        coVerify(exactly = 0) { f.dao.getConversation(any()) }
        coVerify(exactly = 0) { f.dao.getSubagentIds(any()) }
        assertTrue(runCatching { f.manager.spawn("W", "disabled", "Task", f.context) }.isFailure)
        assertTrue(runCatching { f.manager.spawn("W", "Provider:model", "Task", f.context.copy(
            toolEffectIdentity = f.identity.copy(pass = 1))) }.isFailure)
        assertTrue(runCatching { f.manager.spawn("W", "Provider:model", "Task",
            f.context.copy(subagentToolsEnabled = false)) }.isFailure)
        assertEquals(setOf("parent"), f.children.keys)
    }

    @Test fun liveTotalLimitRejectsCreationWithoutStoppingOrRewritingExistingChildren() = runTest {
        val f = Fixture(backgroundScope)
        coEvery { f.settings.getSubagentMaxTotal() } returns 1
        val first = f.spawn("First")
        runCurrent()
        val before = checkNotNull(f.children[first])
        val failure = runCatching { f.spawn("Rejected") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(before, f.children[first])
        assertEquals(setOf("parent", first), f.children.keys)
        coVerify(exactly = 1) { f.dao.upsertConversation(any()) }
        coVerify(exactly = 1) { f.dao.upsertConversationSettingsTransfer(any()) }
        coVerify(exactly = 0) { f.engine.stopConversationAndAwait(any()) }
        coEvery { f.settings.getSubagentMaxTotal() } returns 2
        val second = f.spawn("Second")
        assertNotEquals(first, second)
        assertEquals(3, f.children.size)
        assertEquals(before, f.children[first])
    }

    @Test fun frozenAndLiveCandidateRestrictionsRejectBeforeChildCreation() = runTest {
        val f = Fixture(backgroundScope)
        val onlyOther = f.context.copy(subagentModelIds = listOf("Provider:other"))
        assertTrue(runCatching { f.manager.spawn("W", "Provider:model", "Task", onlyOther) }.isFailure)
        assertTrue(runCatching { f.manager.spawn("W", "Provider:model", "Task",
            f.context.copy(subagentModelIds = emptyList())) }.isFailure)
        coVerify(exactly = 0) { f.settings.getSubagentModelIds() }
        coEvery { f.settings.getSubagentModelIds() } returns emptySet()
        assertTrue(runCatching { f.manager.spawn("W", "Provider:model", "Task", f.context) }.isFailure)
        assertEquals(setOf("parent"), f.children.keys)
        coVerify(exactly = 0) { f.dao.upsertConversation(any()) }
        coVerify(exactly = 0) {
            f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun missingFrozenOrLiveConfigNeverFallsBackToGloballyEnabledModels() = runTest {
        val f = Fixture(backgroundScope)
        assertTrue(runCatching { f.manager.spawn("W", "Provider:model", "Task",
            f.context.copy(subagentModelIds = null)) }.isFailure)
        coVerify(exactly = 0) { f.settings.getSubagentModelIds() }
        f.configuredModels.value = null
        assertTrue(runCatching { f.manager.spawn("W", "Provider:model", "Task", f.context) }.isFailure)
        assertEquals(setOf("parent"), f.children.keys)
        coVerify(exactly = 0) { f.dao.upsertConversation(any()) }
        coVerify(exactly = 0) {
            f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun liveGlobalRemovalCannotBeBypassedByAnOlderFrozenContext() = runTest {
        val f = Fixture(backgroundScope)
        every { f.settings.enabledModels } returns MutableStateFlow(emptySet())
        assertTrue(runCatching { f.manager.spawn("W", "Provider:model", "Task", f.context) }.isFailure)
        assertEquals(setOf("parent"), f.children.keys)
        coVerify(exactly = 1) { f.settings.getSubagentModelIds() }
    }

    @Test fun liveMembershipIsReadFromPersistenceNotStalePublishedModels() = runTest {
        val f = Fixture(backgroundScope)
        every { f.settings.enabledModels } returns MutableStateFlow(emptySet())
        coEvery { f.settings.getSubagentModelIds() } returns setOf("Provider:model")
        val context = f.context.copy(subagentModelIds = listOf("Provider:model"),
            subagentRecommendedModel = "Provider:other")
        val child = f.manager.spawn("W", "Provider:model", "Task", context)
        assertEquals("Provider:model", f.children[child]?.modelId)
        runCurrent()
        coVerify(exactly = 1) {
            f.engine.runOnceWithAutomationGuardsHeld(child, "Task", "Provider:model", any(), any(), any(), "subagent", null, context)
        }
    }

    @Test fun removingCandidatesDoesNotCancelAdmittedWorkOrBlockFollowupsAndWait() = runTest {
        val f = Fixture(backgroundScope)
        val result = CompletableDeferred<TaskExecutionEngine.Result>()
        coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            result.await()
        }
        val child = f.spawn()
        runCurrent()
        val worker = checkNotNull(f.repository.subagentJobs.value[child])
        coEvery { f.settings.getSubagentModelIds() } returns emptySet()
        val context = f.context.copy(subagentModelIds = emptyList())
        assertEquals("queued-1", f.manager.send(child, "Follow up", context))
        assertTrue(worker.isActive)
        assertTrue(f.manager.wait(listOf(child), 1L, context).isEmpty())
        f.answer(child)
        result.complete(TaskExecutionEngine.Result.Success("answer", "Result"))
        runCurrent()
        assertEquals(listOf(child), f.manager.wait(listOf(child), 1L, context).map { it.first })
        coVerify(exactly = 0) { f.engine.stopConversationAndAwait(any()) }
        coVerify(exactly = 1) { f.settings.getSubagentModelIds() }
    }

    @Test fun childrenRunWhileParentHoldsLeaseAndCompletionsStaySeparateAfterItsRelease() = runTest {
        val f = Fixture(backgroundScope)
        val parentEntered = CompletableDeferred<Unit>()
        val releaseParent = CompletableDeferred<Unit>()
        val lease = backgroundScope.launch {
            f.coordinator.withAutomationConversationLock("parent") {
                parentEntered.complete(Unit)
                releaseParent.await()
            }
        }
        parentEntered.await()
        val first = f.spawn("One")
        val second = f.spawn("Two")
        runCurrent()
        assertEquals(setOf(first, second), f.manager.wait(listOf(first, second), 50L, f.context).map { it.first }.toSet())
        assertEquals("parent", f.children[first]?.parentConversationId)
        coVerify(exactly = 0) { f.engine.runOnceWithAutomationGuardsHeld("parent", any(), any(), any(), any(), any(), any(), any(), any()) }
        releaseParent.complete(Unit)
        lease.join()
        runCurrent()
        val queued = checkNotNull(f.registry.get("parent")).queuedSends.value
        assertEquals(2, queued.size)
        assertEquals(2, queued.map { it.id }.distinct().size)
        assertEquals(listOf(first, second), queued.map { it.source?.subagentResults?.keys?.single() })
        assertTrue(queued[0].text.contains("One"))
        assertFalse(queued[0].text.contains("Two"))
        assertTrue(queued[1].text.contains("Two"))
        assertFalse(queued[1].text.contains("One"))
        queued.forEach { send ->
            val child = checkNotNull(send.source?.subagentResults?.keys?.single())
            assertEquals(mapOf(child to f.children[child]?.subagentResultId), send.source?.subagentResults)
        }
        coVerify(exactly = 0) { f.engine.runOnceWithAutomationGuardsHeld("parent",
            any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { f.engine.runOnceWithAutomationGuardsHeld(first, "Task", "Provider:model", any(), any(), any(), "subagent", null, f.context) }
    }

    @Test fun stoppingWaitsForWorkerCancellationCleanupAndDoesNotStopOtherOwners() = runTest {
        val f = Fixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cancelled.complete(Unit); cleanup.await() }
            }
        }
        val child = f.spawn()
        entered.await()
        val stop = async { f.manager.stop(child, f.context) }
        cancelled.await()
        assertFalse(stop.isCompleted)
        cleanup.complete(Unit)
        stop.await()
        assertEquals("stopped", f.children[child]?.subagentResultStatus)
        coVerify(exactly = 1) { f.engine.stopConversationAndAwait(child) }
        coVerify(exactly = 0) { f.engine.stopConversationAndAwait("parent") }
        coVerify(exactly = 0) { f.engine.stopConversationAndAwait("other") }
    }

    @Test fun admittedTaskStaysRunningAcrossCompactAndAutomaticContinuationGaps() = runTest {
        val f = Fixture(backgroundScope)
        val result = CompletableDeferred<TaskExecutionEngine.Result>()
        coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            result.await()
        }
        val child = f.spawn()
        val row = SubagentSummary(child, "Worker", "Provider:model", "parent", "idle")
        f.summaries.value = listOf(row)
        val statuses = mutableListOf<String>()
        backgroundScope.launch {
            f.repository.observeSubagents("parent").collect { rows -> statuses += rows.single().status }
        }
        runCurrent()
        // Each Compact/ordinary Run is terminal before its successor is admitted. The worker
        // still owns the task throughout both the pre-send and tool-round handoffs.
        listOf("running", "completed", "running", "completed", "running", "completed").forEach { status ->
            f.summaries.value = listOf(row.copy(status = status))
            runCurrent()
            assertEquals("running", statuses.last())
            assertEquals("running", f.manager.list(f.context).single().status)
        }
        assertEquals(listOf("running"), statuses)
        f.answer(child, text = "Answer")
        result.complete(TaskExecutionEngine.Result.Success("answer", "Answer"))
        runCurrent()
        assertEquals(listOf("running", "completed"), statuses)
        assertTrue(f.repository.subagentJobs.value.isEmpty())
        assertEquals("completed", f.manager.list(f.context).single().status)
    }

    @Test fun compactOnlySuccessCannotCreateTaskOutboxOrParentQueue() = runTest {
        listOf(true, false).forEach { hasDurableMessage ->
            val f = Fixture(backgroundScope)
            val compactId = Constants.COMPACT_MSG_PREFIX + "summary"
            coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val child = firstArg<String>()
                if (hasDurableMessage) {
                    f.messages[child] = MessageEntity(id = compactId, conversationId = child, text = "Summary",
                        participant = Participant.MODEL, status = MessageStatus.SUCCESS, timestamp = 2L,
                        runId = "compact-run", runSequence = 0L)
                }
                TaskExecutionEngine.Result.Success(compactId, "Summary")
            }
            val child = f.spawn()
            runCurrent()
            assertNull(f.children[child]?.subagentResultId)
            assertTrue(f.repository.pendingSubagentResults("parent").isEmpty())
            assertTrue(f.registry.get("parent")?.queuedSends?.value.orEmpty().isEmpty())
            assertTrue(f.repository.subagentJobs.value.isEmpty())
            coVerify(exactly = 0) { f.dao.setSubagentResult(any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test fun cancellationDuringCompactReportsTaskStopWithoutPublishingSummary() = runTest {
        val f = Fixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val child = firstArg<String>()
            f.messages[child] = MessageEntity(id = Constants.COMPACT_MSG_PREFIX + "stopped", conversationId = child,
                text = "Partial summary", participant = Participant.MODEL, status = MessageStatus.STOPPED,
                timestamp = 2L, runId = "compact-run", runSequence = 0L)
            entered.complete(Unit)
            awaitCancellation()
        }
        val child = f.spawn()
        entered.await()
        f.manager.stop(child, f.context)
        runCurrent()
        val completion = checkNotNull(SubagentCompletion.decode(f.children[child]?.subagentResultJson))
        assertEquals("stopped", completion.status)
        assertEquals("Subagent stopped", completion.text)
        assertFalse(completion.id.startsWith(Constants.COMPACT_MSG_PREFIX))
        assertNull(completion.modelMessageId)
        assertEquals(mapOf(child to completion.id), checkNotNull(f.registry.get("parent")).queuedSends.value.single().source?.subagentResults)
        coVerify(exactly = 1) { f.dao.setSubagentResult(child, "parent", any(), completion.id, "stopped", any()) }
    }

    @Test fun compactTailCannotReplaceOrdinaryEngineResult() = runTest {
        val f = Fixture(backgroundScope)
        coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val child = firstArg<String>()
            f.messages[child] = MessageEntity(id = Constants.COMPACT_MSG_PREFIX + "summary", conversationId = child,
                text = "Summary", participant = Participant.MODEL, status = MessageStatus.SUCCESS,
                timestamp = 2L, runId = "compact-run", runSequence = 0L)
            f.answer(child, text = "Final answer")
            TaskExecutionEngine.Result.Success("answer", "Final answer")
        }
        val child = f.spawn()
        runCurrent()
        val completion = SubagentCompletion.decode(f.children[child]?.subagentResultJson)
        assertEquals(SubagentCompletion("answer", "completed", "Final answer", "answer"), completion)
        assertEquals(mapOf(child to "answer"), checkNotNull(f.registry.get("parent")).queuedSends.value.single().source?.subagentResults)
        coVerify(exactly = 1) { f.dao.setSubagentResult(child, "parent", any(), "answer", "completed", any()) }
    }

    @Test fun listIncludesFailedCompletionDetailWithoutLoadingOtherStatuses() = runTest {
        val f = Fixture(backgroundScope)
        val result = SubagentCompletion("result", "failed", "Partial", "model",
            errorText = "Connection closed", errorCode = "connection_closed")
        f.children["failed"] = ChatEntity("failed", "Failed", parentConversationId = "parent",
            subagentResultJson = SubagentCompletion.encode(result), subagentResultId = result.id,
            subagentResultStatus = "failed")
        f.summaries.value = listOf(SubagentSummary("failed", "Failed", null, "parent", "failed"),
            SubagentSummary("idle", "Idle", null, "parent", "idle"))
        val listed = f.manager.list(f.context)
        assertEquals("Connection closed", listed.first().errorText)
        assertEquals("connection_closed", listed.first().errorCode)
        assertNull(listed.last().errorText)
        coVerify(exactly = 0) { f.dao.getSubagentCompletionJson("idle", any()) }
    }

    @Test fun exactEngineTerminalWinsOverNewerChronologicalFailure() = runTest {
        val f = Fixture(backgroundScope)
        coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val child = firstArg<String>()
            f.answer(child, "recovered", "Exact recovered answer")
            f.messages[child] = MessageEntity(id = "other-branch", conversationId = child, text = "Old branch failure",
                participant = Participant.MODEL, status = MessageStatus.ERROR, timestamp = Long.MAX_VALUE,
                runId = "other-run", runSequence = 1L)
            TaskExecutionEngine.Result.Success("recovered", "Untrusted duplicate payload")
        }
        val child = f.spawn()
        runCurrent()
        val expected = SubagentCompletion("recovered", "completed", "Exact recovered answer", "recovered")
        assertEquals(expected, SubagentCompletion.decode(f.children[child]?.subagentResultJson))
        assertEquals(mapOf(child to "recovered"), checkNotNull(f.registry.get("parent")).queuedSends.value.single().source?.subagentResults)
        coVerify(exactly = 0) { f.dao.getLastMessageForConversation(any()) }
    }

    @Test fun rejectedExactTerminalNeverFallsBackToSuccessPayloadOrChronologicalMessage() = runTest {
        listOf("missing", "foreign", "user", "sending", "compact", "live", "continuation").forEach { invalid ->
            val f = Fixture(backgroundScope)
            coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val child = firstArg<String>()
                f.answer(child, "chronological", "Do not publish")
                f.messages[child] = checkNotNull(f.terminalMessages["chronological"])
                val id = if (invalid == "compact") Constants.COMPACT_MSG_PREFIX + "exact" else "exact"
                if (invalid != "missing") {
                    f.answer(child, id)
                    val exact = checkNotNull(f.terminalMessages[id])
                    f.terminalMessages[id] = when (invalid) {
                        "foreign" -> exact.copy(conversationId = "other")
                        "user" -> exact.copy(participant = Participant.USER)
                        "sending" -> exact.copy(status = MessageStatus.SENDING)
                        else -> exact
                    }
                }
                if (invalid == "live") coEvery { f.dao.getLiveRun(child) } returns RunEntity(
                    "live", child, null, RunStatus.ACTIVE, 1, 1L, 1L)
                if (invalid == "continuation") coEvery { f.dao.getRun("run-$child") } returns RunEntity(
                    "run-$child", child, null, RunStatus.COMPLETED, null, 1L, 2L,
                    endedAt = 2L, endReason = RunEndReason.CONTINUATION_PENDING)
                TaskExecutionEngine.Result.Success(id, "Success payload alone is insufficient")
            }
            val child = f.spawn()
            runCurrent()
            assertNull(invalid, f.children[child]?.subagentResultId)
            assertTrue(f.registry.get("parent")?.queuedSends?.value.orEmpty().isEmpty())
            coVerify(exactly = 0) { f.dao.setSubagentResult(any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test fun postCommitOutboxExceptionRetriesExactCompletionWithoutSyntheticFailure() = runTest {
        val f = Fixture(backgroundScope)
        var attempts = 0
        coEvery { f.dao.setSubagentResult(any(), any(), any(), any(), any(), any()) } answers {
            val child = firstArg<String>()
            val previous = checkNotNull(f.children[child])
            f.children[child] = previous.copy(subagentResultJson = arg(2), subagentResultId = arg(3), subagentResultStatus = arg(4))
            if (++attempts == 1) error("Committed before callback failed")
            1
        }
        val child = f.spawn()
        runCurrent()
        assertEquals(2, attempts)
        assertEquals("completed", f.children[child]?.subagentResultStatus)
        assertEquals("answer-$child", f.children[child]?.subagentResultId)
        assertEquals(1, checkNotNull(f.registry.get("parent")).queuedSends.value.size)
        coVerify(exactly = 0) { f.dao.setSubagentResult(any(), any(), any(), any(), "failed", any()) }
    }

    @Test fun engineFailureUsesExactStoppedOverlayAndProcessRecoveryReason() = runTest {
        listOf(false, true).forEach { recovered ->
            val f = Fixture(backgroundScope)
            coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val child = firstArg<String>()
                val message = MessageEntity(id = "stopped", conversationId = child, text = "Partial answer",
                    participant = Participant.MODEL, status = MessageStatus.STOPPED, timestamp = 2L,
                    runId = "stopped-run", runSequence = 1L)
                f.terminalMessages[message.id] = message
                coEvery { f.dao.getRun(message.runId) } returns RunEntity(message.runId, child, null,
                    RunStatus.STOPPED, null, 1L, 2L, endedAt = 2L,
                    endReason = if (recovered) RunEndReason.PROCESS_RECOVERED else RunEndReason.USER_STOPPED)
                val state = f.registry.getOrCreate(child)
                val token = checkNotNull(state.acquireForSend())
                state.streamUpdate(token, ChatMessage(id = message.id, text = message.text,
                    participant = Participant.MODEL, status = MessageStatus.STOPPED,
                    runId = message.runId, runSequence = 1L))
                TaskExecutionEngine.Result.Failure("Generation failed")
            }
            val child = f.spawn()
            runCurrent()
            val result = checkNotNull(SubagentCompletion.decode(f.children[child]?.subagentResultJson))
            assertEquals("stopped", result.modelMessageId)
            assertEquals(if (recovered) "failed" else "stopped", result.status)
        }
    }

    @Test fun compactAndIntermediateOrdinaryRunsQueueOnlyFinalTaskResultOnce() = runTest {
        val f = Fixture(backgroundScope)
        val result = CompletableDeferred<TaskExecutionEngine.Result>()
        coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            result.await()
        }
        val child = f.spawn()
        runCurrent()
        val worker = checkNotNull(f.repository.subagentJobs.value[child])
        listOf(Constants.COMPACT_MSG_PREFIX + "pre-send", "intermediate-answer",
            Constants.COMPACT_MSG_PREFIX + "tool-round").forEach { messageId ->
            f.messages[child] = MessageEntity(id = messageId, conversationId = child, text = "Interim output",
                participant = Participant.MODEL, status = MessageStatus.SUCCESS, timestamp = 2L,
                runId = "run-$messageId", runSequence = 0L)
            f.repository.persistSubagentTerminal(child, 3L)
            f.manager.conversationIdle(child)
            runCurrent()
            assertTrue(worker.isActive)
            assertNull(f.children[child]?.subagentResultId)
            assertTrue(f.repository.pendingSubagentResults("parent").isEmpty())
            assertTrue(f.registry.get("parent")?.queuedSends?.value.orEmpty().isEmpty())
            coVerify(exactly = 0) { f.dao.setSubagentResult(any(), any(), any(), any(), any(), any()) }
        }
        f.messages[child] = MessageEntity(id = "final-answer", conversationId = child, text = "Final answer",
            participant = Participant.MODEL, status = MessageStatus.SUCCESS, timestamp = 4L,
            runId = "final-run", runSequence = 0L)
        result.complete(TaskExecutionEngine.Result.Success("final-answer", "Final answer"))
        runCurrent()
        assertTrue(worker.isCompleted)
        val expected = SubagentCompletion("final-answer", "completed", "Final answer", "final-answer")
        assertEquals(expected, SubagentCompletion.decode(f.repository.pendingSubagentResults("parent").single().resultJson))
        repeat(2) {
            f.manager.conversationIdle(child)
            f.manager.conversationOpened("parent")
            runCurrent()
        }
        assertEquals(mapOf(child to "final-answer"), checkNotNull(f.registry.get("parent")).queuedSends.value.single().source?.subagentResults)
        coVerify(exactly = 1) { f.dao.setSubagentResult(child, "parent", any(), "final-answer", "completed", any()) }
    }

    @Test fun failedTaskReleasesRunningStatusWithoutAnotherRoomEmission() = runTest {
        val f = Fixture(backgroundScope)
        val result = CompletableDeferred<TaskExecutionEngine.Result>()
        coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            result.await()
        }
        val child = f.spawn()
        val worker = checkNotNull(f.repository.subagentJobs.value[child])
        val row = SubagentSummary(child, "Worker", "Provider:model", "parent", "completed")
        f.summaries.value = listOf(row)
        val statuses = mutableListOf<String>()
        backgroundScope.launch {
            f.repository.observeSubagents("parent").collect { rows -> statuses += rows.single().status }
        }
        runCurrent()
        assertTrue(worker.isActive)
        assertEquals(listOf("running"), statuses)
        f.summaries.value = listOf(row.copy(status = "failed"))
        runCurrent()
        assertEquals("running", statuses.last())
        result.complete(TaskExecutionEngine.Result.Failure("Provider failed"))
        runCurrent()
        assertTrue(worker.isCompleted)
        assertEquals(listOf("running", "failed"), statuses)
        assertEquals("failed", f.manager.list(f.context).single().status)
        assertTrue(f.repository.subagentJobs.value.isEmpty())
        assertTrue(f.queuedMessages.isEmpty())
        coVerify(exactly = 1) {
            f.engine.runOnceWithAutomationGuardsHeld(child, "Task", "Provider:model", any(), any(), any(), "subagent", null, f.context)
        }
    }

    @Test fun cancellingWorkerRemainsRunningUntilNonCancellableCleanupSettles() = runTest {
        val f = Fixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cancelled.complete(Unit); cleanup.await() }
            }
        }
        val child = f.spawn()
        val row = SubagentSummary(child, "Worker", "Provider:model", "parent", "completed")
        f.summaries.value = listOf(row)
        val statuses = mutableListOf<String>()
        backgroundScope.launch {
            f.repository.observeSubagents("parent").collect { rows -> statuses += rows.single().status }
        }
        entered.await()
        runCurrent()
        val stop = async { f.manager.stop(child, f.context) }
        cancelled.await()
        f.summaries.value = listOf(row.copy(status = "stopped"))
        runCurrent()
        assertFalse(stop.isCompleted)
        assertEquals("running", f.manager.list(f.context).single().status)
        assertEquals(listOf("running"), statuses)
        assertTrue(f.manager.wait(listOf(child), 1L, f.context).isEmpty())
        cleanup.complete(Unit)
        stop.await()
        runCurrent()
        assertEquals(listOf("running", "stopped"), statuses)
        assertTrue(f.repository.subagentJobs.value.isEmpty())
    }

    @Test fun followupFailureBeforeAdmissionCannotRepublishPreviousAnswer() = runTest {
        val f = Fixture(backgroundScope)
        val previous = SubagentCompletion("old", "completed", "Old answer", "old")
        val child = ChatEntity("child", "Worker", parentConversationId = "parent", modelId = "Provider:model",
            subagentResultId = previous.id, subagentDeliveredResultId = previous.id,
            subagentResultJson = SubagentCompletion.encode(previous), subagentResultStatus = previous.status)
        f.children[child.id] = child
        val oldMessage = MessageEntity(id = "old", conversationId = child.id, text = previous.text,
            participant = Participant.MODEL, status = MessageStatus.SUCCESS, timestamp = 1L, runId = "old-run", runSequence = 0L)
        f.messages[child.id] = oldMessage
        val failure = IllegalStateException("Queue admission failed")
        val attempted = mutableListOf<Triple<String, String, GenerationContext>>()
        val manager = SubagentManager(f.repository, f.settings, f.transfers, f.engine, f.registry, f.coordinator,
            AutomationExecutionGate(), backgroundScope, enqueueCompletions = f.delivery::deliver,
            enqueueMessage = { id, text, context ->
                attempted += Triple(id, text, context)
                throw failure
            })
        val outcome = runCatching { manager.send(child.id, "New task", f.context) }
        assertSame(failure, outcome.exceptionOrNull())
        runCurrent()
        assertEquals(listOf(Triple(child.id, "New task", f.context)), attempted)
        assertEquals(child, f.children[child.id])
        assertEquals(oldMessage, f.messages[child.id])
        assertTrue(f.repository.pendingSubagentResults("parent").isEmpty())
        assertTrue(f.registry.get("parent")?.queuedSends?.value.orEmpty().isEmpty())
        assertTrue(f.repository.subagentJobs.value.isEmpty())
        coVerify(exactly = 0) { f.dao.setSubagentResult(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.engine.stopConversationAndAwait(any()) }
        coVerify(exactly = 0) {
            f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun busyChildSendDelegatesQueueReceiptWithoutCancellingWorkerOrRunningEngine() = runTest {
        val f = Fixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val result = CompletableDeferred<TaskExecutionEngine.Result>()
        coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            result.await()
        }
        val child = f.spawn()
        entered.await()
        val worker = checkNotNull(f.repository.subagentJobs.value[child])
        val receipt = f.manager.send(child, "Follow up", f.context)
        runCurrent()
        assertEquals("queued-1", receipt)
        assertEquals(listOf(Triple(child, "Follow up", f.context)), f.queuedMessages)
        assertSame(worker, f.repository.subagentJobs.value[child])
        assertTrue(worker.isActive)
        assertFalse(worker.isCancelled)
        assertFalse(result.isCompleted)
        assertNull(f.children[child]?.subagentResultId)
        coVerify(exactly = 0) { f.engine.stopConversationAndAwait(any()) }
        coVerify(exactly = 1) {
            f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) {
            f.engine.runOnceWithAutomationGuardsHeld(child, "Task", "Provider:model", any(), any(), any(), "subagent", null, f.context)
        }
        f.answer(child)
        result.complete(TaskExecutionEngine.Result.Success("answer", "Result"))
        runCurrent()
        assertTrue(worker.isCompleted)
    }

    @Test fun idleChildSendDelegatesQueueReceiptWithoutStartingWorkerOrRunningEngine() = runTest {
        val f = Fixture(backgroundScope)
        val child = ChatEntity("child", "Worker", parentConversationId = "parent", modelId = "Provider:model")
        f.children[child.id] = child
        val receipt = f.manager.send(child.id, "Follow up", f.context)
        runCurrent()
        assertEquals("queued-1", receipt)
        assertEquals(listOf(Triple(child.id, "Follow up", f.context)), f.queuedMessages)
        assertEquals(child, f.children[child.id])
        assertTrue(f.repository.subagentJobs.value.isEmpty())
        coVerify(exactly = 0) { f.dao.setSubagentResult(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.engine.stopConversationAndAwait(any()) }
        coVerify(exactly = 0) {
            f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // Open/idle of a conversation without results must not take its lease: a held lease makes the
    // open path skip runtime recovery and makes a claimed queued send wait.
    @Test fun openAndIdleWithoutResultsNeverTakeTheConversationLease() = runTest {
        val f = Fixture(backgroundScope)
        val leaseHeldAtRead = mutableListOf<Boolean>()
        coEvery { f.dao.pendingSubagentResults(any()) } answers {
            leaseHeldAtRead += f.coordinator.isExecuting(firstArg())
            emptyList()
        }
        f.manager.conversationOpened("parent")
        f.manager.conversationIdle("parent")
        runCurrent()
        assertTrue(leaseHeldAtRead.isNotEmpty())
        assertTrue(leaseHeldAtRead.none { it })
        coVerify(exactly = 0) { f.engine.runOnceWithAutomationGuardsHeld("parent", any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun openingParentQueuesResultWithoutTakingItsLeaseOrRunningEngine() = runTest {
        val f = Fixture(backgroundScope)
        f.children["child"] = ChatEntity("child", "Worker", parentConversationId = "parent",
            subagentResultId = "done", subagentResultJson = SubagentCompletion.encode(SubagentCompletion("done", "completed", "Answer")))
        f.manager.conversationOpened("parent")
        runCurrent()
        val queued = checkNotNull(f.registry.get("parent")).queuedSends.value.single()
        assertEquals(mapOf("child" to "done"), queued.source?.subagentResults)
        assertFalse(f.coordinator.isExecuting("parent"))
        coVerify(exactly = 0) { f.dao.recoverConversationRuntime("parent", any()) }
        coVerify(exactly = 0) {
            f.engine.runOnceWithAutomationGuardsHeld("parent", any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun cancelledScopeDoesNotReadOwnersOrRearmDelivery() = runTest {
        val owner = SupervisorJob()
        val f = Fixture(CoroutineScope(backgroundScope.coroutineContext + owner))
        owner.cancel()
        repeat(3) {
            f.manager.conversationIdle("parent")
            f.manager.conversationOpened("parent")
        }
        runCurrent()
        coVerify(exactly = 0) { f.dao.getConversation(any()) }
        coVerify(exactly = 0) { f.dao.pendingSubagentResults(any()) }
    }

    @Test fun waitDoesNotConsumeReceiptBeforeToolRoundCommitAndRejectsForeignChildren() = runTest {
        val f = Fixture(backgroundScope)
        f.children["child"] = ChatEntity("child", "Worker", parentConversationId = "parent",
            subagentResultId = "done", subagentResultJson = SubagentCompletion.encode(SubagentCompletion("done", "completed", "Answer")))
        f.children["foreign"] = ChatEntity("foreign", "Other", parentConversationId = "other")
        assertEquals("done", f.manager.wait(listOf("child"), 1L, f.context).single().second.id)
        assertNull(f.children["child"]?.subagentDeliveredResultId)
        coVerify(exactly = 0) { f.dao.acknowledgeSubagentResult(any(), any(), any(), any()) }
        assertTrue(runCatching { f.manager.wait(listOf("foreign"), 1L, f.context) }.isFailure)
        assertTrue(runCatching { f.manager.stop("foreign", f.context) }.isFailure)
        coVerify(exactly = 0) { f.engine.stopConversationAndAwait("foreign") }
    }
}
