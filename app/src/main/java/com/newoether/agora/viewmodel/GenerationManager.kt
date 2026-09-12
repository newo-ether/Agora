package com.newoether.agora.viewmodel

import android.app.Application
import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.GenerationError
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
import com.newoether.agora.model.StreamingTextDelta
import com.newoether.agora.model.RequestTokenUsageAccumulator
import com.newoether.agora.model.TokenUsage
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
        releaseForegroundLease = AgoraForegroundService::releaseLease,
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
            maxContextWindow = config.maxContextWindow,
            codeExecutionEnabled = config.codeExecutionEnabled,
            googleSearchEnabled = config.googleSearchEnabled,
            openAiWebSearchEnabled = config.openAiWebSearchEnabled,
            tools = definitions,
            includeImages = !context.imageTranscriptionEnabled,
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
        val totalText = StringBuilder()
        var totalThoughts = ""
        var thinkingPlaceholder = ""
        var totalThoughtTitle: String? = null
        var totalTokenCount = 0
        var totalTokenUsage: TokenUsage? = null
        val tokenUsageAccumulator = RequestTokenUsageAccumulator()
        val thoughtTiming = GenerationThoughtTiming()
        var currentStatus = MessageStatus.SENDING
        var generationErrorMessage: String? = null
        var generationErrorCode: String? = null
        var retryText: String? = null
        val toolOverlay = GenerationToolOverlay(toolExecutor, config.providerName)
        val generatedImages = mutableListOf<String>()
        var currentAnswerBuf = StringBuilder()
        var currentAnswerDeltas = mutableListOf<StreamingTextDelta>()
        var nextAnswerDeltaSequence = 0L
        var currentThoughtBuf = StringBuilder()
        var currentThoughtSignature: String? = null
        var currentThoughtSignatureProvider: String? = null
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
                totalText.clear()
                totalText.append(snapshot.text)
                totalThoughts = snapshot.thoughts.orEmpty()
                totalThoughtTitle = snapshot.thoughtTitle
                totalTokenCount = snapshot.tokenCount
                totalTokenUsage = snapshot.tokenUsage
                thoughtTiming.adoptTotalDuration(snapshot.thoughtTimeMs)
                generatedImages.clear()
                generatedImages.addAll(snapshot.images)
                toolOverlay.replaceAll(snapshot.segments.orEmpty())
            }
        }

        try {
            val provider = requireRegisteredProvider(providerInstances, config.providerName)
            onLoadingChange(true)
            // Slot ownership (generating flag / active set) is claimed synchronously by the
            // controller before this coroutine runs — GenerationManager no longer touches it.
            com.newoether.agora.util.CrashReporter.note("generate provider=${config.providerName}")
            thinkingPlaceholder = context.getString(R.string.thinking_ellipsis)
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
                        AgoraForegroundService.acquireLease(modelMessageId)
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
                toolOverlay.prependAll(transcription.segments)
            }
            if (transcription.error != null) {
                generationErrorMessage = transcription.error
                currentStatus = MessageStatus.ERROR
            }

            if (currentStatus != MessageStatus.ERROR) {
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
                text = totalText.toString(), thoughts = totalThoughts.ifBlank { null },
                thoughtTitle = totalThoughtTitle, tokenCount = totalTokenCount,
                tokenUsage = totalTokenUsage,
                status = currentStatus, participant = Participant.MODEL,
                timestamp = startTime, thoughtTimeMs = thoughtTiming.totalDurationMs,
                modelName = modelName, toolCall = toolCallData,
                images = generatedImages.toList(),
                segments = buildLiveSegments(
                    toolOverlay.snapshot(),
                    currentAnswerBuf,
                    currentThoughtBuf,
                    currentThoughtSignature,
                    currentThoughtSignatureProvider,
                    thoughtTiming.liveDurationMs(),
                    generationErrorMessage,
                    generationErrorCode,
                    answerDeltas = currentAnswerDeltas,
                ),
                retryText = retryText,
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

            fun flushAnswerSegment() {
                if (currentAnswerBuf.isNotEmpty()) {
                    toolOverlay.append(
                        MessageSegment(
                            type = "answer",
                            content = currentAnswerBuf.toString(),
                            streamingTextDeltas = currentAnswerDeltas.toList(),
                        ),
                    )
                    currentAnswerBuf = StringBuilder()
                    currentAnswerDeltas = mutableListOf()
                }
            }

            fun flushThoughtSegment() {
                thoughtTiming.finishCurrent()
                if (currentThoughtBuf.isNotEmpty()) {
                    toolOverlay.append(
                        MessageSegment(
                            type = "thought",
                            content = currentThoughtBuf.toString(),
                            signature = currentThoughtSignature,
                            signatureProvider = currentThoughtSignatureProvider,
                            durationMs = thoughtTiming.currentDurationMs.takeIf { it > 0L },
                        ),
                    )
                    currentThoughtBuf = StringBuilder()
                    currentThoughtSignature = null
                    currentThoughtSignatureProvider = null
                }
                thoughtTiming.resetCurrentDuration()
            }

            fun upsertStreamingToolSegment(
                streamKey: String,
                toolCallId: String?,
                name: String,
                arguments: String,
                signature: String?,
            ): Boolean {
                if (!toolOverlay.hasStream(streamKey)) {
                    flushAnswerSegment()
                    flushThoughtSegment()
                }
                return toolOverlay.upsert(streamKey, toolCallId, name, arguments, signature)
            }

            suspend fun executeAcceptedToolBatch() {
                if (completedToolCalls.isEmpty()) return
                val batchEffect = toolRoundEffects.requireBatchEffect()
                val calls = completedToolCalls.values.toList()
                completedToolCalls.clear()
                currentStatus = MessageStatus.TOOL_CALLING
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
                    overlay = toolOverlay,
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
                currentStatus = MessageStatus.SENDING
                publishStreamUpdate(forceCheckpoint = true)
                uiUpdateGate.recordPublished(System.currentTimeMillis())
            }

            suspend fun handleStreamEvent(
                event: StreamEvent,
                providerAnswerStart: Int,
            ) {
                when (event) {
                    is StreamEvent.TextChunk -> {
                        val answerText = if (currentStatus == MessageStatus.THINKING) event.text.trimStart() else event.text
                        if (currentStatus == MessageStatus.THINKING && answerText.isBlank()) {
                            retryText = null
                            return
                        }
                        if (currentStatus == MessageStatus.THINKING) {
                            flushThoughtSegment()
                        }
                        totalText.append(answerText)
                        currentAnswerBuf.append(answerText)
                        val deltaCodePointCount =
                            answerText.codePointCount(0, answerText.length)
                        if (deltaCodePointCount > 0) {
                            currentAnswerDeltas += StreamingTextDelta(
                                sequence = nextAnswerDeltaSequence++,
                                codePointCount = deltaCodePointCount,
                            )
                        }
                        if (answerText.isNotBlank()) {
                            currentStatus = MessageStatus.SENDING
                        }
                        retryText = null
                    }
                    is StreamEvent.CitationUpdate -> {
                        toolOverlay.upsertCitation(
                            rebaseCitationForFinalAnswer(
                                citation = event.citation,
                                providerAnswerStart = providerAnswerStart,
                                finalAnswer = totalText.toString(),
                            ),
                        )
                    }
                    is StreamEvent.ThoughtChunk -> {
                        val updatedCompletedThought = event.thought.isEmpty() &&
                            currentStatus != MessageStatus.THINKING &&
                            (event.title != null || event.signature != null) &&
                            toolOverlay.updateLastThoughtMetadata(
                                signature = event.signature,
                                signatureProvider = provider.name.takeIf {
                                    event.signature != null
                                },
                            )
                        if (updatedCompletedThought) {
                            if (event.title != null) totalThoughtTitle = event.title
                            return
                        }
                        flushAnswerSegment()
                        currentStatus = MessageStatus.THINKING
                        retryText = null
                        thoughtTiming.ensureStarted()
                        if (totalThoughts.isEmpty()) totalThoughts = thinkingPlaceholder
                        if (event.thought.isNotEmpty()) {
                            currentThoughtBuf.append(event.thought)
                            if (totalThoughts == thinkingPlaceholder) totalThoughts = event.thought
                            else totalThoughts += event.thought
                        }
                        if (event.title != null) totalThoughtTitle = event.title
                        if (event.signature != null) {
                            currentThoughtSignature = event.signature
                            currentThoughtSignatureProvider = provider.name
                        }
                    }
                    is StreamEvent.UsageUpdate -> {
                        tokenUsageAccumulator.observeRequestSnapshot(event.usage)
                        totalTokenUsage = tokenUsageAccumulator.snapshot()
                        totalTokenCount = totalTokenUsage?.totalTokenCount ?: 0
                        if (totalText.isEmpty() && event.thoughtsTokenCount > 0) {
                            currentStatus = MessageStatus.THINKING
                            thoughtTiming.ensureStarted()
                            if (totalThoughts.isEmpty()) totalThoughts = thinkingPlaceholder
                        }
                    }
                    is StreamEvent.Retrying -> {
                        retryText = context.getString(R.string.generation_retry_attempt, event.attempt, event.maxAttempts)
                        onStreamUpdate(projectOutput(modelMessage()))
                    }
                    is StreamEvent.Error -> {
                        flushThoughtSegment()
                        flushAnswerSegment()
                        retryText = null
                        toolOverlay.failIncompleteStreams(completedToolCalls.keys)
                        currentStatus = MessageStatus.ERROR
                        generationErrorMessage = localizedGenerationError(context, event.error)
                        generationErrorCode = (event.error as? GenerationError.LocalModel)?.code
                    }
                    is StreamEvent.HostedToolCallUpdate -> {
                        if (!toolOverlay.hasStream(event.streamKey)) {
                            flushAnswerSegment()
                            flushThoughtSegment()
                        }
                        val created = toolOverlay.upsertHosted(event)
                        currentStatus = MessageStatus.TOOL_CALLING
                        retryText = null
                        val now = System.currentTimeMillis()
                        if (created || event.result != null || uiUpdateGate.isDue(now)) {
                            publishStreamUpdate(forceCheckpoint = created || event.result != null)
                            uiUpdateGate.recordPublished(now)
                        }
                    }
                    is StreamEvent.ToolCallUpdate -> {
                        val created = upsertStreamingToolSegment(
                            streamKey = event.streamKey,
                            toolCallId = event.id,
                            name = event.name,
                            arguments = event.arguments,
                            signature = event.signature,
                        )
                        currentStatus = MessageStatus.TOOL_CALLING
                        retryText = null
                        val now = System.currentTimeMillis()
                        if (created || uiUpdateGate.isDue(now)) {
                            publishStreamUpdate(forceCheckpoint = created)
                            uiUpdateGate.recordPublished(now)
                        }
                    }
                    is StreamEvent.ToolCallRequest -> {
                        upsertStreamingToolSegment(
                            streamKey = event.streamKey,
                            toolCallId = event.id,
                            name = event.name,
                            arguments = event.arguments,
                            signature = event.signature,
                        )
                        currentStatus = MessageStatus.TOOL_CALLING
                        publishStreamUpdate(forceCheckpoint = true)
                        uiUpdateGate.recordPublished(System.currentTimeMillis())
                    }
                    is StreamEvent.ToolCallsRequest -> {
                        event.calls.forEach { call ->
                            upsertStreamingToolSegment(
                                streamKey = call.streamKey,
                                toolCallId = call.id,
                                name = call.name,
                                arguments = call.arguments,
                                signature = call.signature,
                            )
                        }
                        currentStatus = MessageStatus.TOOL_CALLING
                        publishStreamUpdate(forceCheckpoint = true)
                        uiUpdateGate.recordPublished(System.currentTimeMillis())
                    }
                }

                val now = System.currentTimeMillis()
                val isSignificant =
                    event is StreamEvent.Error || event is StreamEvent.CitationUpdate
                if (uiUpdateGate.isDue(now) || isSignificant) {
                    publishStreamUpdate(forceCheckpoint = isSignificant)
                    uiUpdateGate.recordPublished(now)
                }
            }

            suspend fun collectProviderRequest(
                messages: List<ChatMessage>,
                onFirstEvent: (() -> Unit)? = null,
            ): ProviderPassOutcome {
                val providerAnswerStart = totalText.length
                tokenUsageAccumulator.beginRequest()
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
                                handleStreamEvent(event, providerAnswerStart)
                            },
                        ),
                    )
                } finally {
                    tokenUsageAccumulator.finishRequest()
                    totalTokenUsage = tokenUsageAccumulator.snapshot()
                    totalTokenCount = totalTokenUsage?.totalTokenCount ?: totalTokenCount
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
                    -> check(currentStatus == MessageStatus.ERROR) {
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
            thoughtTiming.finishCurrent()
            currentStatus = statusAfterThoughtPhaseFinished(currentStatus)
            if (currentStatus != MessageStatus.ERROR) executeAcceptedToolBatch()
            // Publish the final in-memory snapshot without waiting for another Room round trip.
            // The terminal transaction below persists this exact state after fencing the
            // checkpoint writer, while genuine tool lifecycle boundaries remain forced.
            if (generationJob?.isCancelled != true) {
                publishStreamUpdate()
            }

            // Multi-tool loop
            var toolRound = 0
            toolPath = currentPath

            while (toolCallDataList.isNotEmpty() && currentStatus != MessageStatus.ERROR && currentCoroutineContext().isActive) {
                toolRound++
                val roundToolList = roundToolSegments.toList()
                roundToolSegments.clear()
                val thoughtSegs = toolRoundThoughtSegments(
                    segments = toolOverlay.contentSnapshot(),
                    fromIndex = toolRoundSegmentCursor,
                )
                val txedSegments = if (thoughtSegs.isNotEmpty()) thoughtSegs + roundToolList else roundToolList
                toolRoundSegmentCursor = toolOverlay.size
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
                toolOverlay.releaseCommittedResponseState(
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
                thoughtTiming.finishCurrent()
                currentStatus = statusAfterThoughtPhaseFinished(currentStatus)
                if (currentStatus != MessageStatus.ERROR) executeAcceptedToolBatch()
                // Publish the round's final UI state immediately. The next loop boundary or the
                // terminal transaction supplies durability, so blocking here would only duplicate
                // I/O and visibly delay the transition out of generating.
                publishStreamUpdate()
            }

            if (!currentCoroutineContext().isActive) {
                currentStatus = MessageStatus.STOPPED
            }

            if (currentStatus != MessageStatus.ERROR) {
                // A queue-steered interruption is a SUCCESSFUL turn even with no answer text —
                // its value is the persisted tool activity.
                currentStatus = if (
                    totalText.isNotEmpty() ||
                    totalThoughts.isNotEmpty() ||
                    endedAtGuidanceBoundary ||
                    endedForFollowUp
                ) {
                    MessageStatus.SUCCESS
                } else MessageStatus.ERROR
            }
            generationErrorMessage = terminalGenerationErrorMessage(
                status = currentStatus,
                currentError = generationErrorMessage,
                fallbackError = context.getString(R.string.failed_to_generate),
            )
            if (generationJob?.isCancelled == true && currentStatus != MessageStatus.ERROR) {
                currentStatus = MessageStatus.STOPPED
            }
            } // else { // called buildApiPath when currentStatus == ERROR
        } catch (e: CancellationException) {
            // transcribe() owns its mutable segment list until it returns. If cancellation lands
            // mid-transcription, copy the latest durable/UI snapshot into the terminal accumulator
            // so the final upsert does not overwrite that checkpoint with empty content.
            adoptIncompleteTranscriptionSnapshot()
            toolOverlay.stopIncompleteTools()
            currentStatus = MessageStatus.STOPPED
            throw e
        } catch (e: Exception) {
            adoptIncompleteTranscriptionSnapshot()
            val isCancelled = generationJob?.isCancelled == true
            currentStatus = if (isCancelled) MessageStatus.STOPPED else MessageStatus.ERROR
            if (!isCancelled) {
                generationErrorMessage =
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
                        thoughtTiming.finishCurrent()
                        // Bound the row's toolCallJson aggregate (#51) and the unbounded answer
                        // text column — together they can exceed the 2MB CursorWindow otherwise.
                        val generatedMessage = GenerationFinalSnapshot(
                            messageId = modelMessageId,
                            parentId = parentId,
                            text = totalText.toString(),
                            images = generatedImages.toList(),
                            thoughts = totalThoughts,
                            thoughtTitle = totalThoughtTitle,
                            tokenCount = totalTokenCount,
                            tokenUsage = totalTokenUsage,
                            status = currentStatus,
                            timestamp = startTime,
                            thoughtTimeMs = thoughtTiming.totalDurationMs,
                            modelName = modelName,
                            flushedSegments = toolOverlay.snapshot(),
                            answerBuffer = currentAnswerBuf.toString(),
                            thoughtBuffer = currentThoughtBuf.toString(),
                            thoughtSignature = currentThoughtSignature,
                            thoughtSignatureProvider = currentThoughtSignatureProvider,
                            thoughtDurationMs = thoughtTiming.currentDurationMs.takeIf { it > 0L },
                            errorMessage = generationErrorMessage,
                            errorCode = generationErrorCode,
                            runId = runId,
                            runSequence = modelRunSequence,
                            answerDeltas = currentAnswerDeltas.toList(),
                        ).toMessage()
                        val finalMessage = projectOutput(generatedMessage)
                        terminalOutputText = finalMessage.text
                        terminalConversationVisible = callbacks.isConversationVisible?.invoke()
                        val terminalDisposition = generationTerminalDisposition(
                            messageStatus = currentStatus,
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
                                        "message=$modelMessageId run=$runId status=$currentStatus"
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
                    status = currentStatus,
                    text = terminalOutputText,
                    notificationText = if (currentStatus == MessageStatus.ERROR) {
                        generationErrorMessage.orEmpty()
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
