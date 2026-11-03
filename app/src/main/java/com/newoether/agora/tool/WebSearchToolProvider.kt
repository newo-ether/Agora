package com.newoether.agora.tool

import androidx.core.text.HtmlCompat
import com.newoether.agora.api.DuckDuckGoScraper
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.Constants
import com.newoether.agora.data.normalizeWebSearchProvider
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import java.util.concurrent.TimeUnit

internal fun searxngSearchUrl(configuredBaseUrl: String, query: String): String {
    val baseUrl = configuredBaseUrl.ifBlank { "https://searx.be" }.trimEnd('/')
    val encodedQuery = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
    return "$baseUrl/search?q=$encodedQuery&format=json"
}

/** Exa answers without a key over MCP; the billed REST API takes an `x-api-key` header. */
internal const val EXA_MCP_URL = "https://mcp.exa.ai/mcp"
internal const val EXA_REST_URL = "https://api.exa.ai/search"

internal sealed interface ExaSearchOutcome {
    data class Hits(val results: JsonArray) : ExaSearchOutcome
    data object EmptyResults : ExaSearchOutcome
    data class Failure(val error: String = "search_error", val message: String? = null) : ExaSearchOutcome
}

/**
 * Keyless path. `web_search_exa` requires an `objective` beside the query, and the tool forwards
 * both to the same neural search, so the query doubles as the ranking brief.
 */
internal fun exaMcpRequestBody(query: String, numResults: Int): String =
    Json.encodeToString(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "web_search_exa")
                put("arguments", buildJsonObject {
                    put("query", query)
                    put("objective", query)
                    put("numResults", numResults.coerceIn(1, 10))
                })
            })
        }
    )

internal fun exaSearchRequestBody(query: String, numResults: Int): String =
    Json.encodeToString(
        buildJsonObject {
            put("query", query)
            put("type", "auto")
            put("numResults", numResults.coerceIn(1, 10))
            put("contents", buildJsonObject { put("highlights", true) })
        }
    )

/**
 * Exa exposes two shapes of the same search: the billed REST API behind `x-api-key`, and the
 * keyless MCP endpoint behind a JSON-RPC `tools/call`. Endpoint, body, and auth header all differ,
 * so the choice is made once here instead of at the call site.
 */
internal data class ExaRequest(
    val url: String,
    val body: String,
    val headers: Map<String, String>,
)

internal fun exaSearchRequest(query: String, numResults: Int, apiKey: String): ExaRequest =
    if (apiKey.isNotBlank()) {
        ExaRequest(
            url = EXA_REST_URL,
            body = exaSearchRequestBody(query, numResults),
            headers = mapOf("x-api-key" to apiKey),
        )
    } else {
        ExaRequest(
            url = EXA_MCP_URL,
            body = exaMcpRequestBody(query, numResults),
            headers = mapOf("Accept" to "application/json, text/event-stream"),
        )
    }

/**
 * Maps an Exa response onto hits. The keyless endpoint answers over SSE with a JSON-RPC envelope:
 * its text block is one `Title:/URL:/Published:/Author:/Highlights:` record per result, separated
 * by `---`, optionally led by `Note:` lines that describe how the results were produced. The keyed
 * REST API returns the same fields as JSON.
 */
internal fun normalizeExaSearchResponse(body: String, numResults: Int): ExaSearchOutcome {
    // Exa streams the envelope as one `data:` event; a plain JSON body is the REST path.
    val payloads = sseDataPayloads(body) ?: listOf(body)
    if (payloads.isEmpty()) return ExaSearchOutcome.Failure("search_error")
    var failure: ExaSearchOutcome.Failure? = null
    for (payload in payloads) {
        when (val outcome = normalizeExaPayload(payload, numResults)) {
            is ExaSearchOutcome.Hits, ExaSearchOutcome.EmptyResults -> return outcome
            is ExaSearchOutcome.Failure -> failure = outcome
        }
    }
    return failure ?: ExaSearchOutcome.EmptyResults
}

/** Renders an Exa outcome as the `web_search` tool payload the model sees. */
internal fun exaOutcomeToToolJson(outcome: ExaSearchOutcome, query: String): String =
    when (outcome) {
        is ExaSearchOutcome.Hits -> buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("results", outcome.results)
        }.toString()
        ExaSearchOutcome.EmptyResults -> buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("error", "no_results")
        }.toString()
        is ExaSearchOutcome.Failure -> buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("error", outcome.error)
            outcome.message?.let { put("message", it) }
        }.toString()
    }

