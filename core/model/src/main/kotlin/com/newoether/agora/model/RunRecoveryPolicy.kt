package com.newoether.agora.model
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pure process-death recovery rules for content stored inside a model message.
 *
 * A tool that was only CALLING/RUNNING cannot still be executing after the Android process that
 * owned it has died. Terminal tool states remain authoritative, including BACKGROUND_RUNNING
 * where the external operation intentionally outlives the request coroutine.
 */
object RunRecoveryPolicy {
    fun recoverMessageStatus(
        participant: Participant,
        status: MessageStatus,
    ): MessageStatus = if (
        participant == Participant.MODEL &&
        status in setOf(
            MessageStatus.SENDING,
            MessageStatus.THINKING,
            MessageStatus.TOOL_CALLING,
            MessageStatus.TRANSCRIBING,
        )
    ) {
        MessageStatus.STOPPED
    } else {
        status
    }

    fun stopIncompleteTools(segments: List<MessageSegment>): List<MessageSegment> =
        segments.map { segment ->
            if (
                segment.type == "tool" &&
                segment.toolState !in ToolExecutionStates.TERMINAL
            ) {
                segment.copy(toolState = ToolExecutionStates.STOPPED)
            } else {
                segment
            }
        }
    fun stopIncompleteToolsJson(raw: String): String? {
        val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        val segments = root as? JsonArray ?: return null
        var changed = false
        val recovered = JsonArray(
            segments.map { element ->
                val segment = element as? JsonObject ?: return@map element
                val type = (segment["type"] as? JsonPrimitive)?.contentOrNull
                val state = (segment["toolState"] as? JsonPrimitive)?.contentOrNull
                if (type == "tool" && state !in ToolExecutionStates.TERMINAL) {
                    changed = true
                    JsonObject(
                        segment +
                            ("toolState" to JsonPrimitive(ToolExecutionStates.STOPPED))
                    )
                } else {
                    element
                }
            }
        )
        return if (changed) recovered.toString() else raw
    }
}
