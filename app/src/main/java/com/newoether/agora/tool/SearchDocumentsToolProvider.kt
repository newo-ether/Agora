package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.UserDocumentManager
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Searches the user's imported documents (RAG over your own files) semantically. Isolated from
 * conversation history and the bundled manual — it only reads the `user_docs` corpus. Offered only
 * when the user has imported at least one document and an embedding model is configured.
 */
class SearchDocumentsToolProvider(
    private val documents: UserDocumentManager,
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (ctx.activeEmbeddingConfig == null) return emptyList()
        // Avoid offering a dead tool when the user has imported nothing.
        val hasDocs = runCatching { runBlocking { documents.docCount() > 0 } }.getOrDefault(false)
        if (!hasDocs) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = TOOL_NAME,
                    description = "Search the user's imported documents (their own uploaded files) " +
                        "by meaning. Use to answer questions grounded in the user's documents. " +
                        "Returns the most relevant passages with their document title.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "query" to ToolProperty("string", "What to look for in the user's documents."),
                            "limit" to ToolProperty("integer", "Max documents to return (1-10, default 5)."),
                        ),
                        required = listOf("query"),
                    ),
                ),
            ),
        )
    }

    override fun handles(name: String): Boolean = name == TOOL_NAME

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != TOOL_NAME) return "Error: unknown tool: $name"
        val args = parse(arguments)
        val query = args["query"].orEmpty().trim()
        if (query.isEmpty()) return "Error: query is required"
        val limit = (args["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val hits = documents.search(
            query = query,
            limit = limit,
            threshold = ctx.ragThreshold,
            config = ctx.activeEmbeddingConfig,
            apiKey = ctx.embeddingApiKey.ifBlank { null },
        )
        if (hits.isEmpty()) {
            return "No imported documents matched \"$query\". Tell the user nothing in their " +
                "documents covers this (or they may need to import the relevant file)."
        }
        return buildString {
            append("Relevant passages from the user's imported documents:\n")
            hits.forEachIndexed { i, hit ->
                append("\n[${i + 1}] ${hit.title}\n")
                append(hit.snippet.trim())
                append("\n")
            }
        }
    }

    private fun parse(arguments: String): Map<String, String> = try {
        Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
            .mapValues { (_, v) -> runCatching { v.jsonPrimitive.content }.getOrDefault("") }
    } catch (_: Exception) {
        emptyMap()
    }

    companion object {
        const val TOOL_NAME = "search_documents"
        private const val DEFAULT_LIMIT = 5
        private const val MAX_LIMIT = 10
    }
}
