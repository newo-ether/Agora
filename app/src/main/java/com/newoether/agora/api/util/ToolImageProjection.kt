package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import java.security.MessageDigest

/** Projects the latest generated image set onto the latest normal user turn for visual follow-ups. */
fun projectAssistantImagesToLatestUserMessage(
    messages: List<ChatMessage>,
    includeImages: Boolean,
): List<ChatMessage> {
    if (messages.isEmpty()) return messages
    val latestUserIndex = messages.indexOfLast {
        it.participant == Participant.USER && !it.isToolProtocolMessage()
    }
    val generated = if (includeImages && latestUserIndex >= 0) {
        messages.asSequence().take(latestUserIndex)
            .filter { it.isNormalAssistantMessage() && it.images.isNotEmpty() }
            .lastOrNull()?.projectedImageOccurrences().orEmpty().distinctAvailableImages()
    } else emptyList()
    return messages.mapIndexed { index, message ->
        var projected = if (message.isNormalAssistantMessage() && message.images.isNotEmpty()) {
            message.withProjectedImages(emptyList())
        } else if (message.images.isNotEmpty()) {
            val occurrences = message.projectedImageOccurrences()
            val available = occurrences.filter { it.path.isNotBlank() && it.artifact?.unavailable != true }
            if (available.size == occurrences.size) message else message.withProjectedImages(available)
        } else message
        if (index == latestUserIndex && generated.isNotEmpty()) {
            projected = projected.withProjectedImages(
                (generated + projected.projectedImageOccurrences()).distinctAvailableImages(),
            ).copy(text = addGeneratedImageContextNote(projected.text, generated.size))
        }
        projected
    }
}

/** Adds one API-only user turn after a complete tool-result batch without breaking adjacency. */
fun projectToolResultImagesToUserMessage(
    messages: List<ChatMessage>,
    includeImages: Boolean,
): List<ChatMessage> {
    if (messages.none {
        it.id.startsWith(Constants.RESULT_MSG_PREFIX) &&
            (it.images.isNotEmpty() || transcriptionDescriptionsForBatch(listOf(it)) != null)
    }) {
        return messages
    }
    val projected = ArrayList<ChatMessage>(messages.size + 2)
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        projected += message
        if (!message.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
            index++
            continue
        }
        val batch = mutableListOf(message)
        var next = index + 1
        while (next < messages.size && messages[next].id.startsWith(Constants.RESULT_MSG_PREFIX)) {
            projected += messages[next]
            batch += messages[next]
            next++
        }
        val occurrences = batch.flatMap { it.projectedImageOccurrences() }.distinctAvailableImages()
        val description = transcriptionDescriptionsForBatch(batch)
        if (occurrences.isNotEmpty() || description != null) {
            val first = batch.first()
            // This digest identifies the protocol batch from message IDs, never image bytes.
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(batch.joinToString(":") { it.id }.toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
            // The result rows retain descriptions across a round-boundary path rebuild.
            val descriptionBlock = description
                ?.let { "\n\n--- Image Transcription: file_view_image ---\n$it" }.orEmpty()
            projected += ChatMessage(
                // Provider serializers reserve tool_, result_ and compact_ for protocol rows.
                id = "image_context_$digest",
                parentId = batch.last().id,
                text = if (includeImages && occurrences.isNotEmpty()) {
                    "[Tool visual result: inspect the attached image${if (occurrences.size == 1) "" else "s"} before continuing.]$descriptionBlock"
                } else if (descriptionBlock.isNotBlank()) {
                    "[Tool visual result: the image description follows.]$descriptionBlock"
                } else {
                    "[Tool visual result unavailable: the current model does not support image input.]"
                },
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
                timestamp = batch.last().timestamp,
                runId = first.runId,
                runSequence = first.runSequence,
            ).let { if (includeImages) it.withProjectedImages(occurrences) else it }
        }
        index = next
    }
    return projected
}

private fun transcriptionDescriptionsForBatch(batch: List<ChatMessage>): String? = batch
    .flatMap { message ->
        message.segments.orEmpty().filter { it.type == "tool" }
            .mapNotNull { it.toolTranscription?.takeIf(String::isNotBlank) }
            .ifEmpty { listOfNotNull(message.toolCall?.transcription?.takeIf(String::isNotBlank)) }
    }
    .distinct().joinToString("\n\n").takeIf(String::isNotBlank)

private fun ChatMessage.isNormalAssistantMessage(): Boolean =
    participant == Participant.MODEL && !isToolProtocolMessage()

private fun addGeneratedImageContextNote(text: String, imageCount: Int): String {
    val note = if (imageCount == 1) {
        "[Visual context: the first attached image was generated by the assistant earlier in this conversation.]"
    } else {
        "[Visual context: the first $imageCount attached images were generated by the assistant earlier in this conversation.]"
    }
    return if (text.isBlank()) note else "$note\n\n$text"
}
