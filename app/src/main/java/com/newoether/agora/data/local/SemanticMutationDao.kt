package com.newoether.agora.data.local

import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert

/** Public message/graph writes own their derived semantic input; raw primitives stay here. */
interface SemanticMutationDao : SemanticEmbeddingDao {
    @Upsert
    suspend fun upsertConversationRow(conversation: ChatEntity)

    @Transaction
    suspend fun upsertConversation(conversation: ChatEntity) {
        ensureMetadataState()
        val before = getSemanticConversation(conversation.id)
        upsertConversationRow(conversation)
        ensureSemanticConversation(conversation.id)
        val eligible = conversation.taskId == null
        if (before != null && before.cacheEligible != eligible) {
            setSemanticConversationEligibility(conversation.id, eligible)
            advanceSemanticConversation(conversation.id, 0)
            if (!eligible) deleteEmbeddingsByConversation(conversation.id)
            markSemanticModelsPending(System.currentTimeMillis())
        }
    }

    @Upsert
    suspend fun upsertMessageRow(message: MessageEntity)

    @Insert
    suspend fun insertMessageRow(message: MessageEntity)

    @Transaction
    suspend fun upsertMessage(message: MessageEntity) {
        ensureMetadataState()
        upsertMessageRow(message)
        maintainSemanticSource(semanticSource(message.id, message.conversationId, message.participant, message.text))
    }

    @Transaction
    suspend fun insertMessage(message: MessageEntity) {
        ensureMetadataState()
        insertMessageRow(message)
        maintainSemanticSource(semanticSource(message.id, message.conversationId, message.participant, message.text))
    }

    @Update(entity = MessageEntity::class)
    suspend fun updateMessageCheckpointRow(checkpoint: MessageStreamCheckpoint): Int

    @Transaction
    suspend fun updateMessageCheckpoint(checkpoint: MessageStreamCheckpoint): Int {
        ensureMetadataState()
        val identity = getSemanticMessageIdentity(checkpoint.id) ?: return 0
        val changed = updateMessageCheckpointRow(checkpoint)
        if (changed == 1) maintainSemanticSource(
            semanticSource(identity.id, identity.conversationId, identity.participant, checkpoint.text),
        )
        return changed
    }

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteMessageRowsByIds(ids: List<String>)

    @Transaction
    suspend fun deleteMessagesByIds(ids: List<String>) {
        ids.distinct().forEach { removeSemanticSource(it) }
        deleteMessageRowsByIds(ids)
    }

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessageRowsByConversation(conversationId: String)

    @Query("DELETE FROM semantic_sources WHERE conversationId = :conversationId")
    suspend fun deleteConversationSemanticSources(conversationId: String)

    @Transaction
    suspend fun deleteMessagesByConversation(conversationId: String) {
        val owner = getSemanticConversation(conversationId)
        deleteEmbeddingsByConversation(conversationId)
        deleteConversationSemanticSources(conversationId)
        if (owner != null && owner.eligibleCount > 0) {
            advanceSemanticConversation(conversationId, -owner.eligibleCount)
            markSemanticModelsPending(System.currentTimeMillis())
        }
        deleteMessageRowsByConversation(conversationId)
    }

    @Query("WITH RECURSIVE removed(id) AS (SELECT id FROM runs WHERE id = :runId UNION SELECT r.id FROM runs r JOIN removed ON r.parentRunId = removed.id) SELECT m.id FROM messages m JOIN removed ON m.runId = removed.id")
    suspend fun getRunCascadeMessageIds(runId: String): List<String>

    @Query("DELETE FROM runs WHERE id = :runId")
    suspend fun deleteRunRow(runId: String): Int

    @Transaction
    suspend fun deleteRun(runId: String): Int {
        getRunCascadeMessageIds(runId).forEach { removeSemanticSource(it) }
        return deleteRunRow(runId)
    }

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversationRow(conversationId: String)

    @Transaction
    suspend fun deleteConversation(conversationId: String) {
        deleteEmbeddingsByConversation(conversationId)
        deleteConversationRow(conversationId)
        markSemanticModelsPending(System.currentTimeMillis())
    }

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversationRows()

    @Transaction
    suspend fun deleteAllConversations() {
        deleteAllEmbeddings()
        deleteAllConversationRows()
        advanceLegacyMetadata(null, true)
        markSemanticModelsPending(System.currentTimeMillis())
    }
}
