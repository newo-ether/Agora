package com.newoether.agora.data.local

import androidx.room.Query
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
) {
    init {
        require(total >= 0 && cachedByModel.values.all { it in 0..total })
    }
}

data class EmbeddingCacheCountRow(
    val messageId: String,
    val eligible: Boolean,
    val modelId: String?,
)

internal const val CACHE_COUNT_PAGE_SIZE = 128
private const val CACHE_COUNT_ELIGIBILITY = """
    c.id IS NOT NULL AND c.taskId IS NULL AND m.participant IN ('USER', 'MODEL')
    AND LENGTH(CAST(m.text AS BLOB)) > 0
    AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%'
"""
// LIMIT precedes qualification; hidden identities still advance the page cursor.
private const val CACHE_COUNT_PROJECTION = """
    SELECT page.id AS messageId, CASE WHEN $CACHE_COUNT_ELIGIBILITY THEN 1 ELSE 0 END AS eligible,
           e.modelId
    FROM (
"""
private const val CACHE_COUNT_JOINS = """
    ) page CROSS JOIN messages m ON m.id = page.id
    LEFT JOIN conversations c ON c.id = m.conversationId
    LEFT JOIN embeddings e ON e.messageId = m.id AND e.modelId IN (:modelIds)
        AND $CACHE_COUNT_ELIGIBILITY
    ORDER BY page.id
"""
internal const val CACHE_COUNT_FIRST_PAGE = CACHE_COUNT_PROJECTION + """
        SELECT id FROM messages ORDER BY id LIMIT :limit
""" + CACHE_COUNT_JOINS
internal const val CACHE_COUNT_NEXT_PAGE = CACHE_COUNT_PROJECTION + """
        SELECT id FROM messages WHERE id > :afterId ORDER BY id LIMIT :limit
""" + CACHE_COUNT_JOINS

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
interface ChatSearchDao {
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
        val physicalPageSize = minOf(limit, 256)
        var cursor = afterId
        while (result.size < limit) {
            currentCoroutineContext().ensureActive()
            val ids = getEmbeddingSearchIdsPage(modelId, cursor, physicalPageSize)
            currentCoroutineContext().ensureActive()
            if (ids.isEmpty()) break
            val page = getEmbeddingSearchRowsForIds(modelId, ids, minimumTextLength)
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
        CROSS JOIN messages m ON e.messageId = m.id
        CROSS JOIN conversations c ON m.conversationId = c.id
        WHERE e.id IN (:ids) AND e.modelId = :modelId
          AND $HISTORY_MESSAGE_ELIGIBILITY
          AND LENGTH(m.text) >= :minimumTextLength
        ORDER BY e.id
        """,
    )
    suspend fun getEmbeddingSearchRowsForIds(
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

    suspend fun getEmbeddingCacheCounts(modelIds: List<String>): EmbeddingCacheCounts {
        val requestedIds = modelIds.distinct()
        val counts = requestedIds.associateWith { 0 }.toMutableMap()
        var total = 0
        var afterId: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val cursor = afterId
            val page = if (cursor == null) {
                getFirstEmbeddingCacheCountPage(requestedIds, CACHE_COUNT_PAGE_SIZE)
            } else {
                getEmbeddingCacheCountPage(requestedIds, cursor, CACHE_COUNT_PAGE_SIZE)
            }
            currentCoroutineContext().ensureActive()
            if (page.isEmpty()) break
            val eligibleIds = HashSet<String>()
            page.forEach { row ->
                if (row.eligible) eligibleIds += row.messageId
                row.modelId?.let { id -> counts[id] = Math.addExact(counts.getValue(id), 1) }
            }
            total = Math.addExact(total, eligibleIds.size)
            afterId = page.last().messageId
            yield()
        }
        return EmbeddingCacheCounts(total, counts)
    }

    @Query(CACHE_COUNT_FIRST_PAGE)
    suspend fun getFirstEmbeddingCacheCountPage(
        modelIds: List<String>, limit: Int,
    ): List<EmbeddingCacheCountRow>

    @Query(CACHE_COUNT_NEXT_PAGE)
    suspend fun getEmbeddingCacheCountPage(
        modelIds: List<String>, afterId: String, limit: Int,
    ): List<EmbeddingCacheCountRow>

    @Query(
        """
        SELECT m.id, m.text FROM messages m INNER JOIN conversations c ON m.conversationId = c.id
        WHERE c.taskId IS NULL AND m.participant IN ('USER', 'MODEL') AND m.text != ''
          AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%'
          AND (:afterId IS NULL OR m.id > :afterId)
        ORDER BY m.id LIMIT :limit
        """,
    )
    suspend fun getSearchableMessagesPage(afterId: String?, limit: Int): List<IndexableMessage>

    @Query(
        """
        SELECT m.id, m.text
        FROM messages m
        INNER JOIN conversations c ON m.conversationId = c.id
        WHERE c.taskId IS NULL
          AND m.participant IN ('USER', 'MODEL')
          AND m.text != ''
          AND m.id NOT LIKE 'tool_%'
          AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%'
          AND NOT EXISTS (
              SELECT 1 FROM embeddings e
              WHERE e.messageId = m.id AND e.modelId = :modelId
          )
          AND (:afterId IS NULL OR m.id > :afterId)
        ORDER BY m.id
        LIMIT :limit
        """
    )
    suspend fun getUnembeddedMessagesPage(
        modelId: String,
        afterId: String?,
        limit: Int,
    ): List<IndexableMessage>

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
