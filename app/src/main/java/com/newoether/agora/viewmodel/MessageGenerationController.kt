package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.NewChatPersistEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Adapts message commands (send / regenerate / edit / delete) to their typed services and the
 * conversation runtime. Durable accepted-input execution is delegated after mailbox admission.
 *
 * Generation state is held per-conversation in [ConversationGenerationState]
 * (obtained from [ConversationStateRegistry]). Graph projections fan out through [ChatClients] to
 * every attached client that has the conversation open, and visibility means any attached client
 * can see it, so a background generation never writes into a client showing another
 * conversation.
 */
internal class MessageGenerationController(
    private val scope: CoroutineScope,
    private val application: Application,
    private val appContext: Context,
    // -- Process-scoped collaborators --
    private val convRepo: ConversationRepository,
    private val settings: SettingsRepository,
    private val registry: ConversationStateRegistry,
    private val generationManagerProvider: () -> GenerationManager,
    private val requestBuilder: GenerationRequestBuilder,
    private val payloadBuilder: MessagePayloadBuilder,
    private val providerRegistry: ProviderRegistry,
    private val localProvider: LocalProvider,
    private val executionCoordinator: ConversationExecutionCoordinator,
    /** Every attached client; conversation-scoped projections and visibility go through it. */
    private val clients: ChatClients,
    // -- Runtime-wide messages (not tied to one command's origin client) --
    private val onSnackbar: (String) -> Unit,
    // Called once when a hidden task/loop execution becomes searchable. The callback
    // only enqueues background work; embedding computation must not run under the send lock.
    // Called after a USER message row is persisted (send / edit), so incremental RAG
    // indexing covers the user's side too -the model reply is indexed at generation end
    // via GenerationManager.onMessagePersisted, and without this hook user messages only
    // ever entered the cache through a manual full re-cache. Enqueues background work only.
    private val onUserMessagePersisted: (messageId: String, text: String) -> Unit = { _, _ -> },
    private val pauseConversationTasks: suspend (String) -> Unit = {},
) {
    private val titleGenerator = ConversationTitleGenerator(convRepo, settings, providerRegistry)
    private val contextCompactor = ContextCompactor(
        conversations = convRepo,
        appContext = appContext,
        generationErrorFormatter = { raw ->
            normalizePersistedGenerationErrorText(appContext, raw)
        },
    )
    private val terminalSettlement = GenerationTerminalSettlementController(
        conversations = convRepo,
        stopFinalizer = GenerationFinalizer(convRepo) { _, _ -> },
        runFinalizationEffects = RunFinalizationEffectCoordinator(),
        failureText = { appContext.getString(R.string.failed_to_generate) },
        toUiMessage = { it.toUiChatMessage(appContext) },
        onSnackbar = onSnackbar,
        isConversationVisible = ::isConversationVisible,
        onTerminalNotification = { text, conversationId, status ->
            generationManagerProvider().showTerminalNotification(text, conversationId, status)
        },
    )
    private val boundRunGenerationLauncher = BoundRunGenerationLauncher(
        conversations = convRepo,
        generationManagerProvider = generationManagerProvider,
        automaticCompactNeeded = contextCompactor::automaticNeeded,
        terminalSettlement = terminalSettlement,
        toUiMessage = { it.toUiChatMessage(appContext) },
        isConversationVisible = ::isConversationVisible,
        onAutomaticCompactContinuation = ::scheduleAutomaticCompactContinuation,
    )
    private val standardContinuationLauncher = StandardGenerationContinuationLauncher(
        conversations = convRepo,
        executionCoordinator = executionCoordinator,
        terminalSettlement = terminalSettlement,
        boundRunGenerationLauncher = { boundRunGenerationLauncher },
        toUiMessage = { it.toUiChatMessage(appContext) },
        isConversationOpen = clients::isConversationOpen,
        projectGraph = { conversationId, committedMessages, selectedChildren, streamingMessage ->
            clients.commitGraph(
                conversationId = conversationId,
                committedMessages = committedMessages,
                selectedChildren = selectedChildren,
                streamingMessage = streamingMessage,
            )
        },
    )
    private val compactController = ConversationCompactController(
        conversations = convRepo,
        operation = contextCompactor,
        requestBuilder = requestBuilder,
        generationManagerProvider = generationManagerProvider,
        continuationLauncher = { standardContinuationLauncher },
        onCompactStarted = { conversationId, messageId ->
            requestScroll(conversationId, messageId, attachedOnly = true, origin = null)
        },
    )
    private val acceptanceNotifier = SendAcceptanceNotifier(clients)
    private val directAcceptedInputExecutor = DirectAcceptedInputEffectExecutor(
        conversations = convRepo,
        settings = settings,
        executionCoordinator = executionCoordinator,
        graphWriter = AcceptedInputGraphWriter(convRepo),
        clients = clients,
        requestBuilder = requestBuilder,
        terminalSettlement = terminalSettlement,
        boundRunGenerationLauncher = boundRunGenerationLauncher,
        acceptanceNotifier = acceptanceNotifier,
        toUiMessage = { it.toUiChatMessage(appContext) },
        onUserMessagePersisted = onUserMessagePersisted,
        onGenerateTitle = ::generateTitle,
    )
    private val queuedGuidanceDrainExecutor = QueuedGuidanceDrainExecutor(
        conversations = convRepo,
        settings = settings,
        requestBuilder = requestBuilder,
        executionCoordinator = executionCoordinator,
        terminalSettlement = terminalSettlement,
        boundRunGenerationLauncher = boundRunGenerationLauncher,
        toUiMessage = { it.toUiChatMessage(appContext) },
        isConversationOpen = clients::isConversationOpen,
        projectGraph = { conversationId, committedMessages, selectedChildren, streamingMessage ->
            clients.commitGraph(
                conversationId = conversationId,
                committedMessages = committedMessages,
                selectedChildren = selectedChildren,
                streamingMessage = streamingMessage,
            )
        },
        onScrollToAbsoluteBottomAfter = { conversationId, messageId ->
            requestScroll(conversationId, messageId, attachedOnly = false, origin = null)
        },
        onUserMessagePersisted = onUserMessagePersisted,
    )
    private val editService = ConversationEditService(
        conversations = convRepo,
        requestBuilder = requestBuilder,
        executionCoordinator = executionCoordinator,
        inputCloner = EditedRunInputCloner(
            java.io.File(application.filesDir, "run-inputs"),
        ),
        terminalSettlement = terminalSettlement,
        boundRunGenerationLauncher = boundRunGenerationLauncher,
        guidanceDrain = queuedGuidanceDrainExecutor,
        toUiMessage = { it.toUiChatMessage(appContext) },
        isConversationOpen = clients::isConversationOpen,
        projectGraph = { conversationId, committedMessages, selectedChildren, streamingMessage ->
            clients.commitGraph(
                conversationId = conversationId,
                committedMessages = committedMessages,
                selectedChildren = selectedChildren,
                streamingMessage = streamingMessage,
            )
        },
        onUserMessagePersisted = onUserMessagePersisted,
    )
    private val regenerationService = ConversationRegenerationService(
        conversations = convRepo,
        requestBuilder = requestBuilder,
        executionCoordinator = executionCoordinator,
        terminalSettlement = terminalSettlement,
        boundRunGenerationLauncher = boundRunGenerationLauncher,
        guidanceDrain = queuedGuidanceDrainExecutor,
        toUiMessage = { it.toUiChatMessage(appContext) },
        isConversationOpen = clients::isConversationOpen,
        projectGraph = { conversationId, committedMessages, selectedChildren, streamingMessage ->
            clients.commitGraph(
                conversationId = conversationId,
                committedMessages = committedMessages,
                selectedChildren = selectedChildren,
                streamingMessage = streamingMessage,
            )
        },
    )
    private val branchMutationService = ConversationBranchMutationService(
        scope = scope,
        conversations = convRepo,
        executionCoordinator = executionCoordinator,
        toUiMessage = { it.toUiChatMessage(appContext) },
        isConversationOpen = clients::isConversationOpen,
        projectGraph = { conversationId, all, selected ->
            clients.replaceGraph(conversationId, allMessages = all, selectedChildren = selected)
        },
    )

    /** Scroll effect for [origin], or for every client showing the conversation when null. */
    private fun requestScroll(
        conversationId: String,
        messageId: String,
        attachedOnly: Boolean,
        origin: ChatClient?,
    ) {
        clients.effectTargets(conversationId, origin).forEach { client ->
            client.requestScrollToBottomAfter(conversationId, messageId, attachedOnly)
        }
    }

    private fun isConversationVisible(conversationId: String): Boolean =
        clients.isConversationVisible(conversationId)

    suspend fun compactManual(conversationId: String?, request: CompactRequest): CompactResult {
        conversationId ?: return CompactResult.Failed(CompactFailureReason.OPEN_CONVERSATION)
        return compactController.manual(
            conversationId = conversationId,
            request = request,
            state = registry.getOrCreate(conversationId),
        )
    }

    // ==================================
    // deleteMessage
    // ==================================

    /**
     * Deletes one structural message branch. A USER target removes its complete edit subtree; a
     * MODEL target removes its regeneration subtree while retaining the shared boundary USER.
     * ACTIVE and STOPPING both reject deletion; Stop is never an implicit side effect.
     */
    fun deleteMessage(
        origin: ChatClient,
        conversationId: String?,
        messageId: String,
        snapshot: List<ChatMessage>,
        onResult: ((Boolean) -> Unit)? = null,
    ): Int {
        val currentId = conversationId ?: run {
            onResult?.invoke(false)
            return 0
        }
        val state = registry.getOrCreate(currentId)
        return branchMutationService.delete(
            conversationId = currentId,
            messageId = messageId,
            state = state,
            snapshot = snapshot,
            origin = origin,
            onResult = onResult,
        )
    }

    // ==================================
    // regenerate
    // ==================================

    fun regenerate(
        origin: ChatClient,
        conversationId: String?,
        messageId: String,
        modelId: String,
        visiblePath: List<ChatMessage>,
    ): Boolean {
        val genId = conversationId ?: return false
        val state = registry.getOrCreate(genId)
        return regenerationService.regenerate(
            ConversationRegenerationRequest(
                conversationId = genId,
                messageId = messageId,
                modelId = modelId,
                visiblePath = visiblePath,
                origin = origin,
            ),
            state,
        )
    }

    // ==================================
    // editMessage
    // ==================================

    suspend fun editMessage(
        origin: ChatClient,
        conversationId: String?,
        messageId: String,
        newText: String,
        modelId: String,
        visiblePath: List<ChatMessage>,
    ): Boolean = withContext(Dispatchers.Default) {
        editMessageOffMain(origin, conversationId, messageId, newText, modelId, visiblePath)
    }

    private suspend fun editMessageOffMain(
        origin: ChatClient,
        conversationId: String?,
        messageId: String,
        newText: String,
        modelId: String,
        visiblePath: List<ChatMessage>,
    ): Boolean {
        if (newText.isBlank()) return false
        val genId = conversationId ?: return false
        val state = registry.getOrCreate(genId)
        requestBuilder.awaitProviderKey(modelId, origin::showSnackbar) ?: return false
        return editService.edit(
            ConversationEditRequest(
                conversationId = genId,
                messageId = messageId,
                newText = newText,
                modelId = modelId,
                visiblePath = visiblePath,
                origin = origin,
            ),
            state,
        )
    }

    // ==================================
    // sendMessage
    // ==================================

    /**
     * Captures a send target from the sending client's own state: the conversation it shows
     * ([currentId]), whether it shows its New Chat page, that page's entry, and its active model.
     */
    internal fun captureForegroundSendTarget(
        ownerId: String,
        currentId: String?,
        isNewChatMode: Boolean,
        newChatEntryId: Long,
        modelId: String,
        captureNewChatWorkspace: () -> NewChatWorkspaceSnapshot,
    ): ForegroundSendTarget? {
        val wasNewChat = ownerId == NEW_CHAT_WORKSPACE_ID
        if (wasNewChat) {
            if (!isNewChatMode || currentId != null) return null
        } else if (isNewChatMode || currentId != ownerId) {
            return null
        }
        return ForegroundSendTarget(
            ownerId = ownerId,
            conversationId = if (wasNewChat) UUID.randomUUID().toString() else ownerId,
            runId = UUID.randomUUID().toString(),
            wasNewChat = wasNewChat,
            newChatEntryId = newChatEntryId.takeIf { wasNewChat },
            modelId = modelId,
            newChatWorkspace = if (wasNewChat) captureNewChatWorkspace() else null,
        )
    }

    internal suspend fun prepareForegroundSend(
        target: ForegroundSendTarget,
        composer: ConversationComposerSnapshot,
        origin: ChatClient,
    ): ForegroundSendAdmission? =
        requestBuilder.prepareForegroundSend(target, composer, application, origin::showSnackbar)

    internal suspend fun sendMessage(
        admission: ForegroundSendAdmission,
        text: String,
        attachments: List<SelectedAttachment>,
        onAccepted: suspend (SendAcceptance) -> Unit,
        origin: ChatClient,
    ): SendAcceptance? = withContext(Dispatchers.Default) {
        val target = admission.target
        val startedNs = System.nanoTime()
        fun markStage(name: String) {
            com.newoether.agora.util.DebugLog.sendStage(
                runId = target.runId,
                component = "send",
                stage = name,
                elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L,
            )
        }
        markStage("start")
        if (!target.wasNewChat) {
            val state = registry.getOrCreate(target.conversationId)
            if (!state.generating.value) {
                val snapshot = admission.generationSnapshot
                markStage("fixed-context-cost")
                val fixedTokenCost = generationManagerProvider().resolvedFixedContextTokenCost(
                    snapshot.config,
                    snapshot.context,
                )
                markStage("automatic-compact")
                when (
                    val compact = compactController.startAutomaticBeforeSend(
                        conversationId = target.conversationId,
                        contextLimit = snapshot.config.maxContextWindow,
                        config = snapshot.automaticCompact.copy(
                            fixedTokenCost = fixedTokenCost,
                            mainModelId = snapshot.config.modelId,
                            includeAssistantReasoning = generationManagerProvider()
                                .includesAssistantReasoning(snapshot.config, snapshot.context),
                        ),
                        state = state,
                    )
                ) {
                    is CompactResult.Failed -> {
                        origin.showSnackbar(compactFailureMessage(appContext, compact))
                        return@withContext null
                    }
                    is CompactResult.Stopped -> return@withContext null
                    CompactResult.NotNeeded,
                    is CompactResult.Created -> Unit
                }
            }
        }
        markStage("placement")
        sendInto(
            genId = target.conversationId,
            wasNewChat = target.wasNewChat,
            newConversation = admission.newConversation,
            text = text,
            attachments = attachments,
            modelId = admission.generationSnapshot.selectedModelId,
            touchConversationOnAdmission = true,
            onAccepted = onAccepted,
            origin = origin,
            newConversationSettings = admission.newConversationSettings,
            newChatPersistSnapshot = admission.newChatPersistSnapshot,
            proposedRunId = target.runId,
            admissionSnapshot = admission.generationSnapshot,
            originNewChatEntryId = target.newChatEntryId,
        )
    }

    internal suspend fun drainQueuedAfterGeneration(state: ConversationGenerationState) {
        queuedGuidanceDrainExecutor.drainAfterSettlement(state)
    }

    /**
     * Core send into a KNOWN conversation [genId] (never re-reads currentConversationId, so a
     * background send lands in its own conversation). Placement enters the conversation command
     * mailbox: a bound, preparing, or Compact Run accepts memory-only guidance through the same
     * FIFO queue, STOPPING waits for release, and IDLE emits one identified persistence
     * effect before generation launches. The installed Job's completion hook releases the slot and
     * requests queue drain; pre-launch failures release via
     * [QueuedGuidanceDrainExecutor.releaseUnlaunchedSlotAndDrain].
     */
    private suspend fun sendInto(
        genId: String,
        wasNewChat: Boolean,
        newConversation: ChatEntity?,
        text: String,
        attachments: List<SelectedAttachment>,
        modelId: String,
        requestKind: String = "chat",
        touchConversationOnAdmission: Boolean,
        onAccepted: suspend (SendAcceptance) -> Unit,
        /** Client that issued this Send; null for automatic sends (queue drain, Loop cycle). */
        origin: ChatClient?,
        newConversationSettings: ConversationSettings? = null,
        newChatPersistSnapshot: NewChatPersistEntity? = null,
        scrollPolicy: SendScrollPolicy = SendScrollPolicy.FORCE,
        alreadyHoldsLock: Boolean = false,
        directOnly: Boolean = false,
        proposedRunId: String = UUID.randomUUID().toString(),
        admissionSnapshot: GenerationAdmissionSnapshot? = null,
        originNewChatEntryId: Long? = null,
        /** Reports the model row this send created, so an automation caller never has to re-derive
         *  it by scanning the conversation tail (a concurrent branch would win that scan). */
        onModelMessageCreated: ((String) -> Unit)? = null,
        onGenerationJob: ((kotlinx.coroutines.Job?) -> Unit)? = null,
    ): SendAcceptance? {
        val startedNs = System.nanoTime()
        fun markStage(name: String) {
            com.newoether.agora.util.DebugLog.sendStage(
                runId = proposedRunId,
                component = "placement",
                stage = name,
                elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L,
            )
        }
        markStage("runtime-state")
        val state = registry.getOrCreate(genId)
        if (admissionSnapshot == null) {
            val report: (String) -> Unit = { message -> origin?.showSnackbar(message) ?: onSnackbar(message) }
            val providerName = requestBuilder.awaitProviderKey(modelId, report)?.providerName ?: return null
            if (providerName == Constants.PROVIDER_LOCAL) {
                val localModelId = modelId.substringAfter("${Constants.PROVIDER_LOCAL}:")
                val config = settings.localChatModels.value.find { it.modelId == localModelId }
                if (config == null || !java.io.File(config.localFilePath).exists()) {
                    report(application.getString(R.string.local_model_not_found))
                    return null
                }
            }
        } else {
            require(admissionSnapshot.conversationId == genId)
            require(admissionSnapshot.runId == proposedRunId)
            require(admissionSnapshot.selectedModelId == modelId)
        }

        // Attachment IO and transformation completed before submission freeze. This is a pure,
        // ordered projection of the frozen READY artifacts.
        val payload = payloadBuilder.buildComposerPayload(attachments)

        suspend fun enqueueAcceptedGuidance(runId: String): QueuedSend {
            val queued = QueuedSend(
                id = UUID.randomUUID().toString(),
                text = text,
                modelId = modelId,
                attachments = attachments,
                runId = runId,
                preparedImages = payload.allImages,
                preparedAttachmentMetaJson = payload.attachmentMeta?.let(Json::encodeToString),
                generationSnapshot = admissionSnapshot,
            )
            // Guidance acceptance is intentionally memory-only. The current provider pass
            // observes it through hasQueuedSends(), but Room, the selected tree, and LazyColumn
            // cannot expose a bubble before the next durable boundary.
            state.enqueueSend(queued)
            try {
                acceptanceNotifier.notify(
                    acceptance = SendAcceptance.Queued(queued.id, genId),
                    onAccepted = onAccepted,
                    origin = origin,
                )
            } catch (error: Exception) {
                state.removeQueuedSend(queued.id)
                throw error
            }
            return queued
        }

        var placement: SendPlacement? = null
        val sendEffectId = "send-$proposedRunId"
        while (placement == null) {
                markStage("await-queue-lock")
                val decision = state.queueMutationMutex.withLock {
                    val pendingQueue = state.queuedSends.value
                    markStage("await-mailbox")
                    val transition = state.commands.requestSend(
                        proposedRunId = proposedRunId,
                        effectId = sendEffectId,
                        directOnly = directOnly,
                        hasPendingGuidance = pendingQueue.isNotEmpty(),
                    )
                    check(transition.accepted)
                    when (val effect = transition.effects.single()) {
                        is RunEffect.PersistAcceptedInput -> SendPlacement.Direct(
                            uiToken = effect.identity.ownerToken,
                            runId = effect.identity.runId,
                            inputEffect = effect,
                        )
                        is RunEffect.DrainGuidanceFirst -> {
                            // A previously accepted batch may still be waiting for its asynchronous
                            // handoff. Never let a newer Direct send leapfrog the FIFO batch.
                            check(!directOnly)
                            val queued = enqueueAcceptedGuidance(pendingQueue.last().runId)
                            val lease = checkNotNull(state.claimQueuedSends())
                            val claim = queuedGuidanceDrainExecutor.claimUnderLock(state, lease)
                            if (claim != null) {
                                SendPlacement.QueuedAndDrain(queued.id, claim)
                            } else {
                                SendPlacement.Queued(queued.id)
                            }
                        }
                        is RunEffect.AcceptGuidance -> {
                            // Reducer acceptance is the only placement authority. The guidance is
                            // memory-only, so a concurrent Room terminalization cannot attach it to
                            // the old Run. If settlement already won, immediately hand the FIFO
                            // batch back through a fresh normal Send rather than stranding it.
                            val queued = enqueueAcceptedGuidance(effect.identity.runId)
                            if (!state.generating.value) {
                                val lease = checkNotNull(state.claimQueuedSends())
                                val claim = queuedGuidanceDrainExecutor.claimUnderLock(state, lease)
                                if (claim != null) {
                                    SendPlacement.QueuedAndDrain(queued.id, claim)
                                } else {
                                    SendPlacement.Queued(queued.id)
                                }
                            } else {
                                SendPlacement.Queued(queued.id)
                            }
                        }
                        is RunEffect.AwaitRunRelease -> SendPlacement.RetryAfterRelease
                        is RunEffect.RejectSendBusy -> SendPlacement.Rejected
                        else -> error(
                            "SendRequested emitted unexpected effect ${effect.javaClass.simpleName}",
                        )
                    }
                }
                if (decision == SendPlacement.RetryAfterRelease) {
                    markStage("await-run-release")
                    state.awaitSendAvailable()
                } else {
                    placement = decision
                }
            }

        if (placement is SendPlacement.Rejected) {
            markStage("rejected")
            return null
        }
        if (placement is SendPlacement.Queued) {
            markStage("queued")
            return SendAcceptance.Queued(placement.messageId, genId)
        }
        if (placement is SendPlacement.QueuedAndDrain) {
            markStage("queued-drain")
            queuedGuidanceDrainExecutor.launchClaim(state, placement.claim)
            return SendAcceptance.Queued(placement.messageId, genId)
        }
        val direct = placement as SendPlacement.Direct
        markStage("launch-input-job")
        val execution = directAcceptedInputExecutor.launch(
            DirectAcceptedInputRequest(
                inputEffect = direct.inputEffect,
                wasNewChat = wasNewChat,
                newConversation = newConversation,
                userText = text,
                payload = payload,
                modelId = modelId,
                requestKind = requestKind,
                touchConversationOnAdmission = touchConversationOnAdmission,
                newConversationSettings = newConversationSettings,
                newChatPersistSnapshot = newChatPersistSnapshot,
                alreadyHoldsLock = alreadyHoldsLock,
                origin = origin,
                requestScroll = { conversationId, messageId ->
                    requestScroll(
                        conversationId = conversationId,
                        messageId = messageId,
                        attachedOnly = scrollPolicy == SendScrollPolicy.ATTACHED_ONLY,
                        origin = origin,
                    )
                },
                onAccepted = onAccepted,
                onModelMessageCreated = onModelMessageCreated,
                generationSnapshot = admissionSnapshot,
                originNewChatEntryId = originNewChatEntryId,
            ),
            state,
        )
        onGenerationJob?.invoke(execution.job)
        markStage("await-durable-acceptance")
        return execution.awaitAcceptance()
    }

    /**
     * Entry point for loop cycles on the foreground-open conversation.
     *
     * The coordinator lock is already held by `LoopManager.executeByConversationId`, so neither
     * the setup phase nor the generation job re-acquires it. That is only correct because this
     * function SUSPENDS until the generation job completes: the caller's lease therefore spans the
     * whole turn, exactly as it would for a headless run. Returning early would leave the
     * generation running unlocked and would also report success to the Loop before the cycle
     * actually produced anything.
     *
     * `directOnly` is mandatory here, not an optimization. Both alternatives would need the
     * conversation lock this caller already holds:
     *  - waiting on `generating` deadlocks against a manual send that is itself blocked on the
     *    lock, because that send can only release the slot after acquiring it;
     *  - a queued send is answered by a drain that also takes the lock, so this function would
     *    have no job to join and would have to guess an outcome it never observed.
     * [AutomationSendOutcome.SlotBusy] lets the Loop treat the cycle as not-run instead.
     *
     * [AutomationSendOutcome.Delivered.modelMessageId] is the row this send actually created.
     * Callers must never re-derive it by scanning the conversation tail: a concurrent branch or an
     * older run can win that scan and report a previous turn's answer as this cycle's result.
     *
     * Scrolls only when the viewport is attached at the bottom ([SendScrollPolicy.ATTACHED_ONLY])
     * so automated messages never steal the user's scroll position.
     */
    internal suspend fun sendMessageFromAutomationAwaitingCompletion(
        genId: String,
        text: String,
        modelId: String,
        requestKind: String,
    ): AutomationSendOutcome {
        var generationJob: kotlinx.coroutines.Job? = null
        var createdModelMessageId: String? = null
        val acceptance = sendInto(
            genId = genId,
            wasNewChat = false,
            newConversation = null,
            text = text,
            attachments = emptyList(),
            modelId = modelId,
            requestKind = requestKind,
            touchConversationOnAdmission = false,
            onAccepted = {},
            origin = null,
            scrollPolicy = SendScrollPolicy.ATTACHED_ONLY,
            alreadyHoldsLock = true,
            directOnly = true,
            onModelMessageCreated = { createdModelMessageId = it },
            onGenerationJob = { generationJob = it },
        ) ?: return AutomationSendOutcome.SlotBusy
        // directOnly makes Queued unreachable. Assert rather than reporting a cycle this call
        // never actually ran.
        check(acceptance is SendAcceptance.Direct) {
            "A direct-only automation send must never be queued"
        }
        // launchGenerationJob returns null when the slot was revoked between claim and launch (a
        // Stop landing in that window). Nothing generated, so the cycle did not run.
        val job = generationJob ?: return AutomationSendOutcome.SlotBusy
        try {
            job.join()
        } catch (e: CancellationException) {
            // The Loop/Worker lease owns this delegated turn. If its caller is cancelled, do not
            // leave a process-scoped controller job generating outside that released lease.
            job.cancel()
            throw e
        }
        val modelMessageId = createdModelMessageId ?: return AutomationSendOutcome.SlotBusy
        return AutomationSendOutcome.Delivered(modelMessageId)
    }

    private fun scheduleAutomaticCompactContinuation(
        request: AutomaticCompactContinuationRequest,
        state: ConversationGenerationState,
    ) {
        val guidanceClaimRevision = state.guidanceClaimRevision()
        val originOwnerToken = request.generationRequest.uiToken
        state.retainRunHandoff(originOwnerToken)
        // The current Assistant must release first. Suppress exactly that release's ordinary queue
        // drain so the already-decided Compact generation owns the next standard slot.
        state.deferNextQueueDrain()
        state.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                executionCoordinator.withGenerationAdmission(state.conversationId) admission@ {
                    state.awaitSendAvailable()
                    val compactLaunch = compactController.startAutomaticStandard(
                        conversationId = request.generationRequest.conversationId,
                        contextLimit = request.generationRequest.snapshot.config.maxContextWindow,
                        config = request.config,
                        state = state,
                        continuesAfterSuccess = true,
                    ) ?: return@admission
                    try {
                        compactLaunch.job.join()
                        state.awaitSendAvailable()
                        val compactMessageId = compactLaunch.messageId
                        val compactStatus = convRepo.getMessage(compactMessageId)?.status
                        if (!automaticCompactAllowsHandoff(compactStatus)) {
                            state.finishRunHandoff(compactLaunch.uiToken)
                            return@admission
                        }

                        // Guidance inspection and continuation admission share the ordinary queue boundary.
                        val continuation = launchStandardContinuationAfterGuidance(
                            state = state,
                            guidanceClaimRevision = guidanceClaimRevision,
                            handoffOwnerToken = compactLaunch.uiToken,
                        ) {
                            standardContinuationLauncher.launch(
                                request = StandardGenerationContinuationRequest(
                                    conversationId = request.generationRequest.conversationId,
                                    parentMessageId = compactMessageId,
                                    snapshot = request.generationRequest.snapshot,
                                    touchConversationOnAdmission = false,
                                ),
                                state = state,
                            )
                        }
                        continuation?.job?.join()
                    } catch (error: Exception) {
                        state.finishRunHandoff(compactLaunch.uiToken)
                        throw error
                    }
                }
            } finally {
                state.finishRunHandoff(originOwnerToken)
            }
        }
    }

    /** Title notices go to [origin], or runtime-wide when the request has none. */
    fun generateTitle(conversationId: String, origin: ChatClient?) {
        val runtimeSnapshot = registry.get(conversationId)?.generationSnapshot?.value
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            titleGenerator.generateWithNotifications(
                conversationId, settings, appContext, runtimeSnapshot,
            ) { message -> origin?.showSnackbar(message) ?: onSnackbar(message) }
        }
    }
}
