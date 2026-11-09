package com.newoether.agora.data.local

import androidx.room.Query

data class ConversationDraftAttachmentReference(val id: String, val draftAttachments: String?)

/** Selected-owner pages seek identities before fetching attachment payloads. */
interface ChatMediaReferencesDao {
    @Query("SELECT id, draftAttachments FROM conversations WHERE id = :id")
    suspend fun getConversationDraftAttachmentReference(id: String): ConversationDraftAttachmentReference?

    @Query("SELECT id, images, attachmentMeta FROM messages WHERE conversationId = :conversationId ORDER BY id LIMIT :limit")
    suspend fun getConversationAttachmentFirstPage(conversationId: String, limit: Int): List<MessageAttachmentReference>

    @Query("SELECT id, images, attachmentMeta FROM messages WHERE conversationId = :conversationId AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun getConversationAttachmentNextPage(conversationId: String, afterId: String, limit: Int): List<MessageAttachmentReference>

    suspend fun getConversationMessageAttachmentReferencesPage(
        conversationId: String,
        afterId: String?,
        limit: Int,
    ): List<MessageAttachmentReference> =
        if (afterId == null) getConversationAttachmentFirstPage(conversationId, limit)
        else getConversationAttachmentNextPage(conversationId, afterId, limit)
}