/**
 * The `data:` payloads of an SSE response, or null when the body is not an event stream. Consecutive
 * `data:` lines belong to one event; a blank line ends it.
 */
private fun sseDataPayloads(body: String): List<String>? {
    val trimmed = body.trimStart()
    if (!trimmed.startsWith("event:") && !trimmed.startsWith("data:")) return null
    val payloads = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isNotEmpty()) {
            payloads += current.toString()
            current.clear()
        }
    }
    for (line in body.lineSequence()) {
        val stripped = line.trimEnd('\r')
        when {
            stripped.startsWith("data:") -> {
                if (current.isNotEmpty()) current.append('\n')
                current.append(stripped.removePrefix("data:").removePrefix(" "))
            }
            stripped.isBlank() -> flush()
        }
    }
    flush()
    return payloads
}

private fun normalizeExaPayload(payload: String, numResults: Int): ExaSearchOutcome {
    val root = Json.parseToJsonElement(payload) as? JsonObject
        ?: return ExaSearchOutcome.Failure("search_error")
    if (!root.containsKey("jsonrpc")) {
        return normalizeExaRestPayload(root, numResults)
    }
    (root["error"] as? JsonObject)?.let { error ->
        return ExaSearchOutcome.Failure(
            "search_error",
            (error["message"] as? JsonPrimitive)?.content,
        )
    }
    val result = root["result"] as? JsonObject
        ?: return ExaSearchOutcome.Failure("search_error")
    val text = (result["content"] as? JsonArray)
        ?.firstOrNull { element -> (element as? JsonObject)?.get("text") is JsonPrimitive }
        ?.let { element -> ((element as JsonObject)["text"] as JsonPrimitive).content }
        .orEmpty()
    val isError = (result["isError"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
    if (isError) return ExaSearchOutcome.Failure("search_error", text.ifBlank { null })
    return normalizeExaMcpText(text, numResults)
}

private fun normalizeExaMcpText(text: String, numResults: Int): ExaSearchOutcome {
    // Search notices lead the text block, one `Note:` line each, followed by a blank line.
    val body = text.trimStart()
        .lines()
        .dropWhile { line -> line.isBlank() || line.startsWith("Note:") }
        .joinToString("\n")
        .trim()
    if (body.isEmpty()) return ExaSearchOutcome.Failure("search_error")
    if (body.startsWith("No search results found")) return ExaSearchOutcome.EmptyResults
    val seenUrls = HashSet<String>()
    val normalizedResults = buildJsonArray {
        var added = 0
        for (block in body.split("\n\n---\n\n")) {
            if (added >= numResults.coerceIn(1, 10)) break
            val result = parseExaTextBlock(block) ?: continue
            val url = (result["url"] as? JsonPrimitive)?.content.orEmpty()
            if (!seenUrls.add(url)) continue
            add(result)
            added++
        }
    }
    return if (normalizedResults.isEmpty()) {
        ExaSearchOutcome.EmptyResults
    } else {
        ExaSearchOutcome.Hits(normalizedResults)
    }
}

/** Parses one `Title:/URL:/Published:/Author:/Highlights:` record into a hit. */
private fun parseExaTextBlock(block: String): JsonObject? {
    var title = ""
    var url = ""
    val description = StringBuilder()
    var inBody = false
    for (line in block.lines()) {
        if (inBody) {
            description.appendLine(line)
            continue
        }
        when {
            line.startsWith("Title: ") -> title = line.removePrefix("Title: ").trim()
            line.startsWith("URL: ") -> url = line.removePrefix("URL: ").trim()
            line.startsWith("Published: ") || line.startsWith("Author: ") -> Unit
            line.trim() == "Highlights:" -> inBody = true
            line.startsWith("Text: ") -> {
                inBody = true
                description.appendLine(line.removePrefix("Text: "))
            }
        }
    }
    if (url.isBlank()) return null
    return buildJsonObject {
        put("title", title)
        put("url", url)
        put("description", description.toString().trim())
    }
}

private fun normalizeExaRestPayload(root: JsonObject, numResults: Int): ExaSearchOutcome {
    val resultsArray = root["results"] as? JsonArray ?: run {
        val message = (root["error"] as? JsonPrimitive)?.content
            ?: ((root["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.content
        return if (message.isNullOrBlank()) {
            ExaSearchOutcome.EmptyResults
        } else {
            ExaSearchOutcome.Failure("search_error", message)
        }
    }
    val seenUrls = HashSet<String>()
    val normalizedResults = buildJsonArray {
        var added = 0
        for (element in resultsArray) {
            if (added >= numResults.coerceIn(1, 10)) break
            val result = element as? JsonObject ?: continue
            val url = (result["url"] as? JsonPrimitive)?.content.orEmpty().trim()
            if (url.isBlank() || !seenUrls.add(url)) continue
            val highlights = (result["highlights"] as? JsonArray)
                .orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
            val description = highlights.joinToString("\n\n").ifEmpty {
                sequenceOf(result["text"], result["summary"])
                    .mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
                    .firstOrNull()
                    .orEmpty()
            }
            add(buildJsonObject {
                put("title", (result["title"] as? JsonPrimitive)?.content.orEmpty())
                put("url", url)
                put("description", description)
            })
            added++
        }
    }
    return if (normalizedResults.isEmpty()) {
        ExaSearchOutcome.EmptyResults
    } else {
        ExaSearchOutcome.Hits(normalizedResults)
    }
}

internal fun kagiSearchRequestBody(query: String, numResults: Int): String =
    Json.encodeToString(
        buildJsonObject {
            put("query", query)
            put("workflow", "search")
            put("limit", numResults.coerceIn(1, 10))
        }
    )

internal fun normalizeKagiSearchResponse(
    responseBody: String,
    query: String,
    numResults: Int,
): String {
    val root = Json.parseToJsonElement(responseBody) as? JsonObject
    val data = root?.get("data") as? JsonObject
    val searchResults = data?.get("search") as? JsonArray
        ?: return buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("error", "no_results")
        }.toString()

    val normalizedResults = buildJsonArray {
        var added = 0
        for (element in searchResults) {
            if (added >= numResults.coerceIn(1, 10)) break
            val result = element as? JsonObject ?: continue
            val url = (result["url"] as? JsonPrimitive)?.content.orEmpty()
            if (url.isBlank()) continue
            add(
                buildJsonObject {
                    put("title", (result["title"] as? JsonPrimitive)?.content.orEmpty())
                    put("url", url)
                    put("description", (result["snippet"] as? JsonPrimitive)?.content.orEmpty())
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

internal fun webSearchProviderDisplayName(provider: String): String = when (provider) {
    "kagi" -> "Kagi"
    "serper" -> "Serper"
    "tavily" -> "Tavily"
    "exa" -> "Exa"
    "searxng" -> "SearXNG"
    "duckduckgo" -> "DuckDuckGo"
    else -> "Brave Search"
}

class WebSearchToolProvider : ToolProvider {
    private val webClient = HttpClient.client.newBuilder()
        .callTimeout(Constants.NETWORK_TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(Constants.NETWORK_TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.webSearchEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "web_search",
                description = "Search the web for current information. Use this to find facts, news, or data not in your training set.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "query" to ToolProperty("string", "The search query to execute."),
                        "num_results" to ToolProperty("integer", "Number of results to return (1-10, default 5).")
                    ),
                    required = listOf("query")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "web_fetch",
                description = "Fetch and read the full text content of a web page. Use this after web_search when you need more detail from a specific page.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "url" to ToolProperty("string", "The URL of the page to fetch."),
                        "maxChars" to ToolProperty("integer", "Maximum characters of text to return (default 8000, max 100000). If the result has \"truncated\": true, call again with a larger maxChars to get more.")
                    ),
                    required = listOf("url")
                )
            ))
        )
    }

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        when (name) {
            "web_search" -> executeWebSearch(arguments, ctx)
            "web_fetch" -> executeWebFetch(arguments, ctx)
            else -> "Unknown tool: $name"
        }
    }

    override fun handles(name: String): Boolean = name in setOf("web_search", "web_fetch")

    private fun executeWebSearch(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val query = (args["query"] as? JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "web_search"); put("error", "no_query") }.toString()
        val numResults = ((args["num_results"] as? JsonPrimitive)?.content?.toIntOrNull() ?: ctx.webSearchNumResults).coerceIn(1, 10)
        val provider = normalizeWebSearchProvider(ctx.webSearchProvider)

        return try {
            // DuckDuckGo is a scraper, not an API — handle it separately.
            if (provider == "duckduckgo") {
                val scraper = DuckDuckGoScraper(webClient)
                return when (val r = scraper.search(query, numResults)) {
                    is DuckDuckGoScraper.SearchResponse.Success -> {
                        val rawResults = buildJsonArray {
                            r.results.forEach { result ->
                                add(buildJsonObject {
                                    put("title", result.title)
                                    put("url", result.url)
                                    put("description", result.snippet)
                                })
                            }
                        }
                        buildJsonObject {
                            put("type", "web_search")
                            put("query", query)
                            put("results", rawResults)
                        }.toString()
                    }
                    is DuckDuckGoScraper.SearchResponse.Error -> {
                        buildJsonObject {
                            put("type", "web_search")
                            put("query", query)
                            put("error", r.type.name.lowercase())
                            put("message", r.message)
                        }.toString()
                    }
                }
            }

            val apiKey = ctx.webSearchApiKeys[provider].orEmpty()
            // Exa answers over its keyless public MCP endpoint too; searxng only needs a URL.
            if (provider != "searxng" && provider != "exa" && apiKey.isBlank()) {
                return buildJsonObject {
                    put("type", "web_search")
                    put("query", query)
                    put("error", "no_api_key")
                    put("provider", webSearchProviderDisplayName(provider))
                }.toString()
            }

            if (provider == "exa") {
                val request = exaSearchRequest(query, numResults, apiKey)
                val response = HttpClient.postTextResponse(
                    request.url,
                    request.body,
                    request.headers,
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
                if (!response.isSuccessful) {
                    return buildJsonObject {
                        put("type", "web_search")
                        put("query", query)
                        put("error", "search_error")
                        put("message", response.body)
                    }.toString()
                }
                return exaOutcomeToToolJson(normalizeExaSearchResponse(response.body, numResults), query)
            }

            val body = when (provider) {
                "kagi" -> HttpClient.post(
                    "https://kagi.com/api/v1/search",
                    kagiSearchRequestBody(query, numResults),
                    mapOf(
                        "Accept" to "application/json",
                        "Authorization" to "Bearer $apiKey",
                    ),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
                "serper" -> HttpClient.post(
                    "https://google.serper.dev/search",
                    Json.encodeToString(buildJsonObject { put("q", query); put("num", numResults) }),
                    mapOf("X-API-KEY" to apiKey),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
                "tavily" -> HttpClient.post(
                    "https://api.tavily.com/search",
                    Json.encodeToString(buildJsonObject {
                        put("api_key", apiKey)
                        put("query", query)
                        put("max_results", numResults)
                        put("search_depth", "advanced")
                        put("include_answer", true)
                    }),
                    emptyMap(),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
                "searxng" -> {
                    // Don't pin engines=google,brave: many public/self-hosted SearXNG instances
                    // disable those engines (rate-limited/require config), and pinning them yields
                    // an empty result set. Letting the instance use its own default-enabled engines
                    // matches how other SearXNG clients behave. Send a browser-like User-Agent so
                    // bot-filtering instances don't 403 us (same reason web_fetch sets one).
                    HttpClient.fetchModels(
                        searxngSearchUrl(ctx.webSearchBaseUrl, query),
                        mapOf("User-Agent" to Constants.WEB_FETCH_USER_AGENT),
                        callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                    )
                }
                else -> HttpClient.fetchModels(
                    "https://api.search.brave.com/res/v1/web/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&count=$numResults",
                    mapOf("Accept" to "application/json", "X-Subscription-Token" to apiKey),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
            } ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_response") }.toString()

            if (provider == "kagi") {
                return normalizeKagiSearchResponse(body, query, numResults)
            }

            val json: Map<String, kotlinx.serialization.json.JsonElement> = Json.decodeFromString(body)

            if (provider == "tavily") {
                val resultsArray = json["results"]?.jsonArray
                    ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()
                if (resultsArray.isEmpty())
                    return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()
                val answer = (json["answer"] as? JsonPrimitive)?.content
                val rawResults = buildJsonArray {
                    for (element in resultsArray) {
                        val obj = element.jsonObject
                        add(buildJsonObject {
                            put("title", (obj["title"] as? JsonPrimitive)?.content ?: "")
                            put("url", (obj["url"] as? JsonPrimitive)?.content ?: "")
                            put("content", (obj["content"] as? JsonPrimitive)?.content ?: "")
                            val score = (obj["score"] as? JsonPrimitive)?.content?.toFloatOrNull()
                            if (score != null) put("score", score)
                        })
                    }
                }
                return buildJsonObject {
                    put("type", "web_search")
                    put("query", query)
                    if (!answer.isNullOrBlank()) put("answer", answer)
                    put("results", rawResults)
                }.toString()
            }

            val resultsArray = when {
                json.containsKey("organic") -> json["organic"]?.jsonArray
                json.containsKey("web") -> {
                    val web = json["web"]?.jsonObject
                    web?.get("results")?.jsonArray
                }
                json.containsKey("results") -> json["results"]?.jsonArray
                else -> null
            } ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()

            if (resultsArray.isEmpty())
                return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()

            val rawResults = buildJsonArray {
                for (element in resultsArray) {
                    val obj = element.jsonObject
                    add(buildJsonObject {
                        put("title", (obj["title"] as? JsonPrimitive)?.content ?: "")
                        put("url", (obj["link"] as? JsonPrimitive)?.content ?: (obj["url"] as? JsonPrimitive)?.content ?: "")
                        put("description", (obj["snippet"] as? JsonPrimitive)?.content ?: (obj["content"] as? JsonPrimitive)?.content ?: (obj["description"] as? JsonPrimitive)?.content ?: "")
                    })
                }
            }
            buildJsonObject {
                put("type", "web_search")
                put("query", query)
                put("results", rawResults)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "web_search")
                put("query", query)
                put("error", "search_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    private suspend fun executeWebFetch(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val url = (args["url"] as? JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "web_fetch"); put("error", "no_url") }.toString()
        val maxChars = (try {
            (args["maxChars"] as? JsonPrimitive)?.content?.toIntOrNull()
        } catch (_: Exception) { null } ?: 8000).coerceIn(1, 100_000)

        return try {
            val html = HttpClient.fetchModels(url, mapOf(
                "User-Agent" to Constants.WEB_FETCH_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,*/*"
            ), callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS)
                ?: return buildJsonObject { put("type", "web_fetch"); put("url", url); put("error", "no_response") }.toString()
            val fullText = htmlToReadableText(html)
            val text = fullText.take(maxChars)
            buildJsonObject {
                put("type", "web_fetch")
                put("url", url)
                put("text", text)
                put("truncated", fullText.length > text.length)
                put("totalChars", fullText.length)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "web_fetch")
                put("url", url)
                put("error", "fetch_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    /**
     * Extracts readable text from an HTML page.
     *
     * Strips non-content blocks (comments, script/style/noscript/svg/head), then lets
     * [HtmlCompat] decode entities and flatten the remaining markup while keeping block
     * elements as line breaks. Extraction runs over the whole (capped) HTML and the caller
     * truncates the resulting *text* — so article content past the page's boilerplate is no
     * longer cut off, and entities (—, ’, accents, numeric refs) are decoded correctly
     * instead of being dropped to spaces.
     */
    private fun htmlToReadableText(rawHtml: String): String {
        val stripped = rawHtml
            .take(Constants.MAX_WEB_FETCH_HTML_LENGTH)
            .replace(Regex("<!--[\\s\\S]*?-->"), " ")
            .replace(
                Regex("<(script|style|noscript|svg|head)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE),
                " "
            )
            // Drop common page chrome so navigation/menus/footers don't eat the text budget.
            .replace(
                Regex("<(nav|header|footer|aside)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE),
                " "
            )
        val text = HtmlCompat.fromHtml(stripped, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
        return text
            .replace(Regex("[ \\t\\x0B\\u000C\\r]+"), " ") // collapse intra-line whitespace
            .replace(Regex(" *\\n *"), "\n")               // trim around line breaks
            .replace(Regex("\\n{3,}"), "\n\n")             // collapse blank-line runs
            .trim()
    }
}
