package com.newoether.agora.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Conversation-owned task progress, independent of message branches. */
@Serializable
data class ConversationTodoItem(val content: String, val status: String = "pending")

object ConversationTodo {
    private val json = Json { ignoreUnknownKeys = true }
    val statuses = setOf("pending", "in_progress", "completed")

    fun encode(items: List<ConversationTodoItem>): String? {
        require(items.all { it.content.isNotBlank() && it.status in statuses }) {
            "Each todo requires nonblank content and a pending, in_progress or completed status"
        }
        return items.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) }
    }

    fun decode(raw: String?): List<ConversationTodoItem> =
        raw?.let { json.decodeFromString<List<ConversationTodoItem>>(it) }.orEmpty().also {
            encode(it)
        }
}
