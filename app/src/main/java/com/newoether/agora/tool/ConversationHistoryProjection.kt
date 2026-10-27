package com.newoether.agora.tool

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.resolveToolRoundReferences
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.terminalErrorSegment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val historyJson = Json { ignoreUnknownKeys = true }

/** Read-only detail projection for conversation_read selected-branch pages. */
internal suspend fun ConversationRepository.projectHistoryMessage(
    message: MessageEntity,
    showToolCalls: Boolean,
    showThinking: Boolean,
    toolOffset: Int,
    toolLimit: Int,
): JsonObject {
    val decoded = runCatching {
        message.toolCallJson?.let { historyJson.decodeFromString<List<MessageSegment>>(it) }.orEmpty()
    }
    val segments = decoded.getOrDefault(emptyList())
    val tools = segments.withIndex().filter { it.value.type == "tool" }
    val selectedTools = if (showToolCalls) tools.drop(toolOffset).take(toolLimit) else emptyList()
    val roundIds = selectedTools.mapNotNull { it.value.toolRoundId }.distinct()
    // Only the requested tool page is hydrated, never every round in a long-running reply.
    val rounds = if (roundIds.isEmpty()) emptyList() else getMessagesByIds(roundIds)
    val resolvedTools = message.resolveToolRoundReferences(selectedTools.map { it.value }, rounds).orEmpty()
    val roundById = rounds.associateBy { it.id }
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return buildJsonObject {
        put("message_id", message.id)
        put("participant", message.participant.name)
        put("text", message.text)
        put("status", message.status.name)
        put("run_id", message.runId)
        put("run_sequence", message.runSequence)
        put("timestamp", dateFormat.format(Date(message.timestamp)))
        terminalErrorSegment(message.status, message.participant, message.text, segments)?.let { failure ->
            put("error", failure.content)
            failure.errorCode?.let { put("error_code", it) }
        }
        if ((showToolCalls || showThinking) && decoded.isFailure) {
            put("details_error", "invalid_stored_segments")
        }
        if (showThinking) {
            putJsonArray("thinking") {
                val thoughts = segments.withIndex().filter { it.value.type == "thought" && it.value.content.isNotEmpty() }
                if (thoughts.isNotEmpty()) {
                    for ((index, thought) in thoughts) add(buildJsonObject {
                        put("segment_index", index)
                        put("text", thought.content)
                        thought.durationMs?.let { put("duration_ms", it) }
                    })
                } else if (!message.thoughts.isNullOrEmpty()) {
                    add(buildJsonObject {
                        put("text", message.thoughts)
                        message.thoughtTimeMs?.let { put("duration_ms", it) }
                    })
                }
            }
        }
        if (showToolCalls) {
            val nextOffset = toolOffset.coerceAtMost(tools.size) + selectedTools.size
            put("tool_calls_total", tools.size)
            put("tool_offset", toolOffset)
            put("tool_limit", toolLimit)
            put("tool_calls_has_more", nextOffset < tools.size)
            if (nextOffset < tools.size) put("tool_calls_next_offset", nextOffset)
            else put("tool_calls_next_offset", JsonNull)
            putJsonArray("tool_calls") {
                for ((position, tool) in resolvedTools.withIndex()) add(buildJsonObject {
                    put("segment_index", selectedTools[position].index)
                    tool.toolCallId?.let { put("tool_call_id", it) }
                    tool.toolName?.let { put("name", it) }
                    tool.toolArgs?.let { put("arguments", it) }
                    tool.toolResult?.let { put("result", it) }
                    tool.toolState?.let { put("state", it) }
                    tool.durationMs?.let { put("duration_ms", it) }
                    tool.toolTarget?.let { put("target", it) }
                    tool.toolDisplayName?.let { put("display_name", it) }
                    tool.toolResultText?.let { put("result_text", it) }
                    tool.toolStructuredResult?.let { put("structured_result", it) }
                    tool.toolTranscription?.let { put("image_transcription", it) }
                    if (tool.toolImages.isNotEmpty()) put("image_count", tool.toolImages.size)
                    tool.toolRoundId?.let { roundId ->
                        put("tool_round_id", roundId)
                        roundById[roundId]?.let { round ->
                            put("timestamp", dateFormat.format(Date(round.timestamp)))
                            put("timestamp_source", "round_commit")
                        }
                    }
                })
            }
        }
    }
}
