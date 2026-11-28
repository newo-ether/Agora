package com.newoether.agora.data.repository

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val referenceJson = Json { ignoreUnknownKeys = true }

internal fun MessageEntity.toolRoundReferenceIds(): List<String> = toolCallJson?.let { raw ->
    referenceJson.decodeFromString<List<MessageSegment>>(raw).mapNotNull { it.toolRoundId }.distinct()
}.orEmpty()

internal suspend fun ConversationRepository.loadReferencedToolRounds(
    entity: MessageEntity,
): List<MessageEntity> {
    // Legacy malformed payloads keep their existing projection behavior.
    val ids = runCatching { entity.toolRoundReferenceIds() }.getOrDefault(emptyList())
    return ids.chunked(500).flatMap { getMessagesByIds(it) }
}

/**
 * Replaces stub tool segments with their committed round bodies.
 *
 * Every lookup fails soft. A persisted reference can dangle (a refused Compact round never
 * reaches Room) or point at a row that no longer qualifies, and none of that may take down
 * message hydration: the original segment stays as it was persisted.
 */
internal fun MessageEntity.resolveToolRoundReferences(
    segments: List<MessageSegment>?,
    rounds: List<MessageEntity>,
): List<MessageSegment>? {
    val referencedIds = segments.orEmpty().mapNotNull { it.toolRoundId }.toSet()
    if (referencedIds.isEmpty()) return segments
    val toolsByRound = rounds
        .filter { it.id in referencedIds }
        .filter { round ->
            round.conversationId == conversationId && runId.isNotBlank() && round.runId == runId &&
                round.id.startsWith(Constants.TOOL_MSG_PREFIX) && round.status == MessageStatus.SUCCESS
        }
        .associate { round ->
            val body = round.toolCallJson?.let { raw ->
                runCatching { referenceJson.decodeFromString<List<MessageSegment>>(raw) }.getOrNull()
            }.orEmpty()
            round.id to body
        }
    return segments?.map { segment ->
        val roundId = segment.toolRoundId ?: return@map segment
        val body = toolsByRound[roundId]?.singleOrNull {
            it.type == "tool" && it.toolCallId == segment.toolCallId
        } ?: return@map segment
        body.copy(
            toolRoundId = roundId,
            responseOutputItems = emptyList(),
            responseOutputItemProvider = null,
        )
    }
}

internal fun remapToolRoundReferences(raw: String?, messageIds: Map<String, String>): String? {
    if (raw == null) return null
    val segments = runCatching {
        referenceJson.decodeFromString<List<MessageSegment>>(raw)
    }.getOrNull() ?: return raw
    if (segments.none { it.toolRoundId != null }) return raw
    return referenceJson.encodeToString(segments.map { segment ->
        segment.toolRoundId?.let { roundId ->
            // A round that never persisted (refused Compact calls) has no forked counterpart;
            // dropping the reference keeps the fork alive with the segment's inline data.
            segment.copy(toolRoundId = messageIds[roundId])
        } ?: segment
    })
}
