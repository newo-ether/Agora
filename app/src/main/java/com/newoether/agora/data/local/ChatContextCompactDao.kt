package com.newoether.agora.data.local

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.RunStatus
import com.newoether.agora.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private fun decodeCompactSelections(raw: String?): MutableMap<String?, String> =
    raw?.let {
        runCatching {
            Json.decodeFromString<Map<String, String>>(it)
                .mapKeysTo(mutableMapOf()) { entry ->
                    if (entry.key == "null") null else entry.key
                }
        }.getOrDefault(mutableMapOf())
    } ?: mutableMapOf()

private fun encodeCompactSelections(selections: Map<String?, String>): String =
    Json.encodeToString(selections.mapKeys { it.key ?: "null" })

/**
 * Context-Compact graph transactions inherited by [ChatDao].
 *
 * This is not a second database access object: [ChatDao] remains the sole `@Dao`. The split keeps
 * the atomic graph contract reviewable and under the repository's permanent source-size gate.
 */
interface ChatContextCompactDao : SemanticMutationDao {
    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversation(conversationId: String): ChatEntity?

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessage(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: RunEntity)

    @Query("SELECT * FROM runs WHERE id = :runId")
    suspend fun getRun(runId: String): RunEntity?

    @Query("SELECT * FROM messages WHERE runId IN (:runIds) ORDER BY runSequence, timestamp, id")
    suspend fun getMessagesForRuns(runIds: List<String>): List<MessageEntity>

    @Query("SELECT NOT EXISTS(SELECT 1 FROM messages WHERE runId = :runId AND id != :messageId)")
    suspend fun isOnlyMessageInRun(runId: String, messageId: String): Boolean

    @Query(
        """
        UPDATE conversations
        SET selectedBranchesJson = :selectedBranchesJson,
            selectedRunBranchesJson = :selectedRunBranchesJson,
            lastUpdated = :at,
            dataChangedAt = :at
        WHERE id = :conversationId
        """
    )
    suspend fun updateSelectionsForRunDeletion(
        conversationId: String,
        selectedBranchesJson: String,
        selectedRunBranchesJson: String,
        at: Long,
    ): Int

    @Query(
        """
        UPDATE conversations
        SET selectedBranchesJson = :selectedBranchesJson,
            dataChangedAt = :at
        WHERE id = :conversationId
        """
    )
    suspend fun updateMessageBranchSelections(
        conversationId: String,
        selectedBranchesJson: String,
        at: Long,
    ): Int

    @Query(
        """
        UPDATE conversations
        SET selectedRunBranchesJson = :selectedRunBranchesJson,
            dataChangedAt = :at
        WHERE id = :conversationId
        """
    )
    suspend fun updateRunBranchSelections(
        conversationId: String,
        selectedRunBranchesJson: String,
        at: Long,
    ): Int

    @Query(
        """
        UPDATE conversations
        SET selectedBranchesJson = :selectedBranchesJson,
            selectedRunBranchesJson = :selectedRunBranchesJson,
            dataChangedAt = :at
        WHERE id = :conversationId
        """
    )
    suspend fun updateBranchSelections(
        conversationId: String,
        selectedBranchesJson: String,
        selectedRunBranchesJson: String,
        at: Long,
    ): Int

    @Query("SELECT * FROM runs WHERE conversationId = :conversationId AND activeSlot = 1 LIMIT 1")
    suspend fun getLiveRun(conversationId: String): RunEntity?

    @Query("SELECT COALESCE(MAX(runSequence), -1) + 1 FROM messages WHERE runId = :runId")
    suspend fun nextRunSequence(runId: String): Long

    @Query("UPDATE runs SET lastCheckpointAt = :at WHERE id = :runId")
    suspend fun touchRun(runId: String, at: Long): Int

    @Query("UPDATE messages SET parentId = :replacementParentId WHERE parentId = :removedMessageId")
    suspend fun reparentMessageChildren(removedMessageId: String, replacementParentId: String?): Int

    @Query("UPDATE runs SET parentRunId = :replacementParentRunId WHERE parentRunId = :removedRunId")
    suspend fun reparentRunChildren(removedRunId: String, replacementParentRunId: String?): Int

    @Query(
        """
        UPDATE messages
        SET runId = :newRunId, runSequence = 0, text = '', status = 'SENDING',
            modelName = :modelName
        WHERE id = :messageId AND runId = :oldRunId
          AND status IN ('SUCCESS', 'ERROR', 'STOPPED')
        """
    )
    suspend fun replaceContextCompactMessageRun(
        messageId: String,
        oldRunId: String,
        newRunId: String,
        modelName: String,
    ): Int

