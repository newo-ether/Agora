package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.util.imageArtifactOccurrences
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
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
        appContext = context,
        generationErrorFormatter = { raw ->
            normalizePersistedGenerationErrorText(context, raw)
        },
    )

    data class TranscriptionTarget(
        val messageId: String,
        val imagePath: String,
        val metaItemIndex: Int,
        val imageArtifact: ImageArtifact? = null,
        val sourceMessage: MessageEntity? = null,
        val preparedMetadata: AttachmentMeta? = null,
        val imageIndex: Int? = null,
    )

    /** Latest USER images are refreshed; older USER and MODEL descriptions remain cached. */
    suspend fun collectTargets(
        conversationId: String,
        parentId: String?,
    ): List<TranscriptionTarget> = collectTargets(
        contextLoader.load(
            DurableSelectedContextRequest(
                conversationId = conversationId,
                anchorMessageId = parentId,
                includeStoredTranscriptions = false,
            ),
        ),
    )

    /** Collection is read-only; missing legacy metadata is committed with successful results. */
    internal fun collectTargets(loaded: DurableSelectedContext): List<TranscriptionTarget> {
        val mediaById = loaded.messages.associateBy(ChatMessage::id)
        val latestUserId = loaded.entities.lastOrNull {
            it.participant == Participant.USER && !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
        }?.id
        return buildList {
            for (message in loaded.entities) {
                if (message.images.isEmpty() || message.participant !in setOf(Participant.USER, Participant.MODEL) ||
                    message.id.startsWith(Constants.TOOL_MSG_PREFIX) || message.id.startsWith(Constants.RESULT_MSG_PREFIX)
                ) continue
                val media = checkNotNull(mediaById[message.id]) { "Transcription image projection is missing" }
                check(media.images.size == message.images.size) { "Transcription image projection changed" }
                val occurrences = media.imageArtifactOccurrences().mapIndexed { index, artifact ->
                    artifact ?: ImageArtifact(
                        path = media.images[index], mimeType = "application/octet-stream",
                        sizeBytes = 0, unavailable = true,
                    )
                }
                val items = decodeMetadata(message.attachmentMeta).items.toMutableList()
                val owners = IntArray(message.images.size) { -1 }
                items.forEachIndexed { itemIndex, item ->
                    if (item.storage.isLocalSandbox || item.type !in setOf("image", "pdf", "video")) return@forEachIndexed
                    val start = item.imageIndex ?: return@forEachIndexed
                    val count = if (item.type == "image") 1 else {
                        item.pageCount ?: item.imageArtifacts.size.takeIf { it > 0 } ?: 1
                    }
                    check(start >= 0 && count > 0 && start.toLong() + count <= owners.size) {
                        "Transcription image metadata has an invalid range"
                    }
                    repeat(count) { offset ->
                        check(owners[start + offset] == -1) { "Transcription image metadata overlaps" }
                        owners[start + offset] = itemIndex
                    }
                }
                media.images.forEachIndexed { imageIndex, path ->
                    if (owners[imageIndex] >= 0) return@forEachIndexed
                    val artifact = occurrences[imageIndex]
                    items += AttachmentItem(
                        type = "image",
                        fileName = File(path).name,
                        mimeType = artifact.mimeType,
                        imageIndex = imageIndex,
                        pixelWidth = artifact.width,
                        pixelHeight = artifact.height,
                        fileSize = artifact.sizeBytes,
                        imageArtifacts = listOf(artifact),
                        unavailable = artifact.unavailable,
                    )
                    owners[imageIndex] = items.lastIndex
                }
                val metadata = AttachmentMeta(items.mapIndexed { itemIndex, item ->
                    val artifacts = media.images.indices.filter { owners[it] == itemIndex }.map { imageIndex ->
                        occurrences[imageIndex]
                    }
                    if (artifacts.isEmpty()) item else item.copy(
                        imageArtifacts = artifacts,
                        unavailable = item.unavailable || artifacts.any(ImageArtifact::unavailable),
                    )
                })
                media.images.forEachIndexed { imageIndex, path ->
                    val itemIndex = owners[imageIndex]
                    val item = items[itemIndex]
                    val artifact = occurrences[imageIndex]
                    if (path.isBlank() || artifact.unavailable || artifact.path != path) return@forEachIndexed
                    if (message.id == latestUserId || item.transcription.isNullOrBlank()) {
                        add(TranscriptionTarget(
                            messageId = message.id,
                            imagePath = path,
                            metaItemIndex = itemIndex,
                            imageArtifact = artifact,
                            imageIndex = imageIndex,
                            sourceMessage = message,
                            preparedMetadata = metadata,
                        ))
                    }
                }
            }
        }
    }

    private fun decodeMetadata(raw: String?): AttachmentMeta =
        if (raw.isNullOrBlank()) AttachmentMeta() else Json.decodeFromString<AttachmentMeta>(raw)

    /** The existing Room transaction fences every identity check and write, with no Provider IO. */
    internal suspend fun persistTranscriptions(
        conversationId: String,
        results: List<Pair<TranscriptionTarget, String>>,
    ) = conversations.withProviderContextSnapshot {
        val replacements = results.groupBy { it.first.messageId }.map { (messageId, updates) ->
            val current = checkNotNull(conversations.getMessage(messageId)) {
                "Transcription source message was deleted"
            }
            check(current.id == messageId && current.conversationId == conversationId) {
                "Transcription conversation changed"
            }
            val firstTarget = updates.first().first
            val source = firstTarget.sourceMessage
            if (source != null) {
                check(source.id == messageId && source.conversationId == conversationId &&
                    current.parentId == source.parentId && current.participant == source.participant &&
                    current.timestamp == source.timestamp && current.runId == source.runId &&
                    current.runSequence == source.runSequence && current.images == source.images &&
                    current.attachmentMeta == source.attachmentMeta
                ) { "Transcription image identity changed" }
            }
            val metadata = firstTarget.preparedMetadata ?: decodeMetadata(current.attachmentMeta)
            check(updates.all { (target, _) ->
                target.sourceMessage == source && target.preparedMetadata == firstTarget.preparedMetadata
            }) { "Transcription targets have different source identities" }
            val items = metadata.items.toMutableList()
            updates.groupBy { it.first.metaItemIndex }.forEach { (index, descriptions) ->
                check(index in items.indices) { "Transcription attachment metadata changed" }
                val item = items[index]
                val start = checkNotNull(item.imageIndex) { "Transcription image index is missing" }
                val count = if (item.type == "image") 1 else {
                    item.pageCount ?: item.imageArtifacts.size.takeIf { it > 0 } ?: 1
                }
                check(start >= 0 && count > 0 && start.toLong() + count <= current.images.size &&
                    item.type in setOf("image", "pdf", "video")
                ) { "Transcription image range changed" }
                val offsets = (0 until count).filter { offset ->
                    val artifact = item.imageArtifacts.getOrNull(offset)
                    artifact?.unavailable != true &&
                        (artifact?.path ?: current.images[start + offset]).isNotBlank() &&
                        !(item.type == "image" && item.unavailable)
                }
                check(descriptions.size == offsets.size) { "Transcription image range changed" }
                descriptions.zip(offsets).forEach { (description, offset) ->
                    val (target, text) = description
                    val artifact = target.imageArtifact
                    check((target.imageIndex == null || target.imageIndex == start + offset) &&
                        text.isNotBlank() && if (source != null) {
                        artifact != null && artifact.path == target.imagePath && !artifact.unavailable &&
                            (item.imageArtifacts.isEmpty() || item.imageArtifacts.getOrNull(offset) == artifact)
                    } else current.images[start + offset] == target.imagePath) {
                        "Transcription image artifact changed"
                    }
                }
                val joined = if (count == 1) descriptions.single().second else {
                    descriptions.zip(offsets).joinToString("\n\n") { (description, offset) ->
                        "[Page ${offset + 1}]\n${description.second}"
                    }
                }
                items[index] = item.copy(transcription = joined)
            }
            current.copy(attachmentMeta = Json.encodeToString(AttachmentMeta(items)))
        }
        replacements.forEach { conversations.upsertMessage(it) }
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
        if (generationJob?.isCancelled == true || !currentCoroutineContext().isActive) {
            throw CancellationException("Tool image transcription cancelled")
        }
        if (image.unavailable || image.path.isBlank()) return null
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
                ctx.transcriptionProviderName == Constants.PROVIDER_OPENCODE_GO
            },
        )
        val promptMessages = listOf(
            ChatMessage(
                text = ctx.imageTranscriptionPrompt.ifBlank {
                    BuiltInPrompts.IMAGE_TRANSCRIPTION_USER
                },
                images = listOf(image.path),
                imageArtifacts = mapOf(image.path to image),
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
        if (generationJob?.isCancelled == true || !currentCoroutineContext().isActive) {
            throw CancellationException("Transcription cancelled")
        }
        val availableTargets = targets.filter { target ->
            target.imagePath.isNotBlank() && target.imageArtifact?.unavailable != true &&
                target.imageArtifact?.let { it.path == target.imagePath } != false
        }
        if (availableTargets.isEmpty()) return Pair(emptyList(), null)
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
                providerName == Constants.PROVIDER_OPENCODE_GO
            },
        )
        val placeholder = conversations.getMessage(modelMessageId)
        val parentId = placeholder?.parentId
        val results = mutableListOf<Pair<TranscriptionTarget, String>>()
        val transcriptionSegments = mutableListOf<MessageSegment>()
        var processed = 0
        val total = availableTargets.size

        for (target in availableTargets) {
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
                imageArtifacts = target.imageArtifact?.let { mapOf(target.imagePath to it) }.orEmpty(),
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
            if (text.isEmpty()) return Pair(transcriptionSegments, "The transcription stream produced no text.")
            transcriptionSegments[transcriptionSegments.lastIndex] = currentSegment.copy(content = text)
            results += target to text
            processed++
        }

        if (generationJob?.isCancelled == true || !currentCoroutineContext().isActive) {
            throw CancellationException("Transcription cancelled")
        }
        persistTranscriptions(conversationId, results)
        return Pair(transcriptionSegments, null)
    }
}
