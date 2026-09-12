package com.newoether.agora.data.local

import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Bulk export/import operations inherited by [ChatDao].
 *
 * This is not a second database access object: [ChatDao] remains the sole `@Dao`. The split keeps
 * the export/import contract reviewable and under the repository's permanent source-size gate.
 */
interface ChatExportImportDao {
    @Query("SELECT * FROM conversations")
    suspend fun getAllConversationsList(): List<ChatEntity>

    /** Repairs derived Run-selection metadata without making the conversation look newly edited. */
    @Query(
        """
        UPDATE conversations
        SET selectedRunBranchesJson = :replacement
        WHERE id = :conversationId AND selectedRunBranchesJson = :expected
        """
    )
    suspend fun compareAndSetRunBranchSelections(
        conversationId: String,
        expected: String,
        replacement: String,
    ): Int

    @Query("SELECT id FROM conversations")
    suspend fun getAllConversationIds(): List<String>

    @Query("SELECT id FROM tasks")
    suspend fun getAllTaskIds(): List<String>

    @Query(
        """
        SELECT *
        FROM messages
        WHERE (:afterId IS NULL OR id > :afterId)
        ORDER BY id
        LIMIT :limit
        """
    )
    suspend fun getMessagesPage(afterId: String?, limit: Int): List<MessageEntity>

    @Query(
        """
        SELECT id, images, attachmentMeta
        FROM messages
        WHERE (:afterId IS NULL OR id > :afterId)
          AND (:pathToken IS NULL OR instr(images, :pathToken) > 0 OR instr(attachmentMeta, :pathToken) > 0)
          AND (
              (images != '' AND images != '[]')
              OR (attachmentMeta IS NOT NULL AND attachmentMeta != '')
          )
        ORDER BY id
        LIMIT :limit
        """
    )
    suspend fun getMessageAttachmentReferencesPage(
        afterId: String?,
        limit: Int,
        pathToken: String? = null,
    ): List<MessageAttachmentReference>

    @Query(
        """
        SELECT id, images, attachmentMeta
        FROM messages
        WHERE conversationId = :conversationId
          AND (:afterId IS NULL OR id > :afterId)
          AND (
              (images != '' AND images != '[]')
              OR (attachmentMeta IS NOT NULL AND attachmentMeta != '')
          )
        ORDER BY id
        LIMIT :limit
        """
    )
    suspend fun getConversationMessageAttachmentReferencesPage(
        conversationId: String,
        afterId: String?,
        limit: Int,
    ): List<MessageAttachmentReference>

    @Query(
        """
        SELECT id, draftAttachments
        FROM conversations
        WHERE (:afterId IS NULL OR id > :afterId)
          AND (:pathToken IS NULL OR instr(draftAttachments, :pathToken) > 0)
          AND draftAttachments IS NOT NULL
          AND draftAttachments != ''
        ORDER BY id
        LIMIT :limit
        """
    )
    suspend fun getConversationDraftAttachmentReferencesPage(
        afterId: String?,
        limit: Int,
        pathToken: String? = null,
    ): List<ConversationDraftAttachmentReference>

    @Query(
        """
        SELECT draftAttachments
        FROM new_chat_persist
        WHERE id = 0
          AND (:pathToken IS NULL OR instr(draftAttachments, :pathToken) > 0)
          AND draftAttachments IS NOT NULL
          AND draftAttachments != ''
        """
    )
    suspend fun getNewChatDraftAttachmentReference(pathToken: String? = null): NewChatDraftAttachmentReference?

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesByConversation(conversationId: String)

    @Query("SELECT id FROM messages WHERE id IN (:ids)")
    suspend fun findExistingMessageIds(ids: List<String>): List<String>
}