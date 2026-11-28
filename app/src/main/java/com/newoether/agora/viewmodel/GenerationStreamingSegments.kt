package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.model.StreamingTextDelta

internal class GenerationThoughtTiming(
    /**
     * Monotonic by default. A wall clock can jump backwards (network time sync) and would turn a
     * running thought into a negative elapsed time, which used to publish a null duration and
     * make the live timer disappear.
     */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var cumulativeDurationMs = 0L
    private var currentStartedAtMs: Long? = null

    var currentDurationMs: Long = 0L
        private set
    var totalDurationMs: Long? = null
        private set

    /** True while a thought span is running and only then may a live UI timer count up. */
    val isRunning: Boolean
        get() = currentStartedAtMs != null

    fun ensureStarted() {
        if (currentStartedAtMs == null) currentStartedAtMs = nowMs()
    }

    /**
     * The duration to publish for the currently streaming thought. A running span always yields a
     * positive value so the live card can count up from the first chunk; a clock that steps
     * backwards clamps to the already accumulated time instead of reporting an unknown duration.
     */
    fun liveDurationMs(): Long? {
        val startedAt = currentStartedAtMs
        val liveElapsed = startedAt?.let { (nowMs() - it).coerceAtLeast(0L) } ?: 0L
        val totalDuration = currentDurationMs + liveElapsed
        return when {
            startedAt != null -> totalDuration.coerceAtLeast(1L)
            totalDuration > 0L -> totalDuration
            else -> null
        }
    }

    fun finishCurrent() {
        val startedAt = currentStartedAtMs ?: return
        val elapsed = nowMs() - startedAt
        if (elapsed > 0L) {
            cumulativeDurationMs += elapsed
            currentDurationMs += elapsed
            totalDurationMs = cumulativeDurationMs
        }
        currentStartedAtMs = null
    }

    fun resetCurrentDuration() {
        currentDurationMs = 0L
    }

    /** Restores the exact request-boundary timing without retaining a failed attempt's clock. */
    fun capture(): () -> Unit {
        val cumulative = cumulativeDurationMs
        val duration = currentDurationMs
        val total = totalDurationMs
        val wasRunning = currentStartedAtMs != null
        return {
            cumulativeDurationMs = cumulative
            currentDurationMs = duration
            totalDurationMs = total
            currentStartedAtMs = if (wasRunning) nowMs() else null
        }
    }

    fun adoptTotalDuration(durationMs: Long?) {
        totalDurationMs = durationMs
    }
}

internal fun statusAfterThoughtPhaseFinished(status: MessageStatus): MessageStatus =
    if (status == MessageStatus.THINKING) MessageStatus.SENDING else status

internal fun appendMergedSegment(
    target: MutableList<MessageSegment>,
    segment: MessageSegment,
) {
    val last = target.lastOrNull()
    val canMerge = last != null &&
        last.type == segment.type &&
        (
            segment.type == "answer" ||
                (
                    segment.type == "thought" &&
                        last.signature == null &&
                        segment.signature == null
                    )
            )
    if (canMerge) {
        target[target.lastIndex] = last.copy(
            content = last.content + segment.content,
            signature = segment.signature ?: last.signature,
            signatureProvider = segment.signatureProvider ?: last.signatureProvider,
            durationMs = mergeDurationMs(last.durationMs, segment.durationMs),
            durationSampledAtElapsedRealtimeMs = segment.durationSampledAtElapsedRealtimeMs
                ?: last.durationSampledAtElapsedRealtimeMs,
            streamingTextDeltas = last.streamingTextDeltas + segment.streamingTextDeltas,
        )
    } else {
        target.add(segment)
    }
}

internal fun rebaseCitationForFinalAnswer(
    citation: CitationRecord,
    providerAnswerStart: Int,
    finalAnswer: String,
): CitationRecord {
    if (citation.anchors.isEmpty()) return citation
    val shifted = citation.copy(
        anchors = citation.anchors.mapNotNull { anchor ->
            val start = providerAnswerStart.toLong() + anchor.startIndex
            val end = providerAnswerStart.toLong() + anchor.endIndex
            if (start !in 0..Int.MAX_VALUE.toLong() || end !in 0..Int.MAX_VALUE.toLong()) {
                null
            } else {
                anchor.copy(startIndex = start.toInt(), endIndex = end.toInt())
            }
        },
    )
    return CitationPolicy.normalize(shifted, finalAnswer)
        ?: citation.copy(anchors = emptyList())
}

private fun mergeDurationMs(first: Long?, second: Long?): Long? {
    val merged = (first ?: 0L) + (second ?: 0L)
    return merged.takeIf { it > 0L }
}

