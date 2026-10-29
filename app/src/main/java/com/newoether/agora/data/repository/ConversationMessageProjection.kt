package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageStreamCheckpoint
import com.newoether.agora.data.local.MessageEntity
import kotlinx.coroutines.flow.Flow
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageSegment

internal fun ConversationRepository.observeMessagesByIds(
    conversationId: String,
    ids: List<String>,
): Flow<List<MessageEntity>> = chatDao.observeMessagesByIds(conversationId, ids)

internal fun ChatEntity.toConversation() = ChatConversation(
    id = id, title = title, systemPromptId = systemPromptId, modelId = modelId,
    taskId = taskId, origin = origin, graduated = graduated,
    hasUnreadGeneration = hasUnreadGeneration,
    isPinned = isPinned,
    selectedBranchesJson = selectedBranchesJson,
    parentConversationId = parentConversationId,
)

internal fun ChatMessage.toStreamCheckpoint(): MessageStreamCheckpoint {
    val persistedSegments = segments?.takeIf { it.isNotEmpty() } ?: toolCall?.let {
        listOf(
            MessageSegment(
                type = "tool",
                toolName = it.toolName,
                toolArgs = it.arguments,
                toolResult = it.result,
                signature = it.signature,
                toolCallId = it.toolCallId,
                toolDisplayName = it.displayName,
                toolTarget = it.target,
                toolResultText = it.resultText,
                toolStructuredResult = it.structuredResult,
                toolImages = it.resultImages,
                toolTranscription = it.transcription,
                responseOutputItems = it.responseOutputItems,
                responseOutputItemProvider = it.responseOutputItemProvider,
            )
        )
    }
    return MessageStreamCheckpoint(
        id = id,
        text = MessagePersistenceGuard.clipText(text),
        images = images,
        thoughts = thoughts?.let(MessagePersistenceGuard::clipText),
        thoughtTitle = thoughtTitle,
        tokenCount = tokenCount,
        inputTokenCount = tokenUsage?.inputTokenCount,
        cachedInputTokenCount = tokenUsage?.cachedInputTokenCount,
        cacheWriteInputTokenCount = tokenUsage?.cacheWriteInputTokenCount,
        uncachedInputTokenCount = tokenUsage?.uncachedInputTokenCount,
        outputTokenCount = tokenUsage?.outputTokenCount,
        reasoningTokenCount = tokenUsage?.reasoningTokenCount,
        generationDurationMs = tokenUsage?.generationDurationMs,
        timeToFirstTokenMs = tokenUsage?.timeToFirstTokenMs,
        totalGenerationMs = tokenUsage?.totalGenerationMs,
        status = status,
        thoughtTimeMs = thoughtTimeMs,
        toolCallJson = MessagePersistenceGuard.encodeSegmentsBounded(persistedSegments?.map { segment ->
            if (segment.toolRoundId == null) segment else MessageSegment(
                type = "tool",
                toolCallId = checkNotNull(segment.toolCallId),
                toolRoundId = segment.toolRoundId,
            )
        }),
    )
}
