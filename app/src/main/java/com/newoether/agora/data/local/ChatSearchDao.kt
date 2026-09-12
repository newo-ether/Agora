package com.newoether.agora.data.local

import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Full-text search and citation search inherited by [ChatDao].
 *
 * This is not a second database access object: [ChatDao] remains the sole `@Dao`. The split keeps
 * the search contract reviewable and under the repository's permanent source-size gate.
 */
interface ChatSearchDao {
    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE c.taskId IS NULL AND (m.text LIKE '%' || :query || '%' ESCAPE '\\' OR c.title LIKE '%' || :query || '%' ESCAPE '\\') AND m.participant IN ('USER', 'MODEL') AND m.text != '' AND substr(m.id, 1, 5) != 'tool_' AND substr(m.id, 1, 7) != 'result_' ORDER BY m.timestamp DESC, m.id DESC LIMIT :limit")
    suspend fun searchMessages(query: String, limit: Int = 10): List<MessageEntity>

    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE c.taskId IS NULL AND m.toolCallJson LIKE '%\"type\":\"citation\"%' AND m.participant = 'MODEL' AND substr(m.id, 1, 5) != 'tool_' AND substr(m.id, 1, 7) != 'result_' AND m.id > :afterId ORDER BY m.id ASC LIMIT :pageSize")
    suspend fun getMessagesWithCitationSegmentsPage(
        afterId: String,
        pageSize: Int,
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageForConversation(conversationId: String): MessageEntity?

    /** Message invalidations for task execution summaries. Unlike getExecutionsForTask(),
     * this Flow observes the messages table, so terminal status/snippet changes are emitted. */
    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id WHERE c.taskId = :taskId ORDER BY m.timestamp ASC")
    fun observeExecutionMessagesForTask(taskId: String): Flow<List<MessageEntity>>
}