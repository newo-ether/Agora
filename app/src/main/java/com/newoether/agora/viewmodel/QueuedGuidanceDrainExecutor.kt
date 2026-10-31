package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.consumedSubagentResults
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunStatus
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One exact FIFO lease paired with the mailbox effect that authorizes its fresh Run. */
internal data class QueuedDrainClaim(
    val lease: GuidanceBatchLease,
    val inputEffect: RunEffect.PersistAcceptedInput,
) {
    val batch: List<QueuedSend> get() = lease.batch
    val uiToken: Long get() = inputEffect.identity.ownerToken
}

/**
 * Transfers one claimed in-memory guidance batch through the normal fresh-Run durable boundary.
 *
 * Pending and claimed guidance remain owned by the call-scoped runtime host. This executor stores
 * no lease, Run state, Job, scope, overlay, or continuation authority.
 */
internal class QueuedGuidanceDrainExecutor(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val requestBuilder: GenerationRequestBuilder,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val terminalSettlement: GenerationTerminalSettlementController,
    private val boundRunGenerationLauncher: BoundRunGenerationLauncher,
    private val toUiMessage: (MessageEntity) -> ChatMessage,
    private val isConversationOpen: (String) -> Boolean,
    private val projectGraph: (
        conversationId: String,
        messages: List<ChatMessage>,
        selectedChildren: Map<String?, String>,
        streamingMessage: ChatMessage,
    ) -> Unit,
    private val onScrollToAbsoluteBottomAfter: (conversationId: String, messageId: String) -> Unit,
    private val onUserMessagePersisted: (messageId: String, text: String) -> Unit,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Called only while [ConversationGenerationState.queueMutationMutex] is held. */
    suspend fun claimUnderLock(
        state: ConversationGenerationState,
        lease: GuidanceBatchLease,
    ): QueuedDrainClaim? {
        val receipts = lease.batch.flatMap { send -> send.source?.subagentResults.orEmpty().map { it.toPair() } }
            .toSet()
        val activeLease = if (receipts.isEmpty()) lease else try {
            val consumed = conversations.consumedSubagentResults(state.conversationId, receipts)
            state.retireSubagentResults(lease, consumed) ?: return null
        } catch (error: Exception) {
            state.settleGuidanceClaim(lease.id, durable = false)
            throw error
        }
        val proposedRunId = idFactory()
        val transition = try {
            state.commands.requestSend(
                proposedRunId = proposedRunId,
                effectId = "guidance-$proposedRunId",
                directOnly = false,
                hasPendingGuidance = false,
            )
        } catch (error: Exception) {
            state.settleGuidanceClaim(lease.id, durable = false)
            throw error
        }
        val inputEffect = transition.effects
            .filterIsInstance<RunEffect.PersistAcceptedInput>()
            .singleOrNull()
        if (!transition.accepted || inputEffect == null) {
            state.settleGuidanceClaim(lease.id, durable = false)
            return null
        }
        return QueuedDrainClaim(activeLease, inputEffect)
    }

    /** One FIFO lease becomes separate durable user messages in one fresh Run. */
    suspend fun drainAfterSettlement(state: ConversationGenerationState) {
        val claim = state.queueMutationMutex.withLock {
            if (!state.consumeQueueDrainPermission()) return@withLock null
            val originOwnerToken = state.handoffOwnerToken()
            try {
                state.claimQueuedSends()?.let { lease -> claimUnderLock(state, lease) }
            } finally {
                state.finishRunHandoff(originOwnerToken, queuedDrainOnly = true)
            }
        } ?: return
        launchClaim(state, claim)
    }

    /**
     * Releases a claimed slot for which no generation Job was installed, then drains its queue.
     */
    suspend fun releaseUnlaunchedSlotAndDrain(
        state: ConversationGenerationState,
        uiToken: Long,
    ): Unit = withContext(kotlinx.coroutines.NonCancellable) {
        var drainClaim: QueuedDrainClaim? = null
        state.queueMutationMutex.withLock {
            if (state.endGeneration(uiToken) && state.consumeQueueDrainPermission()) {
                state.claimQueuedSends()?.let { lease ->
                    drainClaim = claimUnderLock(state, lease)
                }
            }
        }
        drainClaim?.let { launchClaim(state, it) }
        Unit
    }

    fun launchClaim(
        state: ConversationGenerationState,
        claim: QueuedDrainClaim,
    ) {
        var activeLease = claim.lease
        val conversationId = state.conversationId
        check(claim.inputEffect.identity.conversationId == conversationId)
        val uiToken = claim.uiToken
        val runId = claim.inputEffect.identity.runId
        state.loadingChange(uiToken, true)
        val jobBodyStarted = AtomicBoolean(false)
        val generationJob = state.launchGenerationJob(uiToken) {
            jobBodyStarted.set(true)
            var setupModelMessageId: String? = null
            var graphCommitted = false
            var runBound = false
            var stopFinalizationClaimed = false
            suspend fun reconcileGuidanceOwnership(): Boolean =
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (!graphCommitted) {
                        graphCommitted = conversations.getRun(runId) != null
                    }
                    state.settleGuidanceClaim(claim.lease.id, durable = graphCommitted)
                    graphCommitted
                }
            try {
                if (!state.acquireGenerationAdmission(uiToken, executionCoordinator)) return@launchGenerationJob
                val persistId = state.nextPersistId()
                executionCoordinator.withConversationLock(conversationId) {
                    // A selected-owner completion may reach this lock before page recovery.
                    // No live process execution owns this conversation while admission holds it.
                    conversations.recoverConversationRuntime(conversationId)
                    var activeBatch = activeLease.batch
                    val receipts = activeBatch.flatMap { send ->
                        send.source?.subagentResults.orEmpty().map { it.toPair() }
                    }.toSet()
                    if (receipts.isNotEmpty()) {
                        val retained = state.queueMutationMutex.withLock {
                            val consumed = conversations.consumedSubagentResults(conversationId, receipts)
                            state.retireSubagentResults(activeLease, consumed)
                        }
                        if (retained == null) {
                            state.commands.inputPersistenceFailed(claim.inputEffect.identity)
                            return@withConversationLock
                        }
                        activeLease = retained
                        activeBatch = retained.batch
                    }
                    val bubbles = activeBatch
                    val selectedInput = activeBatch.last()
                    val capturedSnapshot = selectedInput.generationSnapshot
                        ?.copy(conversationId = conversationId, runId = runId)
                        ?: requestBuilder.captureAdmissionSnapshot(
                            conversationId = conversationId,
                            runId = runId,
                            modelId = selectedInput.modelId,
                            automationCaller = activeBatch.any { send -> send.source?.kind in setOf(
                                com.newoether.agora.model.MessageSource.Kind.TASK,
                                com.newoether.agora.model.MessageSource.Kind.LOOP,
                            ) || send.generationSnapshot?.context?.automationCaller == true },
                        )
                    val generationSnapshot = narrowQueuedGuidancePermissions(activeBatch, capturedSnapshot)
                    val topology =
                        conversations.getProviderContextTopologySnapshot(conversationId)
                    val leaf = topology?.let { snapshot ->
                        val leafId = selectedVisibleContextMessageIds(snapshot).lastOrNull()
                        snapshot.messages.firstOrNull { it.id == leafId }
                    }
                    val parentId = leaf?.id
                    val start = clock()
                    // Each bubble chains onto the previous one; the model answers them together.
                    val users = bubbles.mapIndexed { index, queued ->
                        MessageEntity(
                            id = queued.id,
                            conversationId = conversationId,
                            parentId = if (index == 0) parentId else bubbles[index - 1].id,
                            text = queued.text,
                            images = queued.preparedImages,
                            thoughts = null,
                            status = MessageStatus.SUCCESS,
                            participant = Participant.USER,
                            timestamp = start + index,
                            attachmentMeta = queued.preparedAttachmentMetaJson,
                            runId = runId,
                            runSequence = index.toLong(),
                            consumedAtPass = 0,
                            sourceJson = MessageSource.encode(queued.source),
                        )
                    }
                    val modelMessageId = idFactory()
                    setupModelMessageId = modelMessageId
                    val placeholderEntity = MessageEntity(
                        id = modelMessageId,
                        conversationId = conversationId,
                        parentId = users.last().id,
                        text = "",
                        thoughts = null,
                        status = MessageStatus.SENDING,
                        participant = Participant.MODEL,
                        timestamp = start + users.size,
                        modelName = generationSnapshot.selectedModelId,
                        runId = runId,
                        runSequence = users.size.toLong(),
                    )
                    val selectionUpdates = buildMap<String?, String> {
                        users.forEach { put(it.parentId, it.id) }
                        put(users.last().id, modelMessageId)
                    }
                    val graphCommit = conversations.createRunWithMessages(
                        run = RunEntity(
                            id = runId,
                            conversationId = conversationId,
                            parentRunId = leaf?.runId,
                            status = RunStatus.ACTIVE,
                            activeSlot = 1,
                            startedAt = start,
                            lastCheckpointAt = placeholderEntity.timestamp,
                        ),
                        messages = users + placeholderEntity,
                        messageSelectionUpdates = selectionUpdates,
                        conversationModelId = generationSnapshot.selectedModelId,
                        at = start,
                        touchConversationOnAdmission = true,
                    )
                    val committedUsers = graphCommit.messages.dropLast(1)
                    val committedPlaceholder = graphCommit.messages.last()
                    graphCommitted = true
                    check(state.settleGuidanceClaim(claim.lease.id, durable = true))
                    val binding = withContext(kotlinx.coroutines.NonCancellable) {
                        state.finishInputPersistence(claim.inputEffect.identity)
                    }
                    runBound = binding is ConversationGenerationState.RunBindingOutcome.Active
                    if (!runBound) {
                        if (binding is ConversationGenerationState.RunBindingOutcome.Stopping) {
                            stopFinalizationClaimed = true
                            terminalSettlement.settleLateBoundStop(state, binding)
                        } else {
                            withContext(kotlinx.coroutines.NonCancellable) {
                                conversations.finishStoppedGeneration(emptyList(), runId)
                            }
                        }
                        return@withConversationLock
                    }
                    committedUsers.forEach { user ->
                        if (user.text.isNotBlank()) {
                            runCatching { onUserMessagePersisted(user.id, user.text) }
                                .onFailure { error ->
                                    DebugLog.w(
                                        "MessageGenerationController",
                                        "Failed to enqueue queued-message indexing for ${user.id}",
                                        error,
                                    )
                                }
                        }
                        try {
                            settings.incrementMessagesSent()
                        } catch (error: Exception) {
                            DebugLog.w(
                                "MessageGenerationController",
                                "Failed to increment the queued sent-message counter",
                                error,
                            )
                        }
                    }
                    val placeholder = toUiMessage(committedPlaceholder)
                    state.streamUpdate(uiToken, placeholder)
                    if (isConversationOpen(conversationId)) {
                        onScrollToAbsoluteBottomAfter(conversationId, committedUsers.last().id)
                        projectGraph(
                            conversationId,
                            committedUsers.map(toUiMessage) + placeholder,
                            graphCommit.messageSelections,
                            placeholder,
                        )
                    }
                    boundRunGenerationLauncher.launch(
                        BoundRunGenerationRequest(
                            conversationId = conversationId,
                            modelMessageId = modelMessageId,
                            startTime = committedPlaceholder.timestamp,
                            snapshot = generationSnapshot,
                            uiToken = uiToken,
                            persistId = persistId,
                            runId = runId,
                            pass = 0,
                            requestKind = "queued_guidance",
                        ),
                        state,
                    )
                }
            } catch (error: CancellationException) {
                val durable = reconcileGuidanceOwnership()
                if (durable && !runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        val binding = state.finishInputPersistence(claim.inputEffect.identity)
                        stopFinalizationClaimed =
                            terminalSettlement.settleCancelledDurableRun(state, binding)
                        if (!stopFinalizationClaimed) {
                            conversations.finishStoppedGeneration(emptyList(), runId)
                        }
                    }
                }
                throw error
            } catch (error: Exception) {
                val durable = reconcileGuidanceOwnership()
                if (durable && !runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        val binding = state.finishInputPersistence(claim.inputEffect.identity)
                        runBound = binding is ConversationGenerationState.RunBindingOutcome.Active
                        if (binding is ConversationGenerationState.RunBindingOutcome.Stopping) {
                            stopFinalizationClaimed = true
                            terminalSettlement.settleLateBoundStop(state, binding)
                        }
                    }
                } else if (!durable) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        state.commands.inputPersistenceFailed(claim.inputEffect.identity)
                    }
                }
                terminalSettlement.failGenerationSetup(
                    conversationId,
                    runId,
                    setupModelMessageId,
                    uiToken,
                    state,
                    error,
                )
                if (!durable) state.deferNextQueueDrain()
            }
        }
        generationJob?.invokeOnCompletion {
            if (!jobBodyStarted.get()) {
                state.settleGuidanceClaim(claim.lease.id, durable = false)
            }
        }
        if (generationJob == null) {
            state.settleGuidanceClaim(claim.lease.id, durable = false)
            state.scope.launch {
                state.awaitSendAvailable()
                val retryClaim = state.queueMutationMutex.withLock {
                    val retryLease = state.claimQueuedSends() ?: return@withLock null
                    claimUnderLock(state, retryLease)
                }
                retryClaim?.let { launchClaim(state, it) }
            }
        }
    }
}
