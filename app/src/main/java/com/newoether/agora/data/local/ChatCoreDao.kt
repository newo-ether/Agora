package com.newoether.agora.data.local

import androidx.room.Query
import androidx.room.Upsert
import com.newoether.agora.model.ChatConversation
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private fun decodeSelectionMap(raw: String?): MutableMap<String?, String> =
    raw?.let {
        runCatching {
            Json.decodeFromString<Map<String, String>>(it)
                .mapKeysTo(mutableMapOf()) { entry ->
                    if (entry.key == "null") null else entry.key
                }
        }.getOrDefault(mutableMapOf())
    } ?: mutableMapOf()

private fun encodeSelectionMap(selections: Map<String?, String>): String =
    Json.encodeToString(selections.mapKeys { it.key ?: "null" })

/**
 * Core conversation/message/Run CRUD and primary queries inherited by [ChatDao].
 *
 * This is not a second database access object: [ChatDao] remains the sole `@Dao`. The split keeps
 * the core contract reviewable and under the repository's permanent source-size gate.
 */
interface ChatCoreDao : ChatModelReferenceDao {
    @Query("SELECT id, title, systemPromptId, modelId, taskId, origin, graduated, hasUnreadGeneration, selectedBranchesJson FROM conversations WHERE taskId IS NULL ORDER BY lastUpdated DESC")
    fun getAllConversations(): Flow<List<ChatConversation>>

    @Query("SELECT * FROM conversations WHERE taskId = :taskId ORDER BY lastUpdated DESC")
    fun getExecutionsForTask(taskId: String): Flow<List<ChatEntity>>

    @Query("SELECT * FROM conversations WHERE origin = :origin ORDER BY lastUpdated DESC LIMIT 1")
    suspend fun getConversationByOrigin(origin: String): ChatEntity?

    @Query("SELECT * FROM conversations WHERE title = :title ORDER BY lastUpdated DESC LIMIT 1")
    suspend fun getConversationByTitle(title: String): ChatEntity?

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun observeConversation(conversationId: String): Flow<ChatEntity?>

    @Query("SELECT * FROM conversation_settings_transfer WHERE conversationId = :conversationId")
    suspend fun getConversationSettingsTransfer(
        conversationId: String,
    ): ConversationSettingsTransferEntity?

    @Query("SELECT * FROM conversation_settings_transfer ORDER BY conversationId")
    suspend fun getPendingConversationSettingsTransfers(): List<ConversationSettingsTransferEntity>

    @Upsert
    suspend fun upsertConversationSettingsTransfer(entity: ConversationSettingsTransferEntity)

    @Query("DELETE FROM conversation_settings_transfer WHERE conversationId = :conversationId")
    suspend fun deleteConversationSettingsTransfer(conversationId: String): Int

    @Query("SELECT * FROM conversation_settings_import_transfer WHERE id = 0")
    suspend fun getConversationSettingsImportTransfer(): ConversationSettingsImportTransferEntity?

    @Upsert
    suspend fun upsertConversationSettingsImportTransfer(
        entity: ConversationSettingsImportTransferEntity,
    )

    @Query(
        """
        DELETE FROM conversation_settings_import_transfer
        WHERE id = 0 AND transferId = :transferId
        """
    )
    suspend fun deleteConversationSettingsImportTransfer(transferId: String): Int

    @Query("SELECT * FROM messages WHERE id = :messageId")
    fun observeMessage(messageId: String): Flow<MessageEntity?>

    @Query(
        """
        UPDATE messages
        SET status = 'STOPPED'
        WHERE conversationId = :conversationId
          AND status IN ('SENDING', 'THINKING', 'TOOL_CALLING', 'TRANSCRIBING')
        """
    )
    suspend fun stopStuckMessagesForConversation(conversationId: String): Int

    @Upsert
    suspend fun upsertConversation(conversation: ChatEntity)

    @Query("UPDATE conversations SET title = :title WHERE id = :conversationId")
    suspend fun updateConversationTitle(conversationId: String, title: String): Int

    @Query(
        """
        UPDATE conversations
        SET hasUnreadGeneration = :unread
        WHERE id = :conversationId AND hasUnreadGeneration != :unread
        """
    )
    suspend fun setConversationUnreadGeneration(
        conversationId: String,
        unread: Boolean,
    ): Int

    @Query("UPDATE conversations SET modelId = :newModelId WHERE modelId = :oldModelId")
    override suspend fun replaceConversationModelReferences(
        oldModelId: String,
        newModelId: String?,
    ): Int

    @Query("UPDATE new_chat_persist SET modelId = :newModelId WHERE id = 0 AND modelId = :oldModelId")
    override suspend fun replaceNewChatModelReference(
        oldModelId: String,
        newModelId: String?,
    ): Int

    @Query(
        """
        UPDATE conversations
        SET title = :newTitle
        WHERE id = :conversationId AND title = :expectedTitle
        """
    )
    suspend fun updateConversationTitleIfUnchanged(
        conversationId: String,
        expectedTitle: String,
        newTitle: String,
    ): Int

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    @Upsert
    suspend fun upsertRun(run: RunEntity)

    @Query("SELECT * FROM runs WHERE conversationId = :conversationId ORDER BY startedAt, id")
    fun getRunsForConversation(conversationId: String): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE conversationId = :conversationId ORDER BY startedAt, id")
    suspend fun getRunsForConversationSnapshot(conversationId: String): List<RunEntity>

    @Query(
        """
        UPDATE conversations
        SET selectedBranchesJson = :selectedBranchesJson,
            selectedRunBranchesJson = :selectedRunBranchesJson,
            modelId = :modelId,
            lastUpdated = CASE
                WHEN :touchConversationOnAdmission THEN :at
                ELSE lastUpdated
            END
        WHERE id = :conversationId
        """
    )
    suspend fun updateConversationForRunAdmission(
        conversationId: String,
        selectedBranchesJson: String,
        selectedRunBranchesJson: String,
        modelId: String,
        at: Long,
        touchConversationOnAdmission: Boolean,
    ): Int
}