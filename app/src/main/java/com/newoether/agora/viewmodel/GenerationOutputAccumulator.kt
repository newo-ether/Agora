package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.RequestTokenUsageAccumulator
import com.newoether.agora.model.StreamingTextDelta
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.tool.ToolImageStore
import kotlinx.serialization.json.put

/** One generation's output buffers and event transitions; execution and persistence stay outside. */
internal class GenerationOutputAccumulator(
    toolExecutor: GenerationToolPresentationSource,
    providerName: String,
) {
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
    val toolOverlay = GenerationToolOverlay(toolExecutor, providerName)
    val generatedImages = mutableListOf<String>()

    /** Inline model-generated images (direct-chat image models). Counts toward run success. */
    var totalImageCount = 0
        private set

    private var cachedImageStore: ToolImageStore? = null

    /** Lazily created per-generation store for direct-chat generated images. */
    private fun lazyImageStore(context: Context): ToolImageStore =
        cachedImageStore ?: ToolImageStore(context.applicationContext).also { cachedImageStore = it }
    var currentAnswerBuf = StringBuilder()
    var currentAnswerDeltas = mutableListOf<StreamingTextDelta>()
    var nextAnswerDeltaSequence = 0L
    var currentThoughtBuf = StringBuilder()
    var currentThoughtSignature: String? = null
    var currentThoughtSignatureProvider: String? = null

    private var restoreProviderAttempt: (() -> Unit)? = null

    /** One baseline per Provider request; transport retries never replace completed tool rounds. */
    fun beginProviderRequest() {
        tokenUsageAccumulator.beginRequest()
        val text = totalText.toString()
        val thoughts = totalThoughts
        val title = totalThoughtTitle
        val status = currentStatus
        val answer = currentAnswerBuf.toString()
        val deltas = currentAnswerDeltas.toList()
        val deltaSequence = nextAnswerDeltaSequence
        val thought = currentThoughtBuf.toString()
        val signature = currentThoughtSignature
        val signatureProvider = currentThoughtSignatureProvider
        val restoreOverlay = toolOverlay.capture()
        val restoreTiming = thoughtTiming.capture()
        restoreProviderAttempt = {
            totalText.clear()
            totalText.append(text)
            totalThoughts = thoughts
            totalThoughtTitle = title
            currentStatus = status
            currentAnswerBuf = StringBuilder(answer)
            currentAnswerDeltas = deltas.toMutableList()
            nextAnswerDeltaSequence = deltaSequence
            currentThoughtBuf = StringBuilder(thought)
            currentThoughtSignature = signature
            currentThoughtSignatureProvider = signatureProvider
            generationErrorMessage = null
            generationErrorCode = null
            restoreOverlay()
            restoreTiming()
            tokenUsageAccumulator.resetCurrentAttempt()
            totalTokenUsage = tokenUsageAccumulator.snapshot()
            totalTokenCount = totalTokenUsage?.totalTokenCount ?: 0
        }
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

    suspend fun handleStreamEvent(
        event: StreamEvent,
        providerAnswerStart: Int,
        provider: LlmProvider,
        context: Context,
        completedToolCalls: Map<String, StreamEvent.ToolCallRequest>,
        uiUpdateGate: StreamingUiUpdateGate,
        publishStreamUpdate: suspend (Boolean) -> Unit,
        publishRetrySnapshot: suspend () -> Unit,
    ) {
        when (event) {
            is StreamEvent.TextChunk -> if (event.text.isNotEmpty()) tokenUsageAccumulator.observeGenerationContent()
            is StreamEvent.ImageChunk -> if (event.bytes.isNotEmpty()) tokenUsageAccumulator.observeGenerationContent()
            is StreamEvent.ThoughtChunk -> if (event.thought.isNotEmpty()) tokenUsageAccumulator.observeGenerationContent()
            is StreamEvent.ToolCallUpdate -> if (event.arguments.isNotEmpty()) tokenUsageAccumulator.observeGenerationContent()
            is StreamEvent.HostedToolCallUpdate -> tokenUsageAccumulator.pauseGeneration()
            is StreamEvent.Retrying -> {
                tokenUsageAccumulator.resetCurrentAttempt()
                totalTokenUsage = tokenUsageAccumulator.snapshot()
                totalTokenCount = totalTokenUsage?.totalTokenCount ?: 0
            }
            else -> Unit
        }
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
            is StreamEvent.ImageChunk -> {
                flushAnswerSegment()
                flushThoughtSegment()
                retryText = null
                val attachment = lazyImageStore(context).persistGeneratedBytes(
                    bytes = event.bytes,
                    filePrefix = "generated_image",
                )
                totalImageCount++
                // The image renders through the generate_image tool segment; do NOT also add
                // it to generatedImages — model-row images would render it a second time.
                toolOverlay.append(
                    MessageSegment(
                        type = "tool",
                        toolName = "generate_image",
                        toolState = ToolExecutionStates.SUCCEEDED,
                        toolImages = listOf(attachment),
                        toolResult = kotlinx.serialization.json.buildJsonObject {
                            put("type", "image_generation")
                            put("status", "ok")
                        }.toString(),
                    ),
                )
                currentStatus = MessageStatus.TOOL_CALLING
                publishStreamUpdate(true)
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
                if (event.resetCurrentAttempt) {
                    checkNotNull(restoreProviderAttempt) {
                        "Provider attempt reset arrived without a request baseline"
                    }.invoke()
                }
                retryText = context.getString(R.string.generation_retry_attempt, event.attempt, event.maxAttempts)
                publishRetrySnapshot()
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
                    publishStreamUpdate(created || event.result != null)
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
                    publishStreamUpdate(created)
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
                publishStreamUpdate(true)
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
                publishStreamUpdate(true)
                uiUpdateGate.recordPublished(System.currentTimeMillis())
            }
        }

        val now = System.currentTimeMillis()
        val isSignificant =
            event is StreamEvent.Error || event is StreamEvent.CitationUpdate
        if (uiUpdateGate.isDue(now) || isSignificant) {
            publishStreamUpdate(isSignificant)
            uiUpdateGate.recordPublished(now)
        }
    }
}