internal fun buildLiveSegments(
    flushed: List<MessageSegment>,
    answer: CharSequence,
    thought: CharSequence,
    signature: String? = null,
    signatureProvider: String? = null,
    thoughtDurationMs: Long? = null,
    errorMessage: String? = null,
    errorCode: String? = null,
    answerDeltas: List<StreamingTextDelta> = emptyList(),
    thoughtDurationSampledAtElapsedRealtimeMs: Long? = null,
): List<MessageSegment>? {
    val citations = flushed.filter { it.type == "citation" }
    val result = flushed.filterTo(mutableListOf()) { it.type != "citation" }
    if (answer.isNotEmpty()) {
        appendMergedSegment(
            result,
            MessageSegment(
                type = "answer",
                content = answer.toString(),
                streamingTextDeltas = answerDeltas.toList(),
            ),
        )
    }
    if (thought.isNotEmpty()) {
        appendMergedSegment(
            result,
            MessageSegment(
                type = "thought",
                content = thought.toString(),
                signature = signature,
                signatureProvider = signatureProvider,
                durationMs = thoughtDurationMs,
                durationSampledAtElapsedRealtimeMs = thoughtDurationSampledAtElapsedRealtimeMs,
            ),
        )
    }
    errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
        result.add(
            MessageSegment(
                type = "error",
                content = error,
                errorCode = errorCode,
            )
        )
    }
    result.addAll(citations)
    return result.ifEmpty { null }
}

internal fun terminalGenerationErrorMessage(
    status: MessageStatus,
    currentError: String?,
    fallbackError: String,
): String? = if (status == MessageStatus.ERROR) {
    currentError?.takeIf(String::isNotBlank) ?: fallbackError
} else {
    currentError
}

internal fun ChatMessage.withBoundedOutputTextTransform(
    transform: (String, MessageStatus) -> String,
): ChatMessage {
    val transformedText = MessagePersistenceGuard.clipText(transform(text, status))
    if (transformedText == text) return this
    val answerSegmentCount = segments.orEmpty().count { it.type == "answer" }
    var answerSegmentIndex = 0
    var transformedCursor = 0
    val transformedSegments = segments?.map { segment ->
        if (segment.type != "answer") return@map segment
        answerSegmentIndex += 1
        val content = if (answerSegmentIndex == answerSegmentCount) {
            transformedText.substring(transformedCursor.coerceAtMost(transformedText.length))
        } else {
            val end = (transformedCursor + segment.content.length)
                .coerceAtMost(transformedText.length)
            transformedText.substring(transformedCursor, end).also {
                transformedCursor = end
            }
        }
        segment.copy(content = content, streamingTextDeltas = emptyList())
    }
    return copy(text = transformedText, segments = transformedSegments)
}

internal data class GenerationFinalSnapshot(
    val messageId: String,
    val parentId: String?,
    val text: String,
    val images: List<String>,
    val thoughts: String,
    val thoughtTitle: String?,
    val tokenCount: Int,
    val tokenUsage: TokenUsage?,
    val status: MessageStatus,
    val timestamp: Long,
    val thoughtTimeMs: Long?,
    val modelName: String,
    val flushedSegments: List<MessageSegment>,
    val answerBuffer: String,
    val thoughtBuffer: String,
    val thoughtSignature: String?,
    val thoughtSignatureProvider: String?,
    val thoughtDurationMs: Long?,
    val errorMessage: String?,
    val errorCode: String? = null,
    val runId: String,
    val runSequence: Long,
    val answerDeltas: List<StreamingTextDelta> = emptyList(),
)

internal fun GenerationFinalSnapshot.toMessage(): ChatMessage = ChatMessage(
    id = messageId,
    parentId = parentId,
    text = MessagePersistenceGuard.clipText(text),
    images = images,
    thoughts = thoughts.ifBlank { null },
    thoughtTitle = thoughtTitle,
    tokenCount = tokenCount,
    tokenUsage = tokenUsage,
    status = status,
    participant = Participant.MODEL,
    timestamp = timestamp,
    thoughtTimeMs = thoughtTimeMs,
    modelName = modelName,
    segments = buildLiveSegments(
        flushedSegments,
        answerBuffer,
        thoughtBuffer,
        thoughtSignature,
        thoughtSignatureProvider,
        thoughtDurationMs,
        errorMessage,
        errorCode,
        answerDeltas = answerDeltas,
    ) ?: flushedSegments.ifEmpty { null },
    runId = runId,
    runSequence = runSequence,
)
