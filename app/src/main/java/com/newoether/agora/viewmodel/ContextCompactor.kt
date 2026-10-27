package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.CONTEXT_SUMMARY_CLOSE_TAG
import com.newoether.agora.api.util.CONTEXT_SUMMARY_OPEN_TAG
import com.newoether.agora.api.util.contextWindowUsage
import com.newoether.agora.api.util.tokens.ContextCostModel
import com.newoether.agora.api.util.tokens.ContextCostModels
import com.newoether.agora.api.util.projectGenerationStatusesForApi
import com.newoether.agora.api.util.splitContextForCompactRetention
import com.newoether.agora.api.util.stripEmptyTurns
import com.newoether.agora.api.util.validateToolMessages
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.model.isSuccessfulContextCompact
import com.newoether.agora.util.Constants

data class CompactRequest(
    val model: String,
    val prompt: String,
    val retainLogicalMessages: Int,
    val replaceMessageId: String? = null,
    // When true the request keeps the conversation's ordinary system prompt and moves the
    // Compact Prompt into the user message. Default false preserves the legacy behaviour for
    // every construction site that has not opted in explicitly.
    val preserveSystemPrompt: Boolean = false,
)

/** Provider-equivalent split input without role coalescing away durable graph ids. */
internal fun compactSplitMessages(messages: List<ChatMessage>): List<ChatMessage> =
    stripEmptyTurns(
        validateToolMessages(messages.distinctBy(ChatMessage::id))
    )

internal fun normalizeContextCompactOutput(text: String): String {
    val firstContentIndex = text.indexOfFirst { !it.isWhitespace() }
    if (firstContentIndex < 0) return text
    val candidate = text.substring(firstContentIndex)
    if (CONTEXT_SUMMARY_OPEN_TAG.startsWith(candidate)) return ""
    if (!candidate.startsWith(CONTEXT_SUMMARY_OPEN_TAG)) return text

    val body = candidate
        .substring(CONTEXT_SUMMARY_OPEN_TAG.length)
        .removePrefix("\r\n")
        .removePrefix("\n")
    val closingIndex = body.lastIndexOf(CONTEXT_SUMMARY_CLOSE_TAG)
    if (
        closingIndex >= 0 &&
        body.substring(closingIndex + CONTEXT_SUMMARY_CLOSE_TAG.length).isBlank()
    ) {
        return body.substring(0, closingIndex).trimEnd()
    }

    val partialClosingLength = minOf(body.length, CONTEXT_SUMMARY_CLOSE_TAG.length - 1)
        .downTo(1)
        .firstOrNull { length ->
            CONTEXT_SUMMARY_CLOSE_TAG.startsWith(body.takeLast(length))
        }
        ?: return body
    return body
        .dropLast(partialClosingLength)
        .removeSuffix("\r\n")
        .removeSuffix("\n")
}

internal fun buildPersistedCompactText(
    summary: String,
    retainedMessages: List<ChatMessage>,
): String = buildString {
    append(summary.trim())
    if (retainedMessages.isEmpty()) return@buildString
    append("\n\n--- Recent messages (verbatim) ---")
    retainedMessages.forEach { message ->
        append("\n\n")
        when {
            message.id.startsWith(Constants.TOOL_MSG_PREFIX) -> {
                val calls = message.segments.orEmpty().filter { it.type == "tool" }
                if (calls.isEmpty()) {
                    append("[Assistant tool request]\n")
                    append(message.text)
                } else {
                    calls.forEachIndexed { index, call ->
                        if (index > 0) append("\n\n")
                        append("[Assistant tool request: ")
                        append(call.toolName?.takeIf(String::isNotBlank) ?: "unknown")
                        append("]\n")
                        append(call.toolArgs.orEmpty())
                    }
                }
            }
            message.id.startsWith(Constants.RESULT_MSG_PREFIX) -> {
                val results = message.segments.orEmpty().filter { it.type == "tool" }
                if (results.isEmpty()) {
                    append("[Tool result]\n")
                    append(message.text)
                } else {
                    results.forEachIndexed { index, result ->
                        if (index > 0) append("\n\n")
                        append("[Tool result: ")
                        append(result.toolName?.takeIf(String::isNotBlank) ?: "unknown")
                        append("]\n")
                        append(result.toolResult.orEmpty())
                    }
                }
            }
            message.participant == Participant.USER -> {
                append("[User]\n")
                append(message.text)
            }
            else -> {
                append("[Assistant]\n")
                append(message.text)
            }
        }
        if (message.images.isNotEmpty()) {
            append("\n[Attached images: ")
            append(message.images.size)
            append(']')
        }
    }
}

enum class CompactFailureReason {
    SELECT_MODEL,
    EMPTY_PROMPT,
    INVALID_RETAIN_COUNT,
    SETUP_UNAVAILABLE,
    SETUP_FAILED,
    NOT_READY_TO_RECOMPACT,
    BOUNDARY_DISAPPEARED,
    GENERATION_BUSY,
    GENERATION_NOT_STARTED,
    MESSAGE_DISAPPEARED,
    OPEN_CONVERSATION,
    GENERIC,
}

sealed interface CompactResult {
    data class Created(val messageId: String) : CompactResult
    data class Stopped(val messageId: String) : CompactResult
    data object NotNeeded : CompactResult
    data class Failed(
        val reason: CompactFailureReason,
        val externalDetail: String? = null,
        val messageId: String? = null,
    ) : CompactResult
}

/** Narrow operation port used by the application-level Compact effect executor. */
internal fun interface ContextCompactOperation {
    suspend fun automaticNeeded(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
    ): Boolean
}

