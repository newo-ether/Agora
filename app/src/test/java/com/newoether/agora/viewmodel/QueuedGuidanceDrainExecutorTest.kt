package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.ProviderContextTopologySnapshot
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.local.RunGraphCommit
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.phone.PhoneControlLease
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedGuidanceDrainExecutorTest {
    @Test
    fun claimUsesFreshRunIdentityAndFailedLeaseReturnsToFifoFront() = runBlocking<Unit> {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        QUEUED.forEach(state::enqueueSend)
        val lease = checkNotNull(state.claimQueuedSends())

        val claim = fixture.executor.claimUnderLock(state, lease)

        assertNotNull(claim)
        assertEquals("fresh-run", claim?.inputEffect?.identity?.runId)
        assertEquals("guidance-fresh-run", claim?.inputEffect?.identity?.effectId)
        assertEquals(QUEUED, claim?.batch)
        assertTrue(state.queuedSends.value.isEmpty())
        assertNull(state.claimQueuedSends())
        val later = QUEUED.first().copy(id = "guidance-3", text = "later")
        state.enqueueSend(later)
        assertTrue(state.settleGuidanceClaim(lease.id, durable = false))
        assertFalse(state.settleGuidanceClaim(lease.id, durable = false))
        assertEquals(QUEUED + later, state.queuedSends.value)
        state.dispose()
        Unit
    }

    @Test
    fun launchCommitsWholeFifoAsSeparateMessagesWithOneRunAndLatestSnapshot() = runBlocking<Unit> {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val olderSnapshot = testGenerationAdmissionSnapshot(
            conversationId = "conversation", runId = "older-run", selectedModelId = "provider:model-1",
        )
        val frozenSnapshot = fixture.snapshot.copy(runId = "old-run")
        val queued = QUEUED.mapIndexed { index, send ->
            send.copy(
                generationSnapshot = if (index == 0) olderSnapshot else frozenSnapshot,
                preparedImages = listOf("image-$index.png"),
                preparedAttachmentMetaJson = Json.encodeToString(AttachmentMeta(listOf(
                    AttachmentItem(type = "image", fileName = "image-$index.png", imageIndex = 0),
                ))),
            )
        }
        queued.forEach(state::enqueueSend)
        val lease = checkNotNull(state.claimQueuedSends())
        val claim = checkNotNull(fixture.executor.claimUnderLock(state, lease))
        val createdRun = slot<RunEntity>()
        val createdMessages = slot<List<MessageEntity>>()
        val selections = slot<Map<String?, String>>()
        val commitAt = slot<Long>()
        val touchPolicy = slot<Boolean>()
        coEvery {
            fixture.conversations.createRunWithMessages(
                run = capture(createdRun),
                messages = capture(createdMessages),
                messageSelectionUpdates = capture(selections),
                conversationModelId = "provider:model-2",
                at = capture(commitAt),
                touchConversationOnAdmission = capture(touchPolicy),
            )
        } answers {
            RunGraphCommit(secondArg(), thirdArg(), emptyMap())
        }

        fixture.executor.launchClaim(state, claim)
        val request = withTimeout(5_000) { fixture.launched.await() }

        coVerify(exactly = 0) {
            fixture.requestBuilder.captureAdmissionSnapshot(any(), any(), any(), any(), any())
        }
        assertEquals("conversation", request.conversationId)
        assertEquals("model-message", request.modelMessageId)
        assertEquals(fixture.snapshot, request.snapshot)
        assertTrue(request.snapshot.config === frozenSnapshot.config)
        assertEquals("fresh-run", request.runId)
        assertEquals(0, request.pass)
        assertEquals("queued_guidance", request.requestKind)
        assertEquals("fresh-run", createdRun.captured.id)
        assertNull(createdRun.captured.parentRunId)
        assertEquals(102L, createdRun.captured.lastCheckpointAt)
        val messages = createdMessages.captured
        assertEquals(listOf("guidance-1", "guidance-2", "model-message"), messages.map { it.id })
        assertEquals(listOf("one", "two", ""), messages.map { it.text })
        assertEquals(listOf(Participant.USER, Participant.USER, Participant.MODEL), messages.map { it.participant })
        assertEquals(listOf(null, "guidance-1", "guidance-2"), messages.map { it.parentId })
        assertEquals(listOf(0L, 1L, 2L), messages.map { it.runSequence })
        assertEquals(listOf(100L, 101L, 102L), messages.map { it.timestamp })
        assertTrue(messages.all { it.runId == "fresh-run" })
        val users = messages.dropLast(1)
        assertEquals(queued.map { it.preparedImages }, users.map { it.images })
        assertEquals(queued.map { it.preparedAttachmentMetaJson }, users.map { it.attachmentMeta })
        assertEquals(listOf(0, 0), users.map {
            Json.decodeFromString<AttachmentMeta>(checkNotNull(it.attachmentMeta)).items.single().imageIndex
        })
        assertTrue(users.all { it.status == MessageStatus.SUCCESS && MessageSource.decode(it.sourceJson) == null })
        assertEquals(MessageStatus.SENDING, messages.last().status)
        assertEquals("provider:model-2", messages.last().modelName)
        assertEquals(mapOf(null to "guidance-1", "guidance-1" to "guidance-2", "guidance-2" to "model-message"),
            selections.captured)
        assertEquals(100L, commitAt.captured)
        assertTrue(touchPolicy.captured)
        assertTrue(queued.all { it.createdAt != commitAt.captured })
        assertEquals(listOf("indexed:guidance-1:one", "indexed:guidance-2:two", "scroll:guidance-2"), fixture.events)
        coVerify(exactly = 1) { fixture.conversations.createRunWithMessages(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { fixture.boundLauncher.launch(any(), state) }
        coVerify(exactly = 2) { fixture.settings.incrementMessagesSent() }
        assertTrue(state.queuedSends.value.isEmpty())
        assertFalse(state.settleGuidanceClaim(lease.id, durable = false))
        state.dispose()
        Unit
    }

    @Test
    fun latestInputWithoutSnapshotCapturesOnceForWholeBatchInsteadOfUsingOlderSnapshot() = runBlocking<Unit> {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        state.enqueueSend(QUEUED.first().copy(generationSnapshot = testGenerationAdmissionSnapshot(
            conversationId = "conversation", runId = "old-run", selectedModelId = "provider:model-1",
        )))
        state.enqueueSend(QUEUED.last())
        coEvery {
            fixture.requestBuilder.captureAdmissionSnapshot("conversation", "fresh-run", "provider:model-2", any(), any())
        } returns fixture.snapshot
        val lease = checkNotNull(state.claimQueuedSends())
        val claim = checkNotNull(fixture.executor.claimUnderLock(state, lease))

        fixture.executor.launchClaim(state, claim)
        val request = withTimeout(5_000) { fixture.launched.await() }

        assertEquals(fixture.snapshot, request.snapshot)
        coVerify(exactly = 1) {
            fixture.requestBuilder.captureAdmissionSnapshot("conversation", "fresh-run", "provider:model-2", any(), any())
        }
        coVerify(exactly = 1) { fixture.conversations.createRunWithMessages(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { fixture.boundLauncher.launch(any(), state) }
        assertTrue(state.queuedSends.value.isEmpty())
        assertFalse(state.settleGuidanceClaim(lease.id, durable = false))
        state.dispose()
        Unit
    }

    @Test
    fun emptyDrainRetiresUnclaimedHandoffButSuppressionPreservesScheduledWork() = runBlocking<Unit> {
        for (suppressed in listOf(false, true)) {
            val fixture = Fixture()
            val phone = PhoneControlLease { 0L }
            val state = phoneState(phone)
            val token = settlePhoneOrigin(state, phone)
            if (suppressed) state.deferNextQueueDrain()

            fixture.executor.drainAfterSettlement(state)

            assertEquals(suppressed, phone.held.value != null)
            state.finishRunHandoff(token)
            assertNull(phone.held.value)
            coVerify(exactly = 0) { fixture.boundLauncher.launch(any(), any()) }
            state.dispose()
        }
    }

    @Test
    fun compactEmptyDrainCannotRetireScheduledContinuationAndFailedLaunchCleansIt() = runBlocking<Unit> {
        val fixture = Fixture()
        val phone = PhoneControlLease { 0L }
        val state = phoneState(phone)
        val token = settlePhoneOrigin(state, phone, retained = true)
        val epoch = phone.held.value!!.epoch

        fixture.executor.drainAfterSettlement(state)
        fixture.executor.drainAfterSettlement(state)
        assertEquals(epoch, phone.held.value!!.epoch)
        assertNull(launchStandardContinuationAfterGuidance(state, state.guidanceClaimRevision(), token) { null })
        assertNull(phone.held.value)
        state.dispose()
    }

    @Test
    fun queuedGuidanceWinsScheduledContinuationAndTransfersPhoneEpochAfterBinding() = runBlocking<Unit> {
        val fixture = Fixture()
        val phone = PhoneControlLease { 0L }
        val state = phoneState(phone)
        val token = settlePhoneOrigin(state, phone, retained = true)
        val epoch = phone.held.value!!.epoch
        val revision = state.guidanceClaimRevision()
        QUEUED.forEach { state.enqueueSend(it.copy(generationSnapshot = fixture.snapshot)) }
        val holdGeneration = CompletableDeferred<Unit>()
        coEvery { fixture.boundLauncher.launch(any(), state) } coAnswers {
            fixture.launched.complete(firstArg())
            holdGeneration.await()
        }

        assertNull(launchStandardContinuationAfterGuidance(state, revision, token) {
            error("Pending FIFO guidance must own admission")
        })
        assertEquals(epoch, phone.held.value!!.epoch)
        fixture.executor.drainAfterSettlement(state)
        val request = withTimeout(5_000) { fixture.launched.await() }
        assertEquals("fresh-run", request.runId)
        assertEquals(PhoneControlLease.Owner("conversation", "fresh-run"), phone.held.value!!.owner)
        assertEquals(epoch, phone.held.value!!.epoch)
        state.finishRunHandoff(token)
        assertEquals(epoch, phone.held.value!!.epoch)
        assertTrue(phone.recentReleases().isEmpty())
        state.dispose()
        holdGeneration.complete(Unit)
    }

    @Test
    fun cancelledGuidanceAfterWinningContinuationLeavesNoRetainedLease() = runBlocking<Unit> {
        val fixture = Fixture()
        val phone = PhoneControlLease { 0L }
        val state = phoneState(phone)
        val token = settlePhoneOrigin(state, phone, retained = true)
        val revision = state.guidanceClaimRevision()
        state.enqueueSend(QUEUED.first())
        launchStandardContinuationAfterGuidance(state, revision, token) { error("Queued guidance wins") }
        state.removeQueuedSend(QUEUED.first().id)
        fixture.executor.drainAfterSettlement(state)
        assertNull(phone.held.value)
        state.dispose()
    }

    @Test
    fun retiredClaimWithoutSuccessorReleasesRetainedPhoneHandoff() = runBlocking<Unit> {
        val phone = PhoneControlLease { 0L }
        val state = phoneState(phone)
        val token = settlePhoneOrigin(state, phone, retained = true)
        val revision = state.guidanceClaimRevision()
        state.enqueueSend(QUEUED.first())
        val claim = requireNotNull(state.claimQueuedSends())
        assertTrue(state.settleGuidanceClaim(claim.id, durable = false))
        state.removeQueuedSend(QUEUED.first().id)

        assertNull(launchStandardContinuationAfterGuidance(state, revision, token) {
            error("Claimed guidance already superseded this continuation")
        })
        assertNull(phone.held.value)
        state.dispose()
    }

    private fun phoneState(phone: PhoneControlLease) = ConversationGenerationState(
        "conversation", onRunStateChanged = phone::onRunStateChanged,
        onRunHandoffFinished = phone::finishHandoff, reclaimQueuedAttachments = {},
    )

    private suspend fun settlePhoneOrigin(
        state: ConversationGenerationState,
        phone: PhoneControlLease,
        retained: Boolean = false,
    ): Long {
        val token = state.acquireForSend()!!
        state.bindRun(token, "origin")
        val identity = RunEffectIdentity("conversation", token, "origin", 0, "finalize-origin")
        phone.claim(identity, "Title")
        if (retained) state.retainRunHandoff(token)
        checkNotNull(state.commands.requestRunFinalization(
            identity, RunStatus.COMPLETED, RunEndReason.CONTINUATION_PENDING, false,
        ))
        state.finishRunFinalization(identity, true)
        assertTrue(state.endGeneration(token))
        return token
    }

    private class Fixture {
        val conversations = mockk<ConversationRepository>()
        val settings = mockk<SettingsRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val terminalSettlement = mockk<GenerationTerminalSettlementController>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val events = mutableListOf<String>()
        val launched = CompletableDeferred<BoundRunGenerationRequest>()
        val snapshot = testGenerationAdmissionSnapshot(
            conversationId = "conversation",
            runId = "fresh-run",
            selectedModelId = "provider:model-2",
        )
        private val ids = ArrayDeque(listOf("fresh-run", "model-message"))
        init {
            coEvery { conversations.recoverConversationRuntime(any(), any()) } returns 0
            coEvery { conversations.getProviderContextTopologySnapshot("conversation") } returns
                ProviderContextTopologySnapshot(null, emptyList())
            coEvery { conversations.createRunWithMessages(any(), any(), any(), any(), any(), any()) } answers {
                RunGraphCommit(secondArg(), thirdArg(), emptyMap())
            }
            coEvery { settings.incrementMessagesSent() } just Runs
            coEvery { boundLauncher.launch(any(), any()) } coAnswers {
                launched.complete(firstArg())
                Unit
            }
        }
        val executor = QueuedGuidanceDrainExecutor(
            conversations = conversations,
            settings = settings,
            requestBuilder = requestBuilder,
            executionCoordinator = ConversationExecutionCoordinator(),
            terminalSettlement = terminalSettlement,
            boundRunGenerationLauncher = boundLauncher,
            toUiMessage = ::toUiMessage,
            isConversationOpen = { true },
            projectGraph = { _, _, _, _ -> Unit },
            onScrollToAbsoluteBottomAfter = { _, messageId -> events += "scroll:$messageId" },
            onUserMessagePersisted = { messageId, text -> events += "indexed:$messageId:$text" },
            idFactory = ids::removeFirst,
            clock = { 100L },
        )
    }

    private companion object {
        val QUEUED = listOf(
            QueuedSend(
                id = "guidance-1", text = "one", modelId = "provider:model-1",
                attachments = emptyList(), runId = "old-run", createdAt = 10L,
            ),
            QueuedSend(
                id = "guidance-2", text = "two", modelId = "provider:model-2",
                attachments = emptyList(), runId = "old-run", createdAt = 20L,
            ),
        )

        fun toUiMessage(entity: MessageEntity) = ChatMessage(
            id = entity.id,
            parentId = entity.parentId,
            text = entity.text,
            participant = entity.participant,
            status = entity.status,
            timestamp = entity.timestamp,
            modelName = entity.modelName,
            runId = entity.runId,
            runSequence = entity.runSequence,
        )
    }
}
