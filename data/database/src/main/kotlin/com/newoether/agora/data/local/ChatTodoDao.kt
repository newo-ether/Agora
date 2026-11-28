package com.newoether.agora.data.local

import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Narrow selected-owner projection; never included in the drawer/startup query. */
interface ChatTodoDao {
    @Query("SELECT todoJson FROM conversations WHERE id = :conversationId")
    fun observeConversationTodo(conversationId: String): Flow<String?>

    @Query("SELECT todoJson FROM conversations WHERE id = :conversationId")
    suspend fun getConversationTodo(conversationId: String): String?

    @Query(
        """
        UPDATE conversations
        SET todoJson = :todoJson, dataChangedAt = MAX(dataChangedAt + 1, :at)
        WHERE id = :conversationId AND EXISTS (
            SELECT 1 FROM runs WHERE id = :runId AND conversationId = :conversationId
                AND status = 'ACTIVE' AND activeSlot = 1 AND currentPass = :pass
                AND stopRequestedAt IS NULL
        )
        """
    )
    suspend fun updateConversationTodo(
        conversationId: String, todoJson: String?, runId: String, pass: Int, at: Long,
    ): Int
}
