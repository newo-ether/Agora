package com.newoether.agora.data.repository

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Newest image path in the conversation (any participant), for image-to-image references.
 * Generated images qualify, so the model can chain edits from previous generations.
 * Kept as an extension to keep ConversationRepository within the source-size guard.
 */
suspend fun ConversationRepository.getLatestImagePath(conversationId: String?): String? {
    if (conversationId.isNullOrBlank()) return null
    val json = chatDao.getLatestImagesJson(conversationId) ?: return null
    val paths = runCatching {
        Json.decodeFromString<List<String>>(json)
    }.getOrNull() ?: return null
    return paths.lastOrNull { it.isNotBlank() }
}
