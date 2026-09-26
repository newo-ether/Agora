package com.newoether.agora.automation

import android.app.Application
import android.content.Context
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.AutomaticCompactContinuationRequest
import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.BoundRunGenerationRequest
import com.newoether.agora.viewmodel.AcceptedInputGraphWriter
import com.newoether.agora.viewmodel.ConversationTitleGenerator
import com.newoether.agora.viewmodel.GenerationManager
import com.newoether.agora.viewmodel.GenerationFinalizer
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.ConversationGenerationState
import com.newoether.agora.viewmodel.GenerationRequestBuilder
import com.newoether.agora.viewmodel.ToolRoundBoundaryDecision
import com.newoether.agora.viewmodel.automaticCompactAllowsHandoff
import com.newoether.agora.viewmodel.ProviderRegistry
import com.newoether.agora.viewmodel.RagManager
import com.newoether.agora.viewmodel.RunFinalizationEffectCoordinator
import com.newoether.agora.viewmodel.ShellConfirmationController
import com.newoether.agora.viewmodel.fallbackConversationTitle
import com.newoether.agora.viewmodel.toUiChatMessage
import com.newoether.agora.tool.AskUserToolProvider
import com.newoether.agora.tool.McpToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Headless single-shot generation engine (process-scoped).
 *
 * Drives one complete generation (including the agentic tool loop) for a conversation without
 * depending on a ViewModel or Compose state, while reusing the same [GenerationManager] pipeline
 * as foreground generation. Background Task/Loop runners call [runOnce]; when the conversation is
 * open, the engine attaches to its shared generation state so Stop and queued guidance retain the
 * same ownership semantics as the foreground path.
 *
 * Collaborators are the process-scoped singletons from `AppContainer`, so the
 * background engine shares the live provider map, the on-device llama engine, and
 * the conversation/settings repositories with the UI.
 */
