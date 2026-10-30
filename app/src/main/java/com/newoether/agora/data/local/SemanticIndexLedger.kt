package com.newoether.agora.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.withTransaction
import com.newoether.agora.util.Constants
import java.security.MessageDigest

@Entity(tableName = "semantic_index_ledger")
data class SemanticIndexLedgerEntity(
    @PrimaryKey val modelId: String,
    val state: String = STATE_NEEDS_RECONCILE,
    val sourceRevision: Long = 0L,
    val completedRevision: Long = 0L,
    /** Revision of the newest full-reconcile request; exact work does not advance it. */
    val reconcileRevision: Long = 0L,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val generation: Long = 0L,
) {
    init {
        require(modelId.isNotBlank())
        require(state in setOf(STATE_NEEDS_RECONCILE, STATE_PENDING, STATE_CURRENT, STATE_DELETED))
        require(sourceRevision >= 0L)
        require(completedRevision in 0L..sourceRevision)
        require(reconcileRevision in 0L..sourceRevision)
        require(state != STATE_CURRENT || completedRevision == sourceRevision)
    }

    companion object {
        const val STATE_NEEDS_RECONCILE = "NEEDS_RECONCILE"
        const val STATE_PENDING = "PENDING"
        const val STATE_CURRENT = "CURRENT"
        const val STATE_DELETED = "DELETED"
    }
}

