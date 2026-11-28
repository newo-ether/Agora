package com.newoether.agora.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Transactional derived data shared by message writes, cache commits and legacy bootstrap. */
@Dao
interface SemanticMetadataDao {
    @Query("SELECT * FROM semantic_metadata_state WHERE id = 0")
    suspend fun getMetadataState(): SemanticMetadataStateEntity?

    @Query("SELECT complete FROM semantic_metadata_state WHERE id = 0")
    fun observeMetadataComplete(): Flow<Boolean?>

    @Query("INSERT OR IGNORE INTO semantic_metadata_state(id, complete, afterMessageId) VALUES(0, NOT EXISTS(SELECT 1 FROM messages LIMIT 1), NULL)")
    suspend fun initializeMetadataState()

    @Transaction
    suspend fun ensureMetadataState(): SemanticMetadataStateEntity {
        getMetadataState()?.let { return it }
        initializeMetadataState()
        return checkNotNull(getMetadataState())
    }

    @Query("SELECT * FROM semantic_sources WHERE messageId = :messageId")
    suspend fun getSemanticSource(messageId: String): SemanticSourceEntity?

    @Query("SELECT id, conversationId, participant FROM messages WHERE id = :messageId")
    suspend fun getSemanticMessageIdentity(messageId: String): SemanticMessageIdentity?

    @Query("SELECT * FROM semantic_conversations WHERE conversationId = :conversationId")
    suspend fun getSemanticConversation(conversationId: String): SemanticConversationEntity?

    @Query("INSERT OR IGNORE INTO semantic_conversations(conversationId, sourceRevision, eligibleCount, cacheEligible) SELECT id, 0, 0, taskId IS NULL FROM conversations WHERE id = :conversationId")
    suspend fun ensureSemanticConversation(conversationId: String)

    @Query("UPDATE semantic_conversations SET sourceRevision = sourceRevision + 1, eligibleCount = eligibleCount + :delta WHERE conversationId = :conversationId")
    suspend fun advanceSemanticConversation(conversationId: String, delta: Int)

    @Query("UPDATE semantic_conversations SET cacheEligible = :eligible WHERE conversationId = :conversationId")
    suspend fun setSemanticConversationEligibility(conversationId: String, eligible: Boolean)

    @Query("UPDATE semantic_index_ledger SET state = CASE WHEN state = 'NEEDS_RECONCILE' THEN state ELSE 'PENDING' END, sourceRevision = sourceRevision + 1, updatedAt = :at WHERE state != 'DELETED'")
    suspend fun markSemanticModelsPending(at: Long)

    @Upsert
    suspend fun putSemanticSource(source: SemanticSourceEntity)

    @Query("DELETE FROM semantic_sources WHERE messageId = :messageId")
    suspend fun removeSemanticSourceRow(messageId: String)

    @Query("SELECT e.messageId, e.modelId FROM embeddings e WHERE e.messageId = :messageId AND $CURRENT_EMBEDDING")
    suspend fun getSourceEmbeddingIdentities(messageId: String): List<SemanticEmbeddingIdentity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSemanticModelConversation(row: SemanticModelConversationEntity)

    @Query("UPDATE semantic_model_conversations SET cachedCount = cachedCount + :delta WHERE modelId = :modelId AND conversationId = :conversationId")
    suspend fun adjustSemanticCachedCount(modelId: String, conversationId: String, delta: Int)

    suspend fun adjustSourceEmbeddingCounts(source: SemanticSourceEntity, delta: Int) {
        if (!source.eligible) return
        getSourceEmbeddingIdentities(source.messageId).forEach { embedding ->
            adjustCachedCount(embedding.modelId, source.conversationId, delta)
        }
    }

    suspend fun adjustCachedCount(modelId: String, conversationId: String, delta: Int) {
        insertSemanticModelConversation(SemanticModelConversationEntity(modelId, conversationId))
        adjustSemanticCachedCount(modelId, conversationId, delta)
    }

    @Query("DELETE FROM embeddings WHERE messageId = :messageId")
    suspend fun deleteSourceEmbeddingRows(messageId: String): Int

