package com.newoether.agora.viewmodel

import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.model.toCitationRecord
import com.newoether.agora.model.toMessageSegment
import com.newoether.agora.tool.ToolExecutionEvent
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.util.Constants

private const val TOOL_PROGRESS_UI_UPDATE_INTERVAL_MS = 50L

internal class GenerationToolOverlay(
    private val presentation: GenerationToolPresentationSource,
    private val providerName: String,
) {
    private val segments = mutableListOf(MessageSegment(type = "answer"))
    private val citations = mutableListOf<CitationRecord>()
    private val streamIndices = mutableMapOf<String, Int>()

    val size: Int
        get() = segments.size

    fun snapshot(): List<MessageSegment> =
        segments.toList() + citations.map { it.toMessageSegment() }

    /** Request-boundary rollback includes merged citations and live stream-to-segment indices. */
    fun capture(): () -> Unit {
        val savedSegments = segments.toList()
        val savedCitations = citations.toList()
        val savedIndices = streamIndices.toMap()
        return {
            segments.clear()
            segments.addAll(savedSegments)
            citations.clear()
            citations.addAll(savedCitations)
            streamIndices.clear()
            streamIndices.putAll(savedIndices)
        }
    }

    fun contentSnapshot(): List<MessageSegment> = segments.toList()

    fun replaceAll(replacement: List<MessageSegment>) {
        segments.clear()
        citations.clear()
        replacement.forEach { segment ->
            if (segment.type == "citation") {
                segment.toCitationRecord()?.let(::upsertCitation)
            } else {
                segments += segment
            }
        }
        streamIndices.clear()
    }

    fun prependAll(prefix: List<MessageSegment>) {
        if (prefix.isEmpty()) return
        val contentPrefix = prefix.filter { it.type != "citation" }
        prefix.mapNotNull { it.toCitationRecord() }.forEach(::upsertCitation)
        if (contentPrefix.isEmpty()) return
        segments.addAll(0, contentPrefix)
        streamIndices.entries.forEach { entry -> entry.setValue(entry.value + contentPrefix.size) }
    }

    fun append(segment: MessageSegment) {
        if (segment.type == "citation") {
            segment.toCitationRecord()?.let(::upsertCitation)
        } else {
            appendMergedSegment(segments, segment)
        }
    }

    /**
     * Appends a thinking-style transcription segment (the same type the main transcription
     * stage streams) and returns its index for live updates. Consecutive calls produce
     * consecutive segments, matching the main flow's ordinal labeling.
     */
    fun appendTranscriptionSegment(content: String): Int {
        val index = segments.size
        segments += MessageSegment(type = "transcription", content = content)
        return index
    }

    fun updateTranscriptionSegment(index: Int, content: String) {
        if (index !in segments.indices) return
        segments[index] = segments[index].copy(content = content)
    }

    fun updateLastThoughtMetadata(
        signature: String?,
        signatureProvider: String?,
    ): Boolean {
        val index = segments.indexOfLast { segment -> segment.type == "thought" }
        if (index < 0) return false
        if (signature != null) {
            segments[index] = segments[index].copy(
                signature = signature,
                signatureProvider = signatureProvider,
            )
        }
        return true
    }

    fun upsertCitation(raw: CitationRecord): Boolean {
        val citation = CitationPolicy.normalize(raw) ?: return false
        val index = citations.indexOfFirst { it.sourceId == citation.sourceId }
        if (index < 0) {
            if (citations.size >= CitationPolicy.MAX_SOURCES) return false
            citations += citation
            return true
        }
        val merged = CitationPolicy.merge(citations[index], citation)
        val changed = merged != citations[index]
        citations[index] = merged
        return changed
    }

    fun hasStream(streamKey: String): Boolean = streamKey in streamIndices

    fun upsert(
        streamKey: String,
        toolCallId: String?,
        name: String,
        arguments: String,
        signature: String?,
    ): Boolean {
        val existingIndex = streamIndices[streamKey]
        if (existingIndex != null) {
            val existing = segments[existingIndex]
            val resolvedName = name.ifBlank { existing.toolName.orEmpty() }
            val metadata = presentation.presentationMetadata(resolvedName, arguments)
            segments[existingIndex] = existing.copy(
                toolName = resolvedName.ifBlank { existing.toolName },
                toolArgs = arguments,
                toolCallId = toolCallId ?: existing.toolCallId ?: streamKey,
                signature = signature ?: existing.signature,
                signatureProvider = providerName.takeIf {
                    signature != null || existing.signature != null
                },
                toolState = ToolExecutionStates.CALLING,
                toolTarget = metadata?.target ?: existing.toolTarget,
                toolDisplayName = metadata?.displayName ?: existing.toolDisplayName,
            )
            return false
        }

        val index = segments.size
        val metadata = presentation.presentationMetadata(name, arguments)
        segments += MessageSegment(
            type = "tool",
            toolName = name.ifBlank { null },
            toolArgs = arguments,
            toolResult = null,
            toolCallId = toolCallId ?: streamKey,
            signature = signature,
            signatureProvider = providerName.takeIf { signature != null },
            toolState = ToolExecutionStates.CALLING,
            toolTarget = metadata?.target,
            toolDisplayName = metadata?.displayName,
        )
        streamIndices[streamKey] = index
        return true
    }

    fun upsertHosted(event: StreamEvent.HostedToolCallUpdate): Boolean {
        val created = upsert(
            streamKey = event.streamKey,
            toolCallId = event.streamKey,
            name = event.name,
            arguments = event.arguments,
            signature = null,
        )
        val index = checkNotNull(streamIndices[event.streamKey])
        val current = segments[index]
        segments[index] = current.copy(
            toolResult = event.result,
            toolState = when {
                event.result == null -> ToolExecutionStates.RUNNING
                event.isError -> ToolExecutionStates.FAILED
                else -> ToolExecutionStates.SUCCEEDED
            },
        )
        return created
    }

    fun start(call: StreamEvent.ToolCallRequest) {
        val index = checkNotNull(streamIndices[call.streamKey]) {
            "Missing live segment for tool call ${call.streamKey}"
        }
        val current = segments[index]
        val metadata = presentation.presentationMetadata(call.name, call.arguments)
        segments[index] = current.copy(
            toolName = call.name,
            toolArgs = call.arguments,
            toolCallId = call.id,
            signature = call.signature,
            signatureProvider = providerName.takeIf { call.signature != null },
            responseOutputItems = call.responseOutputItems,
            responseOutputItemProvider = providerName.takeIf {
                call.responseOutputItems.isNotEmpty()
            },
            toolState = ToolExecutionStates.RUNNING,
            toolTarget = metadata?.target ?: current.toolTarget,
            toolDisplayName = metadata?.displayName ?: current.toolDisplayName,
        )
    }

    fun failUnpersistedStart(call: StreamEvent.ToolCallRequest) {
        val index = checkNotNull(streamIndices[call.streamKey]) {
            "Missing live segment for tool call ${call.streamKey}"
        }
        segments[index] = segments[index].copy(
            toolState = ToolExecutionStates.FAILED,
            responseOutputItems = emptyList(),
            responseOutputItemProvider = null,
        )
    }

    fun markCommittedRound(toolCallIds: Set<String>, toolRoundId: String) {
        if (toolCallIds.isEmpty()) return
        segments.indices.forEach { index ->
            val segment = segments[index]
            if (
                segment.type == "tool" &&
                segment.toolRoundId == null &&
                segment.toolCallId in toolCallIds
            ) {
                segments[index] = segment.copy(
                    toolRoundId = toolRoundId,
                    responseOutputItems = emptyList(),
                    responseOutputItemProvider = null,
                )
            }
        }
    }

    fun applyProgress(callId: String, event: ToolExecutionEvent) {
        val index = segments.indexOfLast { it.toolCallId == callId }
        if (index < 0) return
        val current = segments[index]
        segments[index] = when (event) {
            is ToolExecutionEvent.OutputDelta -> current.copy(
                toolState = ToolExecutionStates.RUNNING,
                toolProgress = appendBoundedToolOutput(current.toolProgress, event.text),
            )
            is ToolExecutionEvent.OutputSnapshot -> current.copy(
                toolState = ToolExecutionStates.RUNNING,
                toolProgress = takeLastWholeCodePoints(
                    event.text,
                    Constants.MAX_TOOL_RESULT_LENGTH,
                ),
            )
            is ToolExecutionEvent.TargetResolved -> current.copy(
                toolTarget = event.target,
                toolDisplayName = event.displayName ?: current.toolDisplayName,
            )
            is ToolExecutionEvent.Progress -> current.copy(toolState = ToolExecutionStates.RUNNING)
            is ToolExecutionEvent.Completed -> current
        }
    }

    fun complete(
        call: StreamEvent.ToolCallRequest,
        result: ToolExecutionResult,
        transcription: String? = null,
    ): CompletedToolCall {
        val index = checkNotNull(streamIndices[call.streamKey]) {
            "Missing live segment for tool call ${call.streamKey}"
        }
        val clipped = result.text.take(Constants.MAX_TOOL_RESULT_LENGTH)
        val displayText = result.displayText?.take(Constants.MAX_TOOL_RESULT_LENGTH)
        val structuredResult = result.structuredContent?.take(Constants.MAX_TOOL_RESULT_LENGTH)
        val completed = segments[index].copy(
            toolResult = clipped,
            toolResultText = displayText,
            toolStructuredResult = structuredResult,
            toolState = finalToolState(result, call.name),
            toolImages = result.images,
            toolTranscription = transcription,
        )
        segments[index] = completed
        return CompletedToolCall(
            segment = completed,
            data = ToolCallData(
                toolName = call.name,
                arguments = call.arguments,
                result = clipped,
                signature = call.signature,
                toolCallId = call.id,
                resultImages = result.images,
                displayName = completed.toolDisplayName,
                target = completed.toolTarget,
                resultText = displayText,
                structuredResult = structuredResult,
                responseOutputItems = completed.responseOutputItems,
                responseOutputItemProvider = completed.responseOutputItemProvider,
                transcription = transcription,
            ),
        )
    }

    fun failIncompleteStreams(completedStreamKeys: Set<String>) {
        streamIndices.forEach { (streamKey, index) ->
            val segment = segments[index]
            if (streamKey !in completedStreamKeys && segment.toolResult == null) {
                segments[index] = segment.copy(toolState = ToolExecutionStates.FAILED)
            }
        }
    }

    fun stopIncompleteTools() {
        segments.indices.forEach { index ->
            val segment = segments[index]
            if (segment.type == "tool" && segment.toolResult == null) {
                segments[index] = segment.copy(toolState = ToolExecutionStates.STOPPED)
            }
        }
    }
}

