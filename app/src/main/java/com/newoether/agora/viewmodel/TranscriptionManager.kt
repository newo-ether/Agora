package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Handles image/video/PDF transcription as a pre-processing stage before
 * LLM generation. Extracted from GenerationManager (~160 lines).
 *
 * Lifecycle: created once per GenerationManager, used for each generation
 * that has images needing transcription.
 */
class TranscriptionManager(
    private val providers: Map<String, LlmProvider>,
    private val conversations: ConversationRepository,
    private val context: Context
) {
    private val contextLoader = DurableSelectedContextLoader(
        conversations = conversations,
        generationErrorFormatter = { raw ->
            normalizePersistedGenerationErrorText(context, raw)
        },
    )

    data class TranscriptionTarget(
        val messageId: String,
        val imagePath: String,
        val metaItemIndex: Int
    )

    /**
     * Collect all images in the message path that need transcription.
     * User attachments in the latest user message are re-transcribed on every
     * send; assistant-generated images are cached after the first transcription.
     */
    suspend fun collectTargets(
        conversationId: String,
        parentId: String?,
    ): List<TranscriptionTarget> {
        val pathMessages = contextLoader.load(
            DurableSelectedContextRequest(
                conversationId = conversationId,
                anchorMessageId = parentId,
                includeStoredTranscriptions = true,
            ),
        ).entities
        val latestUserMsg = pathMessages.lastOrNull { it.participant == Participant.USER }
        val targets = mutableListOf<TranscriptionTarget>()
        for (msg in pathMessages) {
            if (msg.images.isEmpty()) continue

            if (msg.participant == Participant.USER) {
                val meta = msg.attachmentMeta?.let {
                    try { Json.decodeFromString<AttachmentMeta>(it) } catch (_: Exception) { null }
                } ?: continue
                val isLatest = msg.id == latestUserMsg?.id
                meta.items.forEachIndexed { index, item ->
                    val imageIndex = item.imageIndex
                    val imageType = item.type
                    if (imageIndex == null || (imageType != "image" && imageType != "pdf" && imageType != "video")) return@forEachIndexed
                    val count = when (imageType) {
                        "pdf" -> item.pageCount ?: 1
                        "video" -> item.pageCount ?: 1
                        else -> 1
                    }
                    for (i in 0 until count) {
                        val offset = imageIndex + i
                        if (offset !in msg.images.indices) break
                        val imagePath = msg.images[offset]
                        if (isLatest || item.transcription.isNullOrEmpty()) {
                            targets.add(TranscriptionTarget(msg.id, imagePath, index))
                        }
                    }
                }
                continue
            }

            if (msg.participant == Participant.MODEL) {
                val meta = msg.attachmentMeta?.let {
                    try { Json.decodeFromString<AttachmentMeta>(it) } catch (_: Exception) { null }
                } ?: AttachmentMeta()
                val items = meta.items.toMutableList()
                var changed = false
                msg.images.forEachIndexed { imageIndex, imagePath ->
                    val existingIndex = items.indexOfFirst { it.type == "image" && it.imageIndex == imageIndex }
                    val itemIndex = if (existingIndex >= 0) {
                        existingIndex
                    } else {
                        items.add(
                            AttachmentItem(
                                type = "image",
                                fileName = File(imagePath).name,
                                mimeType = com.newoether.agora.api.util.imageMimeType(imagePath),
                                imageIndex = imageIndex
                            )
                        )
                        changed = true
                        items.lastIndex
                    }
                    if (items[itemIndex].transcription.isNullOrEmpty()) {
                        targets.add(TranscriptionTarget(msg.id, imagePath, itemIndex))
                    }
                }
                if (changed) {
                    conversations.upsertMessage(msg.copy(
                        attachmentMeta = Json.encodeToString(
                            AttachmentMeta.serializer(),
                            AttachmentMeta(items = items)
                        )
                    ))
                }
            }
        }
        return targets
    }

    /**
     * Describes one tool-result image (file_view_image) with the same provider resolution, prompt,
     * config, and streaming loop as [transcribe] — but for a single image and without any
     * attachment-metadata persistence (tool images have no attachment row).
     *
     * @return the trimmed description text, or null when the provider is unavailable or the
     * stream fails. Fail-closed on provider lookup: the image is never rerouted to another
     * provider.
     */
    suspend fun describeImageWithProgress(
        image: ToolImageAttachment,
        ctx: GenerationContext,
        generationJob: Job?,
        conversationId: String,
        runId: String,
        pass: Int,
        modelMessageId: String,
        onProgress: suspend (String) -> Unit,
    ): String? {
        // The block must never render as empty: announce the transcribing state immediately and
        // always emit a terminal progress line, mirroring the main transcription stage. This
        // flow transcribes exactly one image per slot, so the label is singular.
        onProgress(context.getString(R.string.transcription_ellipsis_single))
        val provider = providers[ctx.transcriptionProviderName]
        if (provider == null) {
            onProgress(
                context.getString(
                    R.string.generation_error_transcription,
                    "Transcription provider \"${ctx.transcriptionProviderName}\" is not available. Check the image transcription settings.",
                ),
            )
            return null
        }
        val transcriptionConfig = ProviderConfig(
            apiKey = ctx.transcriptionApiKey,
            modelId = ctx.transcriptionModelId,
            systemPrompt = BuiltInPrompts.IMAGE_TRANSCRIPTION_SYSTEM,
            thinkingEnabled = false,
            baseUrl = ctx.transcriptionBaseUrl,
            anthropicCacheEnabled = ctx.transcriptionAnthropicCacheEnabled,
            anthropicCacheTtl = ctx.transcriptionAnthropicCacheTtl,
            sessionId = conversationId.takeIf {
                ctx.transcriptionProviderName == Constants.PROVIDER_OPENCODE_GO ||
                    ctx.transcriptionProviderName == Constants.PROVIDER_MUNITO
            },
        )
        val promptMessages = listOf(
            ChatMessage(
                text = ctx.imageTranscriptionPrompt.ifBlank {
                    BuiltInPrompts.IMAGE_TRANSCRIPTION_USER
                },
                images = listOf(image.path),
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
            ),
        )
        val requestId = "$modelMessageId:tool-image-${UUID.randomUUID()}"
        val requestTrace = HttpClient.RequestTrace(
            requestId = requestId,
            origin = "transcription",
            diagnosticContext = DeveloperDiagnostics.newRequestContext(
                requestId = requestId,
                conversationId = conversationId,
                runId = runId,
                pass = pass,
                provider = ctx.transcriptionProviderName,
                model = ctx.transcriptionModelId,
                requestKind = "transcription",
            ),
        )
        val transcription = StringBuilder()
        var streamError: StreamEvent.Error? = null
        HttpClient.withStreamScope(
            scope = HttpClient.boundStreamScope(),
            requestTrace = requestTrace,
        ) {
            provider.generateResponse(promptMessages, transcriptionConfig).collect { event ->
                requestTrace.recordParsedEvent(event)
                when (event) {
                    is StreamEvent.TextChunk -> {
                        transcription.append(event.text)
                        onProgress(transcription.toString())
                    }
                    is StreamEvent.Retrying -> if (event.resetCurrentAttempt) {
                        transcription.setLength(0)
                        streamError = null
                        onProgress(context.getString(R.string.transcription_ellipsis_single))
                    }
                    is StreamEvent.Error -> streamError = event
                    else -> {}
                }
                if (generationJob?.isCancelled == true || !currentCoroutineContext().isActive) {
                    throw CancellationException("Tool image transcription cancelled")
                }
            }
        }
        val finalStreamError = streamError
        if (finalStreamError != null) {
            onProgress(
                context.getString(
                    R.string.generation_error_transcription,
                    localizedGenerationError(context, finalStreamError.error),
                ),
            )
            return null
        }
        val text = transcription.toString().trim()
        if (text.isEmpty()) {
            onProgress(
                context.getString(
                    R.string.generation_error_transcription,
                    "The transcription stream produced no text.",
                ),
            )
            return null
        }
        return text
    }

    /**
     * Run transcription for all targets. Streams progress via [onProgress].
     * Returns the transcription segments (for display in the UI) and persists
     * results to the message attachment metadata.
     *
     * @return Pair of (segments list, error message or null)
     */
    suspend fun transcribe(
        targets: List<TranscriptionTarget>,
        conversationId: String,
        runId: String,
        pass: Int,
        providerName: String,
        modelId: String,
        apiKey: String,
        baseUrl: String?,
        prompt: String,
        generationJob: Job?,
        modelMessageId: String,
        startTime: Long,
        anthropicCacheEnabled: Boolean = true,
        anthropicCacheTtl: String = "1h",
        onProgress: suspend (ChatMessage) -> Unit
    ): Pair<List<MessageSegment>, String?> {
        // Fail closed: a missing provider must never silently reroute the user's images
        // to an arbitrary other provider (wrong API key/model, and a privacy leak).
        val provider = providers[providerName]
            ?: return Pair(emptyList(), "Transcription provider \"$providerName\" is not available. Check the image transcription settings.")
        val transcriptionConfig = ProviderConfig(
            apiKey = apiKey,
            modelId = modelId,
            systemPrompt = BuiltInPrompts.IMAGE_TRANSCRIPTION_SYSTEM,
            thinkingEnabled = false,
            baseUrl = baseUrl,
            anthropicCacheEnabled = anthropicCacheEnabled,
            anthropicCacheTtl = anthropicCacheTtl,
            sessionId = conversationId.takeIf {
                providerName == Constants.PROVIDER_OPENCODE_GO ||
                    providerName == Constants.PROVIDER_MUNITO
            },
        )
        val placeholder = conversations.getMessage(modelMessageId)
        val parentId = placeholder?.parentId
        val results = mutableMapOf<String, MutableList<Pair<Int, String>>>()
        val transcriptionSegments = mutableListOf<MessageSegment>()
        var processed = 0
        val total = targets.size

        for (target in targets) {
            if (generationJob?.isCancelled == true) throw CancellationException("Transcription cancelled")
            if (!currentCoroutineContext().isActive) throw CancellationException("Transcription cancelled")

            withContext(Dispatchers.Main) {
                AgoraForegroundService.updateText(context.getString(R.string.transcription_progress, processed + 1, total))
            }

            val currentSegment = MessageSegment(
                type = "transcription",
                content = context.getString(R.string.transcription_ellipsis),
            )
            transcriptionSegments.add(currentSegment)
            onProgress(ChatMessage(
                id = modelMessageId, parentId = parentId, text = "",
                participant = Participant.MODEL, status = MessageStatus.TRANSCRIBING, timestamp = startTime,
                thoughtTitle = context.getString(R.string.transcription_label),
                // Trailing empty answer segment keeps the timeline renderer active during
                // transcription (it keys on the presence of an "answer" segment), so the
                // block morphs in place into the thought block instead of disappearing.
                segments = transcriptionSegments.toList() + MessageSegment(type = "answer"),
            ))

            val promptMessages = listOf(ChatMessage(
                text = prompt.ifBlank { BuiltInPrompts.IMAGE_TRANSCRIPTION_USER },
                images = listOf(target.imagePath),
                participant = Participant.USER,
                status = MessageStatus.SUCCESS
            ))
            val requestId = "$modelMessageId:transcription-$processed"
            val requestTrace = HttpClient.RequestTrace(
                requestId = requestId,
                origin = "transcription",
                diagnosticContext = DeveloperDiagnostics.newRequestContext(
                    requestId = requestId,
                    conversationId = conversationId,
                    runId = runId,
                    pass = pass,
                    provider = providerName,
                    model = modelId,
                    requestKind = "transcription",
                ),
            )
            val transcription = StringBuilder()
            var streamError: String? = null
            HttpClient.withStreamScope(
                scope = HttpClient.boundStreamScope(),
                requestTrace = requestTrace,
            ) {
                provider.generateResponse(promptMessages, transcriptionConfig).collect { event ->
                    requestTrace.recordParsedEvent(event)
                    when (event) {
                        is StreamEvent.TextChunk -> {
                            transcription.append(event.text)
                            transcriptionSegments[transcriptionSegments.lastIndex] = currentSegment.copy(content = transcription.toString())
                            onProgress(ChatMessage(
                                id = modelMessageId, parentId = parentId, text = "",
                                participant = Participant.MODEL, status = MessageStatus.TRANSCRIBING, timestamp = startTime,
                                thoughtTitle = context.getString(R.string.transcription_label),
                                // Trailing empty answer segment keeps the timeline renderer active during
                                // transcription (it keys on the presence of an "answer" segment), so the
                                // block morphs in place into the thought block instead of disappearing.
                                segments = transcriptionSegments.toList() + MessageSegment(type = "answer"),
                            ))
                        }
                        is StreamEvent.Retrying -> if (event.resetCurrentAttempt) {
                            transcription.setLength(0)
                            streamError = null
                            transcriptionSegments[transcriptionSegments.lastIndex] = currentSegment
                            onProgress(ChatMessage(
                                id = modelMessageId, parentId = parentId, text = "",
                                participant = Participant.MODEL, status = MessageStatus.TRANSCRIBING, timestamp = startTime,
                                thoughtTitle = context.getString(R.string.transcription_label),
                                segments = transcriptionSegments.toList() + MessageSegment(type = "answer"),
                            ))
                        }
                        is StreamEvent.Error -> { streamError = localizedGenerationError(context, event.error) }
                        else -> {}
                    }
                }
            }
            if (streamError != null) return Pair(transcriptionSegments, streamError)
            val text = transcription.toString().trim()
            transcriptionSegments[transcriptionSegments.lastIndex] = currentSegment.copy(content = text)
            results.getOrPut(target.messageId) { mutableListOf() }
                .add(target.metaItemIndex to text)
            processed++
        }

        // Persist results back to message attachment metadata
        for ((messageId, updates) in results) {
            val entity = conversations.getMessage(messageId)
            if (entity != null) {
                val meta = entity.attachmentMeta?.let {
                    try { Json.decodeFromString<AttachmentMeta>(it) } catch (_: Exception) { null }
                } ?: AttachmentMeta()
                val items = meta.items.toMutableList()
                val grouped: Map<Int, List<String>> = updates.groupBy { it.first }.mapValues { e -> e.value.map { it.second } }
                for ((index, texts) in grouped) {
                    if (index in items.indices) {
                        val joined = if (texts.size == 1) texts.first()
                        else texts.mapIndexed { i, t -> "[Page ${i + 1}]\n$t" }.joinToString("\n\n")
                        items[index] = items[index].copy(transcription = joined)
                    }
                }
                conversations.upsertMessage(entity.copy(
                    attachmentMeta = Json.encodeToString(AttachmentMeta.serializer(), AttachmentMeta(items = items))
                ))
            }
        }
        return Pair(transcriptionSegments, null)
    }
}
