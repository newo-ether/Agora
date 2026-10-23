package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.ManualIndexer
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Self-knowledge tool: answers questions about Agora itself (features, settings, how-to) from the
 * bundled user manual. Semantic ranking when an embedding model is configured, keyword fallback
 * otherwise. Returns one full manual page per call plus related page titles, prefixed with an
 * instruction to answer only from the manual.
 */
class IntrospectToolProvider(
    private val manual: ManualIndexer,
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (manual.pageCount == 0) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = TOOL_NAME,
                    description = "Answer questions about Agora ITSELF — its features, tools, " +
                        "settings, and how to use the app — from Agora's built-in manual. Use " +
                        "whenever the user asks what you can do, how a feature works, or where to " +
                        "change a setting. Returns a relevant manual page; answer ONLY from it and " +
                        "say so if the manual does not cover the question.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "query" to ToolProperty(
                                "string",
                                "What to look up about Agora (a feature, tool, or setting).",
                            ),
                            "top_k" to ToolProperty(
                                "integer",
                                "How many related pages to consider (1-5, default 3).",
                            ),
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
        val topK = (args["top_k"]?.toIntOrNull() ?: DEFAULT_TOP_K).coerceIn(1, MAX_TOP_K)

        val results = manual.search(
            query = query,
            limit = topK,
            threshold = ctx.ragThreshold,
            config = ctx.activeEmbeddingConfig,
            apiKey = ctx.embeddingApiKey.ifBlank { null },
        )
        if (results.isEmpty()) {
            return "The Agora manual has no page matching \"$query\". Tell the user this isn't " +
                "covered by the built-in manual rather than inventing behavior."
        }

        val best = results.first()
        val related = results.drop(1).map { it.title }
        return buildString {
            append("Answer the user's question ONLY from the Agora manual page below. ")
            append("If it does not contain the answer, say the manual doesn't cover it.\n\n")
            append("=== Manual page: ${best.title} ===\n")
            append(best.body)
            if (related.isNotEmpty()) {
                append("\n\n=== Related manual pages (call introspect again to read one) ===\n")
                append(related.joinToString("\n") { "- $it" })
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
        const val TOOL_NAME = "introspect"
        private const val DEFAULT_TOP_K = 3
        private const val MAX_TOP_K = 5
    }
}
