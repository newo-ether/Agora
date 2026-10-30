package com.newoether.agora.data.local

import androidx.room.Query

/** Selected-conversation media reads inherited by the sole [ChatDao]. */
interface ChatMediaReferencesDao {
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
}
