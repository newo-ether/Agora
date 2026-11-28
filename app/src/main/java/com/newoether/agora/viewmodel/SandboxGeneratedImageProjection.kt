package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.projectedImageOccurrences
import com.newoether.agora.api.util.withProjectedImages
import com.newoether.agora.model.ChatMessage
import kotlinx.serialization.json.JsonObject

/** Shares the descriptor decoder with persistence and legacy reference maintenance. */
internal fun sandboxGeneratedOutputImages(toolName: String?, result: String?): List<JsonObject> =
    com.newoether.agora.model.sandboxGeneratedOutputImages(toolName, result)

private fun isSandboxGeneratedOutput(toolName: String?, result: String?): Boolean =
    sandboxGeneratedOutputImages(toolName, result).isNotEmpty()

/** Removes generated image inputs only from API projections; Room and preview slots stay intact. */
internal fun projectSandboxGeneratedImages(message: ChatMessage): ChatMessage {
    val toolSegments = message.segments.orEmpty().filter { it.type == "tool" }
    val hiddenSegments = toolSegments.filter { isSandboxGeneratedOutput(it.toolName, it.toolResult) }.toSet()
    val hideCall = message.toolCall?.let { isSandboxGeneratedOutput(it.toolName, it.result) } == true
    if (hiddenSegments.isEmpty() && !hideCall) return message
    val hiddenPaths = hiddenSegments.flatMap { it.toolImages }.map { it.path }.toSet() +
        message.toolCall?.resultImages.orEmpty().takeIf { hideCall }.orEmpty().map { it.path }
    val orderedImages = toolSegments.flatMap { segment ->
        segment.toolImages.map { it.path to (segment in hiddenSegments) }
    }.ifEmpty { message.toolCall?.resultImages.orEmpty().map { it.path to hideCall } }
    val occurrences = message.projectedImageOccurrences()
    val retained = if (orderedImages.map { it.first } == message.images) {
        occurrences.filterIndexed { index, _ -> !orderedImages[index].second }
    } else {
        occurrences.filterNot { it.path in hiddenPaths }
    }
    val projected = message.withProjectedImages(retained)
    return projected.copy(
        segments = message.segments?.map { segment ->
            if (segment in hiddenSegments) segment.copy(toolImages = emptyList(), toolTranscription = null)
            else segment
        },
        toolCall = message.toolCall?.let { call ->
            if (hideCall) call.copy(resultImages = emptyList(), transcription = null) else call
        },
    )
}
