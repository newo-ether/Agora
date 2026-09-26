package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isSuccessfulContextCompact
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.util.Constants

data class LogicalContextSplit(
    val prefix: List<ChatMessage>,
    val suffix: List<ChatMessage>,
    val logicalMessageCount: Int,
)

data class ContextRetentionSplit(
    val prefix: List<ChatMessage>,
    val retained: List<ChatMessage>,
    val retainedMessageCount: Int,
)

/**
 * Splits provider-visible context by physical message count for Compact retention.
 *
 * A tool request followed by its result rows is one indivisible protocol unit. When the requested
 * cut lands inside that unit, the complete round is retained. Callers must pass the fail-closed
 * output of [validateToolMessages], so malformed tool rows have already become ordinary text and
 * cannot be mistaken for an executable protocol round.
 */
fun splitContextForCompactRetention(
    messages: List<ChatMessage>,
    retainMessages: Int,
): ContextRetentionSplit {
    require(retainMessages >= 0)
    if (messages.isEmpty()) return ContextRetentionSplit(emptyList(), emptyList(), 0)
    if (retainMessages == 0) {
        return ContextRetentionSplit(messages, emptyList(), 0)
    }

    val units = protocolAtomicUnits(messages)
    val retainedUnits = ArrayDeque<List<ChatMessage>>()
    var retainedCount = 0
    for (unit in units.asReversed()) {
        if (retainedCount >= retainMessages) break
        retainedUnits.addFirst(unit)
        retainedCount += unit.size
    }
    val retained = retainedUnits.flatten()
    return ContextRetentionSplit(
        prefix = messages.dropLast(retained.size),
        retained = retained,
        retainedMessageCount = retained.size,
    )
}

/** Splits context using provider role semantics. Tool rows have zero weight and remain atomic. */
fun splitLogicalContext(messages: List<ChatMessage>, retainLogicalMessages: Int): LogicalContextSplit {
    require(retainLogicalMessages >= 0)
    if (messages.isEmpty()) return LogicalContextSplit(emptyList(), emptyList(), 0)
    val normal = messages.mapIndexedNotNull { index, message ->
        if (message.isToolProtocolMessage() || message.id.startsWith(Constants.COMPACT_MSG_PREFIX)) null
        else index to message.participant
    }
    val groups = mutableListOf<MutableList<Int>>()
    var previous: Participant? = null
    normal.forEach { (index, participant) ->
        if (groups.isEmpty() || participant != previous) groups.add(mutableListOf())
        groups.last() += index
        previous = participant
    }
    val count = groups.size
    if (retainLogicalMessages <= 0) return LogicalContextSplit(messages, emptyList(), count)
    if (retainLogicalMessages >= count) return LogicalContextSplit(emptyList(), messages, count)
    var cut = groups[count - retainLogicalMessages].first()
    var cursor = cut - 1
    while (cursor >= 0 && messages[cursor].id.startsWith(Constants.RESULT_MSG_PREFIX)) cursor--
    if (cursor >= 0 && messages[cursor].id.startsWith(Constants.TOOL_MSG_PREFIX)) cut = cursor
    return LogicalContextSplit(messages.take(cut), messages.drop(cut), count)
}

data class ContextWindowUsage(
    val estimatedTokenCount: Int,
    val tokenBudget: Int,
    val logicalMessageCount: Int,
    val hasCompactBoundary: Boolean,
    val systemPromptTokens: Int = 0,
    val toolTokens: Int = 0,
) {
    val progress: Float
        get() = if (tokenBudget <= 0) 0f else
            (estimatedTokenCount.toFloat() / tokenBudget).coerceIn(0f, 1f)

    /**
     * What the transcript itself costs: the estimate minus the fixed prompt and tool definitions.
     * Derived instead of stored so the three parts always add up to [estimatedTokenCount].
     */
    val messageTokens: Int
        get() = (estimatedTokenCount - systemPromptTokens - toolTokens).coerceAtLeast(0)
}

fun contextWindowUsage(
    messages: List<ChatMessage>,
    tokenBudget: Int,
    fixedTokenCost: Int = 0,
    includeAssistantReasoning: Boolean = false,
    fixedComposition: ContextTokenEstimator.FixedContextComposition? = null,
): ContextWindowUsage {
    val safeBudget = tokenBudget.coerceAtLeast(1)
    val canonical = canonicalContextMessages(messages)
    return ContextWindowUsage(
        estimatedTokenCount = (
            ContextTokenEstimator.estimate(
                canonical,
                includeAssistantReasoning = includeAssistantReasoning,
            ).toLong() +
                fixedTokenCost.coerceAtLeast(0).toLong()
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        tokenBudget = safeBudget,
        logicalMessageCount = splitLogicalContext(canonical, retainLogicalMessages = 0)
            .logicalMessageCount,
        hasCompactBoundary = messages.any(ChatMessage::isSuccessfulContextCompact),
        systemPromptTokens = fixedComposition?.systemPromptTokens ?: 0,
        toolTokens = fixedComposition?.toolTokens ?: 0,
    )
}

/** Original message ids retained by the provider's canonical context window. */
fun contextWindowRetainedMessageIds(
    messages: List<ChatMessage>,
    tokenBudget: Int,
    fixedTokenCost: Int = 0,
    includeAssistantReasoning: Boolean = false,
): Set<String> {
    if (messages.isEmpty()) return emptySet()
    val compacted = applyNearestContextCompact(messages)
    val messageBudget = (tokenBudget - fixedTokenCost.coerceAtLeast(0)).coerceAtLeast(1)
    val retained = limitContext(
        canonicalContextMessages(messages),
        messageBudget,
        includeAssistantReasoning = includeAssistantReasoning,
    )
    val firstRetainedId = retained.firstOrNull()?.id ?: return emptySet()
    val sourceAnchorId = firstRetainedId.removePrefix("context_summary_")
    val originalSourceIndex = messages.indexOfFirst { it.id == sourceAnchorId }
    if (originalSourceIndex >= 0) {
        // A Compact is projected with a synthetic context_summary_ id and may then absorb the first
        // same-role suffix row during canonicalization. Recover the durable boundary in the original
        // graph so rollout visualization retains the Compact and every verbatim suffix message.
        return messages
            .drop(originalSourceIndex)
            .filterNot { it.isContextCompact() && !it.isSuccessfulContextCompact() }
            .mapTo(linkedSetOf(), ChatMessage::id)
    }
    val sourceIndex = compacted.indexOfFirst { it.id == sourceAnchorId }
    if (sourceIndex < 0) return retained.mapTo(linkedSetOf()) {
        it.id.removePrefix("context_summary_")
    }
    // The canonical anchor is the first row of any merged same-role group. Keeping the original
    // suffix from that anchor preserves every member and all complete tool rows represented by it.
    return compacted.drop(sourceIndex).mapTo(linkedSetOf(), ChatMessage::id)
}

internal fun protocolAtomicUnits(messages: List<ChatMessage>): List<List<ChatMessage>> {
    val units = mutableListOf<List<ChatMessage>>()
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        if (message.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
            val round = mutableListOf(message)
            index++
            while (
                index < messages.size &&
                messages[index].id.startsWith(Constants.RESULT_MSG_PREFIX)
            ) {
                round += messages[index++]
            }
            units += round
        } else {
            units += listOf(message)
            index++
        }
    }
    return units
}
