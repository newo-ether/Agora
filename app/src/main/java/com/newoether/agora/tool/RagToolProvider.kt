package com.newoether.agora.tool

import com.newoether.agora.api.EmbeddingClient
import com.newoether.agora.api.LlamaEngine
import com.newoether.agora.api.ProviderDefaults
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.EmbeddingIndexer
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
class RagToolProvider(
    private val conversations: ConversationRepository
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.accessPastConversations) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "search_conversations",
                description = "Search past conversations for relevant information. Use this to recall facts, decisions, or context from previous discussions.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "query" to ToolProperty("string", "The search query to find relevant past conversations."),
                        "limit" to ToolProperty("integer", "Maximum number of results (1-20, default 10).")
                    ),
                    required = listOf("query")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "list_conversations",
                description = "List all past conversations. Use this to browse conversation history and find conversations to read. Returns conversation IDs, titles, and last-updated timestamps.",
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
                name = "read_conversation",
                description = "Read a specific conversation by its ID. Shows the selected message branch as a linear list with page controls. Use this after list_conversations or search_conversations to read a conversation of interest. Each message includes participant, text, and timestamp.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "conversation_id" to ToolProperty("string", "The conversation ID to read (from list_conversations or search_conversations results)."),
                        "offset" to ToolProperty("integer", "Number of messages to skip for pagination (default 0)."),
                        "limit" to ToolProperty("integer", "Maximum messages per page (1-100, default 50).")
                    ),
                    required = listOf("conversation_id")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean =
        name == "search_conversations" || name == "list_conversations" || name == "read_conversation"

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String = when (name) {
        "search_conversations" -> executeSearchConversations(arguments, ctx)
        "list_conversations" -> executeListConversations(arguments, ctx)
        "read_conversation" -> executeReadConversation(arguments, ctx)
        else -> "Unknown tool: $name"
    }

    private data class SearchWindow(
        val conversationId: String,
        val conversationTitle: String,
        val messages: List<MessageEntity>,
        val topScore: Float,
        val matchCount: Int
    )

    private suspend fun executeSearchConversations(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val query = (args["query"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "search_conversations"); put("error", "no_query") }.toString()
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
                return buildJsonObject { put("type", "search_conversations"); put("query", query); put("error", "no_results") }.toString()

            val scoreByMessageId = scoredResults.associate { it.first.id to it.second }
            val matchesByConv = scoredResults
                .groupBy({ it.first.conversationId }, { it.first.id })
            if (matchesByConv.isEmpty())
                return buildJsonObject { put("type", "search_conversations"); put("query", query); put("error", "no_results") }.toString()

            // Step 2-4: For each conversation, build branch, expand windows, merge
            val allWindows = mutableListOf<SearchWindow>()

            for ((convId, matchIds) in matchesByConv) {
                val conversation = conversations.getConversation(convId) ?: continue
                val allMsgs = conversations.getMessagesForConversation(convId).first()
                    .filter { it.participant in listOf(Participant.USER, Participant.MODEL) }

                // Build selected branch as indexed list
                val branch = buildSelectedBranch(allMsgs, conversation.selectedBranchesJson)
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
                    allWindows.add(SearchWindow(
                        conversationId = convId,
                        conversationTitle = conversation.title,
                        messages = cappedRange.map { branch[it] }.filter { it.text.isNotEmpty() && !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) },
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
                if (window.messages.size > available) {
                    finalWindows.add(window.copy(messages = window.messages.take(available)))
                    totalMessages = totalCap
                } else {
                    finalWindows.add(window)
                    totalMessages += window.messages.size
                }
            }

            // Step 6: Format output
            val resultArray = buildJsonArray {
                for (window in finalWindows) {
                    add(buildJsonObject {
                        put("title", window.conversationTitle)
                        put("conversation_id", window.conversationId)
                        put("top_score", window.topScore)
                        put("match_count", window.matchCount)
                        putJsonArray("messages") {
                            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            for (msg in window.messages) {
                                add(buildJsonObject {
                                    put("participant", msg.participant.name)
                                    put("text", msg.text)
                                    put("timestamp", dateFormat.format(java.util.Date(msg.timestamp)))
                                })
                            }
                        }
                    })
                }
            }
            buildJsonObject {
                put("type", "search_conversations")
                put("query", query)
                put("results", resultArray)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "search_conversations")
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
            val allConversations = conversations.getAllConversationsList()
            val sorted = if (order == "desc") allConversations.reversed() else allConversations
            val total = sorted.size
            val page = if (offset < total) {
                sorted.subList(offset, (offset + limit).coerceAtMost(total))
            } else {
                emptyList()
            }
            val hasMore = offset + limit < total

            buildJsonObject {
                put("type", "list_conversations")
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
                            put("timestamp", dateFormat.format(java.util.Date(conv.lastUpdated)))
                        })
                    }
                }
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "list_conversations")
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
                put("type", "read_conversation")
                put("error", "missing_conversation_id")
            }.toString()
        }
        val limit = ((args["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 50).coerceIn(1, 100)
        val offset = ((args["offset"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0).coerceAtLeast(0)

        return try {
            val conversation = conversations.getConversation(conversationId)
                ?: return buildJsonObject {
                    put("type", "read_conversation")
                    put("conversation_id", conversationId)
                    put("error", "not_found")
                }.toString()

            val allMessages = conversations.getMessagesForConversation(conversationId).first()
                .filter { it.participant in listOf(Participant.USER, Participant.MODEL) }
            // buildSelectedBranch needs all intermediate nodes to walk the tree without gaps;
            // text emptiness check is deferred: tool-only MODEL msgs must stay as parent-chain links.
            val branch = buildSelectedBranch(allMessages, conversation.selectedBranchesJson)
                .filter { !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) }
            val totalMessages = branch.size
            val page = if (offset < totalMessages) {
                branch.subList(offset, (offset + limit).coerceAtMost(totalMessages))
            } else {
                emptyList()
            }
            val hasMore = offset + limit < totalMessages

            buildJsonObject {
                put("type", "read_conversation")
                put("conversation_id", conversationId)
                put("title", conversation.title)
                put("total_messages", totalMessages)
                put("offset", offset)
                put("limit", limit)
                put("has_more", hasMore)
                putJsonArray("messages") {
                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    for (msg in page) {
                        add(buildJsonObject {
                            put("participant", msg.participant.name)
                            put("text", msg.text)
                            put("timestamp", dateFormat.format(java.util.Date(msg.timestamp)))
                        })
                    }
                }
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "read_conversation")
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
    private fun buildSelectedBranch(
        allMessages: List<MessageEntity>,
        selectedBranchesJson: String?
    ): List<MessageEntity> {
        val selections: Map<String?, String> = try {
            val raw = Json.decodeFromString<Map<String, String>>(selectedBranchesJson ?: "{}")
            raw.mapKeys { if (it.key == "null") null else it.key }
        } catch (_: Exception) { emptyMap() }

        val byParent = allMessages.groupBy { it.parentId }
        val path = mutableListOf<MessageEntity>()
        var parentId: String? = null
        while (true) {
            val siblings = byParent[parentId] ?: break
            if (siblings.isEmpty()) break
            val selectedId = selections[parentId]
            val visible = siblings.filter {
                !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
            }
            val chosen = if (visible.isNotEmpty()) {
                visible.find { it.id == selectedId } ?: visible.last()
            } else {
                siblings.find { it.id == selectedId } ?: siblings.last()
            }
            path.add(chosen)
            parentId = chosen.id
        }
        return path
    }

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

        val all = conversations.getEmbeddingsByModel(config.id)
        DebugLog.d("AgoraVM", "GM RAG: ${all.size} stored embeddings, query dim=${queryEmbedding.size}")
        if (all.isEmpty()) return@withContext emptyList()

        val scored = all.map {
            val stored = EmbeddingIndexer.bytesToFloats(it.embedding)
            it to EmbeddingIndexer.cosineSimilarity(queryEmbedding, stored)
        }
        val best = scored.maxOfOrNull { it.second } ?: 0f
        DebugLog.d("AgoraVM", "GM RAG: best cosine = ${"%.4f".format(best)}")
        val aboveThreshold = scored.filter { it.second > ctx.ragThreshold }
        val messagesById = conversations.getMessagesByIds(aboveThreshold.map { it.first.messageId }).associateBy { it.id }
        val filtered = aboveThreshold
            .filter { (messagesById[it.first.messageId]?.text?.length ?: 0) >= 10 }
            .sortedByDescending { it.second }
            .take(limit)
        filtered.mapNotNull { (embedding, score) -> messagesById[embedding.messageId]?.let { it to score } }
    }

    private fun resolveEmbeddingApiKey(ctx: GenerationContext): String? {
        return ctx.embeddingApiKey.ifBlank { null }
    }
}