@Entity(
    tableName = "semantic_index_work",
    primaryKeys = ["modelId", "messageId"],
    foreignKeys = [
        ForeignKey(
            entity = SemanticIndexLedgerEntity::class,
            parentColumns = ["modelId"],
            childColumns = ["modelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["modelId", "sourceRevision", "messageId"])],
)
data class SemanticIndexWorkEntity(
    val modelId: String,
    val messageId: String,
    /** Null marks an embedding deletion; non-null fingerprints fence generated content. */
    val sourceFingerprint: String?,
    val sourceRevision: Long,
    val updatedAt: Long,
) {
    init {
        require(modelId.isNotBlank())
        require(messageId.isNotBlank())
        require(sourceFingerprint == null || sourceFingerprint.isNotBlank())
        require(sourceRevision > 0L)
    }
}

/** One reconcile page row plus the existing embedding metadata needed for safe reuse. */
data class ReconcileIndexableMessage(
    val id: String,
    val sourcePrefix: ByteArray,
    val embeddingId: Long?,
    val embeddingFingerprint: String?,
    val dimension: Int?,
    val embeddingBytes: Int?,
) {
    @get:Ignore
    val text: String get() = sourcePrefix.toString(Charsets.UTF_8)
}

private fun requireSemanticModelId(modelId: String) {
    require(modelId.isNotBlank())
}

// Decode the bounded BLOB in Kotlin: a split UTF-8 tail must not reach CursorWindow.getString.
// Four UTF-8 bytes per UTF-16 unit cover the existing take() input, including NULs.
// Any partial UTF-8 sequence at the byte boundary is beyond the fingerprinted prefix.
internal const val SEMANTIC_SOURCE_PREFIX_BYTES = Constants.MAX_EMBEDDING_TEXT_LENGTH * 4

internal fun semanticSourceFingerprint(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(text.take(Constants.MAX_EMBEDDING_TEXT_LENGTH).toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return CharArray(digest.size * 2) { index ->
        val value = digest[index / 2].toInt() and 0xff
        hex[if (index % 2 == 0) value ushr 4 else value and 0x0f]
    }.concatToString()
}

internal typealias SemanticModelSnapshot = Pair<String?, Set<String>>

internal fun semanticModelSnapshot(
    activeModelId: String,
    configuredModelIds: Collection<String>,
): SemanticModelSnapshot {
    val configured = configuredModelIds.filterTo(linkedSetOf(), String::isNotBlank)
    return activeModelId.takeIf { it in configured } to configured
}

@Dao
interface SemanticIndexDao : SemanticEmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLedger(ledger: SemanticIndexLedgerEntity): Long

    @Query("SELECT * FROM semantic_index_ledger WHERE modelId = :modelId AND state != 'DELETED'")
    suspend fun getLedger(modelId: String): SemanticIndexLedgerEntity?

    @Query("SELECT * FROM semantic_index_ledger WHERE modelId IN (:modelIds) AND state != 'DELETED'")
    suspend fun getLedgers(modelIds: List<String>): List<SemanticIndexLedgerEntity>

    @Query(
        """
        UPDATE semantic_index_ledger
        SET state = CASE
                WHEN state = 'NEEDS_RECONCILE' THEN 'NEEDS_RECONCILE'
                ELSE 'PENDING'
            END,
            sourceRevision = sourceRevision + 1,
            updatedAt = :updatedAt
        WHERE modelId = :modelId
        """,
    )
    suspend fun advanceForExactWork(modelId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE semantic_index_ledger
        SET state = 'NEEDS_RECONCILE',
            sourceRevision = sourceRevision + 1,
            reconcileRevision = sourceRevision + 1,
            updatedAt = :updatedAt
        WHERE modelId = :modelId
        """,
    )
    suspend fun advanceForReconcile(modelId: String, updatedAt: Long): Int

    @Upsert
    suspend fun upsertWork(work: SemanticIndexWorkEntity)

    @Query("SELECT * FROM semantic_index_work WHERE modelId = :modelId AND messageId = :messageId")
    suspend fun getWork(modelId: String, messageId: String): SemanticIndexWorkEntity?

    @Query(
        """
        SELECT * FROM semantic_index_work
        WHERE modelId = :modelId
          AND sourceRevision <= :maxSourceRevision
          AND (
              sourceRevision > :afterSourceRevision
              OR (sourceRevision = :afterSourceRevision AND messageId > :afterMessageId)
          )
        ORDER BY sourceRevision, messageId
        LIMIT :limit
        """,
    )
    suspend fun getWorkPage(
        modelId: String,
        maxSourceRevision: Long,
        afterSourceRevision: Long,
        afterMessageId: String,
        limit: Int,
    ): List<SemanticIndexWorkEntity>

    @Query(
        """
        SELECT COUNT(*) FROM semantic_index_work
        WHERE modelId = :modelId AND sourceRevision <= :maxSourceRevision
        """,
    )
    suspend fun getWorkCountThroughRevision(
        modelId: String,
        maxSourceRevision: Long,
    ): Int

    @Query("SELECT id FROM messages ORDER BY id LIMIT :limit")
    suspend fun getFirstReconcileMessageIdsPage(limit: Int): List<String>

    // Seek through the covering primary-key index before reading any message payload.
    @Query("SELECT id FROM messages WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun getReconcileMessageIdsPage(afterId: String, limit: Int): List<String>

    @Query(
        """
        SELECT s.messageId AS id,
               SUBSTR(s.sourcePrefix, 1, :sourcePrefixBytes) AS sourcePrefix,
               e.id AS embeddingId,
               e.sourceFingerprint AS embeddingFingerprint,
               e.dimension AS dimension,
               LENGTH(e.embedding) AS embeddingBytes
        FROM semantic_sources s
        JOIN semantic_conversations c ON s.conversationId = c.conversationId
        LEFT JOIN embeddings e ON e.messageId = s.messageId AND e.modelId = :modelId AND $CURRENT_EMBEDDING
        LEFT JOIN semantic_index_work w ON w.messageId = s.messageId AND w.modelId = :modelId
        WHERE s.messageId IN (:messageIds)
          AND c.cacheEligible = 1 AND s.eligible = 1
          AND (w.sourceRevision IS NULL OR w.sourceRevision <= :maxWorkRevision)
        ORDER BY s.messageId
        """,
    )
    suspend fun getReconcileMessagesByIds(
        modelId: String,
        maxWorkRevision: Long,
        messageIds: List<String>,
        sourcePrefixBytes: Int,
    ): List<ReconcileIndexableMessage>

    @Query(
        """
        SELECT s.sourcePrefix
        FROM semantic_sources s
        JOIN semantic_conversations c ON s.conversationId = c.conversationId
        WHERE s.messageId = :messageId AND c.cacheEligible = 1 AND s.eligible = 1
        """,
    )
    suspend fun getSearchableMessageSource(messageId: String): ByteArray?

    @Query("SELECT s.sourceFingerprint FROM semantic_sources s JOIN semantic_conversations c ON s.conversationId = c.conversationId WHERE s.messageId = :messageId AND c.cacheEligible = 1 AND s.eligible = 1")
    suspend fun getSearchableMessageFingerprint(messageId: String): String?

    suspend fun getSearchableMessageText(messageId: String): String? =
        getSearchableMessageSource(messageId)?.toString(Charsets.UTF_8)

    @Query("DELETE FROM semantic_index_work WHERE modelId = :modelId")
    suspend fun deleteWorkForModel(modelId: String): Int

    @Query(
        """
        DELETE FROM semantic_index_work
        WHERE modelId = :modelId AND messageId = :messageId
          AND sourceRevision = :sourceRevision
          AND (
              (sourceFingerprint IS NULL AND :sourceFingerprint IS NULL)
              OR sourceFingerprint = :sourceFingerprint
          )
        """,
    )
    suspend fun deleteMatchingWork(
        modelId: String,
        messageId: String,
        sourceFingerprint: String?,
        sourceRevision: Long,
    ): Int

    @Query(
        """
        UPDATE semantic_index_ledger
        SET state = 'CURRENT', completedRevision = :sourceRevision, updatedAt = :updatedAt
        WHERE modelId = :modelId AND state = 'PENDING'
          AND sourceRevision = :sourceRevision
          AND NOT EXISTS (
              SELECT 1 FROM semantic_index_work WHERE modelId = :modelId
          )
          AND NOT EXISTS (
              SELECT 1 FROM semantic_conversations c
              LEFT JOIN semantic_model_conversations m ON m.conversationId = c.conversationId AND m.modelId = :modelId
              WHERE m.indexedRevision IS NULL OR m.indexedRevision != c.sourceRevision
          )
          AND EXISTS (SELECT 1 FROM semantic_metadata_state WHERE id = 0 AND complete = 1)
        """,
    )
    suspend fun markCurrentAfterExactWork(
        modelId: String,
        sourceRevision: Long,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE semantic_index_ledger
        SET state = 'CURRENT', completedRevision = sourceRevision, updatedAt = :updatedAt
        WHERE modelId = :modelId
          AND reconcileRevision = :expectedReconcileRevision
          AND NOT EXISTS (SELECT 1 FROM semantic_index_work WHERE modelId = :modelId)
          AND NOT EXISTS (
              SELECT 1 FROM semantic_conversations c
              LEFT JOIN semantic_model_conversations m ON m.conversationId = c.conversationId AND m.modelId = :modelId
              WHERE m.indexedRevision IS NULL OR m.indexedRevision != c.sourceRevision
          )
          AND EXISTS (SELECT 1 FROM semantic_metadata_state WHERE id = 0 AND complete = 1)
        """,
    )
    suspend fun markAfterReconcile(
        modelId: String,
        expectedReconcileRevision: Long,
        updatedAt: Long,
    ): Int

    @Transaction
    suspend fun deleteModel(modelId: String): Int {
        deleteWorkForModel(modelId)
        deleteSemanticModelConversations(modelId)
        return deleteModelRow(modelId)
    }

    @Query("UPDATE semantic_index_ledger SET state = 'DELETED', sourceRevision = sourceRevision + 1, reconcileRevision = sourceRevision + 1, generation = generation + 1 WHERE modelId = :modelId")
    suspend fun deleteModelRow(modelId: String): Int

    @Query("UPDATE semantic_index_ledger SET state = 'NEEDS_RECONCILE', updatedAt = :at WHERE modelId = :modelId AND state = 'DELETED'")
    suspend fun reviveModel(modelId: String, at: Long)

    @Query("UPDATE semantic_index_ledger SET generation = generation + 1 WHERE modelId = :modelId")
    suspend fun advanceEmbeddingGeneration(modelId: String)

    @Transaction
    suspend fun admitModel(modelId: String, updatedAt: Long): SemanticIndexLedgerEntity {
        requireSemanticModelId(modelId)
        insertLedger(
            SemanticIndexLedgerEntity(
                modelId = modelId,
                updatedAt = updatedAt,
            ),
        )
        reviveModel(modelId, updatedAt)
        return checkNotNull(getLedger(modelId)) {
            "Semantic index ledger was not readable after admission"
        }
    }

    @Transaction
    suspend fun requestReconcile(modelId: String, updatedAt: Long): SemanticIndexLedgerEntity {
        admitModel(modelId, updatedAt)
        check(advanceForReconcile(modelId, updatedAt) == 1) {
            "Semantic index ledger disappeared during reconcile invalidation"
        }
        invalidateSemanticConversations(modelId)
        return checkNotNull(getLedger(modelId)) {
            "Semantic index ledger was not readable after reconcile invalidation"
        }
    }

    @Transaction
    suspend fun enqueueExactWork(
        modelId: String,
        messageId: String,
        sourceFingerprint: String?,
        updatedAt: Long,
    ): SemanticIndexLedgerEntity {
        requireSemanticModelId(modelId)
        require(messageId.isNotBlank())
        require(sourceFingerprint == null || sourceFingerprint.isNotBlank())
        admitModel(modelId, updatedAt)
        check(advanceForExactWork(modelId, updatedAt) == 1) {
            "Semantic index ledger disappeared during exact invalidation"
        }
        val ledger = checkNotNull(getLedger(modelId)) {
            "Semantic index ledger was not readable after exact invalidation"
        }
        upsertWork(
            SemanticIndexWorkEntity(
                modelId = modelId,
                messageId = messageId,
                sourceFingerprint = sourceFingerprint,
                sourceRevision = ledger.sourceRevision,
                updatedAt = updatedAt,
            ),
        )
        return ledger
    }

    @Transaction
    suspend fun completeExactWork(work: SemanticIndexWorkEntity, updatedAt: Long): Boolean {
        if (
            deleteMatchingWork(
                modelId = work.modelId,
                messageId = work.messageId,
                sourceFingerprint = work.sourceFingerprint,
                sourceRevision = work.sourceRevision,
            ) != 1
        ) {
            return false
        }
        val ledger = getLedger(work.modelId) ?: return false
        markCurrentAfterExactWork(
            modelId = work.modelId,
            sourceRevision = ledger.sourceRevision,
            updatedAt = updatedAt,
        )
        return true
    }

    @Transaction
    suspend fun completeReconcile(
        modelId: String,
        expectedReconcileRevision: Long,
        updatedAt: Long,
    ): Boolean {
        requireSemanticModelId(modelId)
        require(expectedReconcileRevision >= 0L)
        return markAfterReconcile(modelId, expectedReconcileRevision, updatedAt) == 1
    }
}

internal suspend fun SemanticIndexDao.requestSemanticReconcile(
    snapshot: SemanticModelSnapshot,
    updatedAt: Long,
) {
    snapshot.second.forEach { modelId -> requestReconcile(modelId, updatedAt) }
}

private suspend fun SemanticIndexDao.invalidateSemanticSources(
    snapshot: SemanticModelSnapshot,
    sources: Map<String, String?>,
    updatedAt: Long,
) {
    // DAO writes already removed stale vectors and dirtied only the affected conversation.
    snapshot.first?.let { modelId ->
        if (getLedger(modelId) == null) return@let
        sources.forEach { (messageId, fingerprint) ->
            enqueueExactWork(modelId, messageId, fingerprint, updatedAt)
        }
    }
}

internal suspend fun <T> ChatDatabase.withSemanticSourceMutation(
    snapshot: SemanticModelSnapshot,
    messageIds: Collection<String>,
    updatedAt: Long,
    block: suspend () -> T,
): T = withTransaction {
    val semanticDao = semanticIndexDao()
    snapshot.second.forEach { semanticDao.insertLedger(SemanticIndexLedgerEntity(it, updatedAt = updatedAt)) }
    val ids = messageIds.filterTo(linkedSetOf(), String::isNotBlank)
    val before = ids.associateWith { semanticDao.getSearchableMessageFingerprint(it) }
    val result = block()
    val changed = ids.mapNotNull { messageId ->
        val fingerprint = semanticDao.getSearchableMessageFingerprint(messageId)
        if (before[messageId] == fingerprint) null else messageId to fingerprint
    }.toMap()
    semanticDao.invalidateSemanticSources(snapshot, changed, updatedAt)
    result
}

@Suppress("UNUSED_PARAMETER")
internal suspend fun <T> ChatDatabase.withSemanticGraphMutation(
    snapshot: SemanticModelSnapshot,
    clearMessageIds: Collection<String> = emptyList(),
    clearAllEmbeddings: Boolean = false,
    updatedAt: Long,
    block: suspend () -> T,
): T = withTransaction {
    snapshot.second.forEach { semanticIndexDao().insertLedger(SemanticIndexLedgerEntity(it, updatedAt = updatedAt)) }
    // Public graph writes maintain metadata themselves, including cloned vectors and MERGE skips.
    block()
}

@Suppress("UNUSED_PARAMETER")
internal suspend fun <T> ChatDatabase.withSemanticEligibilityMutation(
    snapshot: SemanticModelSnapshot,
    conversationId: String,
    updatedAt: Long,
    block: suspend () -> T,
): T = withTransaction {
    snapshot.second.forEach { semanticIndexDao().insertLedger(SemanticIndexLedgerEntity(it, updatedAt = updatedAt)) }
    block()
}

internal suspend fun ChatDatabase.commitSemanticEmbedding(
    embedding: EmbeddingEntity,
    expectedFingerprint: String,
    updatedAt: Long,
    expectedWorkRevision: Long? = null,
    expectedReconcileRevision: Long? = null,
    completePendingWork: Boolean = true,
): Boolean = withTransaction {
    val semanticDao = semanticIndexDao()
    val ledger = semanticDao.getLedger(embedding.modelId) ?: return@withTransaction false
    if (
        expectedReconcileRevision != null &&
        ledger.reconcileRevision != expectedReconcileRevision
    ) {
        return@withTransaction false
    }
    val currentFingerprint = semanticDao.getSearchableMessageFingerprint(embedding.messageId)
    if (currentFingerprint != expectedFingerprint) return@withTransaction false
    val work = if (completePendingWork) {
        semanticDao.getWork(embedding.modelId, embedding.messageId)
    } else {
        null
    }
    if (
        expectedWorkRevision != null &&
        (work?.sourceRevision != expectedWorkRevision || work.sourceFingerprint != expectedFingerprint)
    ) {
        return@withTransaction false
    }
    semanticDao.upsertEmbedding(embedding)
    work?.takeIf { it.sourceFingerprint == expectedFingerprint }
        ?.let { currentWork -> semanticDao.completeExactWork(currentWork, updatedAt) }
    true
}

internal suspend fun ChatDatabase.invalidateSemanticModel(modelId: String, updatedAt: Long) {
    withTransaction {
        semanticIndexDao().requestReconcile(modelId, updatedAt)
        semanticIndexDao().advanceEmbeddingGeneration(modelId)
        semanticIndexDao().clearSemanticModelCounts(modelId)
        maintenanceDebtDao().enqueue(MaintenanceDebtEntity.KIND_STALE_EMBEDDINGS, modelId, updatedAt)
    }
}

internal suspend fun ChatDatabase.deleteSemanticModel(modelId: String) {
    withTransaction {
        // Retain the tombstone and monotonic revisions so deletion/recreation cannot accept old work.
        semanticIndexDao().admitModel(modelId, System.currentTimeMillis())
        semanticIndexDao().deleteModel(modelId)
        maintenanceDebtDao().enqueue(MaintenanceDebtEntity.KIND_STALE_EMBEDDINGS, modelId, System.currentTimeMillis())
    }
}
