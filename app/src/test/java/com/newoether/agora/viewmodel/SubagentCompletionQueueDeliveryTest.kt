package com.newoether.agora.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.data.repository.pendingSubagentResults
import com.newoether.agora.data.repository.recordSubagentCompletion
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.SubagentCompletion
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubagentCompletionQueueDeliveryTest {
    private lateinit var database: ChatDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var delivery: SubagentCompletionQueueDelivery
    private val registry = ConversationStateRegistry {}
    private val coordinator = ConversationExecutionCoordinator()
    private val requestBuilder = mockk<GenerationRequestBuilder>()
    private val launcher = mockk<BoundRunGenerationLauncher>()
    private val settlement = mockk<GenerationTerminalSettlementController>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val launched = CompletableDeferred<BoundRunGenerationRequest>()
    private val failed = CompletableDeferred<Throwable>()
    private val snapshot = testGenerationAdmissionSnapshot(
        conversationId = "parent", runId = "queued", selectedModelId = "provider:model",
    )
    private lateinit var executor: QueuedGuidanceDrainExecutor

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ConversationRepository(database.chatDao(), database, scheduleMaintenance = {})
        delivery = SubagentCompletionQueueDelivery(repository, registry) { "provider:model" }
        coEvery { launcher.launch(any(), any()) } coAnswers {
            launched.complete(firstArg())
            Unit
        }
        coEvery { settlement.failGenerationSetup(any(), any(), any(), any(), any(), any()) } coAnswers {
            failed.complete(arg(5))
            Unit
        }
        coEvery { requestBuilder.captureAdmissionSnapshot(any(), any(), any(), any(), any()) } coAnswers {
            snapshot.copy(runId = secondArg())
        }
        executor = QueuedGuidanceDrainExecutor(
            conversations = repository,
            settings = settings,
            requestBuilder = requestBuilder,
            executionCoordinator = coordinator,
            terminalSettlement = settlement,
            boundRunGenerationLauncher = launcher,
            toUiMessage = { row -> ChatMessage(
                id = row.id, text = row.text, participant = row.participant,
                status = row.status, runId = row.runId, runSequence = row.runSequence,
            ) },
            isConversationOpen = { false },
            projectGraph = { _, _, _, _ -> },
            onScrollToAbsoluteBottomAfter = { _, _ -> },
            onUserMessagePersisted = { _, _ -> },
        )
    }

    @After fun tearDown() = runBlocking {
        val owner = registry.get("parent")?.scope?.coroutineContext?.get(Job)
        registry.cancelAll()
        owner?.cancelAndJoin()
        database.close()
    }

    @Test fun failedCompletionQueueIncludesDetailAndKeepsTheExactReceipt() = runBlocking {
        seed()
        val result = SubagentCompletion("failed-result", "failed", "Partial answer", "failed-model",
            errorText = "Connection closed", errorCode = "connection_closed")
        assertTrue(repository.recordSubagentCompletion("child", "parent", result))
        delivery.deliver("parent")
        val queued = registry.getOrCreate("parent").queuedSends.value.single()
        assertTrue(queued.text.contains("Partial answer"))
        assertTrue(queued.text.contains("Error: Connection closed"))
        assertTrue(queued.text.contains("Error code: connection_closed"))
        assertEquals(mapOf("child" to result.id), queued.source?.subagentResults)
        assertEquals(MessageSource.SubagentResult("child", "failed", "Connection closed", "connection_closed"),
            queued.source?.subagentResult)
        assertEquals(result.id, queued.runId)
        delivery.deliver("parent")
        assertEquals(1, registry.getOrCreate("parent").queuedSends.value.size)
    }

    private suspend fun seed() {
        database.chatDao().upsertConversation(ChatEntity("parent", "Parent", modelId = "provider:model"))
        database.chatDao().upsertConversation(ChatEntity("other", "Other", draftText = "keep"))
        result("child", "R1")
    }

    private suspend fun result(childId: String, resultId: String) {
        val dao = database.chatDao()
        if (dao.getConversation(childId) == null) {
            dao.upsertConversation(ChatEntity(childId, childId, parentConversationId = "parent"))
        }
        assertTrue(repository.recordSubagentCompletion(
            childId, "parent", SubagentCompletion(resultId, "completed", "Answer $resultId"),
        ))
    }

    private fun queued(id: String, source: MessageSource? = null) = QueuedSend(
        id = id, text = if (source?.kind == MessageSource.Kind.ASK_USER) source.askUserReadableText() else id,
        modelId = "provider:model", attachments = emptyList(), runId = "queued",
        generationSnapshot = snapshot, source = source,
    )

    private fun activeRun(id: String) = RunEntity(
        id = id, conversationId = "parent", parentRunId = null,
        status = RunStatus.ACTIVE, activeSlot = 1, startedAt = 1L, lastCheckpointAt = 1L,
    )

    private suspend fun claim(): QueuedDrainClaim {
        val state = registry.getOrCreate("parent")
        return state.queueMutationMutex.withLock {
            checkNotNull(executor.claimUnderLock(state, checkNotNull(state.claimQueuedSends())))
        }
    }

    private suspend fun admit(): BoundRunGenerationRequest {
        val state = registry.getOrCreate("parent")
        executor.launchClaim(state, claim())
        return withTimeout(10_000) { launched.await() }
    }

    private fun waitRound(receipt: String) = run {
        val call = MessageSegment(type = "tool", toolName = "wait_agents", toolArgs = "{}", toolCallId = "wait")
        listOf(
            MessageEntity(id = "wait-tool", conversationId = "parent", text = "", participant = Participant.MODEL,
                timestamp = 2L, runId = "parent-run", runSequence = 0L,
                toolCallJson = Json.encodeToString(listOf(call))),
            MessageEntity(id = "wait-result", conversationId = "parent", parentId = "wait-tool", text = "",
                participant = Participant.USER, timestamp = 3L, runId = "parent-run", runSequence = 1L,
                toolCallJson = Json.encodeToString(listOf(call.copy(
                    toolResult = """{"subagent_receipts":{"child":"$receipt"},"results":[]}""",
                )))),
        )
    }

    @Test fun idleCompletionRequestsDrainOnlyForNewResultsOutsideTheQueueMutex() = runBlocking {
        seed()
        var drains = 0
        registry.attachUiCallbacks(this) { owner ->
            owner.onQueueDrainRequested = { state ->
                assertFalse(state.queueMutationMutex.isLocked)
                drains++
            }
        }
        val state = registry.getOrCreate("parent")
        val typed = queued("typed")
        state.enqueueSend(typed)
        delivery.deliver("parent")
        val original = state.queuedSends.value
        assertEquals(typed, original.first())
        assertEquals(2, original.size)
        assertEquals(1, drains)
        repeat(2) { delivery.deliver("parent") }
        assertEquals(1, drains)
        val lease = checkNotNull(state.claimQueuedSends())
        delivery.deliver("parent")
        assertEquals(1, drains)
        assertTrue(state.settleGuidanceClaim(lease.id, durable = false))
        result("second", "R2")
        delivery.deliver("parent")
        assertEquals(original, state.queuedSends.value.take(2))
        assertEquals(3, state.queuedSends.value.size)
        assertEquals(2, drains)
        assertNull(repository.getConversation("child")?.subagentDeliveredResultId)
        assertTrue(repository.getRunsForConversationSnapshot("parent").isEmpty())
        coVerify(exactly = 0) { launcher.launch(any(), any()) }
    }

    @Test fun idleCompletionAutomaticallyDrainsTheWholeFifoAndCommitsItsReceiptOnce() = runBlocking {
        seed()
        var drains = 0
        registry.attachUiCallbacks(this) { owner ->
            owner.onQueueDrainRequested = { state ->
                drains++
                state.scope.launch { executor.drainAfterSettlement(state) }
            }
        }
        val state = registry.getOrCreate("parent")
        state.enqueueSend(queued("typed"))
        delivery.deliver("parent")
        val request = withTimeout(10_000) { launched.await() }
        val users = repository.getMessagesForRuns(listOf(request.runId))
            .filter { it.participant == Participant.USER }
        assertEquals(listOf("typed", "child (child) [completed]\nAnswer R1"), users.map { it.text })
        assertNull(MessageSource.decode(users.first().sourceJson))
        assertEquals(mapOf("child" to "R1"), MessageSource.decode(users.last().sourceJson)?.subagentResults)
        assertEquals("R1", repository.getConversation("child")?.subagentDeliveredResultId)
        assertTrue(repository.pendingSubagentResults("parent").isEmpty())
        assertTrue(state.queuedSends.value.isEmpty())
        repeat(2) { delivery.deliver("parent") }
        assertEquals(1, drains)
        assertEquals(1, repository.getRunsForConversationSnapshot("parent").size)
        assertEquals("keep", repository.getConversation("other")?.draftText)
        coVerify(exactly = 1) { launcher.launch(any(), state) }
    }

    @Test fun busyCompletionWaitsForOrdinaryCoroutineSettlementToRequestDrain() = runBlocking {
        seed()
        val drained = CompletableDeferred<List<String>>()
        registry.attachUiCallbacks(this) { owner ->
            owner.onQueueDrainRequested = { state -> drained.complete(state.queuedSends.value.map { it.runId }) }
        }
        val state = registry.getOrCreate("parent")
        val token = checkNotNull(state.acquireForSend())
        val worker = Job()
        assertTrue(state.attachGenerationJob(token, worker))
        delivery.deliver("parent")
        assertEquals(1, state.queuedSends.value.size)
        assertFalse(drained.isCompleted)
        worker.complete()
        assertEquals(listOf("R1"), withTimeout(10_000) { drained.await() })
        delivery.deliver("parent")
        assertEquals(1, state.queuedSends.value.size)
        coVerify(exactly = 0) { launcher.launch(any(), any()) }
    }

    @Test fun duplicateEventsDoNotDuplicatePendingOrClaimedOwnership() = runBlocking {
        seed()
        delivery.deliver("parent")
        delivery.deliver("parent")
        val state = checkNotNull(registry.get("parent"))
        val original = state.queuedSends.value.single()
        val lease = checkNotNull(state.claimQueuedSends())
        delivery.deliver("parent")
        assertTrue(state.queuedSends.value.isEmpty())
        assertTrue(state.containsSubagentResult("child", "R1"))
        assertTrue(state.settleGuidanceClaim(lease.id, durable = false))
        assertEquals(listOf(original), state.queuedSends.value)
        assertNull(registry.get("other"))
        coVerify(exactly = 0) { launcher.launch(any(), any()) }
    }

    @Test fun dismissalPersistsBeforeRemovalAndSurvivesFreshRuntime() = runBlocking {
        seed()
        delivery.deliver("parent")
        val state = checkNotNull(registry.get("parent"))
        state.queueMutationMutex.withLock { state.dismissQueuedSend(state.queuedSends.value.single().id) }
        assertEquals("R1", repository.getConversation("child")?.subagentDeliveredResultId)
        assertTrue(state.queuedSends.value.isEmpty())
        delivery.deliver("parent")
        assertTrue(state.queuedSends.value.isEmpty())
        val freshRegistry = ConversationStateRegistry {}
        try {
            SubagentCompletionQueueDelivery(repository, freshRegistry) { "provider:model" }.deliver("parent")
            assertNull(freshRegistry.get("parent"))
            assertNull(freshRegistry.get("other"))
        } finally {
            freshRegistry.cancelAll()
        }
    }

    @Test fun failedDismissalRetainsVisibleAndRecoverableOwnership() = runBlocking {
        seed()
        delivery.deliver("parent")
        val state = checkNotNull(registry.get("parent"))
        val send = state.queuedSends.value.single()
        state.onQueuedSendDismissed = { error("dismissal failed") }
        assertTrue(runCatching {
            state.queueMutationMutex.withLock { state.dismissQueuedSend(send.id) }
        }.isFailure)
        assertEquals(listOf(send), state.queuedSends.value)
        assertNull(repository.getConversation("child")?.subagentDeliveredResultId)
        delivery.bind(state)
        delivery.deliver("parent")
        assertEquals(listOf(send), state.queuedSends.value)
    }

    @Test fun committedWaitRemovesOnlyItsExactPendingNotification() = runBlocking {
        seed()
        result("second", "R2")
        database.chatDao().insertRun(activeRun("parent-run"))
        delivery.deliver("parent")
        val state = checkNotNull(registry.get("parent"))
        val typed = queued("typed")
        state.enqueueSend(typed)
        assertTrue(repository.appendToolRoundToRun(waitRound("R1"), 0).inserted)
        assertFalse(state.containsSubagentResult("child", "R1"))
        assertTrue(state.containsSubagentResult("second", "R2"))
        assertEquals(typed, state.queuedSends.value.last())
        delivery.deliver("parent")
        assertEquals(2, state.queuedSends.value.size)
        assertEquals("R1", repository.getConversation("child")?.subagentDeliveredResultId)
    }

    @Test fun committedWaitAfterClaimRetiresNotificationBeforeGraphAdmission() = runBlocking {
        seed()
        database.chatDao().insertRun(activeRun("parent-run"))
        delivery.deliver("parent")
        val state = checkNotNull(registry.get("parent"))
        val claimed = claim()
        assertTrue(repository.appendToolRoundToRun(waitRound("R1"), 0).inserted)
        database.chatDao().terminalizeLiveRun("parent-run", RunStatus.COMPLETED, RunEndReason.MODEL_COMPLETED, 5L)
        executor.launchClaim(state, claimed)
        withTimeout(10_000) { state.generating.first { !it } }
        assertNull(repository.getRun(claimed.inputEffect.identity.runId))
        assertFalse(state.containsSubagentResult("child", "R1"))
        assertTrue(state.queuedSends.value.isEmpty())
        coVerify(exactly = 0) { launcher.launch(any(), any()) }
        delivery.deliver("parent")
        assertTrue(state.queuedSends.value.isEmpty())
    }

    @Test fun waitCommittedAfterClaimPreservesTypedInputAndItsFreshRun() = runBlocking {
        seed()
        database.chatDao().insertRun(activeRun("parent-run"))
        delivery.deliver("parent")
        val state = checkNotNull(registry.get("parent"))
        state.enqueueSend(queued("typed"))
        val claimed = claim()
        assertTrue(repository.appendToolRoundToRun(waitRound("R1"), 0).inserted)
        database.chatDao().terminalizeLiveRun("parent-run", RunStatus.COMPLETED, RunEndReason.MODEL_COMPLETED, 5L)
        executor.launchClaim(state, claimed)
        val request = withTimeout(10_000) { launched.await() }
        val users = repository.getMessagesForRuns(listOf(request.runId)).filter { it.participant == Participant.USER }
        assertEquals(listOf("typed"), users.map { it.text })
        assertNull(MessageSource.decode(users.single().sourceJson))
        assertEquals("R1", repository.getConversation("child")?.subagentDeliveredResultId)
        assertTrue(state.queuedSends.value.isEmpty())
        coVerify(exactly = 1) { launcher.launch(any(), state) }
    }

    @Test fun staleSameChildResultCannotBlockTypedInputOrNewResult() = runBlocking {
        seed()
        delivery.deliver("parent")
        val state = checkNotNull(registry.get("parent"))
        state.enqueueSend(queued("typed"))
        result("child", "R2")
        delivery.deliver("parent")
        assertEquals(3, state.queuedSends.value.size)
        val request = admit()
        val messages = repository.getMessagesForRuns(listOf(request.runId))
        val users = messages.filter { it.participant == Participant.USER }
        assertEquals(listOf("typed", "child (child) [completed]\nAnswer R2"), users.map { it.text })
        assertNull(MessageSource.decode(users.first().sourceJson))
        assertEquals(mapOf("child" to "R2"), MessageSource.decode(users.last().sourceJson)?.subagentResults)
        assertEquals("R2", repository.getConversation("child")?.subagentDeliveredResultId)
        assertTrue(repository.pendingSubagentResults("parent").isEmpty())
        delivery.deliver("parent")
        assertTrue(state.queuedSends.value.isEmpty())
        coVerify(exactly = 1) { launcher.launch(any(), state) }
    }

    @Test fun mixedBatchCommitsSeparateAdjacentMessagesAndEveryReceiptExactlyOnceInOneRun() = runBlocking {
        seed()
        val state = registry.getOrCreate("parent")
        val olderSnapshot = testGenerationAdmissionSnapshot(
            conversationId = "parent", runId = "older", selectedModelId = "provider:older",
        )
        val typed = listOf("typed-one", "typed-two").map { id ->
            queued(id).copy(
                modelId = "provider:older", generationSnapshot = olderSnapshot,
                preparedImages = listOf("$id.png"),
                preparedAttachmentMetaJson = Json.encodeToString(AttachmentMeta(listOf(
                    AttachmentItem(type = "image", fileName = "$id.png", imageIndex = 0),
                ))),
            )
        }
        typed.forEach(state::enqueueSend)
        delivery.deliver("parent")
        state.enqueueSend(queued("ask-one", MessageSource.askUser(listOf(MessageSource.AskUserItem("Port?", "8080")))))
        state.enqueueSend(queued("ask-two", MessageSource.askUser(listOf(MessageSource.AskUserItem("Restart?", null)))))
        result("second", "R2")
        delivery.deliver("parent")
        result("third", "R3")
        delivery.deliver("parent")
        val original = state.queuedSends.value
        assertEquals(7, original.size)

        val claimed = claim()
        assertEquals(original, claimed.batch)
        assertTrue(state.queuedSends.value.isEmpty())
        assertNull(state.claimQueuedSends())
        executor.launchClaim(state, claimed)
        val request = withTimeout(10_000) { launched.await() }

        val messages = repository.getMessagesForRuns(listOf(request.runId))
        val users = messages.filter { it.participant == Participant.USER }
        assertEquals(original.map { it.id }, users.map { it.id })
        assertEquals(original.map { it.text }, users.map { it.text })
        assertEquals(original.map { it.source }, users.map { MessageSource.decode(it.sourceJson) })
        assertEquals(original.map { it.preparedImages }, users.map { it.images })
        assertEquals(original.map { it.preparedAttachmentMetaJson }, users.map { it.attachmentMeta })
        assertEquals(8, messages.size)
        assertEquals(listOf(null) + users.map { it.id }, messages.map { it.parentId })
        assertEquals((0L..7L).toList(), messages.map { it.runSequence })
        assertTrue(messages.all { it.runId == request.runId })
        assertEquals(Participant.MODEL, messages.last().participant)
        assertEquals(MessageStatus.SENDING, messages.last().status)
        assertEquals(request.modelMessageId, messages.last().id)
        assertEquals(listOf(null, null, MessageSource.Kind.SUBAGENT, MessageSource.Kind.ASK_USER,
            MessageSource.Kind.ASK_USER, MessageSource.Kind.SUBAGENT, MessageSource.Kind.SUBAGENT),
            users.map { MessageSource.decode(it.sourceJson)?.kind })
        assertEquals(listOf("typed-one", "typed-two", "child (child) [completed]\nAnswer R1", "Port?\n8080",
            "Restart?\n${MessageSource.NO_ANSWER}", "second (second) [completed]\nAnswer R2",
            "third (third) [completed]\nAnswer R3"), users.map { it.text })
        assertEquals(mapOf("child" to "R1"), MessageSource.decode(users[2].sourceJson)?.subagentResults)
        assertEquals(mapOf("second" to "R2"), MessageSource.decode(users[5].sourceJson)?.subagentResults)
        assertEquals(mapOf("third" to "R3"), MessageSource.decode(users[6].sourceJson)?.subagentResults)
        assertEquals(snapshot.copy(runId = request.runId), request.snapshot)
        assertNotEquals("queued", request.runId)
        assertEquals(1, repository.getRunsForConversationSnapshot("parent").size)
        assertEquals("R1", repository.getConversation("child")?.subagentDeliveredResultId)
        assertEquals("R2", repository.getConversation("second")?.subagentDeliveredResultId)
        assertEquals("R3", repository.getConversation("third")?.subagentDeliveredResultId)
        assertTrue(repository.pendingSubagentResults("parent").isEmpty())
        val committedChildren = listOf("child", "second", "third").map { repository.getConversation(it) }
        repeat(2) { delivery.deliver("parent") }
        assertEquals(committedChildren, listOf("child", "second", "third").map { repository.getConversation(it) })
        assertEquals(messages, repository.getMessagesForRuns(listOf(request.runId)))
        assertEquals(1, repository.getRunsForConversationSnapshot("parent").size)
        assertTrue(state.queuedSends.value.isEmpty())
        assertFalse(state.settleGuidanceClaim(claimed.lease.id, durable = false))
        coVerify(exactly = 1) {
            requestBuilder.captureAdmissionSnapshot("parent", request.runId, "provider:model", any(), any())
        }
        coVerify(exactly = 1) { launcher.launch(any(), state) }
    }

    @Test fun failedAdmissionRollsBackReceiptsAndReturnsExactFifoBatch() = runBlocking {
        seed()
        val dao = database.chatDao()
        dao.insertRun(activeRun("old"))
        dao.insertMessage(MessageEntity(id = "collision", conversationId = "parent", text = "keep",
            participant = Participant.USER, timestamp = 1L, runId = "old", runSequence = 0L))
        dao.terminalizeLiveRun("old", RunStatus.COMPLETED, RunEndReason.MODEL_COMPLETED, 2L)
        delivery.deliver("parent")
        result("second", "R2")
        delivery.deliver("parent")
        val state = checkNotNull(registry.get("parent"))
        state.enqueueSend(queued("collision"))
        val original = state.queuedSends.value
        val claimed = claim()
        assertTrue(state.queuedSends.value.isEmpty())
        val later = queued("later")
        state.enqueueSend(later)
        executor.launchClaim(state, claimed)
        withTimeout(10_000) { failed.await(); state.generating.first { !it } }
        assertNull(repository.getRun(claimed.inputEffect.identity.runId))
        assertNull(repository.getConversation("child")?.subagentDeliveredResultId)
        assertNull(repository.getConversation("second")?.subagentDeliveredResultId)
        assertEquals(setOf("child", "second"), repository.pendingSubagentResults("parent").map { it.id }.toSet())
        assertEquals(original + later, state.queuedSends.value)
        assertFalse(state.settleGuidanceClaim(claimed.lease.id, durable = false))
        assertEquals("keep", dao.getMessage("collision")?.text)
        assertEquals(1, repository.getRunsForConversationSnapshot("parent").size)
        delivery.deliver("parent")
        assertEquals(original + later, state.queuedSends.value)
        coVerify(exactly = 0) { launcher.launch(any(), any()) }
    }

    @Test fun failureAfterAdmissionNeverReturnsCommittedInputToQueue() = runBlocking {
        seed()
        delivery.deliver("parent")
        val state = checkNotNull(registry.get("parent"))
        val claimed = claim()
        coEvery { launcher.launch(any(), state) } throws IllegalStateException("provider setup failed")
        executor.launchClaim(state, claimed)
        withTimeout(10_000) { failed.await() }
        assertNotNull(repository.getRun(claimed.inputEffect.identity.runId))
        assertEquals("R1", repository.getConversation("child")?.subagentDeliveredResultId)
        assertFalse(state.settleGuidanceClaim(claimed.lease.id, durable = false))
        assertTrue(state.queuedSends.value.isEmpty())
        delivery.deliver("parent")
        assertTrue(state.queuedSends.value.isEmpty())
        assertTrue(repository.pendingSubagentResults("parent").isEmpty())
        coVerify(exactly = 1) { launcher.launch(any(), state) }
    }

    @Test fun completionDrainRecoversOrphanBeforePageProjectionCanAcquireLock() = runBlocking {
        seed()
        val dao = database.chatDao()
        dao.insertRun(activeRun("orphan"))
        dao.insertMessage(MessageEntity(id = "orphan-model", conversationId = "parent", text = "partial",
            participant = Participant.MODEL, status = MessageStatus.SENDING,
            timestamp = 1L, runId = "orphan", runSequence = 0L))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { requestBuilder.captureAdmissionSnapshot(any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            snapshot.copy(runId = secondArg())
        }
        delivery.deliver("parent")
        val state = checkNotNull(registry.get("parent"))
        val claimed = claim()
        executor.launchClaim(state, claimed)
        try {
            withTimeout(10_000) { entered.await() }
            var pageRecovered = false
            assertFalse(coordinator.tryWithConversationLock("parent") {
                pageRecovered = true
                repository.recoverConversationRuntime("parent")
            })
            assertFalse(pageRecovered)
            assertEquals(RunStatus.STOPPED, repository.getRun("orphan")?.status)
            assertEquals(RunEndReason.PROCESS_RECOVERED, repository.getRun("orphan")?.endReason)
            assertEquals(MessageStatus.STOPPED, dao.getMessage("orphan-model")?.status)
            release.complete(Unit)
            val request = withTimeout(10_000) { launched.await() }
            assertNotEquals("orphan", request.runId)
            assertEquals(2, repository.getRunsForConversationSnapshot("parent").size)
            assertEquals(request.runId, repository.getLiveRun("parent")?.id)
            assertEquals("R1", repository.getConversation("child")?.subagentDeliveredResultId)
            assertEquals("keep", repository.getConversation("other")?.draftText)
            assertNull(registry.get("other"))
            coVerify(exactly = 1) { launcher.launch(any(), state) }
        } finally {
            release.complete(Unit)
        }
    }
}
