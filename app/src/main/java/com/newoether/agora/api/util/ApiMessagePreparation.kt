package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.model.isSuccessfulContextCompact
import com.newoether.agora.util.Constants

private const val CONTEXT_SUMMARY_ID_PREFIX = "context_summary_"
private const val API_INITIAL_USER_ID_PREFIX = "api_initial_user_"
private const val API_COMPACT_CONTINUATION_TEXT = "Please continue."
internal const val CONTEXT_SUMMARY_OPEN_TAG = "<context_summary>"
internal const val CONTEXT_SUMMARY_CLOSE_TAG = "</context_summary>"

/**
 * Non-destructive Compact projection. The nearest successful Compact is the logical context start.
 * Generic callers receive the raw summary text; request-only markup is added by [prepareMessages].
 */
fun applyNearestContextCompact(messages: List<ChatMessage>): List<ChatMessage> =
    projectNearestContextCompact(messages, markSummaryForApi = false)

private fun projectNearestContextCompact(
    messages: List<ChatMessage>,
    markSummaryForApi: Boolean,
    appendContinuationForApi: Boolean = false,
): List<ChatMessage> {
    // A failed, stopped, or in-flight Compact is durable UI history, but it never summarizes
    // anything and therefore has no Provider-context meaning. Keeping it in the wire history can
    // also leave a generation request ending in an assistant row after automatic fallback.
    val hasNonSuccessfulCompact = messages.any {
        it.isContextCompact() && !it.isSuccessfulContextCompact()
    }
    val providerVisible = if (hasNonSuccessfulCompact) {
        messages.filterNot { it.isContextCompact() && !it.isSuccessfulContextCompact() }
    } else {
        messages
    }
    val index = providerVisible.indexOfLast(ChatMessage::isSuccessfulContextCompact)
    if (index < 0) return providerVisible
    val compact = providerVisible[index]
    val projectedText = if (markSummaryForApi) {
        buildString {
            append(CONTEXT_SUMMARY_OPEN_TAG)
            append('\n')
            append(compact.text.trim())
            append('\n')
            append(CONTEXT_SUMMARY_CLOSE_TAG)
            if (appendContinuationForApi) {
                append("\n\n")
                append(API_COMPACT_CONTINUATION_TEXT)
            }
        }
    } else {
        compact.text
    }
    return buildList(providerVisible.size - index) {
        add(
            compact.copy(
                id = "$CONTEXT_SUMMARY_ID_PREFIX${compact.id}",
                text = projectedText,
                participant = Participant.USER,
            )
        )
        addAll(providerVisible.drop(index + 1))
    }
}

/**
 * Canonical provider-visible context before applying the configured window. Compact eligibility,
 * the composer usage indicator, and provider rollout share this projection without request markup.
 */
fun canonicalContextMessages(messages: List<ChatMessage>): List<ChatMessage> =
    canonicalContextMessages(messages, markSummaryForApi = false)

private fun canonicalContextMessages(
    messages: List<ChatMessage>,
    markSummaryForApi: Boolean,
    appendContinuationForApi: Boolean = false,
): List<ChatMessage> {
    val compacted = projectNearestContextCompact(
        messages = messages,
        markSummaryForApi = markSummaryForApi,
        appendContinuationForApi = appendContinuationForApi,
    )
    val canonical = validateToolMessages(
        stripEmptyTurns(compacted.distinctBy(ChatMessage::id))
    )
    return stripEmptyTurns(mergeConsecutiveSameRole(canonical))
}

/** Full fail-closed message preparation pipeline shared by every provider. */
fun prepareMessages(messages: List<ChatMessage>, contextTokenBudget: Int): List<ChatMessage> {
    val previous = messages.getOrNull(messages.lastIndex - 1)
    val prompt = messages.lastOrNull()?.takeIf {
        it.id == "$API_INITIAL_USER_ID_PREFIX${previous?.id.orEmpty()}" &&
            it.parentId == previous?.id &&
            it.participant == Participant.USER
    }
    val history = if (prompt == null) messages else messages.dropLast(1)
    val appendContinuationForApi = shouldAppendCompactContinuation(messages)
    return stripEmptyTurns(
        mergeConsecutiveSameRole(
            enforcePayloadBudget(
                limitContext(
                    canonicalContextMessages(
                        messages = history,
                        markSummaryForApi = true,
                        appendContinuationForApi = appendContinuationForApi,
                    ),
                    contextTokenBudget,
                )
            )
        )
    ) + listOfNotNull(prompt)
}

/**
 * Hard sliding-window cap on total text chars sent to an LLM.
 *
 * [limitContext] deliberately keeps an oversized newest unit whole, so a single huge
 * pasted input (e.g. millions of tokens) still reaches serialization. Streaming request
 * bodies bound transient memory, but a tens-of-MB request is still a process-death and
 * cost risk, so truncate the oldest non-protocol text instead. Tool/thought segments are
 * never touched: pairing and thought signatures stay valid.
 */
fun enforcePayloadBudget(
    messages: List<ChatMessage>,
    maxChars: Int = Constants.MAX_API_PAYLOAD_CHARS,
): List<ChatMessage> {
    if (messages.isEmpty()) return messages
    var total = 0L
    for (message in messages) {
        if (!message.isToolProtocolMessage()) total += message.text.length
        if (total > maxChars) break
    }
    if (total <= maxChars) return messages

    var over = total - maxChars
    return messages.map { message ->
        if (over <= 0L || message.isToolProtocolMessage() || message.text.isEmpty()) {
            message
        } else {
            val cut = over.coerceAtMost(message.text.length.toLong()).toInt()
            over -= cut
            if (cut >= message.text.length) {
                message.copy(text = "[…truncated for context size…]")
            } else {
                val keep = (message.text.length - cut)
                    .coerceAtMost(maxChars.coerceAtMost(message.text.length))
                message.copy(
                    text = "[…truncated $cut chars for context size…]\n" +
                        message.text.takeLast(keep.coerceAtLeast(0)),
                )
            }
        }
    }
}

private fun shouldAppendCompactContinuation(messages: List<ChatMessage>): Boolean {
    val compactIndex = messages.indexOfLast(ChatMessage::isSuccessfulContextCompact)
    if (compactIndex < 0) return false
    val adjacentMessage = messages.getOrNull(compactIndex + 1) ?: return true
    return adjacentMessage.participant != Participant.USER ||
        adjacentMessage.isToolProtocolMessage() ||
        adjacentMessage.isContextCompact()
}
