package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.NewChatPersistEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.RunEffect
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Complete immutable input for executing one reducer-authorized accepted-input effect. */
internal data class DirectAcceptedInputRequest(
    val inputEffect: RunEffect.PersistAcceptedInput,
    val wasNewChat: Boolean,
    val newConversation: ChatEntity?,
    val userText: String,
    val payload: MessagePayloadBuilder.MessagePayload,
    val modelId: String,
    val requestKind: String,
    val touchConversationOnAdmission: Boolean,
    val newConversationSettings: ConversationSettings? = null,
    val newChatPersistSnapshot: NewChatPersistEntity? = null,
    val alreadyHoldsLock: Boolean,
    /** Client that issued this Send; null for automatic sends (queue drain, Loop cycle). */
    val origin: ChatClient?,
    val requestScroll: (conversationId: String, messageId: String) -> Unit,
    val onAccepted: suspend (SendAcceptance) -> Unit,
    val onModelMessageCreated: ((String) -> Unit)?,
    val generationSnapshot: GenerationAdmissionSnapshot? = null,
    val originNewChatEntryId: Long? = null,
) {
    val conversationId: String get() = inputEffect.identity.conversationId
    val runId: String get() = inputEffect.identity.runId
    val uiToken: Long get() = inputEffect.identity.ownerToken

    init {
        require(inputEffect.identity.pass == 0)
        require(modelId.isNotBlank())
        require(requestKind.isNotBlank())
        require(newConversation == null || newConversation.id == conversationId)
        require(wasNewChat == (newConversation != null))
        require(wasNewChat == (originNewChatEntryId != null))
        require(wasNewChat || newChatPersistSnapshot == null)
        // A New Chat belongs to the client that sent it; only that client can open the result.
        require(!wasNewChat || origin != null)
        generationSnapshot?.let { snapshot ->
            require(snapshot.conversationId == conversationId)
            require(snapshot.runId == runId)
            require(snapshot.selectedModelId == modelId)
        }
    }
}

/**
 * Call-scoped handle returned immediately after the runtime host accepts the generation Job.
 * The executor retains neither field after [launch] returns.
 */
internal class DirectAcceptedInputExecution(
    val job: Job?,
    private val durableAcceptance: CompletableDeferred<SendAcceptance?>,
) {
    suspend fun awaitAcceptance(): SendAcceptance? = durableAcceptance.await()
}

/**
 * Executes one identified [RunEffect.PersistAcceptedInput] after Send admission selected Direct.
 *
 * This executor owns no RunState, mailbox, scope, Job field, guidance lease, overlay, or next-stage
 * policy. Room commit is the durable boundary. The matching identified result is delivered to the
 * call-scoped runtime host, and only an Active binding permits Compact/provider execution through
 * the existing downstream ports.
 */
