package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.util.Constants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationBranchMutationServiceTest {
    /** Client that issues every delete in these tests; hooks are set by [service]. */
    private val origin = FakeChatClient(open = "conversation")
    @Test
    fun cancellationDuringOverlayStillCompletesTheBlockedDialog() = runTest {
        val owner = SupervisorJob()
        val events = mutableListOf<String>()
        val results = mutableListOf<Boolean>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val service = service(
            conversations = mockk(),
            events = events,
            ownerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + owner),
            start = {
                CompletableDeferred<Unit>().await()
                7L
            },
        )
        service.delete(
            "conversation", "model", state,
            listOf(chat("model", null, Participant.MODEL)), origin, results::add,
        )
        runCurrent()
        owner.cancel()
        runCurrent()

        assertEquals(listOf(false), results)
        assertEquals(listOf("failed:null"), events)
        state.dispose()
    }

    @Test
    fun busyCoordinatorRejectsDeletionWithoutWaitingForGeneration() = runTest {
        val coordinator = ConversationExecutionCoordinator()
        val events = mutableListOf<String>()
        val results = mutableListOf<Boolean>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        backgroundScope.launch {
            coordinator.withAutomationConversationLock("conversation") {
                CompletableDeferred<Unit>().await()
            }
        }
        runCurrent()
        val service = service(
            mockk(), events,
            ownerScope = backgroundScope, coordinator = coordinator,
        )
        service.delete(
            "conversation", "model", state,
            listOf(chat("model", null, Participant.MODEL)), origin, results::add,
        )
        runCurrent()

        assertEquals(listOf(false), results)
        assertEquals(listOf("start:true", "failed:7"), events)
        state.dispose()
    }

    @Test
    fun busyQueueRejectsDeletionAndLeavesTheOwnersLockHeld() = runTest {
        val conversations = mockk<ConversationRepository>()
        val events = mutableListOf<String>()
        val results = mutableListOf<Boolean>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val owner = Any()
        state.queueMutationMutex.lock(owner)
        try {
            service(conversations, events).delete(
                "conversation", "model", state,
                listOf(chat("model", null, Participant.MODEL)), origin, results::add,
            )
            runCurrent()

            assertEquals(listOf(false), results)
            assertEquals(listOf("start:true", "failed:7"), events)
            assertEquals(true, state.queueMutationMutex.holdsLock(owner))
            coVerify(exactly = 0) { conversations.getLiveRun(any()) }
        } finally {
            state.queueMutationMutex.unlock(owner)
            state.dispose()
        }
    }

    @Test
    fun compactBoundaryUsesDedicatedTransactionWithoutRequestingAScrollTarget() = runTest {
        val conversations = mockk<ConversationRepository>()
        val compactId = "${Constants.COMPACT_MSG_PREFIX}one"
        val remaining = entity("user", null, Participant.USER)
        val offPathTail = entity("off-path-tail", "missing-parent", Participant.MODEL)
        val events = mutableListOf<String>()
        coEvery { conversations.getLiveRun("conversation") } coAnswers {
            events += "live-check"
            null
        }
        coEvery { conversations.removeContextCompact(compactId) } coAnswers {
            events += "remove"
            true
        }
        coEvery { conversations.getMessageTopologySnapshot("conversation") } coAnswers {
            events += "snapshot"
            listOf(remaining, offPathTail).map(::topology)
        }
        coEvery { conversations.restoreBranchSelections("conversation") } coAnswers {
            events += "selections"
            emptyMap()
        }
        val failed = mutableListOf<Long?>()
        val service = service(
            conversations = conversations,
            events = events,
            onFailed = failed::add,
        )
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})

        val previewCount = service.delete(
            conversationId = "conversation",
            messageId = compactId,
            state = state,
            snapshot = listOf(chat(compactId, null, Participant.MODEL)),
            origin = origin,
        )
        advanceUntilIdle()

        assertEquals(1, previewCount)
        assertEquals(
            listOf(
                "start:false",
                "live-check",
                "remove",
                "snapshot",
                "selections",
                "settle:null",
                "project:user, off-path-tail",
            ),
            events,
        )
        assertEquals(emptyList<Long?>(), failed)
        state.dispose()
        Unit
    }

    @Test
    fun structuralDeletionCommitsRoomBeforeDeletingFilesAndSettlingUi() = runTest {
        val conversations = mockk<ConversationRepository>()
        val user = entity("user", null, Participant.USER, sequence = 0)
        val model = entity("model", "user", Participant.MODEL, sequence = 1)
        val events = mutableListOf<String>()
        coEvery { conversations.getLiveRun("conversation") } returns null
        coEvery { conversations.getRunsForConversationSnapshot("conversation") } returns
            listOf(run())
        coEvery { conversations.getMessageTopologySnapshot("conversation") } returns
            listOf(user, model).map(::topology)
        coEvery { conversations.getMessagesByIds(listOf("model")) } returns listOf(model)
        coEvery { conversations.restoreBranchSelections("conversation") } returns
            mapOf("user" to "model")
        coEvery { conversations.restoreRunBranchSelections("conversation") } returns emptyMap()
        coEvery {
            conversations.deleteMessageSubtree(
                conversationId = "conversation",
                rootMessageId = "model",
                staleMessageIds = any(),
                rootRunIdsToDelete = any(),
                messageSelections = any(),
                runSelections = any(),
                at = any(),
            )
        } coAnswers {
            events += "room-commit"
            true
        }
        val service = service(conversations, events)
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})

        val previewCount = service.delete(
            conversationId = "conversation",
            messageId = "model",
            state = state,
            snapshot = listOf(chat("user", null, Participant.USER), chat("model", "user", Participant.MODEL)),
            origin = origin,
            onResult = { events += "result:$it" },
        )
        advanceUntilIdle()

        assertEquals(1, previewCount)
        assertEquals(
            listOf("start:true", "room-commit", "settle:user", "project:user", "result:true"),
            events,
        )
        state.dispose()
        Unit
    }

    @Test
    fun activeRunRejectsDeletionBeforeLaunchingMutation() = runTest {
        val conversations = mockk<ConversationRepository>()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        requireNotNull(state.acquireForSend())

        val results = mutableListOf<Boolean>()
        val previewCount = service(conversations, mutableListOf()).delete(
            conversationId = "conversation",
            messageId = "model",
            state = state,
            snapshot = listOf(chat("model", null, Participant.MODEL)),
            origin = origin,
            onResult = results::add,
        )
        advanceUntilIdle()

        assertEquals(0, previewCount)
        assertEquals(listOf(false), results)
        coVerify(exactly = 0) { conversations.getLiveRun(any()) }
        state.dispose()
        Unit
    }

    private fun kotlinx.coroutines.test.TestScope.service(
        conversations: ConversationRepository,
        events: MutableList<String>,
        onFailed: (Long?) -> Unit = { events += "failed:$it" },
        ownerScope: CoroutineScope = this,
        coordinator: ConversationExecutionCoordinator = ConversationExecutionCoordinator(),
        start: suspend (Boolean) -> Long? = {
            events += "start:$it"
            7L
        },
    ): ConversationBranchMutationService {
        origin.onBeginTreeMutation = { _, scrollToTarget -> start(scrollToTarget) }
        origin.onSettleTreeMutation = { _, target -> events += "settle:$target" }
        origin.onFailTreeMutation = onFailed
        return ConversationBranchMutationService(
        scope = ownerScope,
        conversations = conversations,
        executionCoordinator = coordinator,
        toUiMessage = { entity ->
            chat(entity.id, entity.parentId, entity.participant)
        },
        isConversationOpen = { true },
        projectGraph = { _, messages, _ -> events += "project:${messages.joinToString { it.id }}" },
        ioDispatcher = StandardTestDispatcher(testScheduler),
        resultDispatcher = StandardTestDispatcher(testScheduler),
    )
    }

    private fun entity(
        id: String,
        parentId: String?,
        participant: Participant,
        sequence: Long = 0,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = id,
        participant = participant,
        timestamp = sequence + 1,
        runId = "run",
        runSequence = sequence,
    )

    private fun chat(id: String, parentId: String?, participant: Participant) = ChatMessage(
        id = id,
        parentId = parentId,
        text = id,
        participant = participant,
        runId = "run",
    )

    private fun topology(message: MessageEntity) =
        com.newoether.agora.data.local.MessageContextTopology(
            id = message.id,
            conversationId = message.conversationId,
            parentId = message.parentId,
            status = message.status,
            participant = message.participant,
            timestamp = message.timestamp,
            modelName = message.modelName,
            runId = message.runId,
            runSequence = message.runSequence,
            consumedAtPass = message.consumedAtPass,
        )

    private fun run() = RunEntity(
        id = "run",
        conversationId = "conversation",
        parentRunId = null,
        status = RunStatus.COMPLETED,
        activeSlot = null,
        startedAt = 1L,
        lastCheckpointAt = 2L,
        endedAt = 2L,
        endReason = RunEndReason.MODEL_COMPLETED,
    )
}
