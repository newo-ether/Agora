package com.newoether.agora.data.repository

import com.newoether.agora.data.DEFAULT_SUBAGENT_MAX_TOTAL
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.ConversationSettingsTransferEntity
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.SubagentCompletion
import com.newoether.agora.model.SubagentSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Presentation only: activity remains owned by generation, automation and delegation Jobs. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun ConversationRepository.observeDrawerGeneratingConversationIds(
    foregroundIds: Flow<Set<String>>,
    automationIds: Flow<Set<String>>,
): Flow<Set<String>> = combine(foregroundIds, automationIds, subagentJobs, subagentGenerationIds) { foreground, automation, jobs, admitted ->
    foreground + automation + jobs.keys + admitted
}.distinctUntilChanged().flatMapLatest { activeIds ->
    if (activeIds.isEmpty()) flowOf(emptySet())
    else chatDao.observeSubagentParentIds(activeIds).map { parentIds -> activeIds + parentIds }
}.distinctUntilChanged()

suspend fun ConversationRepository.pendingSubagentResults(parentId: String) =
    chatDao.pendingSubagentResults(parentId)

fun ConversationRepository.observeSubagents(parentId: String): Flow<List<SubagentSummary>> =
    combine(chatDao.observeSubagents(parentId), subagentJobs, subagentGenerationIds) { children, jobs, admitted ->
        children.map { child ->
            if (child.id in jobs || child.id in admitted) child.copy(status = "running") else child
        }
    }.distinctUntilChanged()
fun ConversationRepository.observeParentConversationId(id: String): Flow<String?> =
    chatDao.observeParentConversationId(id)
suspend fun ConversationRepository.getSubagentIds(parentId: String): List<String> =
    chatDao.getSubagentIds(parentId)

suspend fun ConversationRepository.requireSubagentToolOwner(identity: RunEffectIdentity) {
    check(chatDao.isSubagentToolOwnerActive(identity.conversationId, identity.runId, identity.pass)) {
        "Subagent tools require the exact active parent Run and pass"
    }
}

/** Child creation, capacity and parent authorization share one Room transaction. */
suspend fun ConversationRepository.createSubagent(
    identity: RunEffectIdentity, child: ChatEntity, settingsJson: String?,
    maxSubagents: Int = DEFAULT_SUBAGENT_MAX_TOTAL,
) = withProviderContextSnapshot {
    requireSubagentToolOwner(identity)
    require(child.parentConversationId == identity.conversationId)
    require(maxSubagents > 0) { "Subagent count limit must be positive" }
    check(chatDao.countSubagents(identity.conversationId) < maxSubagents) {
        "The parent conversation has reached its Subagent count limit ($maxSubagents)"
    }
    check(chatDao.getConversation(child.id) == null)
    chatDao.upsertConversation(child)
    chatDao.upsertConversationSettingsTransfer(ConversationSettingsTransferEntity(child.id, settingsJson))
}

/** Tool-demanded diagnostics do not widen the ordinary payload-free capsule observation. */
internal suspend fun ConversationRepository.subagentFailureDetail(parentId: String, childId: String): SubagentCompletion? {
    val cached = runCatching { SubagentCompletion.decode(chatDao.getSubagentCompletionJson(childId, parentId)) }
        .getOrNull()?.takeIf { it.status == "failed" } ?: return null
    if (!cached.errorText.isNullOrBlank()) return cached
    return cached.modelMessageId?.let { subagentTerminalCompletion(childId, it) }?.takeIf { it.status == "failed" }
}

suspend fun ConversationRepository.recordSubagentCompletion(
    childId: String, parentId: String, completion: SubagentCompletion,
): Boolean = chatDao.setSubagentResult(
    childId, parentId, SubagentCompletion.encode(completion), completion.id, completion.status,
    System.currentTimeMillis(),
) == 1

suspend fun ConversationRepository.consumeSubagentCompletions(
    identity: RunEffectIdentity, receipts: Map<String, String>,
) = withProviderContextSnapshot {
    requireSubagentToolOwner(identity)
    receipts.forEach { (id, resultId) ->
        check(chatDao.acknowledgeSubagentResult(id, identity.conversationId, resultId, System.currentTimeMillis()) == 1) {
            "Subagent result was already consumed or replaced"
        }
    }
}

/** User dismissal consumes only still-current receipts, never a replacement result. */
suspend fun ConversationRepository.dismissSubagentResults(parentId: String, receipts: Map<String, String>) =
    withProviderContextSnapshot {
        receipts.forEach { (id, resultId) ->
            chatDao.acknowledgeSubagentResult(id, parentId, resultId, System.currentTimeMillis())
        }
    }

/** Revalidate claimed notifications before normal admission; committed waits win over the queue. */
internal suspend fun ConversationRepository.consumedSubagentResults(
    parentId: String, receipts: Set<Pair<String, String>>,
): Set<Pair<String, String>> = withProviderContextSnapshot {
    receipts.filter { (id, resultId) ->
        val child = chatDao.getConversation(id)
        child == null || child.parentConversationId != parentId || child.subagentResultId != resultId ||
            child.subagentDeliveredResultId == resultId
    }.toSet()
}