internal class DirectAcceptedInputEffectExecutor(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val graphWriter: AcceptedInputGraphWriter,
    private val clients: ChatClients,
    private val requestBuilder: GenerationRequestBuilder,
    private val terminalSettlement: GenerationTerminalSettlementController,
    private val boundRunGenerationLauncher: BoundRunGenerationLauncher,
    private val acceptanceNotifier: SendAcceptanceNotifier,
    private val toUiMessage: (MessageEntity) -> ChatMessage,
    private val onUserMessagePersisted: (messageId: String, text: String) -> Unit,
    /** Title generation for a New Chat send; its notices go to the send's origin client. */
    private val onGenerateTitle: (conversationId: String, origin: ChatClient?) -> Unit,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun launch(
        request: DirectAcceptedInputRequest,
        state: ConversationGenerationState,
    ): DirectAcceptedInputExecution {
        check(state.conversationId == request.conversationId)
        val acceptance = CompletableDeferred<SendAcceptance?>()
        val job = state.launchGenerationJob(request.uiToken) generation@ {
            executeInGenerationJob(request, state, acceptance)
        }
        if (job == null) {
            acceptance.complete(null)
        } else {
            job.invokeOnCompletion { acceptance.complete(null) }
        }
        return DirectAcceptedInputExecution(job, acceptance)
    }

    private suspend fun executeInGenerationJob(
        request: DirectAcceptedInputRequest,
        state: ConversationGenerationState,
        durableAcceptance: CompletableDeferred<SendAcceptance?>,
    ) {
        val startedNs = System.nanoTime()
        var stageNs = startedNs
        var previousStage = "begin"
        fun markStage(name: String) {
            val now = System.nanoTime()
            DebugLog.sendStage(
                runId = request.runId,
                component = "input",
                stage = name,
                elapsedMs = (now - startedNs) / 1_000_000L,
                previous = previousStage,
                previousMs = (now - stageNs) / 1_000_000L,
            )
            previousStage = name
            stageNs = now
        }
        val persistId = state.nextPersistId()
        var runBound = false
        var bindingOutcome: ConversationGenerationState.RunBindingOutcome =
            ConversationGenerationState.RunBindingOutcome.Rejected
        var inputGraphCommitted = false
        var titleGenerationEnabled = request.generationSnapshot?.titleGenerationEnabled == true
        var titleGenerationStarted = false
        var newConversationTransferAttempted = false
        var newConversationSelectionAttempted = false
        var newConversationSelected = false
        var newConversationPresentationPublished = false
        val userMessageId = idFactory()
        val modelMessageId = idFactory()
        var roomProjectionFences: ChatClientRoomFences? = null

        suspend fun applyCommittedNewConversationStateIfNeeded() {
            if (request.wasNewChat && !newConversationTransferAttempted) {
                newConversationTransferAttempted = true
                try {
                    checkNotNull(request.origin)
                        .applyCommittedNewConversationState(request.conversationId)
                } catch (error: Exception) {
                    runCatching {
                        DebugLog.w(
                            "AcceptedInputExecutor",
                            "Deferred New Chat settings transfer for ${request.conversationId}",
                            error,
                        )
                    }
                }
            }
        }
        suspend fun publishNewChatIfNeeded(acceptance: SendAcceptance.Direct): Boolean {
            if (!request.wasNewChat) return false
            if (!newConversationSelectionAttempted) {
                newConversationSelected = checkNotNull(request.origin).publishAcceptedNewConversation(
                    request.conversationId,
                    request.modelId,
                    checkNotNull(request.originNewChatEntryId),
                )
                newConversationSelectionAttempted = true
            }
            if (newConversationSelected && !newConversationPresentationPublished) {
                acceptanceNotifier.publish(acceptance, request.origin)
                newConversationPresentationPublished = true
            }
            return newConversationSelected
        }

        fun generateNewConversationTitleIfNeeded() {
            if (request.wasNewChat && titleGenerationEnabled && !titleGenerationStarted) {
                titleGenerationStarted = true
                onGenerateTitle(request.conversationId, request.origin)
            }
        }

        suspend fun reconcileCommittedInput(): Boolean = withContext(NonCancellable) {
            if (!inputGraphCommitted) {
                inputGraphCommitted = conversations.getRun(request.runId) != null
            }
            if (!inputGraphCommitted) return@withContext false
            applyCommittedNewConversationStateIfNeeded()
            if (bindingOutcome is ConversationGenerationState.RunBindingOutcome.Rejected) {
                bindingOutcome = state.finishInputPersistence(request.inputEffect.identity)
                runBound = bindingOutcome is ConversationGenerationState.RunBindingOutcome.Active
            }
            if (!durableAcceptance.isCompleted) {
                val accepted = SendAcceptance.Direct(userMessageId, request.conversationId)
                acceptanceNotifier.notify(
                    accepted,
                    request.onAccepted,
                    origin = request.origin,
                    publishEvent = !request.wasNewChat,
                )
                durableAcceptance.complete(accepted)
                runCatching { request.onModelMessageCreated?.invoke(modelMessageId) }
            }
            publishNewChatIfNeeded(SendAcceptance.Direct(userMessageId, request.conversationId))
            generateNewConversationTitleIfNeeded()
            true
        }

        try {
            markStage(if (request.alreadyHoldsLock) "lock-already-owned" else "await-conversation-lock")
            withOptionalLock(request.conversationId, request.alreadyHoldsLock) generationLock@ {
                if (!state.acquireGenerationAdmission(request.uiToken, executionCoordinator)) return@generationLock
                markStage("resolve-generation-snapshot")
                val generationSnapshot = request.generationSnapshot
                    ?: requestBuilder.captureAdmissionSnapshot(
                        conversationId = request.conversationId,
                        runId = request.runId,
                        modelId = request.modelId,
                        conversationOverride = request.newConversation,
                        conversationSettingsOverride = request.newConversationSettings,
                        automationCaller = request.requestKind == "task" || request.requestKind == "loop",
                    )
                titleGenerationEnabled = generationSnapshot.titleGenerationEnabled
                markStage("commit-message-graph")
                val graphCommit = graphWriter.commit(
                    request = AcceptedInputGraphWriter.Request(
                        inputEffect = request.inputEffect,
                        userMessageId = userMessageId,
                        modelMessageId = modelMessageId,
                        userText = request.userText,
                        images = request.payload.allImages,
                        attachmentMeta = request.payload.attachmentMeta
                            ?.let(Json::encodeToString),
                        modelId = generationSnapshot.selectedModelId,
                        userTimestamp = clock(),
                        touchConversationOnAdmission = request.touchConversationOnAdmission,
                        newConversation = request.newConversation,
                        newConversationSettings = request.newConversationSettings,
                        newChatPersistSnapshot = request.newChatPersistSnapshot,
                        source = MessageSource.forAutomationRequestKind(request.requestKind),
                    ),
                    beforeRoomCommit = {
                        if (!request.wasNewChat) {
                            roomProjectionFences =
                                clients.beginRoomProjectionFences(request.conversationId)
                        }
                    },
                )
                val userEntity = graphCommit.userMessage
                val modelEntity = graphCommit.modelMessage
                inputGraphCommitted = true
                markStage("graph-committed")

                // Room already committed. A caller cancellation cannot undo this acknowledgement.
                withContext(NonCancellable) {
                    markStage("apply-new-chat-state")
                    applyCommittedNewConversationStateIfNeeded()
                    markStage("bind-run")
                    bindingOutcome = state.finishInputPersistence(request.inputEffect.identity)
                    runBound = bindingOutcome is ConversationGenerationState.RunBindingOutcome.Active
                    markStage("persist-acceptance-bookkeeping")
                    notifyPersistedUser(userMessageId, request.userText)
                    val accepted = SendAcceptance.Direct(userMessageId, request.conversationId)
                    markStage("notify-acceptance")
                    acceptanceNotifier.notify(
                        accepted,
                        request.onAccepted,
                        origin = request.origin,
                        publishEvent = !request.wasNewChat,
                    )
                    durableAcceptance.complete(accepted)
                    markStage("acceptance-delivered")
                    runCatching { request.onModelMessageCreated?.invoke(modelMessageId) }
                        .onFailure { error ->
                            DebugLog.w(
                                "AcceptedInputExecutor",
                                "Failed to report created model row $modelMessageId",
                                error,
                            )
                        }

                    markStage("publish-new-chat")
                    if (
                        request.wasNewChat &&
                        publishNewChatIfNeeded(accepted)
                    ) {
                        request.requestScroll(request.conversationId, userMessageId)
                    }

                    markStage("publish-message-projection")
                    val placeholder = toUiMessage(modelEntity)
                    if (runBound) {
                        state.loadingChange(request.uiToken, true)
                        state.streamUpdate(request.uiToken, placeholder)
                    }
                    if (clients.isConversationOpen(request.conversationId)) {
                        if (!request.wasNewChat) {
                            request.requestScroll(request.conversationId, userMessageId)
                        }
                        clients.commitGraph(
                            conversationId = request.conversationId,
                            committedMessages = listOf(
                                toUiMessage(userEntity),
                                if (runBound) placeholder
                                else placeholder.copy(status = MessageStatus.STOPPED),
                            ),
                            selectedChildren = graphCommit.messageSelections,
                            streamingMessage = if (runBound) placeholder else null,
                            fences = roomProjectionFences,
                        )
                        roomProjectionFences = null
                    }
                    roomProjectionFences?.let(clients::releaseRoomProjectionFences)
                    roomProjectionFences = null
                    generateNewConversationTitleIfNeeded()
                }

                if (!runBound) {
                    markStage("settle-unbound-run")
                    val stopping = bindingOutcome as?
                        ConversationGenerationState.RunBindingOutcome.Stopping
                    if (stopping != null) {
                        terminalSettlement.settleLateBoundStop(state, stopping)
                    } else {
                        withContext(NonCancellable) {
                            conversations.finishStoppedGeneration(emptyList(), request.runId)
                        }
                    }
                    return@generationLock
                }
                markStage("execute-generation")
                boundRunGenerationLauncher.launch(
                    BoundRunGenerationRequest(
                        conversationId = request.conversationId,
                        modelMessageId = modelMessageId,
                        startTime = modelEntity.timestamp,
                        snapshot = generationSnapshot,
                        uiToken = request.uiToken,
                        persistId = persistId,
                        runId = request.runId,
                        pass = 0,
                        requestKind = request.requestKind,
                    ),
                    state,
                )
                markStage("generation-returned")
            }
        } catch (error: CancellationException) {
            markStage("cancelled")
            if (
                !runBound &&
                bindingOutcome is ConversationGenerationState.RunBindingOutcome.Rejected
            ) {
                withContext(NonCancellable) {
                    if (reconcileCommittedInput()) {
                        val claimed = terminalSettlement.settleCancelledDurableRun(
                            state,
                            bindingOutcome,
                        )
                        if (!claimed) {
                            conversations.finishStoppedGeneration(emptyList(), request.runId)
                        }
                    }
                }
            }
            throw error
        } catch (error: Exception) {
            markStage("failed")
            val durable = reconcileCommittedInput()
            if (!durable) {
                withContext(NonCancellable) {
                    runCatching {
                        state.commands.inputPersistenceFailed(request.inputEffect.identity)
                    }
                }
            } else {
                val stopping = bindingOutcome as?
                    ConversationGenerationState.RunBindingOutcome.Stopping
                if (stopping != null) {
                    terminalSettlement.settleLateBoundStop(state, stopping)
                }
            }
            terminalSettlement.failGenerationSetup(
                conversationId = request.conversationId,
                runId = request.runId,
                modelMessageId = modelMessageId,
                uiToken = request.uiToken,
                state = state,
                error = error,
            )
        } finally {
            markStage("release-input")
            roomProjectionFences?.let(clients::releaseRoomProjectionFences)
            if (!durableAcceptance.isCompleted) durableAcceptance.complete(null)
            markStage("finished")
        }
    }

    private suspend fun <T> withOptionalLock(
        conversationId: String,
        alreadyHoldsLock: Boolean,
        block: suspend () -> T,
    ): T = if (alreadyHoldsLock) block()
    else executionCoordinator.withConversationLock(conversationId, block)

    private suspend fun notifyPersistedUser(messageId: String, text: String) {
        if (text.isNotBlank()) {
            runCatching { onUserMessagePersisted(messageId, text) }
                .onFailure { error ->
                    DebugLog.w(
                        "AcceptedInputExecutor",
                        "Failed to enqueue user-message indexing for $messageId",
                        error,
                    )
                }
        }
        try {
            settings.incrementMessagesSent()
        } catch (error: Exception) {
            DebugLog.w(
                "AcceptedInputExecutor",
                "Failed to increment the sent-message counter",
                error,
            )
        }
    }
}
