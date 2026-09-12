package com.newoether.agora.data.local

import androidx.room.Query
import androidx.room.Transaction

/**
 * Model/provider renaming operations inherited by [ChatDao].
 *
 * This is not a second database access object: [ChatDao] remains the sole `@Dao`. The split keeps
 * the model reference contract reviewable and under the repository's permanent source-size gate.
 */
interface ChatModelReferenceDao {
    // Abstract methods that must be implemented by ChatDao (from ChatCoreDao)
    suspend fun replaceConversationModelReferences(
        oldModelId: String,
        newModelId: String?,
    ): Int

    suspend fun replaceNewChatModelReference(
        oldModelId: String,
        newModelId: String?,
    ): Int

    suspend fun replaceTaskModelReferences(
        oldModelId: String,
        newModelId: String?,
    ): Int

    suspend fun renameTaskProviderModelReferences(
        oldProvider: String,
        newProvider: String,
    ): Int

    @Transaction
    suspend fun replaceConfiguredModelReferences(
        oldModelId: String,
        newModelId: String?,
    ) {
        replaceConversationModelReferences(oldModelId, newModelId)
        replaceNewChatModelReference(oldModelId, newModelId)
        replaceTaskModelReferences(oldModelId, newModelId)
    }

    @Query(
        """
        UPDATE conversations
        SET modelId = :newProvider || substr(modelId, length(:oldProvider) + 1)
        WHERE substr(modelId, 1, length(:oldProvider) + 1) = :oldProvider || ':'
        """
    )
    suspend fun renameConversationProviderModelReferences(
        oldProvider: String,
        newProvider: String,
    ): Int

    @Query(
        """
        UPDATE new_chat_persist
        SET modelId = :newProvider || substr(modelId, length(:oldProvider) + 1)
        WHERE id = 0
          AND substr(modelId, 1, length(:oldProvider) + 1) = :oldProvider || ':'
        """
    )
    suspend fun renameNewChatProviderModelReference(
        oldProvider: String,
        newProvider: String,
    ): Int

    @Query(
        """
        UPDATE messages
        SET modelName = :newProvider || substr(modelName, length(:oldProvider) + 1)
        WHERE substr(modelName, 1, length(:oldProvider) + 1) = :oldProvider || ':'
        """
    )
    suspend fun renameMessageProviderModelReferences(
        oldProvider: String,
        newProvider: String,
    ): Int

    @Transaction
    suspend fun renameConfiguredProviderModelReferences(oldProvider: String, newProvider: String) {
        renameConversationProviderModelReferences(oldProvider, newProvider)
        renameNewChatProviderModelReference(oldProvider, newProvider)
        renameTaskProviderModelReferences(oldProvider, newProvider)
        renameMessageProviderModelReferences(oldProvider, newProvider)
    }
}