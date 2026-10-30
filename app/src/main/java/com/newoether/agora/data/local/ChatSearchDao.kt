package com.newoether.agora.data.local

import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

data class EmbeddingModelCount(
    val modelId: String,
    val count: Int,
)

data class EmbeddingCacheCounts(
    val total: Int,
    val cachedByModel: Map<String, Int>,
    val complete: Boolean = true,
) {
    init {
        require(total >= 0 && cachedByModel.values.all { it in 0..total })
    }
}

internal const val CITATION_MESSAGE_PAGE_QUERY = """
    SELECT m.*
    FROM messages m
    CROSS JOIN conversations c
    WHERE m.conversationId = c.id
      AND c.taskId IS NULL
      AND c.parentConversationId IS NULL
      AND m.toolCallJson LIKE '%"type":"citation"%'
      AND m.participant = 'MODEL'
      AND substr(m.id, 1, 5) != 'tool_'
      AND substr(m.id, 1, 7) != 'result_'
      AND m.id > :afterId
    ORDER BY m.id ASC
    LIMIT :pageSize
"""

data class KeywordSearchRow(
    val id: String,
    val timestamp: Long,
    val directMatch: Boolean,
    val toolCallJson: String?,
)

// Both ranking paths and final hydration share the same history visibility boundary.
internal const val HISTORY_MESSAGE_ELIGIBILITY = """
    c.taskId IS NULL AND c.parentConversationId IS NULL
    AND m.participant IN ('USER', 'MODEL') AND m.text != ''
    AND substr(m.id, 1, 5) != 'tool_'
    AND substr(m.id, 1, 7) != 'result_'
    AND substr(m.id, 1, 8) != 'compact_'
"""

// LIMIT is inside the subquery: even a miss releases the read connection after one short page.
internal const val KEYWORD_MESSAGE_PROJECTION = """
    SELECT m.id, m.timestamp,
        CASE WHEN $HISTORY_MESSAGE_ELIGIBILITY THEN
            CASE WHEN :literalQuery IS NOT NULL THEN
                (instr(m.text, :literalQuery) > 0 OR instr(c.title, :literalQuery) > 0)
            ELSE (m.text LIKE '%' || :query || '%' ESCAPE '\' OR
                  c.title LIKE '%' || :query || '%' ESCAPE '\') END
            ELSE 0 END AS directMatch,
        CASE WHEN $HISTORY_MESSAGE_ELIGIBILITY
            AND m.participant = 'MODEL'
            AND instr(m.toolCallJson, '"type":"citation"') > 0
            THEN m.toolCallJson ELSE NULL END AS toolCallJson
    FROM (
"""
internal const val KEYWORD_MESSAGE_FIRST_PAGE = KEYWORD_MESSAGE_PROJECTION + """
        SELECT id, timestamp FROM messages
        ORDER BY timestamp DESC, id DESC LIMIT :pageSize
    ) page CROSS JOIN messages m ON m.id = page.id
    LEFT JOIN conversations c ON m.conversationId = c.id
    ORDER BY page.timestamp DESC, page.id DESC
"""
internal const val KEYWORD_MESSAGE_NEXT_PAGE = KEYWORD_MESSAGE_PROJECTION + """
        SELECT id, timestamp FROM messages
        WHERE (timestamp, id) < (:beforeTimestamp, :beforeId)
        ORDER BY timestamp DESC, id DESC LIMIT :pageSize
    ) page CROSS JOIN messages m ON m.id = page.id
    LEFT JOIN conversations c ON m.conversationId = c.id
    ORDER BY page.timestamp DESC, page.id DESC
"""

/** Search eligibility and bounded projections inherited by the sole [ChatDao]. */
interface ChatSearchDao : SemanticMetadataDao {
    @Query(KEYWORD_MESSAGE_FIRST_PAGE)
    suspend fun getKeywordSearchFirstPage(
        query: String,
        pageSize: Int,
        literalQuery: String? = null,
    ): List<KeywordSearchRow>

    @Query(KEYWORD_MESSAGE_NEXT_PAGE)
    suspend fun getKeywordSearchNextPage(
        query: String,
        beforeTimestamp: Long,
        beforeId: String,
        pageSize: Int,
        literalQuery: String? = null,
    ): List<KeywordSearchRow>

    // Keep messages first so each page seeks its primary key instead of sorting the corpus again.
    @Query(CITATION_MESSAGE_PAGE_QUERY)
    suspend fun getMessagesWithCitationSegmentsPage(
        afterId: String,
        pageSize: Int,
    ): List<MessageEntity>

    suspend fun getEmbeddingSearchPage(
        modelId: String,
        afterId: Long,
        minimumTextLength: Int,
        limit: Int,
    ): List<EmbeddingSearchRow> {
        require(limit > 0)
        val result = ArrayList<EmbeddingSearchRow>()
        val legacyPending = !ensureMetadataState().complete
        val physicalPageSize = minOf(limit, 256)
        var cursor = afterId
        while (result.size < limit) {
            currentCoroutineContext().ensureActive()
            val ids = getEmbeddingSearchIdsPage(modelId, cursor, physicalPageSize)
            currentCoroutineContext().ensureActive()
            if (ids.isEmpty()) break
            val page = if (legacyPending) {
                getLegacyEmbeddingSearchRowsForIds(modelId, ids, minimumTextLength)
            } else {
                getEmbeddingSearchRowsForIds(modelId, ids, minimumTextLength)
            }
            result += page.take(limit - result.size)
            cursor = ids.last()
            yield()
        }
        return result
    }

