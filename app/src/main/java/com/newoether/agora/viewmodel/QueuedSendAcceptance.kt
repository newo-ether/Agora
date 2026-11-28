package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.claimQueuedAttachmentDirectories

/** Runs under the existing queue mutation lock, before the origin clears its draft. */
internal suspend fun ConversationGenerationState.acceptQueuedSend(
    repository: ConversationRepository,
    conversationId: String,
    queued: QueuedSend,
    notifier: SendAcceptanceNotifier,
    onAccepted: suspend (SendAcceptance) -> Unit,
    origin: ChatClient?,
): QueuedSend {
    repository.claimQueuedAttachmentDirectories(conversationId, queued.attachments)
    enqueueSend(queued)
    try {
        notifier.notify(SendAcceptance.Queued(queued.id, conversationId), onAccepted, origin)
    } catch (error: Exception) {
        removeQueuedSend(queued.id)
        throw error
    }
    return queued
}
