package com.newoether.agora.data.repository

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun decodeSelectionMap(raw: String?): MutableMap<String?, String> =
    raw?.let {
        runCatching {
            Json.decodeFromString<Map<String, String>>(it)
                .mapKeysTo(mutableMapOf()) { entry ->
                    if (entry.key == "null") null else entry.key
                }
        }.getOrDefault(mutableMapOf())
    } ?: mutableMapOf()

internal fun encodeSelectionMap(selections: Map<String?, String>): String =
    Json.encodeToString(selections.mapKeys { it.key ?: "null" })

/**
 * Outcome of a voice-exchange persist attempt. [Skipped] means a live Run owns the conversation
 * (active text/agent generation) so the voice turn was intentionally NOT written, to avoid racing
 * the live-generation state machine.
 */
sealed interface VoicePersistResult {
    data class Persisted(val runId: String, val userMessageId: String, val assistantMessageId: String) :
        VoicePersistResult

    data object Skipped : VoicePersistResult
    data object Empty : VoicePersistResult
}

/**
 * Appends one completed Live Voice exchange (spoken user turn + spoken assistant reply) to [conversationId]
 * as a single TERMINAL Run of PLAIN-TEXT messages, selected onto the current branch so it shows in history.
 *
 * Plain text only — no tool-protocol segments — so the persisted turns are trivially valid for the Gemini
 * request builder (adaptToolRoundsForProvider / requireValidWireFormat) and cannot create an orphaned tool
 * round in a later multi-agent request. Mirrors AIOPE's messageSink semantics on Agora's Run graph.
 *
 * No-ops (returns [VoicePersistResult.Skipped]) when a live Run already owns the conversation.
 */
suspend fun ConversationRepository.persistVoiceExchange(
    conversationId: String,
    userText: String,
    assistantText: String,
    modelName: String?,
    at: Long = System.currentTimeMillis(),
): VoicePersistResult {
    val user = userText.trim()
    val assistant = assistantText.trim()
    if (user.isEmpty() && assistant.isEmpty()) return VoicePersistResult.Empty

    return withProviderContextSnapshot {
        val conversation = chatDao.getConversation(conversationId) ?: return@withProviderContextSnapshot VoicePersistResult.Empty
        if (chatDao.getLiveRun(conversationId) != null) return@withProviderContextSnapshot VoicePersistResult.Skipped

        // Resolve the current selected leaf (tip) by walking the message selection map root->leaf.
        val topology = chatDao.getMessageContextTopology(conversationId)
        val byParent = topology.associateBy { it.parentId }
        val messageSelections = decodeSelectionMap(conversation.selectedBranchesJson)
        val runSelections = decodeSelectionMap(conversation.selectedRunBranchesJson)
        var tipId: String? = null
        var cursor: String? = null
        while (true) {
            val nextId = messageSelections[cursor] ?: byParent[cursor]?.id ?: break
            val node = topology.firstOrNull { it.id == nextId } ?: break
            tipId = node.id
            cursor = node.id
        }
        val parentRunId = tipId?.let { id -> topology.firstOrNull { it.id == id }?.runId }

        val runId = "run-voice-${UUID.randomUUID()}"
        val userId = "msg-voice-u-${UUID.randomUUID()}"
        val assistantId = "msg-voice-a-${UUID.randomUUID()}"

        val run = RunEntity(
            id = runId,
            conversationId = conversationId,
            parentRunId = parentRunId,
            status = RunStatus.COMPLETED,
            activeSlot = null,
            startedAt = at,
            lastCheckpointAt = at,
            endedAt = at,
            endReason = RunEndReason.MODEL_COMPLETED,
            currentPass = 0,
            legacyAmbiguous = false,
        )

        val messages = mutableListOf<MessageEntity>()
        var seq = 0L
        var lastId: String? = tipId
        if (user.isNotEmpty()) {
            messages += MessageEntity(
                id = userId, conversationId = conversationId, parentId = lastId, text = user,
                status = MessageStatus.SUCCESS, participant = Participant.USER, timestamp = at,
                runId = runId, runSequence = seq++,
            )
            messageSelections[lastId] = userId
            lastId = userId
        }
        if (assistant.isNotEmpty()) {
            messages += MessageEntity(
                id = assistantId, conversationId = conversationId, parentId = lastId, text = assistant,
                status = MessageStatus.SUCCESS, participant = Participant.MODEL, timestamp = at + 1,
                modelName = modelName, runId = runId, runSequence = seq++,
            )
            messageSelections[lastId] = assistantId
            lastId = assistantId
        }
        if (messages.isEmpty()) return@withProviderContextSnapshot VoicePersistResult.Empty

        runSelections[parentRunId] = runId

        chatDao.importRunGraph(listOf(run), messages)
        check(
            chatDao.updateConversationForRunAdmission(
                conversationId = conversationId,
                selectedBranchesJson = encodeSelectionMap(messageSelections),
                selectedRunBranchesJson = encodeSelectionMap(runSelections),
                modelId = null,
                at = at,
                touchConversationOnAdmission = true,
            ) == 1
        ) { "Conversation $conversationId disappeared during voice-exchange persist" }

        VoicePersistResult.Persisted(
            runId = runId,
            userMessageId = if (user.isNotEmpty()) userId else assistantId,
            assistantMessageId = if (assistant.isNotEmpty()) assistantId else userId,
        )
    }
}
