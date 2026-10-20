package com.newoether.agora.viewmodel

import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.model.interactiveAnswer

/** Exact selected-path identity; callers never resolve a different chat from a stale viewer. */
internal fun ChatViewModel.hasInteractiveAnswer(
    conversationId: String,
    messageId: String,
): Boolean = currentConversationId.value == conversationId && messages.value.any { message ->
    message.id == messageId
}

/** Demand-load exactly one persisted owner at submission, then recheck the selected path. */
internal suspend fun ChatViewModel.verifyInteractiveAnswer(
    conversationId: String,
    messageId: String,
    callId: String,
    answer: InteractiveAnswer,
): Boolean {
    if (!hasInteractiveAnswer(conversationId, messageId)) return false
    val message = messagePayloadHydration.loadMessages(conversationId, listOf(messageId)).singleOrNull() ?: return false
    return hasInteractiveAnswer(conversationId, messageId) && message.segments.orEmpty().any {
        it.toolCallId == callId && it.interactiveAnswer() == answer
    }
}