internal fun takeLastWholeCodePoints(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    var start = text.length - maxChars
    if (start > 0 && Character.isLowSurrogate(text[start]) && Character.isHighSurrogate(text[start - 1])) {
        start++
    }
    return text.substring(start)
}

internal data class CompletedToolCall(
    val segment: MessageSegment,
    val data: ToolCallData,
)

internal data class AuthorizedToolBatchRequest(
    val effect: RunEffect.ExecuteToolBatch,
    val calls: List<StreamEvent.ToolCallRequest>,
    val context: GenerationContext,
    val conversationId: String,
    val authorizedToolNames: Set<String>,
    /** Per-generation single-image transcription flow, resolved from this generation's
     *  provider instances. Null disables tool-result image transcription. */
    val toolImageTranscriber: (suspend (ToolImageAttachment, suspend (String) -> Unit) -> String?)? = null,
) {
    init {
        require(calls.isNotEmpty())
    }
}

internal data class AuthorizedToolBatchOutcome(
    val identity: RunEffectIdentity,
    val calls: List<ToolCallData>,
    val segments: List<MessageSegment>,
)

internal data class ToolBatchProgressCallbacks(
    val publish: suspend (forceCheckpoint: Boolean) -> Unit,
    val onPublishedAt: (Long) -> Unit,
)

