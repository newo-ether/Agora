package com.newoether.agora.ui.chat

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.model.ToolImageAttachment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val HYDRATED_MESSAGE_CACHE_MAX_ENTRIES = 16
internal const val HYDRATED_MESSAGE_CACHE_MAX_BYTES = 8L * 1024L * 1024L

internal class HydratedMessagePayloadLru(
    private val maxEntries: Int = HYDRATED_MESSAGE_CACHE_MAX_ENTRIES,
    private val maxWeightBytes: Long = HYDRATED_MESSAGE_CACHE_MAX_BYTES,
    private val weightOf: (ChatMessage) -> Long = ChatMessage::estimatedHydratedPayloadBytes,
) {
    private data class Entry(
        val message: ChatMessage,
        val weightBytes: Long,
    )

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    var totalWeightBytes: Long = 0L
        private set

    val size: Int
        get() = entries.size

    init {
        require(maxEntries >= 0)
        require(maxWeightBytes >= 0L)
    }

    operator fun get(messageId: String): ChatMessage? = entries[messageId]?.message

    fun put(message: ChatMessage) {
        entries.remove(message.id)?.let { previous ->
            totalWeightBytes -= previous.weightBytes
        }
        val weightBytes = weightOf(message).coerceAtLeast(0L)
        if (maxEntries == 0 || weightBytes > maxWeightBytes) return

        entries[message.id] = Entry(message, weightBytes)
        totalWeightBytes += weightBytes
        while (entries.size > maxEntries || totalWeightBytes > maxWeightBytes) {
            val eldest = entries.entries.firstOrNull() ?: break
            entries.remove(eldest.key)
            totalWeightBytes -= eldest.value.weightBytes
        }
    }

    fun contains(messageId: String): Boolean = entries.containsKey(messageId)
}

internal fun ChatMessage.estimatedHydratedPayloadBytes(): Long {
    var bytes = 512L + preparedMarkdownBytes
    bytes += id.estimatedHeapBytes()
    bytes += parentId.estimatedHeapBytes()
    bytes += text.estimatedHeapBytes()
    bytes += images.estimatedStringListHeapBytes()
    bytes += imageArtifacts.entries.sumOf { (path, artifact) ->
        64L + path.estimatedHeapBytes() + artifact.estimatedHeapBytes()
    }
    bytes += markdownImages.entries.sumOf { (link, image) -> 64L + link.estimatedHeapBytes() + (image.attachment?.estimatedHeapBytes() ?: 0L) }
    bytes += thoughts.estimatedHeapBytes()
    bytes += thoughtTitle.estimatedHeapBytes()
    bytes += modelName.estimatedHeapBytes()
    bytes += retryText.estimatedHeapBytes()
    bytes += runId.estimatedHeapBytes()
    bytes += toolCall?.estimatedHeapBytes() ?: 0L
    bytes += segments.orEmpty().sumOf(MessageSegment::estimatedHeapBytes)
    bytes += attachmentMeta?.items.orEmpty().sumOf(AttachmentItem::estimatedHeapBytes)
    return bytes
}

private fun ToolCallData.estimatedHeapBytes(): Long =
    256L +
        toolName.estimatedHeapBytes() +
        arguments.estimatedHeapBytes() +
        result.estimatedHeapBytes() +
        signature.estimatedHeapBytes() +
        toolCallId.estimatedHeapBytes() +
        displayName.estimatedHeapBytes() +
        resultText.estimatedHeapBytes() +
        structuredResult.estimatedHeapBytes() +
        responseOutputItemProvider.estimatedHeapBytes() +
        transcription.estimatedHeapBytes() +
        resultImages.sumOf(ToolImageAttachment::estimatedHeapBytes) +
        responseOutputItems.sumOf(JsonObject::estimatedHeapBytes)

private fun MessageSegment.estimatedHeapBytes(): Long =
    320L +
        type.estimatedHeapBytes() +
        content.estimatedHeapBytes() +
        toolName.estimatedHeapBytes() +
        toolArgs.estimatedHeapBytes() +
        toolResult.estimatedHeapBytes() +
        toolCallId.estimatedHeapBytes() +
        signature.estimatedHeapBytes() +
        signatureProvider.estimatedHeapBytes() +
        toolState.estimatedHeapBytes() +
        toolProgress.estimatedHeapBytes() +
        toolTarget.estimatedHeapBytes() +
        toolDisplayName.estimatedHeapBytes() +
        toolResultText.estimatedHeapBytes() +
        toolStructuredResult.estimatedHeapBytes() +
        toolTranscription.estimatedHeapBytes() +
        responseOutputItemProvider.estimatedHeapBytes() +
        toolImages.sumOf(ToolImageAttachment::estimatedHeapBytes) +
        responseOutputItems.sumOf(JsonObject::estimatedHeapBytes)

private fun ToolImageAttachment.estimatedHeapBytes(): Long =
    128L +
        path.estimatedHeapBytes() +
        mimeType.estimatedHeapBytes()

private fun AttachmentItem.estimatedHeapBytes(): Long =
    160L +
        originalUri.estimatedHeapBytes() +
        type.estimatedHeapBytes() +
        fileName.estimatedHeapBytes() +
        mimeType.estimatedHeapBytes() +
        warning.estimatedHeapBytes() +
        textContent.estimatedHeapBytes() +
        transcription.estimatedHeapBytes() +
        imageArtifacts.sumOf(ToolImageAttachment::estimatedHeapBytes)

private fun JsonElement.estimatedHeapBytes(): Long = when (this) {
    is JsonObject -> estimatedHeapBytes()
    is JsonArray -> 64L + sumOf(JsonElement::estimatedHeapBytes)
    is JsonPrimitive -> 48L + content.estimatedHeapBytes()
}

private fun JsonObject.estimatedHeapBytes(): Long =
    96L + entries.sumOf { (key, value) ->
        32L + key.estimatedHeapBytes() + value.estimatedHeapBytes()
    }

private fun String?.estimatedHeapBytes(): Long =
    this?.let { value -> 24L + value.length.toLong() * 2L } ?: 0L

private fun List<String>.estimatedStringListHeapBytes(): Long =
    24L + sumOf { value -> 8L + value.estimatedHeapBytes() }