    /** Called with the actual persisted text, inside its owning write transaction. */
    @Transaction
    suspend fun maintainSemanticSource(source: SemanticSourceEntity, legacy: Boolean = false) {
        val previous = getSemanticSource(source.messageId)
        ensureSemanticConversation(source.conversationId)
        val changed = previous?.eligible != source.eligible ||
            previous?.sourceFingerprint != source.sourceFingerprint ||
            previous?.conversationId != source.conversationId
        val semanticChanged = changed && (previous?.eligible == true || source.eligible)
        if (!changed && previous?.searchLength == source.searchLength &&
            previous.historyEligible == source.historyEligible) return
        if (semanticChanged) {
            if (previous != null) adjustSourceEmbeddingCounts(previous, -1)
            if (previous != null && previous.conversationId != source.conversationId) {
                advanceSemanticConversation(previous.conversationId, if (previous.eligible) -1 else 0)
            }
            val oldCount = if (previous?.conversationId == source.conversationId && previous.eligible) 1 else 0
            advanceSemanticConversation(source.conversationId, (if (source.eligible) 1 else 0) - oldCount)
            markSemanticModelsPending(System.currentTimeMillis())
        }
        // Bootstrap retains legacy vectors. A new write cannot prove an old vector matches.
        if (changed && !legacy) deleteSourceEmbeddingRows(source.messageId)
        putSemanticSource(source)
        if (semanticChanged) adjustSourceEmbeddingCounts(source, 1)
    }

    @Transaction
    suspend fun removeSemanticSource(messageId: String) {
        val previous = getSemanticSource(messageId)
        if (previous != null) {
            adjustSourceEmbeddingCounts(previous, -1)
            if (previous.eligible) {
                advanceSemanticConversation(previous.conversationId, -1)
                markSemanticModelsPending(System.currentTimeMillis())
            }
            removeSemanticSourceRow(messageId)
        }
        deleteSourceEmbeddingRows(messageId)
    }

    @Query("SELECT conversationId FROM semantic_conversations ORDER BY conversationId LIMIT :limit")
    suspend fun getFirstSemanticConversationIds(limit: Int): List<String>

    @Query("SELECT conversationId FROM semantic_conversations WHERE conversationId > :afterId ORDER BY conversationId LIMIT :limit")
    suspend fun getNextSemanticConversationIds(afterId: String, limit: Int): List<String>

    @Query("SELECT c.* FROM semantic_conversations c LEFT JOIN semantic_model_conversations m ON m.conversationId = c.conversationId AND m.modelId = :modelId WHERE c.conversationId IN (:ids) AND (m.indexedRevision IS NULL OR m.indexedRevision != c.sourceRevision) ORDER BY c.conversationId")
    suspend fun getDirtyConversationsByIds(modelId: String, ids: List<String>): List<SemanticConversationEntity>

    /** Bound physical candidates before filtering; clean pages must still advance and yield. */
    suspend fun getDirtyConversations(
        modelId: String,
        afterConversationId: String?,
        limit: Int,
    ): List<SemanticConversationEntity> {
        require(limit > 0)
        val result = ArrayList<SemanticConversationEntity>()
        var cursor = afterConversationId
        val pageSize = 64
        while (result.size < limit) {
            currentCoroutineContext().ensureActive()
            val ids = if (cursor == null) getFirstSemanticConversationIds(pageSize)
                else getNextSemanticConversationIds(cursor, pageSize)
            if (ids.isEmpty()) break
            currentCoroutineContext().ensureActive()
            result += getDirtyConversationsByIds(modelId, ids).take(limit - result.size)
            cursor = ids.last()
            yield()
            if (ids.size < pageSize) break
        }
        return result
    }

    @Query("SELECT messageId FROM semantic_sources WHERE conversationId = :conversationId ORDER BY messageId LIMIT :limit")
    suspend fun getFirstConversationSourceIds(conversationId: String, limit: Int): List<String>

    @Query("SELECT messageId FROM semantic_sources WHERE conversationId = :conversationId AND messageId > :afterMessageId ORDER BY messageId LIMIT :limit")
    suspend fun getNextConversationSourceIds(conversationId: String, afterMessageId: String, limit: Int): List<String>