/**
 * Executes one already-authorized tool batch without committing or continuing the Run.
 *
 * Tool-result image transcription uses the generation's configured transcriber for every image.
 * One description is published only after every occurrence succeeds, so a partial transcription
 * cannot replace an undescribed image. The executor has no tool-name or source routing.
 */
internal class GenerationToolBatchEffectExecutor(
    private val tools: GenerationToolExecutor,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(
        request: AuthorizedToolBatchRequest,
        overlay: GenerationToolOverlay,
        callbacks: ToolBatchProgressCallbacks,
    ): AuthorizedToolBatchOutcome {
        val results = mutableListOf<ToolCallData>()
        val completedSegments = mutableListOf<MessageSegment>()

        request.calls.forEach { call ->
            overlay.start(call)
            try {
                callbacks.publish(true)
                callbacks.onPublishedAt(nowMs())
            } catch (e: Exception) {
                overlay.failUnpersistedStart(call)
                throw e
            }

            var lastToolUiEmitMs = 0L
            val executed = tools.execute(
                AuthorizedToolCall(
                    batchIdentity = request.effect.identity,
                    callId = call.id,
                    name = call.name,
                    arguments = call.arguments,
                    context = request.context,
                    authorizedToolNames = request.authorizedToolNames,
                    toolImageTranscriber = request.toolImageTranscriber,
                ),
            ) { event ->
                if (event !is ToolExecutionEvent.Completed) {
                    overlay.applyProgress(call.id, event)
                    val now = nowMs()
                    if (now - lastToolUiEmitMs >= TOOL_PROGRESS_UI_UPDATE_INTERVAL_MS) {
                        callbacks.publish(false)
                        callbacks.onPublishedAt(now)
                        lastToolUiEmitMs = now
                    }
                }
            }
            check(executed.batchIdentity == request.effect.identity)
            check(executed.callId == call.id)
            val result = executed.result
            val transcriber = request.toolImageTranscriber
            var transcription: String? = null
            if (result.images.isNotEmpty() && transcriber != null) {
                val descriptions = mutableListOf<String>()
                for (toolImage in result.images) {
                    val segmentIndex = overlay.appendTranscriptionSegment("")
                    callbacks.publish(true)
                    callbacks.onPublishedAt(nowMs())
                    var lastTranscriptionUiEmitMs = 0L
                    var lastPartial = ""
                    val description = transcriber(toolImage) { partial ->
                        lastPartial = partial
                        overlay.updateTranscriptionSegment(segmentIndex, partial)
                        val now = nowMs()
                        if (now - lastTranscriptionUiEmitMs >= TOOL_PROGRESS_UI_UPDATE_INTERVAL_MS) {
                            callbacks.publish(false)
                            callbacks.onPublishedAt(now)
                            lastTranscriptionUiEmitMs = now
                        }
                    }
                    overlay.updateTranscriptionSegment(segmentIndex, description ?: lastPartial)
                    description?.takeIf(String::isNotBlank)?.let(descriptions::add)
                    callbacks.publish(false)
                    callbacks.onPublishedAt(nowMs())
                }
                if (descriptions.size == result.images.size) {
                    transcription = if (descriptions.size == 1) descriptions.single() else
                        descriptions.mapIndexed { index, text -> "[Image ${index + 1}]\n$text" }
                            .joinToString("\n\n")
                }
            }
            val completed = overlay.complete(call, result, transcription = transcription)
            completedSegments += completed.segment
            results += completed.data
            callbacks.publish(false)
            callbacks.onPublishedAt(nowMs())
        }

        return AuthorizedToolBatchOutcome(
            identity = request.effect.identity,
            calls = results,
            segments = completedSegments,
        )
    }
}
