package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

internal data class PersistableToolRound(
    val pathMessages: List<ChatMessage>,
    val entities: List<MessageEntity>,
    val lastResultId: String,
)

/** Builds one immutable Provider-path/Room representation of a completed tool round. */
internal class GenerationToolRoundBuilder(
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun build(
        previousMessageId: String?,
        conversationId: String,
        runId: String,
        modelName: String,
        providerName: String,
        calls: List<ToolCallData>,
        completedSegments: List<MessageSegment>,
    ): PersistableToolRound {
        require(calls.isNotEmpty())
        val toolMessageId = "${Constants.TOOL_MSG_PREFIX}${newId()}"
        val toolSegments = completedSegments.ifEmpty { null }
        val allSegments = toolSegments ?: calls.map { it.toSegment(providerName) }
        // Root checkpoints reference this body after commit, so an oversized round must not
        // degrade to a missing payload through the guard's legacy SQL NULL fallback.
        val boundedSegmentsJson = checkNotNull(MessagePersistenceGuard.encodeSegmentsBounded(allSegments)) {
            "Completed tool round exceeds the persistence budget"
        }
        val resultMessages = calls.map { call ->
            val resultId = "${Constants.RESULT_MSG_PREFIX}${newId()}"
            resultId to ChatMessage(
                id = resultId,
                parentId = toolMessageId,
                text = call.result,
                images = call.resultImages.map { it.path },
                attachmentMeta = call.sandboxRuntimeImageMetadata(),
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
                toolCall = call,
                runId = runId,
            )
        }
        val toolPathMessage = ChatMessage(
            id = toolMessageId,
            parentId = previousMessageId,
            text = "",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            toolCall = calls.first(),
            segments = toolSegments,
            runId = runId,
        )
        val timestamp = nowMs()
        val entities = buildList {
            add(
                MessageEntity(
                    id = toolMessageId,
                    conversationId = conversationId,
                    parentId = previousMessageId,
                    text = "",
                    thoughts = null,
                    status = MessageStatus.SUCCESS,
                    participant = Participant.MODEL,
                    timestamp = timestamp,
                    modelName = modelName,
                    toolCallJson = boundedSegmentsJson,
                    runId = runId,
                ),
            )
            resultMessages.forEachIndexed { index, (resultId, resultMessage) ->
                val call = calls[index]
                add(
                    MessageEntity(
                        id = resultId,
                        conversationId = conversationId,
                        parentId = toolMessageId,
                        text = call.result,
                        thoughts = null,
                        status = MessageStatus.SUCCESS,
                        images = call.resultImages.map { it.path },
                        attachmentMeta = resultMessage.attachmentMeta?.let { Json.encodeToString(it) },
                        participant = Participant.USER,
                        timestamp = timestamp + index + 1,
                        modelName = modelName,
                        runId = runId,
                        toolCallJson = Json.encodeToString(
                            listOf(call.toSegment(providerName, includeResponseState = false)),
                        ),
                    ),
                )
            }
        }
        return PersistableToolRound(
            pathMessages = listOf(toolPathMessage) + resultMessages.map { it.second },
            entities = entities,
            lastResultId = resultMessages.last().first,
        )
    }

    /** The descriptor owns API-visible paths; metadata marks only these preview indices as runtime files. */
    private fun ToolCallData.sandboxRuntimeImageMetadata(): AttachmentMeta? {
        val descriptors = sandboxGeneratedOutputImages(toolName, result)
        if (descriptors.size != resultImages.size || descriptors.isEmpty()) return null
        return AttachmentMeta(resultImages.mapIndexed { index, image ->
            AttachmentItem(
                type = "image", imageIndex = index,
                storage = AttachmentStorage.LOCAL_SANDBOX_RUNTIME,
                mimeType = image.mimeType, fileSize = image.sizeBytes,
                pixelWidth = image.width, pixelHeight = image.height,
            )
        })
    }

    private fun ToolCallData.toSegment(
        providerName: String,
        includeResponseState: Boolean = true,
    ) = MessageSegment(
        type = "tool",
        toolName = toolName,
        toolArgs = arguments,
        toolResult = result,
        signature = signature,
        signatureProvider = providerName.takeIf { signature != null },
        toolCallId = toolCallId,
        toolDisplayName = displayName,
        toolTarget = target,
        toolResultText = resultText,
        toolStructuredResult = structuredResult,
        toolImages = resultImages,
        toolTranscription = transcription,
        responseOutputItems = if (includeResponseState) responseOutputItems else emptyList(),
        responseOutputItemProvider = if (includeResponseState) responseOutputItemProvider else null,
    )
}
