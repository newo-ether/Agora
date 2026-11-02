package com.newoether.agora.tool

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Parallel truncates each result's excerpts to this budget; 800 chars fits ~10 results in context. */
internal const val PARALLEL_EXCERPT_CHARS = 800

internal fun parallelSearchRequestBody(query: String, numResults: Int): String =
    Json.encodeToString(
        buildJsonObject {
            put("objective", query)
            put("search_queries", buildJsonArray { add(JsonPrimitive(query)) })
            // `fast` keeps the tool call near a single keyword SERP; `advanced` adds seconds.
            put("mode", "fast")
            put("advanced_settings", buildJsonObject {
                put("max_results", numResults.coerceIn(1, 10))
                put("excerpt_settings", buildJsonObject {
                    put("max_chars_per_result", PARALLEL_EXCERPT_CHARS)
                })
            })
        }
    )

/** Keyless path: Parallel's public MCP endpoint exposes the same `web_search` payload without a key. */
internal fun parallelMcpRequestBody(query: String): String =
    Json.encodeToString(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "web_search")
                put("arguments", buildJsonObject {
                    put("objective", query)
                    put("search_queries", buildJsonArray { add(JsonPrimitive(query)) })
                })
            })
        }
    )

internal const val PARALLEL_REST_URL = "https://api.parallel.ai/v1/search"
internal const val PARALLEL_MCP_URL = "https://search.parallel.ai/mcp"

/**
 * Parallel exposes two shapes of the same search: the billed REST API behind `x-api-key`, and the
 * keyless MCP endpoint behind a JSON-RPC `tools/call`. Endpoint, body, and auth header all differ,
 * so the choice is made once here instead of at the call site.
 */
internal data class ParallelRequest(
    val url: String,
    val body: String,
    val headers: Map<String, String>,
)

internal fun parallelSearchRequest(query: String, numResults: Int, apiKey: String): ParallelRequest =
    if (apiKey.isNotBlank()) {
        ParallelRequest(
            url = PARALLEL_REST_URL,
            body = parallelSearchRequestBody(query, numResults),
            headers = mapOf("x-api-key" to apiKey),
        )
    } else {
        ParallelRequest(
            url = PARALLEL_MCP_URL,
            body = parallelMcpRequestBody(query),
            headers = mapOf("Accept" to "application/json, text/event-stream"),
        )
    }

/**
 * Unwraps the JSON-RPC envelope the keyless MCP endpoint returns and reuses
 * [normalizeParallelPayload] on the payload it carries in `result.content[0].text`.
 */
internal fun normalizeParallelSearchResponse(body: String, query: String, numResults: Int): String {
    val root = Json.parseToJsonElement(body) as? JsonObject
    if (root == null || !root.containsKey("jsonrpc")) {
        return normalizeParallelPayload(body, query, numResults)
    }
    (root["error"] as? JsonObject)?.let { error ->
        return parallelFailure(
            query,
            "search_error",
            (error["message"] as? JsonPrimitive)?.content,
        )
    }
    val result = root["result"] as? JsonObject
    val text = (result?.get("content") as? JsonArray)
        ?.firstOrNull { element -> (element as? JsonObject)?.get("text") is JsonPrimitive }
        ?.let { element -> ((element as JsonObject)["text"] as JsonPrimitive).content }
        .orEmpty()
    val isError = (result?.get("isError") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
    if (isError || text.isBlank()) {
        return parallelFailure(query, "search_error", text.ifBlank { null })
    }
    return normalizeParallelPayload(text, query, numResults)
}

private fun parallelFailure(query: String, error: String, message: String?): String =
    buildJsonObject {
        put("type", "web_search")
        put("query", query)
        put("error", error)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

/**
 * Maps a Parallel `/v1/search` payload onto the `{title, url, description}` shape the model
 * expects. Parallel returns markdown excerpts per URL rather than a SERP snippet.
 */
private fun normalizeParallelPayload(
    responseBody: String,
    query: String,
    numResults: Int,
): String {
    val root = Json.parseToJsonElement(responseBody) as? JsonObject
    val resultsArray = root?.get("results") as? JsonArray ?: run {
        val errorType = (root?.get("type") as? JsonPrimitive)?.content
        val message = (root?.get("message") as? JsonPrimitive)?.content
        return buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("error", errorType?.takeIf { it.isNotBlank() } ?: "no_results")
            if (!message.isNullOrBlank()) put("message", message)
        }.toString()
    }

    val seenUrls = HashSet<String>()
    val normalizedResults = buildJsonArray {
        var added = 0
        for (element in resultsArray) {
            if (added >= numResults.coerceIn(1, 10)) break
            val result = element as? JsonObject ?: continue
            val url = (result["url"] as? JsonPrimitive)?.content.orEmpty().trim()
            if (url.isBlank() || !seenUrls.add(url)) continue
            val description = (result["excerpts"] as? JsonArray)
                .orEmpty()
                .joinToString("\n\n") { (it as? JsonPrimitive)?.content.orEmpty().trim() }
                .trim()
            add(
                buildJsonObject {
                    put("title", (result["title"] as? JsonPrimitive)?.content.orEmpty())
                    put("url", url)
                    put("description", description)
                }
            )
            added++
        }
    }
    if (normalizedResults.isEmpty()) {
        return buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("error", "no_results")
        }.toString()
    }
    return buildJsonObject {
        put("type", "web_search")
        put("query", query)
        put("results", normalizedResults)
    }.toString()
}
