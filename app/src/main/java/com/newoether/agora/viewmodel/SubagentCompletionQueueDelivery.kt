package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.dismissSubagentResults
import com.newoether.agora.data.repository.pendingSubagentResults
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.SubagentCompletion
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Adapts the durable completion outbox to the sole ordinary guidance queue. Owns no work list. */
internal class SubagentCompletionQueueDelivery(
    private val conversations: ConversationRepository,
    private val registry: ConversationStateRegistry,
    private val fallbackModelId: () -> String?,
) {
    init {
        conversations.onSubagentWaitCommitted = ::waitCommitted
    }

    suspend fun deliver(parentId: String) {
        // No runtime allocation for roots without pending results, and no startup owner scan.
        if (conversations.pendingSubagentResults(parentId).isEmpty()) return
        val parent = conversations.getConversation(parentId) ?: return
        val modelId = parent.modelId?.takeIf(String::isNotBlank)
            ?: fallbackModelId()?.takeIf(String::isNotBlank) ?: return
        val state = registry.getOrCreate(parentId)
        bind(state)
        var enqueued = false
        state.queueMutationMutex.withLock {
            // Re-read under the queue boundary: a wait result or dismissal may have committed
            // since the event was emitted. Duplicate events include already-claimed batches.
            conversations.pendingSubagentResults(parentId).forEach { row ->
                if (state.containsSubagentResult(row.id, row.resultId)) return@forEach
                val result = checkNotNull(SubagentCompletion.decode(row.resultJson))
                val resultText = buildString {
                    append(result.text)
                    result.errorText?.takeIf { it.isNotBlank() && it != result.text }?.let { detail ->
                        if (isNotEmpty()) append('\n')
                        append("Error: ").append(detail)
                    }
                    result.errorCode?.let { code -> append("\nError code: ").append(code) }
                }
                state.enqueueSend(
                    QueuedSend(
                        id = UUID.randomUUID().toString(),
                        text = "${row.title} (${row.id}) [${result.status}]\n$resultText",
                        modelId = modelId,
                        attachments = emptyList(),
                        runId = row.resultId,
                        source = MessageSource(
                            MessageSource.Kind.SUBAGENT,
                            subagentResults = mapOf(row.id to row.resultId),
                            subagentResult = MessageSource.SubagentResult(
                                name = row.title,
                                status = result.status,
                                errorText = result.errorText,
                                errorCode = result.errorCode,
                            ),
                        ),
                    ),
                )
                enqueued = true
            }
        }
        // Idle parents have no remaining settlement signal to wake the ordinary queue drain.
        if (enqueued && !state.generating.value) state.onQueueDrainRequested?.invoke(state)
    }

    suspend fun dismiss(parentId: String, send: QueuedSend) {
        if (send.source?.kind == MessageSource.Kind.SUBAGENT) {
            conversations.dismissSubagentResults(parentId, send.source.subagentResults)
        }
    }

    /** Called only after the wait tool's Room transaction committed, outside the transaction. */
    suspend fun waitCommitted(parentId: String, receipts: Map<String, String>) {
        val state = registry.get(parentId) ?: return
        state.queueMutationMutex.withLock { state.removeSubagentResults(receipts) }
    }

    fun bind(state: ConversationGenerationState) {
        state.onQueuedSendDismissed = { send -> dismiss(state.conversationId, send) }
    }
}
