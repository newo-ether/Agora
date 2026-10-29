package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.ChatMessage
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
        val hasTranscription = includeStoredTranscriptions &&
            attachmentMeta?.items?.any { !it.transcription.isNullOrBlank() } == true
        ChatMessage(
            id = entity.id,
            parentId = entity.parentId,
            text = recovered.text + attachmentText,
            images = if (hasTranscription) emptyList() else entity.images,
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
                timeToFirstTokenMs = entity.timeToFirstTokenMs,
                totalGenerationMs = entity.totalGenerationMs,
            ),
            status = entity.status,
            participant = entity.participant,
            timestamp = entity.timestamp,
            thoughtTimeMs = entity.thoughtTimeMs,
            modelName = entity.modelName,
            segments = segments,
            toolCall = toolCall,
            attachmentMeta = attachmentMeta,
            runId = entity.runId,
            runSequence = entity.runSequence,
            consumedAtPass = entity.consumedAtPass,
            source = MessageSource.decode(entity.sourceJson),
        )
    }
}

internal fun sandboxAttachmentInstruction(item: AttachmentItem): String {
    val label = item.fileName ?: "file"
    val mimeType = item.mimeType ?: "unknown"
    val size = item.fileSize?.let { "$it bytes" } ?: "unknown"
    return "\n\nAttached file $label is available in Local Sandbox at " +
        "${item.sandboxPath}. MIME type: $mimeType. Size: $size. " +
        "Use the Local Sandbox file tools (for example file_read) to inspect it before answering."
}
