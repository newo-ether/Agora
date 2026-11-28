package com.newoether.agora.data.local

import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Payload-free context queries inherited by [ChatDao]. Full message rows are fetched separately
 * inside the repository-owned immutable read transaction.
 */
interface ChatProviderContextDao {
    @Query("SELECT selectedBranchesJson FROM conversations WHERE id = :conversationId")
    suspend fun getProviderContextState(
        conversationId: String,
    ): ConversationProviderContextState?

    @Query(
        """
        SELECT
            id,
            conversationId,
            parentId,
            status,
            participant,
            timestamp,
            tokenCount,
            modelName,
            runId,
            runSequence,
            consumedAtPass
        FROM messages
        WHERE conversationId = :conversationId
        ORDER BY timestamp ASC, id ASC
        """
    )
    suspend fun getMessageContextTopology(
        conversationId: String,
    ): List<MessageContextTopology>

    @Query(
        """
        SELECT
            id,
            conversationId,
            parentId,
            status,
            participant,
            timestamp,
            tokenCount,
            modelName,
            runId,
            runSequence,
            consumedAtPass
        FROM messages
        WHERE conversationId = :conversationId
        ORDER BY timestamp ASC, id ASC
        """
    )
    fun observeMessageContextTopology(
        conversationId: String,
    ): Flow<List<MessageContextTopology>>

    @Transaction
    suspend fun getProviderContextTopologySnapshot(
        conversationId: String,
    ): ProviderContextTopologySnapshot? {
        val state = getProviderContextState(conversationId) ?: return null
        return ProviderContextTopologySnapshot(
            selectedBranchesJson = state.selectedBranchesJson,
            messages = getMessageContextTopology(conversationId),
        )
    }
}
