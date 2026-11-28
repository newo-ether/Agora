package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import kotlinx.coroutines.Job

internal data class GenerationTranscriptionStageRequest(
    val conversationId: String,
    val runId: String,
    val pass: Int,
    val parentId: String?,
    val context: GenerationContext,
    val generationJob: Job?,
    val modelMessageId: String,
    val startTime: Long,
)

internal data class GenerationTranscriptionStageOutcome(
    val performed: Boolean,
    val segments: List<MessageSegment> = emptyList(),
    val error: String? = null,
)

internal class GenerationTranscriptionStage(
    private val manager: TranscriptionManager,
) {
    fun newExecution(): Execution = Execution(manager)

    /** One generate call owns one execution, so incomplete snapshot recovery cannot cross Runs. */
    internal class Execution(
        private val manager: TranscriptionManager,
    ) {
        private var latestSnapshot: ChatMessage? = null
        private var returned = false

        fun incompleteSnapshot(): ChatMessage? = latestSnapshot.takeUnless { returned }

        suspend fun execute(
            request: GenerationTranscriptionStageRequest,
            onSnapshot: suspend (snapshot: ChatMessage, forceCheckpoint: Boolean) -> Unit,
        ): GenerationTranscriptionStageOutcome {
            val context = request.context
            if (!context.imageTranscriptionEnabled) {
                return GenerationTranscriptionStageOutcome(performed = false)
            }
            val targets = manager.collectTargets(
                request.conversationId,
                request.parentId,
            )
            if (targets.isEmpty()) return GenerationTranscriptionStageOutcome(performed = false)
            if (context.transcriptionModelId.isBlank()) return GenerationTranscriptionStageOutcome(
                performed = false,
                error = "Image transcription is enabled but no transcription model is configured.",
            )

            val (segments, error) = manager.transcribe(
                targets,
                request.conversationId,
                request.runId,
                request.pass,
                context.transcriptionProviderName,
                context.transcriptionModelId,
                context.transcriptionApiKey,
                context.transcriptionBaseUrl,
                context.imageTranscriptionPrompt,
                request.generationJob,
                request.modelMessageId,
                request.startTime,
                context.transcriptionAnthropicCacheEnabled,
                context.transcriptionAnthropicCacheTtl,
            ) { snapshot ->
                latestSnapshot = snapshot
                onSnapshot(snapshot, false)
            }
            returned = true
            latestSnapshot?.let { onSnapshot(it, true) }
            return GenerationTranscriptionStageOutcome(
                performed = true,
                segments = segments,
                error = error,
            )
        }
    }
}