    suspend fun getConversationSourceIds(conversationId: String, afterMessageId: String?, limit: Int): List<String> =
        if (afterMessageId == null) getFirstConversationSourceIds(conversationId, limit)
        else getNextConversationSourceIds(conversationId, afterMessageId, limit)

    @Query("UPDATE semantic_model_conversations SET indexedRevision = :sourceRevision WHERE modelId = :modelId AND conversationId = :conversationId AND EXISTS(SELECT 1 FROM semantic_conversations WHERE conversationId = :conversationId AND sourceRevision = :sourceRevision) AND EXISTS(SELECT 1 FROM semantic_index_ledger WHERE modelId = :modelId AND reconcileRevision = :expectedReconcileRevision)")
    suspend fun finishSemanticConversation(modelId: String, conversationId: String, sourceRevision: Long, expectedReconcileRevision: Long): Int

    @Transaction
    suspend fun markConversationIndexed(modelId: String, conversationId: String, sourceRevision: Long, expectedReconcileRevision: Long): Boolean {
        if (getSemanticConversation(conversationId) == null) return false
        insertSemanticModelConversation(SemanticModelConversationEntity(modelId, conversationId))
        return finishSemanticConversation(modelId, conversationId, sourceRevision, expectedReconcileRevision) == 1
    }

    @Query("UPDATE semantic_model_conversations SET indexedRevision = -1 WHERE modelId = :modelId")
    suspend fun invalidateSemanticConversations(modelId: String)

    @Query("DELETE FROM semantic_model_conversations WHERE modelId = :modelId")
    suspend fun deleteSemanticModelConversations(modelId: String)

    @Query("SELECT id FROM messages ORDER BY id LIMIT :limit")
    suspend fun getFirstLegacyMessageIds(limit: Int): List<String>

    @Query("SELECT id FROM messages WHERE id > :afterMessageId ORDER BY id LIMIT :limit")
    suspend fun getNextLegacyMessageIds(afterMessageId: String, limit: Int): List<String>

    suspend fun getLegacyMessageIds(afterMessageId: String?, limit: Int): List<String> =
        if (afterMessageId == null) getFirstLegacyMessageIds(limit)
        else getNextLegacyMessageIds(afterMessageId, limit)

    // The sole legacy payload read: bounded output outside a write transaction, once per old row.
    // Empty BLOB projections can become null in Android cursors; nonempty distinguishes the sentinel.
    @Query("SELECT id AS messageId, conversationId, participant, CASE WHEN text != '' THEN SUBSTR(CAST(text AS BLOB), 1, 32000) ELSE X'00' END AS prefix, LENGTH(text) AS searchLength, text != '' AS nonempty FROM messages WHERE id = :messageId")
    suspend fun getLegacySourceRow(messageId: String): SemanticLegacySourceRow?

    suspend fun getLegacySource(messageId: String): SemanticSourceEntity? =
        getLegacySourceRow(messageId)?.let { row ->
            val text = if (row.nonempty) row.prefix.toString(Charsets.UTF_8) else ""
            semanticSource(row.messageId, row.conversationId, row.participant, text)
                .let { source -> source.copy(searchLength = if (source.historyEligible || source.eligible) row.searchLength else 0) }
        }

    @Query("UPDATE semantic_metadata_state SET afterMessageId = :lastMessageId, complete = :exhausted WHERE id = 0")
    suspend fun advanceLegacyMetadata(lastMessageId: String?, exhausted: Boolean)

    /** Missing-only inserts make concurrent new writes authoritative; cursor and inserts commit together. */
    @Transaction
    suspend fun commitLegacyPage(expectedAfterId: String?, sources: List<SemanticSourceEntity>, lastMessageId: String?, exhausted: Boolean): Boolean {
        val state = ensureMetadataState()
        if (state.complete || state.afterMessageId != expectedAfterId) return false
        sources.forEach { source ->
            if (getSemanticSource(source.messageId) == null && getSemanticMessageIdentity(source.messageId) != null) {
                maintainSemanticSource(source, legacy = true)
            }
        }
        advanceLegacyMetadata(lastMessageId ?: expectedAfterId, exhausted)
        return true
    }
}