internal suspend fun ConversationRepository.persistSubagentTerminal(
    childId: String, at: Long, terminalMessageId: String? = null,
) {
    val messageId = terminalMessageId ?: return
    val parentId = chatDao.getConversation(childId)?.parentConversationId ?: return
    // The delegation worker owns completion across Compact and ordinary Run handoffs.
    if (subagentJobs.value[childId] != null) return
    val completion = subagentTerminalCompletion(childId, messageId) ?: return
    chatDao.setSubagentResult(childId, parentId, SubagentCompletion.encode(completion), completion.id, completion.status, at)
}

/** Read-only exact terminal projection, shared with selected-family validation. */
internal suspend fun ConversationRepository.subagentTerminalCompletion(
    childId: String, terminalMessageId: String,
): SubagentCompletion? {
    if (terminalMessageId.startsWith(com.newoether.agora.util.Constants.COMPACT_MSG_PREFIX)) return null
    val message = chatDao.getMessage(terminalMessageId) ?: return null
    if (message.conversationId != childId || message.participant != com.newoether.agora.model.Participant.MODEL) return null
    val run = chatDao.getRun(message.runId) ?: return null
    if (run.conversationId != childId || !run.status.isTerminal || run.legacyAmbiguous ||
        run.endReason == com.newoether.agora.model.RunEndReason.CONTINUATION_PENDING) return null
    val status = when (message.status) {
        com.newoether.agora.model.MessageStatus.SUCCESS -> "completed"
        com.newoether.agora.model.MessageStatus.STOPPED ->
            if (run.endReason == com.newoether.agora.model.RunEndReason.PROCESS_RECOVERED) "failed" else "stopped"
        com.newoether.agora.model.MessageStatus.ERROR -> "failed"
        else -> return null
    }
    val segments = message.toolCallJson?.let { raw ->
        runCatching { kotlinx.serialization.json.Json.decodeFromString<List<com.newoether.agora.model.MessageSegment>>(raw) }
            .getOrNull()
    }.orEmpty()
    val failure = com.newoether.agora.model.terminalErrorSegment(
        message.status, message.participant, message.text, segments,
    )
    return SubagentCompletion(message.id, status, message.text, message.id,
        errorText = if (status == "failed") generationErrorFormatter(failure?.content.orEmpty()) else null,
        errorCode = failure?.errorCode)
}

internal suspend fun ConversationRepository.commitSubagentWaitReceipts(
    messages: List<com.newoether.agora.data.local.MessageEntity>,
    receipts: MutableMap<String, String>,
) {
    messages.forEach { message ->
        val raw = message.toolCallJson ?: return@forEach
        if (message.participant != com.newoether.agora.model.Participant.USER) return@forEach
        val call = runCatching {
            kotlinx.serialization.json.Json.decodeFromString<List<com.newoether.agora.model.MessageSegment>>(raw)
        }.getOrNull()?.singleOrNull { it.type == "tool" && com.newoether.agora.tool.BuiltInToolNames.canonical(it.toolName.orEmpty()) == "agent_wait" } ?: return@forEach
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(checkNotNull(call.toolResult)) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return@forEach
        val returned = root["subagent_receipts"] as? kotlinx.serialization.json.JsonObject ?: return@forEach
        returned.forEach { (id, value) ->
            val resultId = (value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: error("Invalid result receipt")
            // Duplicate wait calls in one tool batch are idempotent; replacement still fails closed.
            val child = checkNotNull(chatDao.getConversation(id))
            check(child.parentConversationId == message.conversationId && child.subagentResultId == resultId)
            if (child.subagentDeliveredResultId != resultId) {
                check(chatDao.acknowledgeSubagentResult(id, message.conversationId, resultId, System.currentTimeMillis()) == 1)
            }
            receipts[id] = resultId
        }
    }
}

/** The durable wait result is authoritative even when process notification fails or is cancelled. */
internal suspend fun ConversationRepository.notifySubagentWaitCommitted(parentId: String, receipts: Map<String, String>) {
    try {
        onSubagentWaitCommitted?.invoke(parentId, receipts)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        com.newoether.agora.util.DebugLog.w("Subagents", "Committed wait queue notification failed", error)
    }
}

/** Called only inside the accepted-input graph transaction; mismatch rolls the graph back. */
internal suspend fun ConversationRepository.commitSubagentReceipts(
    parentId: String, source: MessageSource?, at: Long,
) {
    if (source?.kind != MessageSource.Kind.SUBAGENT) return
    source.subagentResults.forEach { (id, resultId) ->
        check(chatDao.acknowledgeSubagentResult(id, parentId, resultId, at) == 1) {
            "Subagent result was already consumed or replaced"
        }
    }
}
