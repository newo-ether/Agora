package com.newoether.agora.data.local

import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

data class ProviderMessageReference(val id: String, val conversationId: String)

/** Crash-safe legacy migration: aliases remain until every indexed batch has committed. */
interface ChatProviderIdentityDao : ChatAutomationDao {
    @Query("""
        SELECT id, conversationId FROM messages
        WHERE modelName >= :oldProvider || ':' AND modelName < :oldProvider || ';'
        ORDER BY modelName, id LIMIT :limit
    """)
    suspend fun getProviderMessageReferences(oldProvider: String, limit: Int): List<ProviderMessageReference>

    @Query("""
        UPDATE messages SET modelName = :newProvider || substr(modelName, length(:oldProvider) + 1)
        WHERE id IN (:ids) AND modelName >= :oldProvider || ':' AND modelName < :oldProvider || ';'
    """)
    suspend fun renameMessageProviderModelReferences(ids: List<String>, oldProvider: String, newProvider: String): Int

    @Query("UPDATE conversations SET dataChangedAt = MAX(dataChangedAt + 1, :at) WHERE id IN (:ids)")
    suspend fun touchProviderMessageConversations(ids: List<String>, at: Long)

    @Query("""
        SELECT id FROM conversations
        WHERE modelId >= :oldProvider || ':' AND modelId < :oldProvider || ';'
        ORDER BY modelId LIMIT :limit
    """)
    suspend fun getProviderConversationIds(oldProvider: String, limit: Int): List<String>

    @Query("""
        UPDATE conversations
        SET modelId = :newProvider || substr(modelId, length(:oldProvider) + 1),
            dataChangedAt = MAX(dataChangedAt + 1, :at)
        WHERE id IN (:ids) AND modelId >= :oldProvider || ':' AND modelId < :oldProvider || ';'
    """)
    suspend fun renameConversationProviderModelReferences(ids: List<String>, oldProvider: String, newProvider: String, at: Long): Int

    @Query("""
        UPDATE new_chat_persist SET modelId = :newProvider || substr(modelId, length(:oldProvider) + 1)
        WHERE id = 0 AND substr(modelId, 1, length(:oldProvider) + 1) = :oldProvider || ':'
    """)
    suspend fun renameNewChatProviderModelReference(oldProvider: String, newProvider: String): Int

    @Transaction
    suspend fun renameProviderReferencePage(oldProvider: String, newProvider: String): Boolean {
        val at = System.currentTimeMillis()
        val conversations = getProviderConversationIds(oldProvider, 64)
        if (conversations.isNotEmpty()) {
            renameConversationProviderModelReferences(conversations, oldProvider, newProvider, at)
            return true
        }
        val messages = getProviderMessageReferences(oldProvider, 64)
        if (messages.isNotEmpty()) {
            touchProviderMessageConversations(messages.map { it.conversationId }.distinct(), at)
            check(renameMessageProviderModelReferences(messages.map { it.id }, oldProvider, newProvider) == messages.size)
            return true
        }
        renameNewChatProviderModelReference(oldProvider, newProvider)
        renameTaskProviderModelReferences(oldProvider, newProvider)
        return false
    }

    // No outer transaction: cancellation leaves the durable alias marker for a safe retry.
    suspend fun renameConfiguredProviderModelReferences(oldProvider: String, newProvider: String) {
        if (oldProvider == newProvider) return
        require(oldProvider.isNotEmpty() && newProvider.isNotEmpty())
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!renameProviderReferencePage(oldProvider, newProvider)) return
            yield()
        }
    }
}
