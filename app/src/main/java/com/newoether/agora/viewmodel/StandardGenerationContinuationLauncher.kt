package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** One ordinary Assistant generation that continues from an already-durable graph boundary. */
internal data class StandardGenerationContinuationRequest(
    val conversationId: String,
    val parentMessageId: String,
    val snapshot: GenerationAdmissionSnapshot,
    val alreadyHoldsConversationLock: Boolean = false,
    val modelMessageId: String? = null,
    val replacementMessageId: String? = null,
    val requestKind: String = "chat",
    val touchConversationOnAdmission: Boolean,
    /** Null keeps the conversation preference; the request and output still use the snapshot model. */
    val conversationModelId: String? = snapshot.selectedModelId,
    val queueDrainRequiresSuccess: Boolean = false,
    val continuesAfterSuccess: Boolean = false,
    val transformFinalText: (String, MessageStatus) -> String = { text, _ -> text },
)

internal suspend fun launchStandardContinuationAfterGuidance(
    state: ConversationGenerationState,
    guidanceClaimRevision: Long,
    handoffOwnerToken: Long? = null,
    launch: () -> StandardGenerationContinuationLaunch?,
): StandardGenerationContinuationLaunch? = state.queueMutationMutex.withLock {
    if (state.hasPendingOrClaimedGuidanceSince(guidanceClaimRevision)) {
        if (state.queuedSends.value.isNotEmpty()) state.releaseRunHandoffRetention(handoffOwnerToken)
        else state.finishRunHandoff(handoffOwnerToken)
        null
    } else try {
        launch()
    } finally {
        state.finishRunHandoff(handoffOwnerToken)
    }
}

internal data class StandardGenerationContinuationLaunch(
    val job: Job,
    val modelMessageId: String,
    val started: Deferred<Boolean>,
    val uiToken: Long,
)

/**
 * Starts an ordinary Assistant continuation without persisting a synthetic USER message.
 *
 * Every caller creates a fresh Run. Append callers also create a row; same-position replacement
 * callers atomically move only the target row to the fresh Run and preserve every descendant.
 */
