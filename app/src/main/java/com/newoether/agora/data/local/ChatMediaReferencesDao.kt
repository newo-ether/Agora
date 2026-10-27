package com.newoether.agora.data.local

import androidx.room.Query

/** Bounded media reference reads inherited by the sole [ChatDao]. */
interface ChatMediaReferencesDao {
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
        SELECT id, toolCallJson
        FROM messages
        WHERE (:afterId IS NULL OR id > :afterId)
          AND (:pathToken IS NULL OR instr(toolCallJson, :pathToken) > 0)
          AND toolCallJson IS NOT NULL
          AND toolCallJson != ''
        ORDER BY id
        LIMIT :limit
        """
    )
    suspend fun getMessageToolMediaReferencesPage(
        afterId: String?,
        limit: Int,
        pathToken: String? = null,
    ): List<MessageToolMediaReference>

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
}