internal fun automaticCompactTokenThreshold(
    contextLimit: Int,
    thresholdPercent: Int,
): Int {
    val normalizedLimit = contextLimit.coerceAtLeast(1)
    val normalizedPercent = thresholdPercent.coerceIn(50, 100)
    return ((normalizedLimit.toLong() * normalizedPercent + 99L) / 100L)
        .coerceIn(1L, Int.MAX_VALUE.toLong())
        .toInt()
}

internal fun automaticCompactNeeded(
    entities: List<MessageEntity>,
    selectedChildren: Map<String?, String>,
    contextLimit: Int,
    retainLogicalMessages: Int,
    generationErrorFormatter: (String) -> String,
    includeStoredTranscriptions: Boolean = false,
    fixedTokenCost: Int = 0,
    includeAssistantReasoning: Boolean = false,
    userPrepend: String? = null,
    userPostpend: String? = null,
    assistantPrepend: String? = null,
    assistantPostpend: String? = null,
    costs: ContextCostModel = ContextCostModel.Default,
): Boolean {
    val selectedPath = ConversationUiState.resolvePath(
        allMessages = entities.map { it.toUiChatMessage { text -> text } },
        streamingMsg = null,
        selectedChildren = selectedChildren,
    )
    val entitiesById = entities.associateBy(MessageEntity::id)
    return automaticCompactNeeded(
        path = ApiPathAssembler.assemble(
            selectedPath.mapNotNull { entitiesById[it.id] },
            entities,
        ).let { entities ->
            projectProviderMessages(entities, includeStoredTranscriptions)
        }.let { messages ->
            projectGenerationStatusesForApi(messages, generationErrorFormatter)
        },
        contextLimit = contextLimit,
        retainLogicalMessages = retainLogicalMessages,
        fixedTokenCost = fixedTokenCost,
        includeAssistantReasoning = includeAssistantReasoning,
        userPrepend = userPrepend,
        userPostpend = userPostpend,
        assistantPrepend = assistantPrepend,
        assistantPostpend = assistantPostpend,
        // Stored descriptions remove only their own images before admission accounting.
        includeImages = true,
        costs = costs,
    )
}

internal fun automaticCompactNeeded(
    path: List<ChatMessage>,
    contextLimit: Int,
    retainLogicalMessages: Int,
    fixedTokenCost: Int = 0,
    includeAssistantReasoning: Boolean = false,
    userPrepend: String? = null,
    userPostpend: String? = null,
    assistantPrepend: String? = null,
    assistantPostpend: String? = null,
    includeImages: Boolean = true,
    costs: ContextCostModel = ContextCostModel.Default,
): Boolean {
    if (path.isEmpty() || retainLogicalMessages < 0) return false
    val semanticPath = path.filterNot { it.isContextCompact() && !it.isSuccessfulContextCompact() }
    val nearest = semanticPath.indexOfLast(ChatMessage::isSuccessfulContextCompact)
    val compactablePath = compactSplitMessages(
        semanticPath.drop(nearest.coerceAtLeast(-1) + 1),
    )
    val split = splitContextForCompactRetention(compactablePath, retainLogicalMessages)
    return split.prefix.isNotEmpty() &&
        contextWindowUsage(
            projectGenerationInputMessages(
                messages = semanticPath,
                includeImages = includeImages,
                userPrepend = userPrepend,
                userPostpend = userPostpend,
                assistantPrepend = assistantPrepend,
                assistantPostpend = assistantPostpend,
            ),
            contextLimit.coerceAtLeast(1),
            fixedTokenCost = fixedTokenCost,
            includeAssistantReasoning = includeAssistantReasoning,
            costs = costs,
        ).estimatedTokenCount >=
        contextLimit.coerceAtLeast(1)
}

/** Non-destructive context compaction. Original messages remain in the graph. */
internal class ContextCompactor(
    private val conversations: ConversationRepository,
    private val generationErrorFormatter: (String) -> String,
    appContext: android.content.Context? = null,
    private val contextLoader: DurableSelectedContextLoader =
        DurableSelectedContextLoader(conversations, generationErrorFormatter, appContext = appContext),
) : ContextCompactOperation {
    override suspend fun automaticNeeded(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
    ): Boolean {
        if (!config.enabled) return false
        val threshold = automaticCompactTokenThreshold(
            contextLimit,
            config.thresholdPercent,
        )
        val mainPrompt = config.mainPromptTemplate?.let {
            resolveGenerationPromptTemplate(it, config.mainModelId.orEmpty())
        }
        val path = contextLoader.load(
            DurableSelectedContextRequest(
                conversationId = conversationId,
                followSelectedBranch = true,
                includeStoredTranscriptions =
                    config.mainImageTranscriptionEnabled,
            ),
        ).messages
        return automaticCompactNeeded(
            path = path,
            contextLimit = threshold,
            retainLogicalMessages = config.request.retainLogicalMessages,
            fixedTokenCost = config.fixedTokenCost,
            costs = ContextCostModels.forModel(config.mainModelId),
            includeAssistantReasoning = config.includeAssistantReasoning,
            userPrepend = mainPrompt?.userPrepend ?: config.userPrepend,
            userPostpend = mainPrompt?.userPostpend ?: config.userPostpend,
            assistantPrepend = mainPrompt?.assistantPrepend ?: config.assistantPrepend,
            assistantPostpend = mainPrompt?.assistantPostpend ?: config.assistantPostpend,
            includeImages = true,
        )
    }
}
