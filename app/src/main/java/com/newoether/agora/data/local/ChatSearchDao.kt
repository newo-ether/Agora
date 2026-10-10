package com.newoether.agora.data.local

import androidx.room.Query

data class EmbeddingModelCount(
    val modelId: String,
    val count: Int,
)

/** Search eligibility and bounded projections inherited by the sole [ChatDao]. */
interface ChatSearchDao {
    /** [query] must be pre-escaped for LIKE (see ConversationRepository.escapeLikePattern). */
    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE c.taskId IS NULL AND (m.text LIKE '%' || :query || '%' ESCAPE '\\' OR c.title LIKE '%' || :query || '%' ESCAPE '\\') AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND substr(m.id, 1, 5) != 'tool_' AND substr(m.id, 1, 7) != 'result_' AND substr(m.id, 1, 8) != 'compact_' ORDER BY m.timestamp DESC, m.id DESC LIMIT :limit")
    suspend fun searchMessages(query: String, limit: Int = 10): List<MessageEntity>

    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE c.taskId IS NULL AND m.toolCallJson LIKE '%\"type\":\"citation\"%' AND m.participant = 'MODEL' AND substr(m.id, 1, 5) != 'tool_' AND substr(m.id, 1, 7) != 'result_' AND m.id > :afterId ORDER BY m.id ASC LIMIT :pageSize")
    suspend fun getMessagesWithCitationSegmentsPage(
        afterId: String,
        pageSize: Int,
    ): List<MessageEntity>

    @Query(
        """
        SELECT e.id, e.messageId, e.embedding, e.dimension
        FROM embeddings e
        CROSS JOIN messages m
        CROSS JOIN conversations c
        WHERE e.messageId = m.id
          AND m.conversationId = c.id
          AND e.modelId = :modelId
          AND e.id > :afterId
          AND c.taskId IS NULL
          AND m.participant IN ('USER', 'MODEL')
          AND LENGTH(m.text) >= :minimumTextLength
          AND m.id NOT LIKE 'tool_%'
          AND m.id NOT LIKE 'result_%'
          AND m.id NOT LIKE 'compact_%'
        ORDER BY e.id
        LIMIT :limit
        """
    )
    suspend fun getEmbeddingSearchPage(
        modelId: String,
        afterId: Long,
        minimumTextLength: Int,
        limit: Int,
    ): List<EmbeddingSearchRow>

    @Query("SELECT COUNT(*) FROM embeddings e INNER JOIN messages m ON e.messageId = m.id INNER JOIN conversations c ON m.conversationId = c.id WHERE e.modelId = :modelId AND c.taskId IS NULL AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%'")
    suspend fun getEmbeddingCountByModel(modelId: String): Int

    @Query(
        """
        SELECT e.modelId AS modelId, COUNT(*) AS count
        FROM embeddings e
        INNER JOIN messages m ON e.messageId = m.id
        INNER JOIN conversations c ON m.conversationId = c.id
        WHERE e.modelId IN (:modelIds)
          AND c.taskId IS NULL
          AND m.participant IN ('USER', 'MODEL')
          AND m.text != ''
          AND m.id NOT LIKE 'tool_%'
          AND m.id NOT LIKE 'result_%'
          AND m.id NOT LIKE 'compact_%'
        GROUP BY e.modelId
        """
    )
    suspend fun getEmbeddingCountsByModels(modelIds: List<String>): List<EmbeddingModelCount>

    @Query("SELECT COUNT(*) FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE c.taskId IS NULL AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%'")
    suspend fun getIndexableMessageCount(): Int

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

    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE m.id IN (:ids) AND c.taskId IS NULL AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%'")
    suspend fun getSearchableMessagesByIds(ids: List<String>): List<MessageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE m.id = :messageId AND c.taskId IS NULL AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND m.id NOT LIKE 'compact_%')")
    suspend fun isMessageSearchable(messageId: String): Boolean

    @Query("SELECT * FROM conversations WHERE id = :conversationId AND taskId IS NULL")
    suspend fun getSearchableConversation(conversationId: String): ChatEntity?


    @Query("SELECT COUNT(*) FROM conversations WHERE taskId IS NULL")
    suspend fun getSearchableConversationCount(): Int

    @Query(
        """
        SELECT *
        FROM conversations
        WHERE taskId IS NULL
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
        WHERE taskId IS NULL
        ORDER BY lastUpdated DESC, id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getSearchableConversationsPageDescending(
        offset: Int,
        limit: Int,
    ): List<ChatEntity>
}
