package com.newoether.agora.viewmodel

import android.app.Application
import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.resolveRequest
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.R
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.tool.ToolProvider
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal suspend fun acquireGenerationForegroundLease(
    managedExternally: Boolean,
    acquire: suspend () -> Boolean,
): Boolean {
    if (managedExternally) return false
    return acquire()
}

class GenerationManager(
    private val app: Application,
    private val conversations: com.newoether.agora.data.repository.ConversationRepository,
    private val memoryManager: MemoryManager,
    private val skillManager: SkillManager,
    private val context: android.content.Context,
    private val sandboxFactory: com.newoether.agora.sandbox.SandboxManagerFactory? = null,
    additionalToolProviders: List<ToolProvider> = emptyList(),
    private val customProviders: () -> List<CustomProviderConfig> = { emptyList() },
) {
    var onMessagePersisted: ((messageId: String, text: String) -> Unit)? = null

    /** User-confirmation gate for remote shell mutations. Set by the ViewModel.
     *  Returns true to proceed, false to deny. */
    var onConfirmShellCommand: (suspend (server: String, summary: String) -> Boolean)? = null

    private val toolExecutor = GenerationToolExecutor.createDefault(
        app = app,
        conversations = conversations,
        memoryManager = memoryManager,
        skillManager = skillManager,
        sandboxFactory = sandboxFactory,
        additionalProviders = additionalToolProviders,
        confirmShellCommand = { server, summary ->
            onConfirmShellCommand?.invoke(server, summary) ?: true
        },
    )
    private val providerPassEffects = ProviderPassEffectExecutor()
    private val toolBatchEffects = GenerationToolBatchEffectExecutor(toolExecutor)
    private val toolRoundBuilder = GenerationToolRoundBuilder()
    private val runFinalizationExecutor = GenerationRunFinalizationExecutor(conversations)
    private val apiPathBuilder = GenerationApiPathBuilder(
        conversations = conversations,
        generationErrorFormatter = { raw ->
            normalizePersistedGenerationErrorText(context, raw)
        },
        toolDefinitions = toolExecutor,
    )
    private val completionEffects = GenerationCompletionEffectsExecutor(
        isAppInForeground = { AppForegroundTracker.isInForeground },
        releaseForegroundLease = AgoraForegroundService::release,
        notify = ::showTerminalNotification,
    )

    /** Semantic message search — delegates to the RAG tool provider, which owns the
     *  embedding-search logic. Kept here as the entry point used by ChatViewModel's
     *  in-app conversation search. */
    suspend fun semanticSearch(query: String, limit: Int, ctx: GenerationContext): List<Pair<MessageEntity, Float>> =
        toolExecutor.semanticSearch(query, limit, ctx)

    internal fun showTerminalNotification(
        text: String,
        conversationId: String,
        status: MessageStatus,
    ) {
        AgoraForegroundService.showTerminalNotification(
            context = app,
            responseText = replaceCustomProviderIdsForDisplay(text, customProviders()),
            conversationId = conversationId,
            isError = status == MessageStatus.ERROR,
        )
    }

    internal fun fixedContextTokenCost(
        config: GenerationConfig,
        context: GenerationContext,
    ): Int = ContextTokenEstimator.estimateFixed(
        systemPrompt = config.effectiveSystemPrompt,
        tools = if (config.lowContextModeEnabled) emptyList()
        else toolExecutor.definitions(context),
        initialUserPrompt = config.initialUserPrompt,
        codeExecutionEnabled = config.codeExecutionEnabled,
        googleSearchEnabled = config.googleSearchEnabled,
        openAiWebSearchEnabled = config.openAiWebSearchEnabled,
    )
    /** The same cost as [fixedContextTokenCost], split for the context indicator. */
    internal fun fixedContextComposition(
        config: GenerationConfig,
        context: GenerationContext,
    ): ContextTokenEstimator.FixedContextComposition =
        ContextTokenEstimator.estimateFixedComposition(
            systemPrompt = config.effectiveSystemPrompt,
            tools = if (config.lowContextModeEnabled) emptyList()
            else toolExecutor.definitions(context),
            initialUserPrompt = config.initialUserPrompt,
            codeExecutionEnabled = config.codeExecutionEnabled,
            googleSearchEnabled = config.googleSearchEnabled,
            openAiWebSearchEnabled = config.openAiWebSearchEnabled,
        )

    internal fun includesAssistantReasoning(
        config: GenerationConfig,
        context: GenerationContext,
    ): Boolean =
        !config.responsesApiEnabled &&
            config.thinkingEnabled &&
            (
                config.providerName == Constants.PROVIDER_DEEPSEEK ||
                    com.newoether.agora.api.openai.isDeepSeekModel(config.modelId)
                ) &&
            !config.lowContextModeEnabled &&
            toolExecutor.definitions(context).isNotEmpty()

    internal suspend fun resolvedFixedContextTokenCost(
        config: GenerationConfig,
        context: GenerationContext,
    ): Int {
        val resolver = config.requestResolver ?: return fixedContextTokenCost(config, context)
        val definitions = if (config.lowContextModeEnabled) emptyList()
        else toolExecutor.definitions(context)
        val providerConfig = ProviderConfig(
            apiKey = config.apiKey,
            modelId = config.modelId,
            anthropicCacheEnabled = config.anthropicCacheEnabled,
            anthropicCacheTtl = config.anthropicCacheTtl,
            maxContextWindow = config.maxContextWindow,
            codeExecutionEnabled = config.codeExecutionEnabled,
            googleSearchEnabled = config.googleSearchEnabled,
            openAiWebSearchEnabled = config.openAiWebSearchEnabled,
            tools = definitions,
            includeImages = !context.imageTranscriptionEnabled,
            includeAssistantReasoning = includesAssistantReasoning(config, context),
            requestResolver = resolver,
        )
        val resolvedRequest = providerConfig.resolveRequest(emptyList())
        return ContextTokenEstimator.estimateFixed(
            systemPrompt = resolvedRequest.systemPrompt,
            tools = definitions,
            initialUserPrompt = config.initialUserPrompt,
            codeExecutionEnabled = config.codeExecutionEnabled,
            googleSearchEnabled = config.googleSearchEnabled,
            openAiWebSearchEnabled = config.openAiWebSearchEnabled,
        )
    }

    internal suspend fun buildApiPath(request: GenerationApiPathRequest): GenerationApiPath =
        apiPathBuilder.build(request)

    internal suspend fun generate(
        conversationId: String,
        modelMessageId: String,
        startTime: Long,
        modelName: String,
        runId: String,
        pass: Int,
        ownerToken: Long,
        config: GenerationConfig,
        ctx: GenerationContext,
        providerInstances: Map<String, LlmProvider>,
        generationJob: kotlinx.coroutines.Job?,
        callbacks: GenerationCallbacks,
        streamScope: StreamScope? = null,
        requestTrace: com.newoether.agora.api.HttpClient.RequestTrace? = null,
    ): GenerationExecutionResult =
        com.newoether.agora.api.HttpClient.withStreamScope(streamScope, requestTrace) {
        // Bind every provider/tool stream opened by this generation to its coroutine-local
        // StreamScope. Parallel conversations therefore cannot overwrite one another's Stop
        // ownership, while child dispatcher hops inherit the same context element.
        // Destructure into locals so the body below reads exactly as before.
        val (onStreamUpdate, onLoadingChange, onStreamClear, isLatestPersist) = callbacks

        var foregroundLeaseAcquired = false
        // Set when this Run reaches a tool-round boundary with guidance waiting for a fresh Run.
        var endedAtGuidanceBoundary = false
        var endedForFollowUp = false
        var followUpParentMessageId: String? = null
        val output = GenerationOutputAccumulator(toolExecutor, config.providerName)
        var parentId: String? = null
        var modelRunSequence = -1L
        var toolPath = emptyList<ChatMessage>()
        val transcriptionManager = TranscriptionManager(providerInstances, conversations, context)
        val transcriptionExecution =
            GenerationTranscriptionStage(transcriptionManager).newExecution()
        val checkpoints = StreamingMessageCheckpoints(
            scope = CoroutineScope(currentCoroutineContext()),
            isLatestPersist = isLatestPersist,
            persist = { message ->
                conversations.updateStreamingMessageCheckpoint(message)
            },
            onFailure = { error ->
                DebugLog.e("AgoraVM", "Failed to persist streaming checkpoint", error)
            },
        )
        var terminalPersisted = false
        var terminalConversationVisible: Boolean? = null
        var terminalOutputText = ""

        fun projectOutput(message: ChatMessage): ChatMessage =
            message.withBoundedOutputTextTransform(callbacks.transformFinalText)

        fun adoptIncompleteTranscriptionSnapshot() {
            transcriptionExecution.incompleteSnapshot()?.let { snapshot ->
                output.totalText.clear()
                output.totalText.append(snapshot.text)
                output.totalThoughts = snapshot.thoughts.orEmpty()
                output.totalThoughtTitle = snapshot.thoughtTitle
                output.totalTokenCount = snapshot.tokenCount
                output.totalTokenUsage = snapshot.tokenUsage
                output.thoughtTiming.adoptTotalDuration(snapshot.thoughtTimeMs)
                output.generatedImages.clear()
                output.generatedImages.addAll(snapshot.images)
                output.toolOverlay.replaceAll(snapshot.segments.orEmpty())
            }
        }

        try {
            val provider = requireRegisteredProvider(providerInstances, config.providerName)
            onLoadingChange(true)
            // Slot ownership (generating flag / active set) is claimed synchronously by the
            // controller before this coroutine runs — GenerationManager no longer touches it.
            com.newoether.agora.util.CrashReporter.note("generate provider=${config.providerName}")
            output.thinkingPlaceholder = context.getString(R.string.thinking_ellipsis)
            val placeholder = checkNotNull(conversations.getMessage(modelMessageId)) {
                "Generation placeholder $modelMessageId does not exist"
            }
            check(placeholder.runId == runId) {
                "Generation placeholder $modelMessageId is not owned by Run $runId"
            }
            check(conversations.getRun(runId)?.currentPass == pass) {
                "Generation pass $pass is not current for Run $runId"
            }
            modelRunSequence = placeholder.runSequence
            parentId = placeholder.parentId
            requestTrace?.mark("generation_state_ready")
            foregroundLeaseAcquired = acquireGenerationForegroundLease(
                managedExternally = ctx.foregroundServiceManagedExternally,
                acquire = {
                    withContext(Dispatchers.Main) {
                        AgoraForegroundService.acquire(app, modelMessageId)
                    }
                },
            )

            // Stage 1: Image Transcription
            val transcription = transcriptionExecution.execute(
                request = GenerationTranscriptionStageRequest(
                    conversationId = conversationId,
                    runId = runId,
                    pass = pass,
                    parentId = parentId,
                    context = ctx,
                    generationJob = generationJob,
                    modelMessageId = modelMessageId,
                    startTime = startTime,
                ),
                onSnapshot = { snapshot, forceCheckpoint ->
                    val projected = projectOutput(snapshot)
                    onStreamUpdate(projected)
                    checkpoints.persist(projected, forceCheckpoint)
                },
            )
            if (transcription.segments.isNotEmpty()) {
                output.toolOverlay.prependAll(transcription.segments)
            }
            if (transcription.error != null) {
                output.generationErrorMessage = transcription.error
                output.currentStatus = MessageStatus.ERROR
            }

            if (output.currentStatus != MessageStatus.ERROR) {
            val (currentPath, rawProviderConfig) = apiPathBuilder.build(
                GenerationApiPathRequest(
                    parentId = parentId,
                    conversationId = conversationId,
                    config = config,
                    context = ctx,
                ),
            )
            requestTrace?.mark(
                "api_path_ready",
                "messages=${currentPath.size} tools=${rawProviderConfig.tools.orEmpty().size}",
            )
            val providerConfig = rawProviderConfig

            var toolCallData: ToolCallData? = null
            var toolCallDataList: List<ToolCallData> = emptyList()
            val roundToolSegments = mutableListOf<MessageSegment>()
            val completedToolCalls = linkedMapOf<String, StreamEvent.ToolCallRequest>()
            var toolRoundSegmentCursor = 0
            var providerRequestOrdinal = 0
            val toolRoundEffects = ToolRoundEffectCoordinator(callbacks)

            val uiUpdateGate = StreamingUiUpdateGate()
            var firstUiPublishPending = true

            fun modelMessage() = ChatMessage(
                id = modelMessageId, parentId = parentId,
                text = output.totalText.toString(), thoughts = output.totalThoughts.ifBlank { null },
                thoughtTitle = output.totalThoughtTitle, tokenCount = output.totalTokenCount,
                tokenUsage = output.totalTokenUsage,
                status = output.currentStatus, participant = Participant.MODEL,
                timestamp = startTime, thoughtTimeMs = output.thoughtTiming.totalDurationMs,
                modelName = modelName, toolCall = toolCallData,
                images = output.generatedImages.toList(),
                segments = buildLiveSegments(
                    output.toolOverlay.snapshot(),
                    output.currentAnswerBuf,
                    output.currentThoughtBuf,
                    output.currentThoughtSignature,
                    output.currentThoughtSignatureProvider,
                    output.thoughtTiming.liveDurationMs(),
                    output.generationErrorMessage,
                    output.generationErrorCode,
                    answerDeltas = output.currentAnswerDeltas,
                ),
                retryText = output.retryText,
                runId = runId,
                runSequence = modelRunSequence,
            )

            suspend fun publishStreamUpdate(forceCheckpoint: Boolean = false) {
                val snapshot = projectOutput(modelMessage())
                onStreamUpdate(snapshot)
                if (firstUiPublishPending) {
                    firstUiPublishPending = false
                    requestTrace?.mark("first_ui_publish")
                }
                checkpoints.persist(snapshot, force = forceCheckpoint)
            }

            suspend fun executeAcceptedToolBatch() {
                if (completedToolCalls.isEmpty()) return
                val batchEffect = toolRoundEffects.requireBatchEffect()
                val calls = completedToolCalls.values.toList()
                completedToolCalls.clear()
                output.currentStatus = MessageStatus.TOOL_CALLING
                val outcome = toolBatchEffects.execute(
                    request = AuthorizedToolBatchRequest(
                        effect = batchEffect,
                        calls = calls,
                        context = ctx,
                        conversationId = conversationId,
                        authorizedToolNames = providerConfig.tools.orEmpty()
                            .mapTo(linkedSetOf()) { it.function.name },
                        // view_image results reuse this generation's transcription flow when
                        // image transcription is enabled for the current model.
                        toolImageTranscriber =
                            if (
                                ctx.imageTranscriptionEnabled &&
                                ctx.transcriptionModelId.isNotBlank()
                            ) {
                                { image, onProgress ->
                                    transcriptionManager.describeImageWithProgress(
                                        image = image,
                                        ctx = ctx,
                                        generationJob = generationJob,
                                        conversationId = conversationId,
                                        runId = runId,
                                        pass = pass,
                                        modelMessageId = modelMessageId,
                                        onProgress = onProgress,
                                    )
                                }
                            } else {
                                null
                            },
                    ),
                    overlay = output.toolOverlay,
                    callbacks = ToolBatchProgressCallbacks(
                        publish = ::publishStreamUpdate,
                        onPublishedAt = uiUpdateGate::recordPublished,
                    ),
                )
                check(outcome.identity == batchEffect.identity)
                roundToolSegments.addAll(outcome.segments)
                toolCallData = outcome.calls.firstOrNull()
                toolCallDataList = outcome.calls
                toolRoundEffects.completeBatch(batchEffect.identity)
                output.currentStatus = MessageStatus.SENDING
                publishStreamUpdate(forceCheckpoint = true)
                uiUpdateGate.recordPublished(System.currentTimeMillis())
            }

            suspend fun collectProviderRequest(
                messages: List<ChatMessage>,
                onFirstEvent: (() -> Unit)? = null,
            ): ProviderPassOutcome {
                val providerAnswerStart = output.totalText.length
                output.tokenUsageAccumulator.beginRequest()
                val providerRequestIndex = providerRequestOrdinal++
                val providerRequestTrace = if (providerRequestIndex == 0) {
                    requestTrace
                } else {
                    requestTrace?.child(
                        requestKind = "tool_continuation",
                        requestIdSuffix = "provider-$providerRequestIndex",
                    )
                }
                val proposedIdentity = RunEffectIdentity(
                    conversationId = conversationId,
                    ownerToken = ownerToken,
                    runId = runId,
                    pass = pass,
                    effectId = "provider-$pass-$providerRequestIndex",
                )
                try {
                    return providerPassEffects.execute(
                        request = ProviderPassExecutionRequest(
                            proposedIdentity = proposedIdentity,
                            provider = provider,
                            messages = messages,
                            config = providerConfig,
                            requestTrace = providerRequestTrace,
                        ),
                        callbacks = ProviderPassExecutionCallbacks(
                            requestEffect = callbacks.onProviderPassRequested,
                            returnConsumerFailure = { identity, result ->
                                callbacks.onProviderPassCompleted(identity, result)
                            },
                            onFirstEvent = onFirstEvent,
                            onEvent = { event ->
                                output.handleStreamEvent(
                                    event = event,
                                    providerAnswerStart = providerAnswerStart,
                                    provider = provider,
                                    context = context,
                                    completedToolCalls = completedToolCalls,
                                    uiUpdateGate = uiUpdateGate,
                                    publishStreamUpdate = ::publishStreamUpdate,
                                    publishRetrySnapshot = {
                                        onStreamUpdate(projectOutput(modelMessage()))
                                    },
                                )
                            },
                        ),
                    )
                } finally {
                    output.tokenUsageAccumulator.finishRequest()
                    output.totalTokenUsage = output.tokenUsageAccumulator.snapshot()
                    output.totalTokenCount = output.totalTokenUsage?.totalTokenCount ?: output.totalTokenCount
                }
            }

            suspend fun acceptProviderPass(outcome: ProviderPassOutcome) {
                val result = outcome.resultType()
                callbacks.onProviderPassCompleted(outcome.identity, result)
                    ?.takeIf { it.identity == outcome.identity && it.result == result }
                    ?: throw CancellationException(
                        "Provider pass ${outcome.identity.effectId} outcome is no longer current",
                    )
                when (outcome) {
                    is ProviderPassOutcome.CompletedText -> Unit
                    is ProviderPassOutcome.CompletedToolCalls -> {
                        check(completedToolCalls.isEmpty()) {
                            "A Provider pass cannot overlap an unconsumed tool batch"
                        }
                        toolRoundEffects.acceptValidatedBatch(outcome.identity)
                        outcome.calls.forEach { call ->
                            completedToolCalls[call.streamKey] = call
                        }
                    }
                    is ProviderPassOutcome.Truncated,
                    is ProviderPassOutcome.Failed,
                    -> check(output.currentStatus == MessageStatus.ERROR) {
                        "A failed Provider pass must publish its error before closing"
                    }
                    is ProviderPassOutcome.Cancelled -> throw CancellationException(
                        "Provider pass ${outcome.identity.effectId} was cancelled",
                    )
                }
            }

            val apiPath = if (providerConfig.requestResolver != null) {
                currentPath
            } else {
                projectGenerationInputMessages(
                    messages = currentPath,
                    includeImages = providerConfig.includeImages,
                    userPrepend = config.userPrepend,
                    userPostpend = config.userPostpend,
                    assistantPrepend = config.assistantPrepend,
                    assistantPostpend = config.assistantPostpend,
                    initialUserPrompt = config.initialUserPrompt,
                )
            }
            requestTrace?.mark("provider_dispatch")
            acceptProviderPass(collectProviderRequest(apiPath) {
                requestTrace?.mark("first_semantic_event")
            })
            output.thoughtTiming.finishCurrent()
            output.currentStatus = statusAfterThoughtPhaseFinished(output.currentStatus)
            if (output.currentStatus != MessageStatus.ERROR) executeAcceptedToolBatch()
            // Publish the final in-memory snapshot without waiting for another Room round trip.
            // The terminal transaction below persists this exact state after fencing the
            // checkpoint writer, while genuine tool lifecycle boundaries remain forced.
            if (generationJob?.isCancelled != true) {
                publishStreamUpdate()
            }

            // Multi-tool loop
            var toolRound = 0
            toolPath = currentPath

            while (toolCallDataList.isNotEmpty() && output.currentStatus != MessageStatus.ERROR && currentCoroutineContext().isActive) {
                toolRound++
                val roundToolList = roundToolSegments.toList()
                roundToolSegments.clear()
                val thoughtSegs = toolRoundThoughtSegments(
                    segments = output.toolOverlay.contentSnapshot(),
                    fromIndex = toolRoundSegmentCursor,
                )
                val txedSegments = if (thoughtSegs.isNotEmpty()) thoughtSegs + roundToolList else roundToolList
                toolRoundSegmentCursor = output.toolOverlay.size
                val prevLastId = if (toolRound == 1) modelMessageId else toolPath.lastOrNull()?.id
                val tcds = toolCallDataList
                val round = toolRoundBuilder.build(
                    previousMessageId = prevLastId,
                    conversationId = conversationId,
                    runId = runId,
                    modelName = modelName,
                    providerName = provider.name,
                    calls = tcds,
                    completedSegments = txedSegments,
                )
                toolPath = toolPath + round.pathMessages
                toolRoundEffects.commitRound { commitIdentity ->
                    conversations.appendToolRoundToRun(
                        messages = round.entities,
                        expectedPass = commitIdentity.pass,
                    )
                }
                output.toolOverlay.releaseCommittedResponseState(
                    tcds.mapTo(linkedSetOf()) { call ->
                        checkNotNull(call.toolCallId) {
                            "Committed tool call is missing its provider call id"
                        }
                    },
                )
                publishStreamUpdate(forceCheckpoint = true)
                uiUpdateGate.recordPublished(System.currentTimeMillis())
                // A terminal Conch job may be deleted only after the complete tool result is
                // durable. ACK is best-effort and cannot influence the already-authorized
                // continuation; Conch's bounded retention remains the failure fallback.
                toolExecutor.acknowledgeCommittedShellJobs(tcds, ctx)
                val boundaryDecision = callbacks.onToolRoundPersisted()
                if (boundaryDecision is ToolRoundBoundaryDecision.CompleteForFollowUp) {
                    endedForFollowUp = true
                    followUpParentMessageId = round.lastResultId
                }
                val boundaryParentId = round.lastResultId
                toolPath = apiPathBuilder.build(
                    GenerationApiPathRequest(
                        parentId = boundaryParentId,
                        conversationId = conversationId,
                        config = config,
                        context = ctx,
                    ),
                ).messages

                toolCallData = null
                toolCallDataList = emptyList()

                if (endedForFollowUp) break

                // A send queued mid-generation starts a fresh Run at this round boundary.
                // The round's tool/result rows are already persisted above, so ending here is
                // clean: slot release drains the complete FIFO batch into one merged USER message,
                // and the new generation continues from those durable tool results.
                if (callbacks.hasQueuedSends()) {
                    endedAtGuidanceBoundary = true
                    break
                }

                uiUpdateGate.reset()

                val apiToolPath = if (providerConfig.requestResolver != null) {
                    toolPath
                } else {
                    projectGenerationInputMessages(
                        messages = toolPath,
                        includeImages = providerConfig.includeImages,
                        userPrepend = config.userPrepend,
                        userPostpend = config.userPostpend,
                        assistantPrepend = config.assistantPrepend,
                        assistantPostpend = config.assistantPostpend,
                    )
                }
                acceptProviderPass(collectProviderRequest(apiToolPath))
                output.thoughtTiming.finishCurrent()
                output.currentStatus = statusAfterThoughtPhaseFinished(output.currentStatus)
                if (output.currentStatus != MessageStatus.ERROR) executeAcceptedToolBatch()
                // Publish the round's final UI state immediately. The next loop boundary or the
                // terminal transaction supplies durability, so blocking here would only duplicate
                // I/O and visibly delay the transition out of generating.
                publishStreamUpdate()
            }

            if (!currentCoroutineContext().isActive) {
                output.currentStatus = MessageStatus.STOPPED
            }

            if (output.currentStatus != MessageStatus.ERROR) {
                // A queue-steered interruption is a SUCCESSFUL turn even with no answer text —
                // its value is the persisted tool activity.
                output.currentStatus = if (
                    output.totalText.isNotEmpty() ||
                    output.totalThoughts.isNotEmpty() ||
                    endedAtGuidanceBoundary ||
                    endedForFollowUp
                ) {
                    MessageStatus.SUCCESS
                } else MessageStatus.ERROR
            }
            output.generationErrorMessage = terminalGenerationErrorMessage(
                status = output.currentStatus,
                currentError = output.generationErrorMessage,
                fallbackError = context.getString(R.string.failed_to_generate),
            )
            if (generationJob?.isCancelled == true && output.currentStatus != MessageStatus.ERROR) {
                output.currentStatus = MessageStatus.STOPPED
            }
            } // else { // called buildApiPath when output.currentStatus == ERROR
        } catch (e: CancellationException) {
            // transcribe() owns its mutable segment list until it returns. If cancellation lands
            // mid-transcription, copy the latest durable/UI snapshot into the terminal accumulator
            // so the final upsert does not overwrite that checkpoint with empty content.
            adoptIncompleteTranscriptionSnapshot()
            output.toolOverlay.stopIncompleteTools()
            output.currentStatus = MessageStatus.STOPPED
            throw e
        } catch (e: Exception) {
            adoptIncompleteTranscriptionSnapshot()
            val isCancelled = generationJob?.isCancelled == true
            output.currentStatus = if (isCancelled) MessageStatus.STOPPED else MessageStatus.ERROR
            if (!isCancelled) {
                output.generationErrorMessage =
                    "Error: ${e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName}"
            }
        } finally {
            // Fence the asynchronous checkpoint lane before any terminal transaction. Without
            // this join, an older SENDING snapshot could finish after SUCCESS/STOPPED and revive
            // the exact UI state the terminal write just closed.
            withContext(NonCancellable) {
                checkpoints.close()
            }
            // The mailbox, rather than a mutable token check in this finally block, chooses the
            // one terminal effect that may write Room. A concurrent Stop wins by entering
            // Stopping first; a natural completion wins by entering Finalizing first.
            withContext(NonCancellable) {
                try {
                    val conversationExists = conversations.getConversation(conversationId) != null
                    if (conversationExists) {
                        output.thoughtTiming.finishCurrent()
                        // Bound the row's toolCallJson aggregate (#51) and the unbounded answer
                        // text column — together they can exceed the 2MB CursorWindow otherwise.
                        val generatedMessage = GenerationFinalSnapshot(
                            messageId = modelMessageId,
                            parentId = parentId,
                            text = output.totalText.toString(),
                            images = output.generatedImages.toList(),
                            thoughts = output.totalThoughts,
                            thoughtTitle = output.totalThoughtTitle,
                            tokenCount = output.totalTokenCount,
                            tokenUsage = output.totalTokenUsage,
                            status = output.currentStatus,
                            timestamp = startTime,
                            thoughtTimeMs = output.thoughtTiming.totalDurationMs,
                            modelName = modelName,
                            flushedSegments = output.toolOverlay.snapshot(),
                            answerBuffer = output.currentAnswerBuf.toString(),
                            thoughtBuffer = output.currentThoughtBuf.toString(),
                            thoughtSignature = output.currentThoughtSignature,
                            thoughtSignatureProvider = output.currentThoughtSignatureProvider,
                            thoughtDurationMs = output.thoughtTiming.currentDurationMs.takeIf { it > 0L },
                            errorMessage = output.generationErrorMessage,
                            errorCode = output.generationErrorCode,
                            runId = runId,
                            runSequence = modelRunSequence,
                            answerDeltas = output.currentAnswerDeltas.toList(),
                        ).toMessage()
                        val finalMessage = projectOutput(generatedMessage)
                        terminalOutputText = finalMessage.text
                        terminalConversationVisible = callbacks.isConversationVisible?.invoke()
                        val terminalDisposition = generationTerminalDisposition(
                            messageStatus = output.currentStatus,
                            hasPendingGuidance =
                                callbacks.hasQueuedSends() || endedForFollowUp,
                            conversationVisible = terminalConversationVisible,
                        )
                        val finalizationIdentity = RunEffectIdentity(
                            conversationId = conversationId,
                            ownerToken = ownerToken,
                            runId = runId,
                            pass = pass,
                            effectId = "finalize-$runId-$pass",
                        )
                        val outcome = runFinalizationExecutor.execute(
                            request = GenerationRunFinalizationRequest(
                                identity = finalizationIdentity,
                                message = finalMessage,
                                status = terminalDisposition.runStatus,
                                reason = terminalDisposition.endReason,
                                markConversationUnread = terminalDisposition.markConversationUnread,
                            ),
                            callbacks = callbacks.runFinalizationCallbacks(),
                        )
                        if (outcome is GenerationRunFinalizationOutcome.Settled) {
                            terminalPersisted = outcome.terminalPersisted
                            // Keep the exact final snapshot as the overlay even when Room failed.
                            // It remains non-authoritative, but gives a later explicit Stop the
                            // complete content to persist instead of an older SENDING checkpoint.
                            onStreamUpdate(finalMessage)
                            if (!terminalPersisted) {
                                val failure =
                                    (outcome.durableResult as? RunFinalizationEffectCoordinator.Result.Failed)
                                        ?.lastFailure
                                val message =
                                    "Terminal generation effect failed after ${outcome.durableResult.attempts} attempts: " +
                                        "message=$modelMessageId run=$runId status=${output.currentStatus}"
                                if (failure != null) DebugLog.e("AgoraVM", message, failure)
                                else DebugLog.e("AgoraVM", message)
                            }
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.e("AgoraVM", "Failed to execute terminal generation effect", e)
                    throw e
                }
            }
            completionEffects.execute(
                request = GenerationCompletionEffectsRequest(
                    terminalPersisted = terminalPersisted,
                    status = output.currentStatus,
                    text = terminalOutputText,
                    notificationText = if (output.currentStatus == MessageStatus.ERROR) {
                        output.generationErrorMessage.orEmpty()
                    } else {
                        terminalOutputText
                    },
                    conversationId = conversationId,
                    modelMessageId = modelMessageId,
                    foregroundLeaseAcquired = foregroundLeaseAcquired,
                    isContextCompact = modelMessageId.startsWith(Constants.COMPACT_MSG_PREFIX),
                    conversationVisible = terminalConversationVisible,
                    hasPendingContinuation = endedForFollowUp,
                ),
                callbacks = callbacks.completionEffectsCallbacks(onMessagePersisted),
            )
        }
        GenerationExecutionResult(
            followUpParentMessageId = followUpParentMessageId,
        )
    }
}
