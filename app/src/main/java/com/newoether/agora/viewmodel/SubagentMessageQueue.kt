package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.requireSubagentToolOwner
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.RunEffectIdentity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Produces ordinary child guidance; the existing lease store and drain own its lifecycle. */
internal class SubagentMessageQueue(
    private val conversations: ConversationRepository,
    private val registry: ConversationStateRegistry,
    private val coordinator: ConversationExecutionCoordinator,
    private val prepareSnapshot: suspend (
        conversationId: String,
        runId: String,
        modelId: String,
    ) -> GenerationAdmissionSnapshot,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun enqueue(id: String, text: String, parentContext: GenerationContext): String {
        require(id.isNotBlank()) { "agent_id is required" }
        require(text.isNotBlank()) { "message is required" }
        check(parentContext.subagentToolsEnabled) { "Subagent tools are unavailable in this context" }
        val identity = checkNotNull(parentContext.toolEffectIdentity) { "An active tool effect is required" }
        check(identity.conversationId == parentContext.conversationId)
        val child = conversations.withProviderContextSnapshot { ownedChild(identity, id) }
        val modelId = checkNotNull(child.modelId?.takeIf(String::isNotBlank)) {
            "The subagent has no selected model"
        }
        val releaseAdmission = coordinator.reserveSubagentGeneration(identity.conversationId, id)
        var transferred = false
        try {
            val queuedId = idFactory()
            val runId = idFactory()
            // Provider/settings preparation may suspend. Never hold the queue or Room lock across it.
            val captured = prepareSnapshot(id, runId, modelId)
            check(captured.conversationId == id && captured.runId == runId) {
                "Subagent admission snapshot does not match the queued input"
            }
            val context = parentContext.copy(
                conversationId = id,
                toolEffectIdentity = null,
                todoEnabled = parentContext.todoEnabled && captured.context.todoEnabled,
                subagentToolsEnabled = false,
                accessPastConversations = false,
                foregroundServiceManagedExternally = false,
                enabledModelIds = parentContext.enabledModelIds.toList(),
                webSearchApiKeys = parentContext.webSearchApiKeys.toMap(),
                shellDevices = parentContext.shellDevices.toList(),
            )
            val snapshot = captured.copy(
                context = context,
                automaticCompact = captured.automaticCompact.copy(generationContext = context),
            )
            val queued = QueuedSend(
                id = queuedId,
                text = text,
                modelId = snapshot.selectedModelId,
                attachments = emptyList(),
                runId = runId,
                source = MessageSource(MessageSource.Kind.SUBAGENT),
                generationSnapshot = snapshot,
                releaseGenerationAdmission = releaseAdmission,
            )
            val state = registry.getOrCreate(id)
            state.queueMutationMutex.withLock {
                try {
                    conversations.withProviderContextSnapshot {
                        val currentChild = ownedChild(identity, id)
                        check(currentChild.modelId == child.modelId) { "The subagent model changed before queue admission" }
                        currentCoroutineContext().ensureActive()
                        state.enqueueSend(queued)
                    }
                } catch (error: Throwable) {
                    // No drain can claim this item until the validating transaction has exited.
                    state.removeQueuedSend(queuedId)?.let(state::discardQueuedSend)
                    throw error
                }
            }
            transferred = true
            if (!state.generating.value) state.onQueueDrainRequested?.invoke(state)
            return queuedId
        } finally {
            if (!transferred) releaseAdmission()
        }
    }

    private suspend fun ownedChild(identity: RunEffectIdentity, id: String): ChatEntity {
        conversations.requireSubagentToolOwner(identity)
        return checkNotNull(conversations.getConversation(id)?.takeIf {
            it.parentConversationId == identity.conversationId
        }) { "Subagent does not belong to the current conversation" }
    }
}
