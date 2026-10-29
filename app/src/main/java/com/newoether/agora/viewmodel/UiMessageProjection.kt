package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.resolveToolRoundReferences
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunRecoveryPolicy
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.util.Constants
import com.newoether.agora.util.SearchResultFormatter
import kotlinx.serialization.json.Json
private val persistedSegmentJson = Json { ignoreUnknownKeys = true }

internal fun MessageContextTopology.toUiChatMessageStub(): ChatMessage =
    ChatMessage(
        id = id,
        parentId = parentId,
        text = "",
        tokenCount = if (id.isSyntheticMessageId()) 0 else tokenCount,
        status = status,
        participant = participant,
        timestamp = timestamp,
        modelName = modelName,
        runId = runId,
        runSequence = runSequence,
        consumedAtPass = consumedAtPass,
    )

private fun String.isSyntheticMessageId(): Boolean =
    startsWith(Constants.TOOL_MSG_PREFIX) || startsWith(Constants.RESULT_MSG_PREFIX)

internal fun List<MessageSegment>?.withDurableModelAnswer(
    participant: Participant,
    durableText: String,
): List<MessageSegment>? {
    val segments = this
    if (
        participant != Participant.MODEL || durableText.isBlank() || segments == null ||
        segments.any { it.type == "answer" && it.content.isNotBlank() }
    ) {
        return segments
    }
    val answerIndex = segments.indexOfFirst { it.type == "citation" || it.type == "error" }
        .let { index -> if (index < 0) segments.size else index }
    return segments.toMutableList().apply {
        add(answerIndex, MessageSegment(type = "answer", content = durableText))
    }
}

/**
 * The single projection from a durable message row into UI state.
 *
 * Room observations and controller-owned atomic graph commits must use the same projection.
 * Otherwise a branch mutation can temporarily replace a fully decoded message with a partial
 * copy and leave persisted thought/tool segments invisible until the conversation is reopened.
 */
internal fun MessageEntity.toUiChatMessage(
    context: Context,
    toolRoundRows: List<MessageEntity>? = null,
): ChatMessage = toUiChatMessage(toolRoundRows) { value -> SearchResultFormatter.format(value, context) }

internal fun MessageEntity.toUiChatMessage(
    toolRoundRows: List<MessageEntity>? = null,
    formatText: (String) -> String,
): ChatMessage {
    val isSynthetic = id.isSyntheticMessageId()
    // Protocol rows only participate in the branch walk. Provider history is built from
    // MessageEntity snapshots, so copying their potentially huge results into UI state only
    // increases allocation and GC pressure.
    val decodedSegments = if (isSynthetic) {
        null
    } else {
        toolCallJson?.let { raw ->
            runCatching {
                persistedSegmentJson.decodeFromString<List<MessageSegment>>(raw)
            }.getOrNull()
        }
    }
    val resolvedSegments = if (toolRoundRows == null) decodedSegments else
        resolveToolRoundReferences(decodedSegments, toolRoundRows)
    val terminalSegments = if (
        participant == Participant.MODEL && status == MessageStatus.STOPPED
    ) {
        resolvedSegments?.let(RunRecoveryPolicy::stopIncompleteTools)
    } else {
        resolvedSegments
    }
    val recovered = recoverPersistedThinkingBoundary(
        participant = participant,
        text = text,
        thoughts = thoughts,
        segments = terminalSegments,
    )
    val formattedText = if (isSynthetic) "" else formatText(recovered.text)
    val fallbackSegments = recovered.segments
        ?: recovered.thoughts
            ?.takeIf { thought -> !isSynthetic && thought.isNotBlank() }
            ?.let { thought -> listOf(MessageSegment(type = "thought", content = thought)) }
    val visibleSegments = fallbackSegments.withDurableModelAnswer(participant, formattedText)
    return ChatMessage(
        id = id,
        parentId = parentId,
        text = formattedText,
        images = if (isSynthetic) emptyList() else images,
        thoughts = if (isSynthetic) null else recovered.thoughts,
        thoughtTitle = if (isSynthetic) null else thoughtTitle,
        tokenCount = if (isSynthetic) 0 else tokenCount,
        tokenUsage = if (isSynthetic) {
            null
        } else {
            TokenUsage.fromPersisted(
                totalTokenCount = tokenCount,
                inputTokenCount = inputTokenCount,
                cachedInputTokenCount = cachedInputTokenCount,
                cacheWriteInputTokenCount = cacheWriteInputTokenCount,
                uncachedInputTokenCount = uncachedInputTokenCount,
                outputTokenCount = outputTokenCount,
                reasoningTokenCount = reasoningTokenCount,
                generationDurationMs = generationDurationMs,
                timeToFirstTokenMs = timeToFirstTokenMs,
                totalGenerationMs = totalGenerationMs,
            )
        },
        status = status,
        participant = participant,
        timestamp = timestamp,
        thoughtTimeMs = if (isSynthetic) null else thoughtTimeMs,
        modelName = modelName,
        segments = visibleSegments,
        toolCall = visibleSegments
            ?.lastOrNull { segment -> segment.type == "tool" }
            ?.let { segment ->
                ToolCallData(
                    toolName = segment.toolName.orEmpty(),
                    arguments = segment.toolArgs ?: "{}",
                    result = formatText(segment.toolResult.orEmpty()),
                    signature = segment.signature,
                    toolCallId = segment.toolCallId,
                    resultImages = segment.toolImages,
                    displayName = segment.toolDisplayName,
                    target = segment.toolTarget,
                    resultText = segment.toolResultText,
                    structuredResult = segment.toolStructuredResult,
                )
            },
        attachmentMeta = if (isSynthetic) {
            null
        } else {
            attachmentMeta?.let { raw ->
                runCatching { Json.decodeFromString<AttachmentMeta>(raw) }.getOrNull()
            }
        },
        runId = runId,
        runSequence = runSequence,
        consumedAtPass = consumedAtPass,
        source = if (isSynthetic) null else MessageSource.decode(sourceJson),
    )
}
