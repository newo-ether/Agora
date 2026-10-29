package com.newoether.agora.data.local

import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** Embedding membership and its per-conversation count always change in the same transaction. */
interface SemanticEmbeddingDao : SemanticMetadataDao {
    @Query("SELECT messageId, modelId FROM embeddings WHERE id = :id OR (messageId = :messageId AND modelId = :modelId)")
    suspend fun findEmbeddingIdentities(id: Long, messageId: String, modelId: String): List<SemanticEmbeddingIdentity>

    @Upsert
    suspend fun upsertEmbeddingRow(embedding: EmbeddingEntity)

    @Insert
    suspend fun insertEmbeddingRows(embeddings: List<EmbeddingEntity>): LongArray

    suspend fun adjustEmbeddingMembership(identity: SemanticEmbeddingIdentity, delta: Int) {
        val source = getSemanticSource(identity.messageId) ?: return
        if (source.eligible) adjustCachedCount(identity.modelId, source.conversationId, delta)
    }

    @Transaction
    suspend fun upsertEmbedding(embedding: EmbeddingEntity) {
        val previous = findEmbeddingIdentities(embedding.id, embedding.messageId, embedding.modelId)
        upsertEmbeddingRow(embedding)
        previous.forEach { adjustEmbeddingMembership(it, -1) }
        adjustEmbeddingMembership(SemanticEmbeddingIdentity(embedding.messageId, embedding.modelId), 1)
    }

    @Transaction
    suspend fun insertEmbeddings(embeddings: List<EmbeddingEntity>): LongArray {
        val ids = insertEmbeddingRows(embeddings)
        embeddings.forEach { adjustEmbeddingMembership(SemanticEmbeddingIdentity(it.messageId, it.modelId), 1) }
        return ids
    }

    @Transaction
    suspend fun deleteEmbeddingsForMessages(messageIds: List<String>): Int {
        var count = 0
        messageIds.distinct().forEach { id ->
            getSourceEmbeddingIdentities(id).forEach { adjustEmbeddingMembership(it, -1) }
            count += deleteSourceEmbeddingRows(id)
        }
        return count
    }

    @Query("DELETE FROM embeddings WHERE modelId = :modelId")
    suspend fun deleteEmbeddingRowsForModel(modelId: String): Int

    @Query("UPDATE semantic_model_conversations SET cachedCount = 0, indexedRevision = -1 WHERE modelId = :modelId")
    suspend fun clearSemanticModelCounts(modelId: String)

    @Transaction
    suspend fun deleteEmbeddingsForModel(modelId: String): Int {
        val count = deleteEmbeddingRowsForModel(modelId)
        clearSemanticModelCounts(modelId)
        return count
    }

    @Query("DELETE FROM embeddings")
    suspend fun deleteAllEmbeddingRows(): Int

    @Query("UPDATE semantic_model_conversations SET cachedCount = 0, indexedRevision = -1")
    suspend fun clearAllSemanticModelCounts()

    @Transaction
    suspend fun deleteAllEmbeddings(): Int {
        val count = deleteAllEmbeddingRows()
        clearAllSemanticModelCounts()
        return count
    }

    @Query("SELECT id FROM messages WHERE conversationId = :conversationId")
    suspend fun getSemanticConversationMessageIds(conversationId: String): List<String>

    @Query("DELETE FROM embeddings WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId)")
    suspend fun deleteConversationEmbeddingRows(conversationId: String)

    @Query("UPDATE semantic_model_conversations SET cachedCount = 0, indexedRevision = -1 WHERE conversationId = :conversationId")
    suspend fun clearSemanticConversationCounts(conversationId: String)

    @Transaction
    suspend fun deleteEmbeddingsByConversation(conversationId: String) {
        deleteConversationEmbeddingRows(conversationId)
        clearSemanticConversationCounts(conversationId)
    }

    @Transaction
    suspend fun deleteEmbeddingsByMessageIds(messageIds: List<String>) {
        deleteEmbeddingsForMessages(messageIds)
    }

    // Orphans and synthetic Compact rows never contribute to source summaries.
    @Query("DELETE FROM embeddings WHERE messageId LIKE 'compact_%' OR NOT EXISTS (SELECT 1 FROM messages WHERE messages.id = embeddings.messageId)")
    suspend fun deleteOrphanedEmbeddings()
}