    @Query("SELECT id FROM embeddings WHERE modelId = :modelId AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun getEmbeddingSearchIdsPage(modelId: String, afterId: Long, limit: Int): List<Long>

    @Query(
        """
        SELECT e.id, e.messageId, e.embedding, e.dimension
        FROM embeddings e
        CROSS JOIN semantic_sources s ON e.messageId = s.messageId
        CROSS JOIN conversations c ON s.conversationId = c.id
        WHERE e.id IN (:ids) AND e.modelId = :modelId AND $CURRENT_EMBEDDING
          AND c.taskId IS NULL AND c.parentConversationId IS NULL
          AND s.historyEligible = 1 AND s.searchLength >= :minimumTextLength
        ORDER BY e.id
        """,
    )
    suspend fun getEmbeddingSearchRowsForIds(
        modelId: String,
        ids: List<Long>,
        minimumTextLength: Int,
    ): List<EmbeddingSearchRow>

    // During the one-time upgrade, explicit searches retain legacy results. Only missing
    // metadata uses original eligibility, and only for the already bounded embedding IDs.
    @Query(
        """
        SELECT e.id, e.messageId, e.embedding, e.dimension
        FROM embeddings e
        LEFT JOIN semantic_sources s ON s.messageId = e.messageId
        LEFT JOIN messages m ON s.messageId IS NULL AND m.id = e.messageId
        CROSS JOIN conversations c ON c.id = COALESCE(s.conversationId, m.conversationId)
        WHERE e.id IN (:ids) AND e.modelId = :modelId AND $CURRENT_EMBEDDING
          AND c.taskId IS NULL AND c.parentConversationId IS NULL
          AND CASE WHEN s.messageId IS NOT NULL
              THEN s.historyEligible = 1 AND s.searchLength >= :minimumTextLength
              ELSE ($HISTORY_MESSAGE_ELIGIBILITY) AND LENGTH(m.text) >= :minimumTextLength END
        ORDER BY e.id
        """,
    )
    suspend fun getLegacyEmbeddingSearchRowsForIds(
        modelId: String,
        ids: List<Long>,
        minimumTextLength: Int,
    ): List<EmbeddingSearchRow>

    suspend fun getEmbeddingCountByModel(modelId: String): Int =
        getEmbeddingCacheCounts(listOf(modelId)).cachedByModel.getValue(modelId)

    suspend fun getEmbeddingCountsByModels(modelIds: List<String>): List<EmbeddingModelCount> =
        if (modelIds.isEmpty()) emptyList() else getEmbeddingCacheCounts(modelIds).cachedByModel
            .filterValues { it > 0 }.map { (id, count) -> EmbeddingModelCount(id, count) }

    suspend fun getIndexableMessageCount(): Int = getEmbeddingCacheCounts(emptyList()).total

    @Query("SELECT COALESCE(SUM(eligibleCount), 0) FROM semantic_conversations WHERE cacheEligible = 1")
    suspend fun getSemanticEligibleTotal(): Int

    @Query("SELECT m.modelId, SUM(m.cachedCount) AS count FROM semantic_model_conversations m JOIN semantic_conversations c ON c.conversationId = m.conversationId WHERE c.cacheEligible = 1 AND m.modelId IN (:modelIds) GROUP BY m.modelId")
    suspend fun getSemanticCachedTotals(modelIds: List<String>): List<EmbeddingModelCount>

    /** Only compact conversation summaries are read; no source identities or text are visited. */
    @Transaction
    suspend fun getEmbeddingCacheCounts(modelIds: List<String>): EmbeddingCacheCounts {
        val state = ensureMetadataState()
        val requestedIds = modelIds.distinct()
        if (!state.complete) return EmbeddingCacheCounts(0, requestedIds.associateWith { 0 }, complete = false)
        val cached = getSemanticCachedTotals(requestedIds).associate { it.modelId to it.count }
        return EmbeddingCacheCounts(getSemanticEligibleTotal(), requestedIds.associateWith { cached[it] ?: 0 })
    }

    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE m.id IN (:ids) AND $HISTORY_MESSAGE_ELIGIBILITY")
    suspend fun getSearchableMessagesByIds(ids: List<String>): List<MessageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE m.id = :messageId AND c.taskId IS NULL AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%')")
    suspend fun isMessageSearchable(messageId: String): Boolean

    @Query("SELECT * FROM conversations WHERE id = :conversationId AND taskId IS NULL AND (parentConversationId IS NULL OR parentConversationId = :ownerConversationId)")
    suspend fun getSearchableConversation(conversationId: String, ownerConversationId: String? = null): ChatEntity?


    @Query("SELECT COUNT(*) FROM conversations WHERE taskId IS NULL AND parentConversationId IS NULL")
    suspend fun getSearchableConversationCount(): Int

    @Query(
        """
        SELECT *
        FROM conversations
        WHERE taskId IS NULL AND parentConversationId IS NULL
        ORDER BY lastUpdated ASC, id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getSearchableConversationsPageAscending(
        offset: Int,
        limit: Int,
    ): List<ChatEntity>

    @Query(
        """
        SELECT *
        FROM conversations
        WHERE taskId IS NULL AND parentConversationId IS NULL
        ORDER BY lastUpdated DESC, id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getSearchableConversationsPageDescending(
        offset: Int,
        limit: Int,
    ): List<ChatEntity>
}
