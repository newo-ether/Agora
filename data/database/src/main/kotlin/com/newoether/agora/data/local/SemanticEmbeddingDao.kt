package com.newoether.agora.data.local

import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** Embedding membership and its per-conversation count always change in the same transaction. */
interface SemanticEmbeddingDao : SemanticMetadataDao {
    @Query("SELECT e.messageId, e.modelId FROM embeddings e WHERE (e.id = :id OR (e.messageId = :messageId AND e.modelId = :modelId)) AND $CURRENT_EMBEDDING")
    suspend fun findEmbeddingIdentities(id: Long, messageId: String, modelId: String): List<SemanticEmbeddingIdentity>

    @Query("SELECT id FROM embeddings WHERE messageId = :messageId AND modelId = :modelId LIMIT 1")
    suspend fun findStoredEmbeddingId(messageId: String, modelId: String): Long?

    @Query("SELECT generation FROM semantic_index_ledger WHERE modelId = :modelId")
    suspend fun getStoredEmbeddingGeneration(modelId: String): Long?

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
        val id = if (embedding.id != 0L) embedding.id else findStoredEmbeddingId(embedding.messageId, embedding.modelId) ?: 0L
        upsertEmbeddingRow(embedding.copy(id = id, generation = getStoredEmbeddingGeneration(embedding.modelId) ?: 0L))
        previous.forEach { adjustEmbeddingMembership(it, -1) }
        adjustEmbeddingMembership(SemanticEmbeddingIdentity(embedding.messageId, embedding.modelId), 1)
    }

    @Transaction
    suspend fun insertEmbeddings(embeddings: List<EmbeddingEntity>): LongArray {
        val generations = embeddings.map { it.modelId }.distinct().associateWith { getStoredEmbeddingGeneration(it) ?: 0L }
        val ids = insertEmbeddingRows(embeddings.map { it.copy(generation = generations.getValue(it.modelId)) })
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

    @Query("UPDATE semantic_model_conversations SET cachedCount = 0, indexedRevision = -1 WHERE modelId = :modelId")
    suspend fun clearSemanticModelCounts(modelId: String)

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

}
