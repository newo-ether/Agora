package com.newoether.agora.data.local

import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** New Chat singleton persistence declarations inherited by [ChatDao]. */
interface NewChatPersistDao {
    @Query("SELECT * FROM new_chat_persist WHERE id = 0")
    fun observeNewChatPersist(): Flow<NewChatPersistEntity?>

    @Query("SELECT * FROM new_chat_persist WHERE id = 0")
    suspend fun getNewChatPersist(): NewChatPersistEntity?

    @Upsert
    suspend fun upsertNewChatPersist(entity: NewChatPersistEntity)

    @Query("DELETE FROM new_chat_persist WHERE id = 0")
    suspend fun deleteNewChatPersist(): Int

    @Query(
        """
        DELETE FROM new_chat_persist
        WHERE id = 0
          AND modelId IS :modelId
          AND systemPromptId IS :systemPromptId
          AND conversationSettingsJson IS :conversationSettingsJson
          AND draftText = :draftText
          AND draftAttachments IS :draftAttachments
        """
    )
    suspend fun deleteNewChatPersistIfMatches(
        modelId: String?,
        systemPromptId: String?,
        conversationSettingsJson: String?,
        draftText: String,
        draftAttachments: String?,
    ): Int
}
