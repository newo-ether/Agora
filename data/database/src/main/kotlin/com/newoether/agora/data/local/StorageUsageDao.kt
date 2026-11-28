package com.newoether.agora.data.local

import androidx.room.Dao
import androidx.room.Query

/** Byte totals of one conversation's message rows, grouped by what the bytes hold. */
data class ConversationMessageBytes(
    val conversationId: String,
    val messageCount: Int,
    val textBytes: Long,
    val toolBytes: Long,
    val metadataBytes: Long,
)

/** Byte total of the embedding vectors and chunk text owned by one conversation's messages. */
data class ConversationEmbeddingBytes(
    val conversationId: String,
    val bytes: Long,
)

/** Image sources of one message, used to sum attachment file sizes per conversation. */
data class ConversationImageSources(
    val conversationId: String,
    val images: List<String>,
)

/** Ownership edge used to roll subagent conversations into their parent. */
data class ConversationOwnership(
    val id: String,
    val title: String,
    val parentConversationId: String?,
    val taskId: String?,
    val lastUpdated: Long,
    /** Export watermark; it advances whenever the conversation's stored data changes. */
    val dataChangedAt: Long,
)

/**
 * Read-only aggregates for the Storage page. Message queries take a batch of conversation IDs so a
 * caller can rescan only the conversations whose data changed since its cached result.
 */
@Dao
interface StorageUsageDao {
    @Query(
        """
        SELECT conversationId,
            COUNT(*) AS messageCount,
            COALESCE(SUM(
                COALESCE(length(CAST(text AS BLOB)), 0) +
                COALESCE(length(CAST(thoughts AS BLOB)), 0) +
                COALESCE(length(CAST(thoughtTitle AS BLOB)), 0)
            ), 0) AS textBytes,
            COALESCE(SUM(COALESCE(length(CAST(toolCallJson AS BLOB)), 0)), 0) AS toolBytes,
            COALESCE(SUM(
                COALESCE(length(CAST(images AS BLOB)), 0) +
                COALESCE(length(CAST(attachmentMeta AS BLOB)), 0) +
                COALESCE(length(CAST(sourceJson AS BLOB)), 0)
            ), 0) AS metadataBytes
        FROM messages
        WHERE conversationId IN (:conversationIds)
        GROUP BY conversationId
        """
    )
    suspend fun messageBytes(conversationIds: List<String>): List<ConversationMessageBytes>

    @Query(
        """
        SELECT m.conversationId AS conversationId,
            COALESCE(SUM(length(e.embedding) + COALESCE(length(CAST(e.chunkText AS BLOB)), 0)), 0) AS bytes
        FROM embeddings e
        JOIN messages m ON m.id = e.messageId
        GROUP BY m.conversationId
        """
    )
    suspend fun embeddingBytes(): List<ConversationEmbeddingBytes>

    @Query(
        """
        SELECT conversationId, images FROM messages
        WHERE conversationId IN (:conversationIds) AND images != '' AND images != '[]'
        """
    )
    suspend fun imageSources(conversationIds: List<String>): List<ConversationImageSources>

    @Query("SELECT conversationId, directory FROM conversation_attachment_directories")
    suspend fun attachmentDirectories(): List<ConversationAttachmentDirectory>

    @Query("SELECT id, title, parentConversationId, taskId, lastUpdated, dataChangedAt FROM conversations")
    suspend fun conversations(): List<ConversationOwnership>
}
