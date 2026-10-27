package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.api.util.artifactsForImages
import com.newoether.agora.api.util.projectedImageOccurrences
import com.newoether.agora.api.util.withProjectedImages
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Removes duplicate client tool segments while retaining provider-native replay segments. */
internal fun stripAggregatedToolSegments(toolCallJson: String?): String? {
    val raw = toolCallJson ?: return null
    val segments = runCatching {
        Json.decodeFromString<List<MessageSegment>>(raw)
    }.getOrNull() ?: return null
    val retained = segments.filterNot { it.type == "tool" && !it.isProviderHostedTool }
    return retained.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }
}

/**
 * Lossless Room-to-provider projection shared by generation and Compact.
 *
 * UI queries deliberately omit synthetic tool payloads and therefore must never feed request or
 * token-accounting paths. This projector decodes the durable protocol rows and attachment text
 * without consulting mutable UI state.
 */
internal fun projectProviderMessages(
    entities: List<MessageEntity>,
    includeStoredTranscriptions: Boolean,
): List<ChatMessage> {
    val toolHistoryCompactor = ToolRoundHistoryCompactor()
    return entities.map { entity ->
        val decodedSegments = entity.toolCallJson?.let { json ->
            runCatching { Json.decodeFromString<List<MessageSegment>>(json) }.getOrNull()
        }
        val recovered = recoverPersistedThinkingBoundary(
            participant = entity.participant,
            text = entity.text,
            thoughts = entity.thoughts,
            segments = decodedSegments,
        )
        val providerSegments = recovered.segments?.filterNot { it.type == "citation" }
        val segments = if (
            providerSegments != null && entity.id.startsWith(Constants.TOOL_MSG_PREFIX)
        ) {
            toolHistoryCompactor.compact(entity.runId, providerSegments)
        } else {
            providerSegments
        }
        val toolCall = segments?.lastOrNull { it.type == "tool" }?.let { segment ->
            ToolCallData(
                toolName = segment.toolName ?: "",
                arguments = segment.toolArgs ?: "{}",
                result = segment.toolResult ?: "",
                signature = segment.signature,
                toolCallId = segment.toolCallId,
                resultImages = segment.toolImages,
                transcription = segment.toolTranscription,
                displayName = segment.toolDisplayName,
                target = segment.toolTarget,
                resultText = segment.toolResultText,
                structuredResult = segment.toolStructuredResult,
                responseOutputItems = segment.responseOutputItems,
                responseOutputItemProvider = segment.responseOutputItemProvider,
            )
        }
        val attachmentMeta = entity.attachmentMeta?.let { json ->
            runCatching { Json.decodeFromString<AttachmentMeta>(json) }.getOrNull()
        }
        val attachmentText = attachmentMeta?.items?.mapNotNull { item ->
            when {
                item.storage.isLocalSandbox && !item.sandboxPath.isNullOrBlank() ->
                    sandboxAttachmentInstruction(item)
                item.textContent != null -> {
                    val label = item.fileName ?: "file"
                    "\n\n--- File: $label ---\n${item.textContent}"
                }
                includeStoredTranscriptions && !item.transcription.isNullOrBlank() -> {
                    val label = item.fileName ?: "image"
                    "\n\n--- Image Transcription: $label ---\n${item.transcription}"
                }
                else -> null
            }
        }?.joinToString("").orEmpty()
        val media = if (includeStoredTranscriptions) {
            withoutStoredTranscriptionImages(entity.images, attachmentMeta)
        } else {
            ProviderMediaProjection(entity.images, attachmentMeta)
        }
        ChatMessage(
            id = entity.id,
            parentId = entity.parentId,
            text = recovered.text + attachmentText,
            images = media.images,
            thoughts = recovered.thoughts,
            thoughtTitle = entity.thoughtTitle,
            tokenCount = entity.tokenCount,
            tokenUsage = TokenUsage.fromPersisted(
                totalTokenCount = entity.tokenCount,
                inputTokenCount = entity.inputTokenCount,
                cachedInputTokenCount = entity.cachedInputTokenCount,
                cacheWriteInputTokenCount = entity.cacheWriteInputTokenCount,
                uncachedInputTokenCount = entity.uncachedInputTokenCount,
                outputTokenCount = entity.outputTokenCount,
                reasoningTokenCount = entity.reasoningTokenCount,
                generationDurationMs = entity.generationDurationMs,
            ),
            status = entity.status,
            participant = entity.participant,
            timestamp = entity.timestamp,
            thoughtTimeMs = entity.thoughtTimeMs,
            modelName = entity.modelName,
            segments = segments,
            toolCall = toolCall,
            attachmentMeta = media.metadata,
            runId = entity.runId,
            runSequence = entity.runSequence,
            consumedAtPass = entity.consumedAtPass,
            source = MessageSource.decode(entity.sourceJson),
        ).let { message ->
            val projected = if (includeStoredTranscriptions) {
                withoutStoredToolTranscriptionImages(message)
            } else message
            projected.copy(imageArtifacts = projected.artifactsForImages())
        }
    }
}