internal class StandardGenerationContinuationLauncher(
    private val conversations: ConversationRepository,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val terminalSettlement: GenerationTerminalSettlementController,
    private val boundRunGenerationLauncher: () -> BoundRunGenerationLauncher,
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
    fun launch(
        request: StandardGenerationContinuationRequest,
        state: ConversationGenerationState,
    ): StandardGenerationContinuationLaunch? {
        val uiToken = state.tryAcquireForReplacement() ?: return null
        val runId = idFactory()
        val modelMessageId = request.replacementMessageId ?: request.modelMessageId ?: idFactory()
        val started = CompletableDeferred<Boolean>()
        val generationJob = state.launchGenerationJob(
            uiToken = uiToken,
            suppressAutomaticQueueDrain = request.queueDrainRequiresSuccess,
            retainHandoff = request.continuesAfterSuccess,
        ) generation@{
            var runCreated = false
            var runBound = false
            var stopFinalizationClaimed = false

            suspend fun reconcileCommittedRun(): Boolean =
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (!runCreated) runCreated = conversations.getRun(runId) != null
                    runCreated
                }

            try {
                if (!state.acquireGenerationAdmission(uiToken, executionCoordinator)) return@generation
                val persistId = state.nextPersistId()
                val launchAtBoundary: suspend () -> Unit = boundary@{
                    if (!state.isCurrentToken(uiToken)) return@boundary
                    val parent = conversations.getMessage(request.parentMessageId)
                        ?: return@boundary
                    val selectedChildren =
                        conversations.restoreBranchSelections(request.conversationId)
                    if (
                        request.replacementMessageId == null &&
                        selectedChildren[parent.id] != null
                    ) return@boundary
                    val generationSnapshot = request.snapshot.copy(runId = runId)
                    val messageId = modelMessageId
                    val startTime = maxOf(clock(), parent.timestamp + 1)
                    val modelEntity: MessageEntity
                    val messageSelections: Map<String?, String>
                    if (request.replacementMessageId != null) {
                        val target = conversations.getMessage(request.replacementMessageId)
                            ?: return@boundary
                        if (target.parentId != parent.id) return@boundary
                        modelEntity = conversations.beginRecompactContextCompact(
                            replacementRun = RunEntity(
                                id = runId,
                                conversationId = request.conversationId,
                                parentRunId = parent.runId,
                                status = RunStatus.ACTIVE,
                                activeSlot = 1,
                                startedAt = startTime,
                                lastCheckpointAt = startTime,
                            ),
                            messageId = target.id,
                            modelName = generationSnapshot.selectedModelId,
                            expectedSelections = selectedChildren,
                        )
                        messageSelections = selectedChildren
                    } else {
                        modelEntity = MessageEntity(
                            id = messageId,
                            conversationId = request.conversationId,
                            parentId = parent.id,
                            text = "",
                            status = MessageStatus.SENDING,
                            participant = Participant.MODEL,
                            timestamp = startTime,
                            modelName = generationSnapshot.selectedModelId,
                            runId = runId,
                            runSequence = 0,
                        )
                        val graphCommit = conversations.createRunWithMessages(
                            run = RunEntity(
                                id = runId,
                                conversationId = request.conversationId,
                                parentRunId = parent.runId,
                                status = RunStatus.ACTIVE,
                                activeSlot = 1,
                                startedAt = startTime,
                                lastCheckpointAt = startTime,
                            ),
                            messages = listOf(modelEntity),
                            messageSelectionUpdates = mapOf(parent.id to messageId),
                            conversationModelId = request.conversationModelId,
                            touchConversationOnAdmission = request.touchConversationOnAdmission,
                        )
                        messageSelections = graphCommit.messageSelections
                    }
                    runCreated = true
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
                        return@boundary
                    }

                    val placeholder = toUiMessage(modelEntity)
                    state.streamUpdate(uiToken, placeholder)
                    if (isConversationOpen(request.conversationId)) {
                        projectGraph(
                            request.conversationId,
                            listOf(placeholder),
                            messageSelections,
                            placeholder,
                        )
                    }
                    started.complete(true)
                    boundRunGenerationLauncher().launch(
                        BoundRunGenerationRequest(
                            conversationId = request.conversationId,
                            modelMessageId = messageId,
                            startTime = modelEntity.timestamp,
                            snapshot = generationSnapshot,
                            uiToken = uiToken,
                            persistId = persistId,
                            runId = runId,
                            pass = 0,
                            requestKind = request.requestKind,
                            continuesAfterSuccess = request.continuesAfterSuccess,
                            transformFinalText = request.transformFinalText,
                        ),
                        state,
                    )
                    if (request.queueDrainRequiresSuccess) {
                        val terminalStatus = conversations.getMessage(messageId)?.status
                        if (terminalStatus == MessageStatus.SUCCESS) {
                            state.cancelDeferredQueueDrain()
                        }
                    }
                }
                if (request.alreadyHoldsConversationLock) {
                    launchAtBoundary()
                } else {
                    executionCoordinator.withConversationLock(request.conversationId) {
                        launchAtBoundary()
                    }
                }
            } catch (error: CancellationException) {
                if (reconcileCommittedRun() && !runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        val binding = state.bindPersistedRun(uiToken, runId)
                        stopFinalizationClaimed =
                            terminalSettlement.settleCancelledDurableRun(state, binding)
                        if (!stopFinalizationClaimed) {
                            conversations.finishStoppedGeneration(emptyList(), runId)
                        }
                    }
                }
                throw error
            } catch (error: Exception) {
                if (reconcileCommittedRun() && !runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        val binding = state.bindPersistedRun(uiToken, runId)
                        runBound = binding is ConversationGenerationState.RunBindingOutcome.Active
                        if (binding is ConversationGenerationState.RunBindingOutcome.Stopping) {
                            stopFinalizationClaimed = true
                            terminalSettlement.settleLateBoundStop(state, binding)
                        }
                    }
                }
                terminalSettlement.failGenerationSetup(
                    conversationId = request.conversationId,
                    runId = runId,
                    modelMessageId = modelMessageId,
                    uiToken = uiToken,
                    state = state,
                    error = error,
                )
            }
        } ?: return null
        generationJob.invokeOnCompletion { started.complete(false) }
        return StandardGenerationContinuationLaunch(
            job = generationJob,
            modelMessageId = modelMessageId,
            started = started,
            uiToken = uiToken,
        )
    }
}