    /**
     * Substitutes one terminal Compact's dedicated Run with a fresh active Run while retaining the
     * same message identity and graph position. No non-target message row is updated.
     */
    @Transaction
    suspend fun beginRecompactContextCompact(
        replacementRun: RunEntity,
        messageId: String,
        modelName: String,
        expectedSelectedBranchesJson: String,
    ): MessageEntity {
        val message = getMessage(messageId) ?: error("Compact disappeared")
        check(getLiveRun(message.conversationId) == null) {
            "Conversation became busy during Recompact"
        }
        require(message.id.startsWith(Constants.COMPACT_MSG_PREFIX))
        val oldRun = getRun(message.runId) ?: error("Compact Run disappeared")
        check(oldRun.status.isTerminal && oldRun.activeSlot == null) {
            "Compact Run is not terminal"
        }
        check(isOnlyMessageInRun(oldRun.id, message.id)) {
            "Compact Run is not independently replaceable"
        }
        check(
            message.status in setOf(
                MessageStatus.SUCCESS,
                MessageStatus.ERROR,
                MessageStatus.STOPPED,
            )
        ) { "Compact message is not terminal" }
        val conversation = getConversation(message.conversationId)
            ?: error("Conversation ${message.conversationId} disappeared")
        check(
            decodeCompactSelections(conversation.selectedBranchesJson) ==
                decodeCompactSelections(expectedSelectedBranchesJson)
        ) { "Selected message branch changed before Recompact" }
        val parent = message.parentId?.let { getMessage(it) }
            ?: error("Compact parent disappeared")
        require(replacementRun.id != oldRun.id) { "Recompact requires a fresh Run" }
        require(replacementRun.conversationId == message.conversationId)
        require(replacementRun.status == RunStatus.ACTIVE && replacementRun.activeSlot == 1)
        require(replacementRun.parentRunId == parent.runId)
        check(oldRun.parentRunId == parent.runId) { "Compact Run ancestry changed" }

        val runSelections = decodeCompactSelections(conversation.selectedRunBranchesJson)
        check(
            runSelections.none { (parentRunId, selectedRunId) ->
                selectedRunId == oldRun.id && parentRunId != oldRun.parentRunId
            }
        ) { "Selected Run graph is inconsistent" }
        val selectedChildRun = runSelections.remove(oldRun.id)
        if (runSelections[oldRun.parentRunId] == oldRun.id) {
            runSelections[oldRun.parentRunId] = replacementRun.id
        }
        if (selectedChildRun != null) runSelections[replacementRun.id] = selectedChildRun

        insertRun(replacementRun)
        check(
            replaceContextCompactMessageRun(
                messageId = message.id,
                oldRunId = oldRun.id,
                newRunId = replacementRun.id,
                modelName = modelName,
            ) == 1
        )
        reparentRunChildren(oldRun.id, replacementRun.id)
        check(
            updateSelectionsForRunDeletion(
                conversationId = message.conversationId,
                selectedBranchesJson = conversation.selectedBranchesJson ?: "{}",
                selectedRunBranchesJson = encodeCompactSelections(runSelections),
                at = replacementRun.startedAt,
            ) == 1
        )
        check(deleteRun(oldRun.id) == 1)
        return message.copy(
            runId = replacementRun.id,
            runSequence = 0,
            text = "",
            status = MessageStatus.SENDING,
            modelName = modelName,
        )
    }


    @Transaction
    suspend fun removeContextCompact(messageId: String): Boolean {
        val message = getMessage(messageId) ?: return false
        require(message.id.startsWith(Constants.COMPACT_MSG_PREFIX))
        val conversation = getConversation(message.conversationId) ?: return false
        val compactRun = getRun(message.runId) ?: return false
        val compactOwnsDedicatedRun =
            isOnlyMessageInRun(compactRun.id, message.id)
        reparentMessageChildren(message.id, message.parentId)
        deleteEmbeddingsByMessageIds(listOf(message.id))
        deleteMessagesByIds(listOf(message.id))

        val selections = decodeCompactSelections(conversation.selectedBranchesJson)
        val selectedCompact = selections[message.parentId] == message.id
        val selectedSuffixChildId = selections.remove(message.id)
        if (selectedCompact) {
            if (selectedSuffixChildId == null) selections.remove(message.parentId)
            else selections[message.parentId] = selectedSuffixChildId
        }
        val runSelections = decodeCompactSelections(conversation.selectedRunBranchesJson)
        if (compactOwnsDedicatedRun) {
            val selectedCompactRun = runSelections[compactRun.parentRunId] == compactRun.id
            val selectedCompactRunChild = runSelections.remove(compactRun.id)
            if (selectedCompactRun) {
                if (selectedCompactRunChild == null) runSelections.remove(compactRun.parentRunId)
                else runSelections[compactRun.parentRunId] = selectedCompactRunChild
            }
        }
        check(
            updateSelectionsForRunDeletion(
                conversationId = message.conversationId,
                selectedBranchesJson = encodeCompactSelections(selections),
                selectedRunBranchesJson = encodeCompactSelections(runSelections),
                at = System.currentTimeMillis(),
            ) == 1
        )
        if (compactOwnsDedicatedRun) {
            reparentRunChildren(compactRun.id, compactRun.parentRunId)
            check(deleteRun(compactRun.id) == 1)
        }
        return true
    }
}