class TaskExecutionEngine(
    private val application: Application,
    private val appContext: Context,
    private val convRepo: ConversationRepository,
    private val settings: SettingsRepository,
    private val memoryManager: MemoryManager,
    private val skillManager: SkillManager,
    private val providerRegistry: ProviderRegistry,
    localProvider: LocalProvider,
    sandboxFactory: SandboxManagerFactory?,
    private val appScope: CoroutineScope,
    private val executionCoordinator: ConversationExecutionCoordinator,
    shellConfirmation: ShellConfirmationController,
    mcpToolProvider: McpToolProvider,
    askUser: AskUserController,
    private val generationRegistry: ConversationStateRegistry,
    private val automationExecutionGate: AutomationExecutionGate = AutomationExecutionGate(),
    private val automationWakeLockOwner: AutomationWakeLockOwner =
        AutomationWakeLockOwner(appContext),
    private val pauseConversationLoop: suspend (String) -> Unit = {},
) {
    sealed interface Result {
        data class Success(val modelMessageId: String, val text: String) : Result
        data class Busy(val reason: String = "Conversation is already generating") : Result
        data class Failure(val reason: String) : Result
    }

    /**
     * Optional bridge that redirects loop cycles on the foreground-open conversation through the
     * regular MessageGenerationController send path (with attached-only scroll) instead of the
     * headless engine path. Set by ChatViewModel when it is constructed; cleared on dispose.
     *
     * Contract: the bridge SUSPENDS until the delegated turn finishes and reports the durable
     * outcome. It must not return as soon as the send is accepted, otherwise the caller's
     * conversation lease would be released while the generation is still running, and the Loop
     * would record the cycle as complete before it produced anything.
     */
    private val foregroundBridgeLock = Any()
    private var foregroundBridgeOwner: Any? = null
    private var foregroundSendBridge: (suspend (
        conversationId: String,
        userText: String,
        modelId: String,
        requestKind: String,
    ) -> BridgeOutcome)? = null

    /** Owner-token binding prevents an older ViewModel's late onCleared from erasing a newer one. */
    fun attachForegroundSendBridge(
        owner: Any,
        bridge: suspend (
            conversationId: String,
            userText: String,
            modelId: String,
            requestKind: String,
        ) -> BridgeOutcome,
    ) = synchronized(foregroundBridgeLock) {
        foregroundBridgeOwner = owner
        foregroundSendBridge = bridge
    }

    fun detachForegroundSendBridge(owner: Any) = synchronized(foregroundBridgeLock) {
        if (foregroundBridgeOwner !== owner) return@synchronized
        foregroundBridgeOwner = null
        foregroundSendBridge = null
    }

    private fun currentForegroundSendBridge() = synchronized(foregroundBridgeLock) {
        foregroundSendBridge
    }

    /** Outcome of a delegated foreground send. [NotDelegated] means the caller must run headlessly. */
    sealed interface BridgeOutcome {
        data object NotDelegated : BridgeOutcome
        data class Busy(val reason: String = "Conversation is already generating") : BridgeOutcome
        data class Completed(val modelMessageId: String, val text: String) : BridgeOutcome
        data class Failed(val reason: String) : BridgeOutcome
    }

    /** Embedding subsystem powering RAG/semantic-search context during generation.
     *  One per engine, mirrors `ChatViewModel.ragManager` but on the app scope. */
    private val ragManager = RagManager(
        conversations = convRepo,
        settings = settings,
        appContext = appContext,
        scope = appScope,
        emitSnackbar = {},
    )
    private val stopFinalizer = GenerationFinalizer(convRepo, ragManager::indexMessageForRag)
    private val runFinalizationEffects = RunFinalizationEffectCoordinator()
    private val titleGenerator = ConversationTitleGenerator(convRepo, settings, providerRegistry)
    private val acceptedInputGraphWriter = AcceptedInputGraphWriter(convRepo)
    private val compactPipeline = HeadlessCompactPipeline(
        conversations = convRepo,
        appContext = appContext,
        executionCoordinator = executionCoordinator,
        generationManagerProvider = { generationManager },
    )
    private val contextCompactor get() = compactPipeline.compactor
    private val terminalSettlement get() = compactPipeline.terminalSettlement
    private val compactController get() = compactPipeline.controller

    /**
     * Task-only post-processing. Loop runs share this engine but never call this method, so a
     * conversation loop cannot repeatedly retitle itself after every cycle.
     */
    suspend fun updateTaskExecutionTitle(conversationId: String, response: String) {
        settings.awaitInitialLoad()
        providerRegistry.awaitInitialSync()
        if (settings.titleGenerationEnabled.value) {
            when (val result = titleGenerator.generateAndPersist(conversationId)) {
                is ConversationTitleGenerator.Result.Success -> return
                is ConversationTitleGenerator.Result.Failure ->
                    DebugLog.w(
                        "TaskExecutionEngine",
                        "Task title generation failed; using response fallback",
                    )
            }
        }
        val fallback = fallbackConversationTitle(response)
        if (fallback.isBlank()) return
        convRepo.getConversation(conversationId)?.let { conversation ->
            convRepo.updateConversationTitleIfUnchanged(
                id = conversationId,
                expectedTitle = conversation.title,
                newTitle = fallback,
            )
        }
    }

    private val generationManager = GenerationManager(
        app = application,
        conversations = convRepo,
        memoryManager = memoryManager,
        skillManager = skillManager,
        context = appContext,
        sandboxFactory = sandboxFactory,
        // A background run can ask the user too: the question surfaces in the chat screen's
        // interaction bar, or as a notification while that screen is not showing.
        additionalToolProviders = listOf(mcpToolProvider, AskUserToolProvider(askUser)),
        customProviders = { settings.customProviders.value },
    ).also {
        // Foreground Task/Loop executions share the exact same prompt and session trust state as
        // Chat, including the notification-backed wait when no Activity is visible.
        it.onConfirmShellCommand = shellConfirmation::confirm
        it.onMessagePersisted = ragManager::indexMessageForRag
    }

    /**
     * Injects [userText] as a new user turn at the leaf of [conversationId] and runs
     * one full generation, persisting the assistant reply. [modelId] is the prefixed
     * model id (e.g. "OpenAI:gpt-4o"); null/blank falls back to the app default model.
     *
     * [systemPromptOverride] bypasses the per-conversation / active-prompt resolution:
     * pass a task's own system prompt, or "" to run with no system prompt at all (the
     * default for task executions). Leave null to resolve the prompt the way the
     * foreground chat does (conversation's prompt id, falling back to the active one).
     */
    suspend fun runOnce(
        conversationId: String,
        userText: String,
        modelId: String? = null,
        systemPromptOverride: String? = null,
        foregroundServiceManagedExternally: Boolean = false,
        precondition: suspend () -> Boolean = { true },
        requestKind: String = "task",
    ): Result = automationExecutionGate.withExecution {
        executionCoordinator.withAutomationConversationLock(conversationId) {
            settings.awaitInitialLoad()
            automationWakeLockOwner.whileHeld(settings.automationWakeLockEnabled.value) {
                convRepo.recoverConversationRuntime(conversationId)
                runOnceLocked(
                    conversationId = conversationId,
                    userText = userText,
                    modelId = modelId,
                    systemPromptOverride = systemPromptOverride,
                    foregroundServiceManagedExternally = foregroundServiceManagedExternally,
                    precondition = precondition,
                    requestKind = requestKind,
                )
            }
        }
    }

    /**
     * LoopManager owns both automation guards across its persistent cycle claim, generation, and
     * schedule update. Re-entering either non-reentrant guard from [runOnce] would deadlock, so
     * this entry point trusts the shared gate -> conversation-lease order already held by Loop.
     */
    internal suspend fun runOnceWithAutomationGuardsHeld(
        conversationId: String,
        userText: String,
        modelId: String? = null,
        systemPromptOverride: String? = null,
        foregroundServiceManagedExternally: Boolean = false,
        precondition: suspend () -> Boolean = { true },
        requestKind: String = "loop",
    ): Result {
        settings.awaitInitialLoad()
        return automationWakeLockOwner.whileHeld(settings.automationWakeLockEnabled.value) {
            runOnceLocked(
                conversationId = conversationId,
                userText = userText,
                modelId = modelId,
                systemPromptOverride = systemPromptOverride,
                foregroundServiceManagedExternally = foregroundServiceManagedExternally,
                precondition = precondition,
                requestKind = requestKind,
            )
        }
    }

    private suspend fun runOnceLocked(
        conversationId: String,
        userText: String,
        modelId: String?,
        systemPromptOverride: String?,
        foregroundServiceManagedExternally: Boolean,
        precondition: suspend () -> Boolean,
        requestKind: String,
    ): Result {
        require(requestKind.isNotBlank())
        settings.awaitInitialLoad()
        providerRegistry.awaitInitialSync()
        if (!precondition()) return Result.Failure("Execution cancelled")
        val conversation = convRepo.getConversation(conversationId)
            ?: return Result.Failure("Conversation not found: $conversationId")
        val effectiveModelId = modelId?.takeIf { it.isNotBlank() }
            ?: conversation.modelId?.takeIf { it.isNotBlank() }
            ?: settings.selectedModel.value

        // If the conversation is open in the foreground, delegate the send to the regular
        // controller path so the loop cycle gets bubble animation, scroll, and haptics.
        // The controller manages its own slot; do NOT acquire it here before the bridge check.
        // The bridge only returns once the delegated turn is durably finished, so the caller's
        // conversation lease still spans the whole generation and the Result reflects what
        // actually happened rather than merely "the send was accepted".
        val bridge = currentForegroundSendBridge()
        if (bridge != null) {
            when (
                val outcome = bridge(
                    conversationId,
                    userText,
                    effectiveModelId,
                    requestKind,
                )
            ) {
                is BridgeOutcome.Completed ->
                    return Result.Success(outcome.modelMessageId, outcome.text)
                is BridgeOutcome.Busy -> return Result.Busy(outcome.reason)
                is BridgeOutcome.Failed -> return Result.Failure(outcome.reason)
                BridgeOutcome.NotDelegated -> Unit
            }
        }

        if (effectiveModelId.isBlank()) return Result.Failure("No model selected")
        val generationState = generationRegistry.getOrCreate(conversationId)
        val now = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()
        val userMessageId = UUID.randomUUID().toString()
        val modelMessageId = UUID.randomUUID().toString()
        val startTime = now + 1
        var lastStreamed: ChatMessage? = null
        var runCreated = false
        var runBound = false
        var stopEffectHandled = false
        var bindingOutcome: ConversationGenerationState.RunBindingOutcome =
            ConversationGenerationState.RunBindingOutcome.Rejected
        var inputEffect: RunEffect.PersistAcceptedInput? = null
        var generationOwnerJob: CompletableJob? = null
        var finalModelMessageId = modelMessageId

        return try {
            val providerName = providerRegistry.providerForModel(effectiveModelId)
            val activeKey = settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() }
                ?: settings.resolveActiveKey(providerName) ?: ""
            if (!providerRegistry.isConfigured(providerName, activeKey)) {
                return Result.Failure("Provider not configured: $providerName")
            }

            val builder = GenerationRequestBuilder(
                settings = settings,
                convRepo = convRepo,
                memoryManager = memoryManager,
                skillManager = skillManager,
                providerRegistry = providerRegistry,
                ragManager = ragManager,
                appContext = appContext,
                pendingConversationSettings = MutableStateFlow(null),
                onSnackbar = {},
            )
            val captured = builder.captureAdmissionSnapshot(
                conversationId = conversationId,
                runId = runId,
                modelId = effectiveModelId,
                resolvedPromptOverride = systemPromptOverride?.let {
                    GenerationRequestBuilder.ResolvedPrompt(
                        it.ifBlank { null },
                        null,
                        null,
                    )
                },
            )
            val taskContext = captured.context.copy(
                // Automation tools are intentionally foreground-only: a scheduled run must
                // not recursively create more tasks/loops without a user in the loop.
                automationToolsEnabled = false,
                foregroundServiceManagedExternally = foregroundServiceManagedExternally,
            )
            val generationSnapshot = captured.copy(
                context = taskContext,
                automaticCompact = captured.automaticCompact.copy(
                    generationContext = taskContext,
                ),
            )
            val fixedTokenCost = generationManager.fixedContextTokenCost(
                generationSnapshot.config,
                generationSnapshot.context,
            )
            val automaticCompactConfig = generationSnapshot.automaticCompact.copy(
                fixedTokenCost = fixedTokenCost,
            )

            // Pre-send Compact is an isolated standard generation. Only a fresh durable SUCCESS
            // may hand off to the ordinary direct Send; every launch/status anomaly stops here.
            val preCompactNeeded = compactController.automaticNeeded(
                conversationId = conversationId,
                contextLimit = generationSnapshot.config.maxContextWindow,
                config = automaticCompactConfig,
            )
            val preCompactLaunch = if (preCompactNeeded) {
                compactController.startAutomaticStandard(
                    conversationId = conversationId,
                    contextLimit = generationSnapshot.config.maxContextWindow,
                    config = automaticCompactConfig,
                    state = generationState,
                ) ?: return Result.Failure("Compact generation did not start")
            } else {
                null
            }
            try {
                preCompactLaunch?.job?.join()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    preCompactLaunch?.job?.cancel(cancelled)
                    preCompactLaunch?.job?.join()
                }
                throw cancelled
            }
            val preCompactMessageId = preCompactLaunch?.messageId
            if (preCompactMessageId != null) {
                val preCompactMessage = convRepo.getMessage(preCompactMessageId)
                if (!automaticCompactAllowsHandoff(preCompactMessage?.status)) {
                    return Result.Failure(
                        preCompactMessage?.text?.takeIf(String::isNotBlank)
                            ?: "Compact generation did not complete successfully",
                    )
                }
            }

            // Headless Task/Loop uses the same direct-only Send command as the foreground bridge.
            // The queue mutex keeps pending guidance from being overtaken between inspection and
            // the mailbox decision. Busy is typed and persists no message/Run side effect.
            val admission = AutomationRuntimeAdmission.request(
                state = generationState,
                proposedRunId = runId,
                effectId = "automation-send-$runId",
            )
            val acceptedInputEffect = when (admission) {
                AutomationRuntimeAdmission.Decision.Busy -> return Result.Busy()
                is AutomationRuntimeAdmission.Decision.Accepted -> admission.inputEffect
            }
            inputEffect = acceptedInputEffect
            val uiToken = acceptedInputEffect.identity.ownerToken
            val currentJob = currentCoroutineContext()[Job]
                ?: return Result.Failure("Generation worker is unavailable")
            val ownerJob = Job(currentJob)
            generationOwnerJob = ownerJob
            if (!generationState.attachGenerationJob(uiToken, ownerJob)) {
                ownerJob.cancel()
                withContext(NonCancellable) {
                    if (generationState.commands.abandonSendLaunch(acceptedInputEffect.identity)) {
                        generationState.onQueueDrainRequested?.invoke(generationState)
                    }
                }
                return Result.Failure("Conversation generation slot was revoked")
            }
            val persistToken = generationState.nextPersistId()
            val graphCommit = acceptedInputGraphWriter.commit(
                AcceptedInputGraphWriter.Request(
                    inputEffect = acceptedInputEffect,
                    userMessageId = userMessageId,
                    modelMessageId = modelMessageId,
                    userText = userText,
                    modelId = generationSnapshot.selectedModelId,
                    userTimestamp = now,
                    touchConversationOnAdmission = false,
                ),
            )
            runCreated = true
            if (userText.isNotBlank()) {
                runCatching { ragManager.indexMessageForRag(userMessageId, userText) }
                    .onFailure { error ->
                        DebugLog.w(
                            "TaskExecutionEngine",
                            "Failed to enqueue user-message indexing for $userMessageId",
                            error,
                        )
                    }
            }
            bindingOutcome = withContext(NonCancellable) {
                generationState.finishInputPersistence(acceptedInputEffect.identity)
            }
            runBound = bindingOutcome is ConversationGenerationState.RunBindingOutcome.Active
            if (!runBound) {
                val stopping = bindingOutcome as?
                    ConversationGenerationState.RunBindingOutcome.Stopping
                if (stopping != null) {
                    stopFinalizer.settleTaskStopEffect(
                        state = generationState,
                        effect = stopping.finalizationEffect,
                        messages = emptyList(),
                    )
                    stopEffectHandled = true
                } else {
                    // Runtime disposal is the only rejected durable edge. The ordinary Stop race
                    // is represented by the exact effect handled above.
                    withContext(NonCancellable) {
                        convRepo.finishStoppedGeneration(emptyList(), runId)
                    }
                }
                ownerJob.complete()
                ownerJob.join()
                generationState.awaitSendAvailable()
                currentCoroutineContext().ensureActive()
                return Result.Failure("Execution cancelled")
            }
            val placeholder = graphCommit.modelMessage.toUiChatMessage(appContext)
            generationState.loadingChange(uiToken, true)
            generationState.streamUpdate(uiToken, placeholder)

            val requestTrace = HttpClient.RequestTrace(
                requestId = modelMessageId,
                origin = requestKind,
                diagnosticContext = DeveloperDiagnostics.newRequestContext(
                    requestId = modelMessageId,
                    conversationId = conversationId,
                    runId = runId,
                    pass = 0,
                    provider = generationSnapshot.config.providerName,
                    model = generationSnapshot.selectedModelId,
                    requestKind = requestKind,
                ),
            )
            val baseCallbacks = generationState.callbacksFor(uiToken, persistToken)
            val generationResult = withContext(ownerJob) {
                generationManager.generate(
                conversationId = conversationId,
                modelMessageId = modelMessageId,
                startTime = startTime,
                modelName = generationSnapshot.selectedModelId,
                runId = runId,
                pass = 0,
                ownerToken = uiToken,
                config = generationSnapshot.config,
                ctx = generationSnapshot.context,
                providerInstances = generationSnapshot.providerInstances,
                generationJob = currentCoroutineContext()[Job],
                callbacks = baseCallbacks.copy(
                    onStreamUpdate = { message ->
                        lastStreamed = message
                        baseCallbacks.onStreamUpdate(message)
                    },
                    onToolRoundPersisted = {
                        if (
                            contextCompactor.automaticNeeded(
                                conversationId = conversationId,
                                contextLimit = generationSnapshot.config.maxContextWindow,
                                config = automaticCompactConfig,
                            )
                        ) {
                            ToolRoundBoundaryDecision.CompleteForFollowUp
                        } else {
                            ToolRoundBoundaryDecision.Continue
                        }
                    },
                ),
                streamScope = generationState.streamScope,
                requestTrace = requestTrace,
                )
            }
            val boundaryParentId = generationResult.followUpParentMessageId
            if (boundaryParentId != null) generationState.deferNextQueueDrain()
            ownerJob.complete()
            ownerJob.join()
            generationState.awaitSendAvailable()

            if (boundaryParentId != null) {
                val continuationResult = compactController.continueTaskGenerations(
                    convRepo = convRepo,
                    executionCoordinator = executionCoordinator,
                    terminalSettlement = terminalSettlement,
                    generationManager = generationManager,
                    contextCompactor = contextCompactor,
                    appContext = appContext,
                    initialRequest = AutomaticCompactContinuationRequest(
                        generationRequest = BoundRunGenerationRequest(
                            conversationId = conversationId,
                            modelMessageId = modelMessageId,
                            startTime = startTime,
                            snapshot = generationSnapshot,
                            uiToken = uiToken,
                            persistId = persistToken,
                            runId = runId,
                            pass = 0,
                            requestKind = requestKind,
                        ),
                        parentMessageId = boundaryParentId,
                        config = automaticCompactConfig,
                    ),
                    state = generationState,
                )
                if (continuationResult.aborted) {
                    return Result.Failure("Compact generation did not complete successfully")
                }
                continuationResult.modelMessageId?.let { finalModelMessageId = it }
            }

            val finalMsg = convRepo.getMessage(finalModelMessageId)
            if (finalMsg != null && finalMsg.status == MessageStatus.SUCCESS) {
                Result.Success(finalModelMessageId, finalMsg.text)
            } else {
                Result.Failure(finalMsg?.text?.takeIf { it.isNotBlank() } ?: "Generation failed")
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                generationOwnerJob?.cancel(e)
                generationOwnerJob?.join()
            }
            if (!runCreated && inputEffect != null) {
                withContext(NonCancellable) {
                    runCreated = convRepo.getRun(runId) != null
                    if (!runCreated) {
                        generationState.commands.inputPersistenceFailed(inputEffect.identity)
                    }
                }
            }
            withContext(NonCancellable) {
                // If Room committed just before cancellation surfaced, first echo that exact
                // persistence result. This either binds Active or emits the mailbox-owned late
                // Stop effect; it never invents a second terminal writer.
                if (
                    runCreated &&
                    !runBound &&
                    bindingOutcome is ConversationGenerationState.RunBindingOutcome.Rejected &&
                    inputEffect != null
                ) {
                    bindingOutcome = generationState.finishInputPersistence(inputEffect.identity)
                    runBound = bindingOutcome is
                        ConversationGenerationState.RunBindingOutcome.Active
                }
                when (val binding = bindingOutcome) {
                    is ConversationGenerationState.RunBindingOutcome.Stopping -> {
                        if (!stopEffectHandled) {
                            stopFinalizer.settleTaskStopEffect(
                                state = generationState,
                                effect = binding.finalizationEffect,
                                messages = emptyList(),
                            )
                            stopEffectHandled = true
                        }
                    }
                    ConversationGenerationState.RunBindingOutcome.Active -> {
                        // An external user Stop already owns its effect. Worker/Task cancellation
                        // enters the same mailbox only when no Stop is in progress.
                        if (!generationState.stopping.value) {
                            val stopped = generationState.stop()
                            stopped.finalizationEffect?.let { effect ->
                                stopFinalizer.settleTaskStopEffect(
                                    state = generationState,
                                    effect = effect,
                                    messages = stopped.stoppedMessage?.let(::listOf).orEmpty(),
                                )
                            }
                        }
                    }
                    ConversationGenerationState.RunBindingOutcome.Rejected -> {
                        if (runCreated) {
                            // Runtime disposal/replacement is an exceptional recovery edge.
                            val stopped = lastStreamed?.copy(status = MessageStatus.STOPPED)
                            convRepo.finishStoppedGeneration(
                                stopped?.let(::listOf).orEmpty(),
                                runId,
                            )
                        }
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                generationOwnerJob?.complete()
                generationOwnerJob?.join()
            }
            DebugLog.e(
                "TaskExecutionEngine",
                "runOnce failed for conversation=$conversationId " +
                    "errorType=${e.javaClass.simpleName}",
            )
            val reason = e.localizedMessage ?: "Unexpected error"
            if (!runCreated && inputEffect != null) {
                withContext(NonCancellable) {
                    runCreated = convRepo.getRun(runId) != null
                    if (!runCreated) {
                        generationState.commands.inputPersistenceFailed(inputEffect.identity)
                    }
                }
            }
            if (
                runCreated &&
                !runBound &&
                bindingOutcome is ConversationGenerationState.RunBindingOutcome.Rejected &&
                inputEffect != null
            ) {
                withContext(NonCancellable) {
                    bindingOutcome = generationState.finishInputPersistence(inputEffect.identity)
                    runBound = bindingOutcome is
                        ConversationGenerationState.RunBindingOutcome.Active
                }
            }
            if (runCreated) {
                val failedMessage = ChatMessage(
                    id = modelMessageId,
                    parentId = userMessageId,
                    text = reason,
                    thoughts = null,
                    status = MessageStatus.ERROR,
                    participant = Participant.MODEL,
                    timestamp = startTime,
                    modelName = effectiveModelId.takeIf { it.isNotBlank() },
                    runId = runId,
                    runSequence = 1,
                )
                val stopping = bindingOutcome as?
                    ConversationGenerationState.RunBindingOutcome.Stopping
                if (stopping != null) {
                    if (!stopEffectHandled) {
                        stopFinalizer.settleTaskStopEffect(
                            state = generationState,
                            effect = stopping.finalizationEffect,
                            messages = emptyList(),
                        )
                        stopEffectHandled = true
                    }
                } else if (runBound) {
                    val effectIdentity = RunEffectIdentity(
                        conversationId = conversationId,
                        ownerToken = inputEffect?.identity?.ownerToken
                            ?: error("Bound automation Run has no input identity"),
                        runId = runId,
                        pass = 0,
                        effectId = "finalize-$runId-0",
                    )
                    val effect = generationState.commands.requestRunFinalization(
                        identity = effectIdentity,
                        status = RunStatus.FAILED,
                        reason = RunEndReason.PROVIDER_ERROR,
                        markConversationUnread = false,
                    )
                    if (effect != null) {
                        val result = runFinalizationEffects.execute(effect) { requested ->
                            convRepo.finishGeneration(
                                message = failedMessage,
                                conversationId = requested.identity.conversationId,
                                runId = requested.identity.runId,
                                status = requested.status,
                                reason = requested.reason,
                                markConversationUnread = requested.markConversationUnread,
                            )
                        }
                        val success = result is
                            RunFinalizationEffectCoordinator.Result.Succeeded
                        generationState.finishRunFinalization(effect.identity, success)
                        if (success) {
                            generationState.streamUpdate(effect.identity.ownerToken, failedMessage)
                            generationState.streamClear(effect.identity.ownerToken)
                        }
                    }
                } else if (
                    bindingOutcome is ConversationGenerationState.RunBindingOutcome.Rejected
                ) {
                    // The runtime disappeared after Room commit; repair the durable graph even
                    // though no process writer remains to accept a result command.
                    convRepo.finishGeneration(
                        message = failedMessage,
                        conversationId = conversationId,
                        runId = runId,
                        status = RunStatus.FAILED,
                        reason = RunEndReason.PROVIDER_ERROR,
                        markConversationUnread = false,
                    )
                }
            }
            Result.Failure(reason)
        }
    }
}
