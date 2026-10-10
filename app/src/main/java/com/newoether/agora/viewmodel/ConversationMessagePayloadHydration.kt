package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class ConversationMessagePayloadHydration(
    private val conversations: ConversationRepository,
    private val appContext: Context,
    projectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val projector = MessagePayloadProjector(projectionDispatcher)

    fun observeMessage(
        messageId: String,
        transform: (ChatMessage) -> ChatMessage = { it },
    ): Flow<ChatMessage?> = conversations.observeMessage(messageId).distinctUntilChanged().map { entity ->
        entity?.takeIf { it.id == messageId }?.let { row ->
            projector.project { transform(row.toUiChatMessage(appContext)) }
        }
    }

    suspend fun loadMessages(
        conversationId: String,
        messageIds: List<String>,
        transform: (ChatMessage) -> ChatMessage = { it },
    ): List<ChatMessage> {
        val requestedIds = messageIds.distinct()
        val entitiesById = conversations.getMessagesByIds(requestedIds)
            .filter { entity -> entity.conversationId == conversationId }
            .associateBy { entity -> entity.id }
        val orderedEntities = requestedIds.mapNotNull(entitiesById::get)
        return projector.projectAll(orderedEntities) { entity ->
            transform(entity.toUiChatMessage(appContext))
        }
    }
}
