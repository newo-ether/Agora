package com.newoether.agora.webui

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.ui.chat.bottombar.contextReservedTokens
import com.newoether.agora.ui.chat.bottombar.contextUsageExceedsCompactThreshold
import com.newoether.agora.viewmodel.ConversationContextProjector
import com.newoether.agora.viewmodel.ConversationContextProjection
import com.newoether.agora.viewmodel.GenerationManager
import com.newoether.agora.viewmodel.GenerationRequestBuilder
import kotlinx.coroutines.flow.StateFlow

/**
 * What one browser asks to have priced: the conversation it shows plus the model, window and system
 * prompt it chose.
 *
 * The branch topology is deliberately not part of the request. A browser asks again whenever its own
 * visible path changes, and picking another branch is exactly that on this side, so the path already
 * carries the information without copying the phone's projection key.
 */
internal data class WebContextAccountingRequest(
    val conversationId: String?,
    val seq: Long,
    val selectedModelId: String,
    val tokenBudget: Int,
    val systemPromptId: String?,
)

/**
 * One projection as the browser sees it, with the arithmetic of the phone's bottom bar: the three
 * parts the provider is given, the tail automatic compaction keeps free and the remainder in between.
 *
 * A projection keeps its previous figure while it re-prices and does not say which target that figure
 * came from, so [settledConversationId] — the target this connection last reported a completed figure
 * for — decides whether the figure may still be shown. Without it, opening another conversation would
 * flash the previous conversation's total under the new one.
 */
internal fun contextAccountingEvent(
    request: WebContextAccountingRequest,
    projection: ConversationContextProjection,
    settledConversationId: String?,
    compactThresholdPercent: Int,
    compactEnabled: Boolean,
): WebSyncEvent.Context {
    val settled = projection.conversationId == request.conversationId &&
        (projection.completed || settledConversationId == request.conversationId)
    val usage = projection.usage.takeIf { settled && !projection.failed }
    val budget = request.tokenBudget
    val system = usage?.systemPromptTokens ?: 0
    val tools = usage?.toolTokens ?: 0
    val messages = usage?.messageTokens ?: 0
    val used = (system + tools + messages).coerceAtMost(budget)
    val reserved = if (compactEnabled) contextReservedTokens(budget, compactThresholdPercent) else null
    val free = (budget - used - (reserved ?: 0)).coerceAtLeast(0)
    val parts = if (usage == null) emptyList() else listOfNotNull(
        WebContextPart("system", system, ContextBudget.compactLabel(system)),
        WebContextPart("tools", tools, ContextBudget.compactLabel(tools)),
        WebContextPart("messages", messages, ContextBudget.compactLabel(messages)),
        WebContextPart("free", free, ContextBudget.compactLabel(free)),
        reserved?.let { WebContextPart("reserved", it, ContextBudget.compactLabel(it)) },
    )
    val estimated = usage?.estimatedTokenCount
    return WebSyncEvent.Context(
        conversationId = request.conversationId,
        seq = request.seq,
        tokenBudget = budget,
        budgetLabel = ContextBudget.compactLabel(budget),
        estimatedTokens = estimated,
        estimatedLabel = estimated?.let(ContextBudget::compactLabel),
        parts = parts,
        compactThresholdPercent = compactThresholdPercent,
        compactEnabled = compactEnabled,
        overCompactThreshold = estimated != null &&
            contextUsageExceedsCompactThreshold(estimated, budget, compactThresholdPercent),
        loading = projection.loading,
        failed = projection.failed && projection.conversationId == request.conversationId,
    )
}

/**
 * Builds the context accounting of one browser connection.
 *
 * Each connection projects on its own, so a browser prices the conversation it shows and never reads
 * the phone's projection or another browser's. The compaction policy is the phone's own settings flow,
 * the same one the bottom bar reads.
 */
internal class WebUiContextAccounting(
    private val conversations: ConversationRepository,
    private val requestBuilder: GenerationRequestBuilder,
    private val generationManager: () -> GenerationManager,
    private val generationErrorFormatter: (String) -> String,
    val compactThresholdPercent: StateFlow<Int>,
    val compactEnabled: StateFlow<Boolean>,
) {
    /**
     * The projector of one connection. Its New Chat overrides come from that connection's own New Chat
     * workspace, so a browser pricing the New Chat page prices what it actually shows.
     */
    fun open(
        newChatSystemPromptId: () -> String?,
        newChatConversationSettings: () -> ConversationSettings?,
    ) = ConversationContextProjector(
        conversations = conversations,
        requestBuilder = requestBuilder,
        generationManager = generationManager,
        generationErrorFormatter = generationErrorFormatter,
        newChatSystemPromptId = newChatSystemPromptId,
        newChatConversationSettings = newChatConversationSettings,
    )
}