/** Legacy descriptions prove image coverage only for a mapped single-image result. */
private fun withoutStoredToolTranscriptionImages(message: ChatMessage): ChatMessage {
    if (!message.id.startsWith(Constants.RESULT_MSG_PREFIX) || message.images.isEmpty()) return message
    val toolSegments = message.segments.orEmpty().filter { it.type == "tool" }
    val orderedImages = toolSegments.flatMap { it.toolImages }
    val removed = mutableSetOf<Int>()
    if (orderedImages.map { it.path } == message.images) {
        var offset = 0
        toolSegments.forEach { segment ->
            if (segment.toolImages.size == 1 && !segment.toolTranscription.isNullOrBlank()) {
                removed += offset
            }
            offset += segment.toolImages.size
        }
    } else if (message.images.size == 1 && orderedImages.isEmpty() && toolSegments.size == 1 &&
        !toolSegments.single().toolTranscription.isNullOrBlank()
    ) {
        removed += 0
    } else if (message.images.size == 1 && toolSegments.isEmpty() &&
        !message.toolCall?.transcription.isNullOrBlank()
    ) {
        removed += 0
    }
    if (removed.isEmpty()) return message
    return message.withProjectedImages(message.projectedImageOccurrences().filterIndexed { index, _ ->
        index !in removed
    })
}

private data class ProviderMediaProjection(
    val images: List<String>,
    val metadata: AttachmentMeta?,
)

/** Filters described occurrences and remaps their metadata before legacy artifact hydration. */
private fun withoutStoredTranscriptionImages(
    images: List<String>,
    metadata: AttachmentMeta?,
): ProviderMediaProjection {
    if (metadata == null) return ProviderMediaProjection(images, null)
    val claimed = mutableSetOf<Int>()
    val ranges = metadata.items.map { item ->
        if (item.storage.isLocalSandbox || item.type !in setOf("image", "pdf", "video")) {
            emptyList()
        } else {
            val start = item.imageIndex?.takeIf { it in images.indices }
            if (start != null) {
                val count = if (item.type == "image") 1 else {
                    item.pageCount ?: item.imageArtifacts.size.takeIf { it > 0 } ?: 1
                }
                List(count.coerceIn(0, images.size - start)) { start + it }.also { claimed.addAll(it) }
            } else if (item.imageIndex == null) {
                item.imageArtifacts.mapNotNull { artifact ->
                    images.indices.firstOrNull {
                        artifact.path.isNotBlank() && it !in claimed && images[it] == artifact.path
                    }?.also { claimed += it }
                }
            } else {
                emptyList()
            }
        }
    }
    val describedItems = metadata.items.indices.filter { index ->
        val item = metadata.items[index]
        !item.transcription.isNullOrBlank() && item.textContent == null &&
            !item.storage.isLocalSandbox && item.type in setOf("image", "pdf", "video")
    }.toSet()
    if (describedItems.isEmpty()) return ProviderMediaProjection(images, metadata)
    val removed = describedItems.flatMap { ranges[it] }.toSet()
    val retained = images.indices.filterNot(removed::contains)
    val oldToNew = retained.withIndex().associate { it.value to it.index }
    val remapped = metadata.copy(items = metadata.items.mapIndexed { index, item ->
        if (item.storage.isLocalSandbox || item.type !in setOf("image", "pdf", "video")) {
            item
        } else {
            val remaining = if (index in describedItems) emptyList() else {
                ranges[index].filter(oldToNew::containsKey)
            }
            val artifacts = remaining.map { oldIndex ->
                val offset = ranges[index].indexOf(oldIndex)
                val path = images[oldIndex]
                item.imageArtifacts.getOrNull(offset)?.takeIf { it.path == path }
                    ?: item.imageArtifacts.firstOrNull { path.isNotBlank() && it.path == path }
                    ?: ImageArtifact(
                        path = path, mimeType = "application/octet-stream", sizeBytes = 0L,
                        unavailable = path.isBlank(),
                    )
            }
            item.copy(
                imageIndex = remaining.firstOrNull()?.let(oldToNew::get),
                pageCount = if (item.type == "image" && item.pageCount == null) null else remaining.size,
                imageArtifacts = artifacts,
            )
        }
    })
    return ProviderMediaProjection(retained.map(images::get), remapped)
}

internal fun sandboxAttachmentInstruction(item: AttachmentItem): String {
    val label = item.fileName ?: "file"
    val mimeType = item.mimeType ?: "unknown"
    val size = item.fileSize?.let { "$it bytes" } ?: "unknown"
    return "\n\nAttached file $label is available in Local Sandbox at " +
        "${item.sandboxPath}. MIME type: $mimeType. Size: $size. " +
        "Use the Local Sandbox file tools (for example file_read) to inspect it before answering."
}
