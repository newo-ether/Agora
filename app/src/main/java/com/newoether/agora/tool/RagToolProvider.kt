package com.newoether.agora.tool

import com.newoether.agora.api.EmbeddingClient
import com.newoether.agora.api.LlamaEngine
import com.newoether.agora.api.ProviderDefaults
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Conversation-history tools: search / list / read past conversations.
 *
 * Owns both the tool definitions AND their execution (semantic + keyword search,
 * branch reconstruction, pagination). Execution depends on [ConversationRepository]
 * and embedding search.
 */
private const val MIN_SEMANTIC_SEARCH_TEXT_LENGTH = 10

class RagToolProvider(
    private val conversations: ConversationRepository,
    private val generatingConversationIds: () -> Set<String> = { emptySet() },
) : ToolProvider {
    private val semanticSelector = BoundedSemanticEmbeddingSelector()

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.accessPastConversations) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "conversation_search",
                description = "Search past top-level conversations for relevant information. Subagent conversations are excluded. Use this to recall facts, decisions, or context from previous discussions.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "query" to ToolProperty("string", "The search query to find relevant past conversations."),
                        "limit" to ToolProperty("integer", "Maximum number of results (1-20, default 10).")
                    ),
                    required = listOf("query")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "conversation_list",
                description = "List past top-level conversations, excluding subagents. Use this to browse conversation history and find conversations to read. Returns conversation IDs, titles, last-updated timestamps, and is_generating (a boolean snapshot of current generation activity).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "order" to ToolProperty("string", "Sort order by last updated time: 'asc' (oldest first) or 'desc' (newest first). Default: 'desc'."),
                        "limit" to ToolProperty("integer", "Maximum conversations per page (1-50, default 20)."),
                        "offset" to ToolProperty("integer", "Number of conversations to skip for pagination (default 0).")
                    ),
                    required = emptyList()
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "conversation_read",
                description = "Read a specific top-level conversation or a subagent owned by this conversation by its ID. Other conversations' subagents are inaccessible. Shows the selected message branch as a linear list with page controls. Use this after conversation_list or conversation_search to read a conversation of interest. Each message includes message_id, participant, text, timestamp, status, run_id and run_sequence; terminal failures include error and error_code when available.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "conversation_id" to ToolProperty("string", "The conversation ID to read (from conversation_list or conversation_search results)."),
                        "offset" to ToolProperty("integer", "Ordinary messages to skip (default 0); unaffected by detail flags. Prefer after_message_id when continuing a page."),
                        "after_message_id" to ToolProperty("string", "Continue after this message on the currently selected branch. Overrides offset. Use next_after_message_id from the previous page; an absent or deleted anchor returns cursor_not_found."),
                        "limit" to ToolProperty("integer", "Maximum ordinary messages per page (1-100, default 50). Tool calls are nested and do not consume this limit."),
                        "show_tool_calls" to ToolProperty("boolean", "Include stored tool calls and results inside each message (default false). Does not change message pagination."),
                        "show_thinking" to ToolProperty("boolean", "Include stored readable thinking inside each message (default false). Does not change message pagination."),
                        "tool_offset" to ToolProperty("integer", "Tool calls to skip within each message when show_tool_calls=true (default 0). Separate from message offset."),
                        "tool_limit" to ToolProperty("integer", "Maximum tool calls per message (1-100, default 20). Follow tool_calls_next_offset to read more tools from the same message."),
                    ),
                    required = listOf("conversation_id")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean =
        BuiltInToolNames.canonical(name) in setOf("conversation_search", "conversation_list", "conversation_read")

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String = when (BuiltInToolNames.canonical(name)) {
        "conversation_search" -> executeSearchConversations(arguments, ctx)
        "conversation_list" -> executeListConversations(arguments, ctx)
        "conversation_read" -> executeReadConversation(arguments, ctx)
        else -> "Unknown tool: $name"
    }

    private data class SearchWindowPlan(
        val conversationId: String,
        val conversationTitle: String,
        val messageIds: List<String>,
        val topScore: Float,
        val matchCount: Int
    )

    private data class SearchWindow(
        val conversationId: String,
        val conversationTitle: String,
        val messages: List<MessageEntity>,
        val topScore: Float,
        val matchCount: Int,
    )

    private suspend fun executeSearchConversations(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val query = (args["query"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "conversation_search"); put("error", "no_query") }.toString()
        val limit = ((args["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: ctx.searchMatchLimit).coerceIn(1, 30)
        val n = ((args["context_window"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: ctx.searchContextWindow).coerceIn(4, 32)
        val halfN = n / 2
        val maxWindowSize = n * 3
        val totalCap = 200

        return try {
            // Step 1: Search — normalize to List<Pair<MessageEntity, Float>>
            val scoredResults: List<Pair<MessageEntity, Float>> = if (ctx.modelSearchMethod == Constants.SEARCH_METHOD_RAG && ctx.activeEmbeddingConfig != null) {
                semanticSearch(query, limit, ctx)
                    .filter { it.second >= ctx.ragThreshold }
            } else {
                conversations.searchMessages(query, limit).map { it to 1.0f }
            }
            if (scoredResults.isEmpty())
                return buildJsonObject { put("type", "conversation_search"); put("query", query); put("error", "no_results") }.toString()

            val scoreByMessageId = scoredResults.associate { it.first.id to it.second }
            val matchesByConv = scoredResults
                .groupBy({ it.first.conversationId }, { it.first.id })
            if (matchesByConv.isEmpty())
                return buildJsonObject { put("type", "conversation_search"); put("query", query); put("error", "no_results") }.toString()

            // Step 2-4: For each conversation, build branch, expand windows, merge
            val allWindows = mutableListOf<SearchWindowPlan>()

            for ((convId, matchIds) in matchesByConv) {
                val conversation = conversations.getSearchableConversation(convId) ?: continue
                val topology = conversations.getMessageTopologySnapshot(convId)
                    .filter { it.participant in listOf(Participant.USER, Participant.MODEL) }

                // Build selected branch as indexed list
                val branch = buildSelectedTopologyBranch(topology, conversation.selectedBranchesJson)
                    .filterNot { isSyntheticMessageId(it.id) }
                val indexMap = branch.withIndex().associate { (i, m) -> m.id to i }
                val branchMatchIds = matchIds.filter { it in indexMap }.toSet()

                // For each match, expand window N/2 before and N/2 after
                val windows = mutableListOf<Pair<IntRange, Float>>() // (range, score)
                for (matchId in matchIds) {
                    val centerIdx = indexMap[matchId] ?: continue
                    val score = scoreByMessageId[matchId] ?: 1.0f
                    val before = halfN.coerceAtMost(centerIdx)
                    val after = halfN.coerceAtMost(branch.size - 1 - centerIdx)
                    // Asymmetric fill: compensate short sides with extra from the other side
                    val extraBefore = (halfN - before).coerceAtMost(branch.size - 1 - centerIdx - after)
                    val extraAfter = (halfN - after - extraBefore).coerceAtLeast(0).coerceAtMost(centerIdx - before)
                    val start = (centerIdx - before - extraAfter).coerceAtLeast(0)
                    val end = (centerIdx + after + extraBefore).coerceAtMost(branch.size - 1)
                    windows.add((start..end) to score)
                }

                // Merge overlapping windows within this conversation
                val sorted = windows.sortedByDescending { it.second }
                val merged = mutableListOf<Pair<IntRange, Float>>()
                for ((range, score) in sorted) {
                    var mergedRange = range
                    val overlapIdx = merged.indexOfFirst { (existing, _) ->
                        mergedRange.first <= existing.last + 1 && existing.first <= mergedRange.last + 1
                    }
                    if (overlapIdx >= 0) {
                        val (existing, existingScore) = merged[overlapIdx]
                        mergedRange = (minOf(mergedRange.first, existing.first)..maxOf(mergedRange.last, existing.last))
                        merged[overlapIdx] = mergedRange to maxOf(score, existingScore)
                    } else {
                        merged.add(mergedRange to score)
                    }
                }
                // Convert to SearchWindow, apply cap
                for ((range, score) in merged) {
                    var cappedRange = range
                    if (range.last - range.first + 1 > maxWindowSize) {
                        val centerId = branchMatchIds.maxByOrNull { scoreByMessageId[it] ?: 0f }
                        val centerIdx = if (centerId != null) indexMap[centerId]!! else (range.first + range.last) / 2
                        cappedRange = ((centerIdx - halfN).coerceAtLeast(range.first)..(centerIdx + halfN).coerceAtMost(range.last))
                    }
                    val windowMsgIds = branch.subList(cappedRange.first, cappedRange.last + 1).map { it.id }.toSet()
                    val matchedInWindow = branchMatchIds.count { it in windowMsgIds }
                    allWindows.add(SearchWindowPlan(
                        conversationId = convId,
                        conversationTitle = conversation.title,
                        messageIds = cappedRange
                            .map { branch[it].id }
                            .filterNot(::isSyntheticMessageId),
                        topScore = score,
                        matchCount = matchedInWindow
                    ))
                }
            }

            // Step 5: Sort by topScore desc, cap total messages
            val finalWindows = mutableListOf<SearchWindow>()
            var totalMessages = 0
            for (window in allWindows.sortedByDescending { it.topScore }) {
                if (totalMessages >= totalCap) break
                val available = totalCap - totalMessages
                val selectedIds = window.messageIds.take(available)
                val messagesById = conversations.getMessagesByIds(selectedIds).associateBy { it.id }
                val hydratedMessages = selectedIds.mapNotNull(messagesById::get)
                finalWindows.add(
                    SearchWindow(
                        conversationId = window.conversationId,
                        conversationTitle = window.conversationTitle,
                        messages = hydratedMessages,
                        topScore = window.topScore,
                        matchCount = window.matchCount,
                    )
                )
                totalMessages += selectedIds.size
            }

            // Step 6: Return text and identity only; stored details belong to conversation_read.
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            val resultArray = buildJsonArray {
                for (window in finalWindows) {
                    add(buildJsonObject {
                        put("title", window.conversationTitle)
                        put("conversation_id", window.conversationId)
                        put("top_score", window.topScore)
                        put("match_count", window.matchCount)
                        putJsonArray("messages") {
                            for (message in window.messages) add(buildJsonObject {
                                put("message_id", message.id)
                                put("participant", message.participant.name)
                                put("text", message.text)
                                put("status", message.status.name)
                                put("run_id", message.runId)
                                put("run_sequence", message.runSequence)
                                put("timestamp", dateFormat.format(java.util.Date(message.timestamp)))
                            })
                        }
                    })
                }
            }
            buildJsonObject {
                put("type", "conversation_search")
                put("query", query)
                put("count", resultArray.size)
                put("results", resultArray)
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "conversation_search")
                put("query", query)
                put("error", "search_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    private suspend fun executeListConversations(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val order = ((args["order"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "desc").lowercase()
        val limit = ((args["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val offset = ((args["offset"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0).coerceAtLeast(0)

        return try {
            val total = conversations.getSearchableConversationCount()
            val page = conversations.getSearchableConversationsPage(
                offset = offset,
                limit = limit,
                descending = order == "desc",
            )
            val hasMore = offset + limit < total
            val activeIds = generatingConversationIds()

            buildJsonObject {
                put("type", "conversation_list")
                put("total", total)
                put("offset", offset)
                put("limit", limit)
                put("has_more", hasMore)
                putJsonArray("conversations") {
                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    for (conv in page) {
                        add(buildJsonObject {
                            put("id", conv.id)
                            put("title", conv.title)
                            put("is_generating", conv.id in activeIds)
                            put("timestamp", dateFormat.format(java.util.Date(conv.lastUpdated)))
                        })
                    }
                }
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "conversation_list")
                put("error", "list_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    private suspend fun executeReadConversation(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val conversationId = ((args["conversation_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "").trim()
        if (conversationId.isEmpty()) {
            return buildJsonObject {
                put("type", "conversation_read")
                put("error", "missing_conversation_id")
            }.toString()
        }
        val limit = ((args["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 50).coerceIn(1, 100)
        val requestedOffset = ((args["offset"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val afterMessageId = (args["after_message_id"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        val showToolCalls = (args["show_tool_calls"] as? JsonPrimitive)?.booleanOrNull == true
        val showThinking = (args["show_thinking"] as? JsonPrimitive)?.booleanOrNull == true
        val toolOffset = ((args["tool_offset"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val toolLimit = ((args["tool_limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 20).coerceIn(1, 100)

        return try {
            val conversation = conversations.getSearchableConversation(conversationId, ctx.conversationId)
                ?: return buildJsonObject {
                    put("type", "conversation_read")
                    put("conversation_id", conversationId)
                    put("error", "not_found")
                }.toString()

            val topology = conversations.getMessageTopologySnapshot(conversationId)
                .filter { it.participant in listOf(Participant.USER, Participant.MODEL) }
            val branch = buildSelectedTopologyBranch(topology, conversation.selectedBranchesJson)
                .filterNot { isSyntheticMessageId(it.id) }
            val totalMessages = branch.size
            val offset = if (afterMessageId != null) {
                val anchorIndex = branch.indexOfFirst { it.id == afterMessageId }
                if (anchorIndex < 0) return buildJsonObject {
                    put("type", "conversation_read")
                    put("conversation_id", conversationId)
                    put("error", "cursor_not_found")
                    put("message", "The anchor is absent from the selected branch. Read again from offset 0.")
                }.toString()
                anchorIndex + 1
            } else requestedOffset
            val endOffset = offset.coerceAtMost(totalMessages) + minOf(limit, (totalMessages - offset).coerceAtLeast(0))
            val pageTopology = if (offset < totalMessages) {
                branch.subList(offset, endOffset)
            } else {
                emptyList()
            }
            val pageIds = pageTopology.map { it.id }
            val pageById = conversations.getMessagesByIds(pageIds).associateBy { it.id }
            // A concurrent deletion must not silently advance a cursor past missing content.
            if (pageIds.any { it !in pageById }) return buildJsonObject {
                put("type", "conversation_read")
                put("conversation_id", conversationId)
                put("error", "history_changed")
                put("message", "Messages changed while reading this page. Retry from the same anchor or offset.")
            }.toString()
            val page = pageIds.map { pageById.getValue(it) }
            val projectedMessages = page.map { message ->
                conversations.projectHistoryMessage(message, showToolCalls, showThinking, toolOffset, toolLimit)
            }
            val hasMore = endOffset < totalMessages

            buildJsonObject {
                put("type", "conversation_read")
                put("conversation_id", conversationId)
                put("title", conversation.title)
                put("total_messages", totalMessages)
                put("offset", offset)
                put("limit", limit)
                put("has_more", hasMore)
                if (hasMore) put("next_offset", endOffset) else put("next_offset", JsonNull)
                put("next_after_message_id", page.lastOrNull()?.id ?: afterMessageId)
                putJsonArray("messages") {
                    projectedMessages.forEach { add(it) }
                }
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "conversation_read")
                put("conversation_id", conversationId)
                put("error", "read_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    /**
     * Reconstruct the user-selected message branch for a conversation.
     * Uses selectedBranchesJson (Map<parentId → childId>) to walk from root to leaf.
     */
    private fun buildSelectedTopologyBranch(
        allMessages: List<MessageContextTopology>,
        selectedBranchesJson: String?
    ): List<MessageContextTopology> {
        val selections: Map<String?, String> = try {
            val raw = Json.decodeFromString<Map<String, String>>(selectedBranchesJson ?: "{}")
            raw.mapKeys { if (it.key == "null") null else it.key }
        } catch (_: Exception) { emptyMap() }

        val byParent = allMessages
            .groupBy { it.parentId }
            .mapValues { (_, siblings) ->
                siblings.sortedWith(
                    compareBy<MessageContextTopology> { it.timestamp }.thenBy { it.id },
                )
            }
        val path = mutableListOf<MessageContextTopology>()
        val visited = mutableSetOf<String>()
        var parentId: String? = null
        while (true) {
            val siblings = byParent[parentId] ?: break
            if (siblings.isEmpty()) break
            val selectedId = selections[parentId]
            val visible = siblings.filter {
                !isSyntheticMessageId(it.id)
            }
            val chosen = if (visible.isNotEmpty()) {
                visible.find { it.id == selectedId } ?: visible.last()
            } else {
                siblings.find { it.id == selectedId } ?: siblings.last()
            }
            check(visited.add(chosen.id)) { "Selected message branch contains a cycle" }
            path.add(chosen)
            parentId = chosen.id
        }
        return path
    }

    private fun isSyntheticMessageId(messageId: String): Boolean =
        messageId.startsWith(Constants.TOOL_MSG_PREFIX) ||
            messageId.startsWith(Constants.RESULT_MSG_PREFIX) ||
            messageId.startsWith(Constants.COMPACT_MSG_PREFIX)

    suspend fun semanticSearch(query: String, limit: Int, ctx: GenerationContext): List<Pair<MessageEntity, Float>> = withContext(Dispatchers.IO) {
        val config = ctx.activeEmbeddingConfig
        if (config == null) {
            DebugLog.w("AgoraVM", "GM RAG: no active embedding config")
            return@withContext emptyList()
        }
        val queryEmbedding = if (config.type == com.newoether.agora.data.EmbeddingModelType.LOCAL) {
            if (!LlamaEngine.isModelReady(config.localFilePath)) {
                DebugLog.w("AgoraVM", "GM RAG: local model not ready")
                return@withContext emptyList()
            }
            LlamaEngine.computeEmbedding(query, config.localFilePath)
        } else {
            val apiKey = resolveEmbeddingApiKey(ctx)
            if (apiKey == null) {
                DebugLog.w("AgoraVM", "GM RAG: no API key")
                return@withContext emptyList()
            }
            EmbeddingClient.computeEmbedding(
                text = query,
                apiKey = apiKey,
                model = config.remoteModelName,
                baseUrl = config.remoteBaseUrl.ifBlank { ProviderDefaults.OPENAI_BASE_URL }
            )
        }
        if (queryEmbedding == null) {
            DebugLog.w("AgoraVM", "GM RAG: failed to compute query embedding")
            return@withContext emptyList()
        }

        val selection = semanticSelector.select(
            queryEmbedding = queryEmbedding,
            threshold = ctx.ragThreshold,
            limit = limit,
        ) { afterId, pageLimit ->
            conversations.getEmbeddingSearchPage(
                modelId = config.id,
                afterId = afterId,
                minimumTextLength = MIN_SEMANTIC_SEARCH_TEXT_LENGTH,
                limit = pageLimit,
            )
        }
        DebugLog.d(
            "AgoraVM",
            "GM RAG: scanned=${selection.scannedRows}, invalid=${selection.skippedInvalidRows}, " +
                "query dim=${queryEmbedding.size}, best cosine=${"%.4f".format(selection.bestScore)}",
        )
        if (selection.candidates.isEmpty()) return@withContext emptyList()

        val messagesById = conversations
            .getSearchableMessagesByIds(selection.candidates.map { it.messageId })
            .associateBy { it.id }
        selection.candidates.mapNotNull { candidate ->
            messagesById[candidate.messageId]
                ?.takeIf { it.text.length >= MIN_SEMANTIC_SEARCH_TEXT_LENGTH }
                ?.let { it to candidate.score }
        }
    }

    private fun resolveEmbeddingApiKey(ctx: GenerationContext): String? {
        return ctx.embeddingApiKey.ifBlank { null }
    }
}
