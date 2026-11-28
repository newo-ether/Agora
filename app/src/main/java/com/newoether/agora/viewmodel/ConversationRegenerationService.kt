package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageGenerationBoundaryResolver
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Immutable UI/Provider snapshot for one regenerate intent. */
internal data class ConversationRegenerationRequest(
    val conversationId: String,
    val messageId: String,
    val modelId: String,
    val visiblePath: List<ChatMessage>,
    /** Client that issued the regenerate; it plays the branch transition. */
    val origin: ChatClient,
)

/**
 * Creates one regeneration branch after the existing visual transition admits the intent.
 *
 * The call-scoped runtime host remains the sole Run-state owner; this service stores no mutable
 * transition, Run, Job, scope, stream, or overlay state.
 */
internal class ConversationRegenerationService(
    private val conversations: ConversationRepository,
    private val requestBuilder: GenerationRequestBuilder,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val terminalSettlement: GenerationTerminalSettlementController,
    private val boundRunGenerationLauncher: BoundRunGenerationLauncher,
    private val guidanceDrain: QueuedGuidanceDrainExecutor,
    private val toUiMessage: (MessageEntity) -> ChatMessage,
    private val isConversationOpen: (String) -> Boolean,
    private val projectGraph: (
        conversationId: String,
        messages: List<ChatMessage>,
        selectedChildren: Map<String?, String>,
        streamingMessage: ChatMessage,
    ) -> Unit,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun regenerate(
        request: ConversationRegenerationRequest,
        state: ConversationGenerationState,
    ): Boolean {
        val boundary = MessageGenerationBoundaryResolver.containing(
            request.visiblePath,
            request.messageId,
        ) ?: return false
        if (boundary.lastAssistant?.id != request.messageId) return false
        val firstAssistant = boundary.firstAssistant ?: return false
        val rootOutputId = firstAssistant.id
        val targetParentMessageId =
            boundary.input?.id ?: firstAssistant.parentId ?: return false

        val uiToken = state.tryAcquireForReplacement() ?: return false
        val transitions = request.origin.branchTransitions
        val transition = transitions.begin(
            conversationId = request.conversationId,
            oldMessageId = request.messageId,
            targetUserMessageId = targetParentMessageId,
        ) ?: run {
            state.scope.launch {
                guidanceDrain.releaseUnlaunchedSlotAndDrain(state, uiToken)
            }
            return false
        }
        val runId = idFactory()
        var graphCommitted = false
        val generationJob = state.launchGenerationJob(uiToken) generation@{
            var setupModelMessageId: String? = null
            var runBound = false
            var stopFinalizationClaimed = false
            try {
                if (!state.acquireGenerationAdmission(uiToken, executionCoordinator)) return@generation
                if (!transitions.awaitFade(transition.id)) return@generation
                if (
                    !state.isCurrentToken(uiToken) ||
                    !transitions.isAnimating(transition.id) ||
                    !isConversationOpen(request.conversationId)
                ) {
                    return@generation
                }
                val persistId = state.nextPersistId()
                executionCoordinator.withConversationLock(request.conversationId) lock@{
                    if (
                        !state.isCurrentToken(uiToken) ||
                        !transitions.isAnimating(transition.id) ||
                        !isConversationOpen(request.conversationId)
                    ) {
                        return@lock
                    }
                    val persistedMessages = conversations.getMessagesByIds(
                        listOf(request.messageId, rootOutputId, targetParentMessageId),
                    ).associateBy(MessageEntity::id)
                    val persistedTarget = persistedMessages[request.messageId] ?: return@lock

                    if (!MessageGenerationBoundaryResolver.isOrdinaryAssistant(toUiMessage(persistedTarget))) {
                        return@lock
                    }
                    val persistedRoot = persistedMessages[rootOutputId] ?: return@lock
                    val sourceBoundary =
                        persistedMessages[targetParentMessageId] ?: return@lock
                    if (
                        !MessageGenerationBoundaryResolver.isOrdinaryAssistant(toUiMessage(persistedRoot)) ||
                        persistedRoot.parentId != sourceBoundary.id ||
                        persistedRoot.runId != persistedTarget.runId
                    ) {
                        return@lock
                    }
                    if (
                        boundary.input != null &&
                        !MessageGenerationBoundaryResolver.isRealUser(
                            toUiMessage(sourceBoundary),

                        )
                    ) {
                        return@lock
                    }
                    val generationSnapshot = requestBuilder.captureAdmissionSnapshot(
                        conversationId = request.conversationId,
                        runId = runId,
                        modelId = request.modelId,
                    )
                    val modelMessageId = idFactory()
                    setupModelMessageId = modelMessageId
                    val startTime = maxOf(clock(), persistedTarget.timestamp + 1)
                    val modelEntity = MessageEntity(
                        id = modelMessageId,
                        conversationId = request.conversationId,
                        parentId = sourceBoundary.id,
                        text = "",
                        thoughts = null,
                        thoughtTitle = null,
                        status = MessageStatus.SENDING,
                        participant = Participant.MODEL,
                        timestamp = startTime,
                        modelName = generationSnapshot.selectedModelId,
                        runId = runId,
                        runSequence = 0,
                    )
                    val graphCommit = conversations.createRunWithMessages(
                        RunEntity(
                            id = runId,
                            conversationId = request.conversationId,
                            parentRunId = sourceBoundary.runId,
                            status = RunStatus.ACTIVE,
                            activeSlot = 1,
                            startedAt = startTime,
                            lastCheckpointAt = startTime,
                        ),
                        listOf(modelEntity),
                        messageSelectionUpdates = mapOf(sourceBoundary.id to modelEntity.id),
                        conversationModelId = generationSnapshot.selectedModelId,
                        at = startTime,
                        touchConversationOnAdmission = true,
                    )
                    graphCommitted = true
                    transitions.markCommitted(transition.id)
                    val binding = state.bindPersistedRun(uiToken, runId)
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
                        return@lock
                    }
                    val placeholder = toUiMessage(modelEntity)
                    state.streamUpdate(uiToken, placeholder)
                    if (isConversationOpen(request.conversationId)) {
                        projectGraph(
                            request.conversationId,
                            listOf(placeholder),
                            graphCommit.messageSelections,
                            placeholder,
                        )
                    }
                    boundRunGenerationLauncher.launch(
                        BoundRunGenerationRequest(
                            conversationId = request.conversationId,
                            modelMessageId = modelMessageId,
                            startTime = startTime,
                            snapshot = generationSnapshot,
                            uiToken = uiToken,
                            persistId = persistId,
                            runId = runId,
                            pass = 0,
                            requestKind = "chat",
                        ),
                        state,
                    )
                }
            } catch (error: CancellationException) {
                if (!runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (conversations.getRun(runId) != null) {
                            graphCommitted = true
                            transitions.markCommitted(transition.id)
                            val binding = state.bindPersistedRun(uiToken, runId)
                            stopFinalizationClaimed =
                                terminalSettlement.settleCancelledDurableRun(state, binding)
                            if (!stopFinalizationClaimed) {
                                conversations.finishStoppedGeneration(emptyList(), runId)
                            }
                        }
                    }
                }
                throw error
            } catch (error: Exception) {
                if (!runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (conversations.getRun(runId) != null) {
                            graphCommitted = true
                            transitions.markCommitted(transition.id)
                            val binding = state.bindPersistedRun(uiToken, runId)
                            runBound = binding is
                                ConversationGenerationState.RunBindingOutcome.Active
                            if (binding is ConversationGenerationState.RunBindingOutcome.Stopping) {
                                stopFinalizationClaimed = true
                                terminalSettlement.settleLateBoundStop(state, binding)
                            }
                        }
                    }
                }
                terminalSettlement.failGenerationSetup(
                    conversationId = request.conversationId,
                    runId = runId,
                    modelMessageId = setupModelMessageId,
                    uiToken = uiToken,
                    state = state,
                    error = error,
                )
            } finally {
                if (!graphCommitted) transitions.abort(transition.id)
            }
        }
        if (generationJob == null) transitions.abort(transition.id)
        return generationJob != null
    }
}
