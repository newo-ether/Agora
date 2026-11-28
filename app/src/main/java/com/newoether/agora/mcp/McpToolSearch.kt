package com.newoether.agora.mcp

import com.newoether.agora.util.Constants
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/** Pure catalog matching and complete-item pagination; no registry or history mutation. */
internal fun searchMcpTools(
    tools: List<McpToolDescriptor>,
    query: String,
    serverId: String? = null,
    limit: Int = 5,
    offset: Int = 0,
    maxChars: Int = Constants.MAX_TOOL_RESULT_LENGTH,
): String {
    require(limit in 1..10 && offset >= 0)
    require(maxChars >= 512)
    val normalized = query.trim().lowercase(Locale.ROOT)
    val terms = normalized.split(Regex("\\s+")).filter(String::isNotEmpty)
    val eligible = tools.filter { it.enabled && (serverId == null || it.serverId == serverId) }
    val exact = eligible.firstOrNull { it.publicName == query.trim() }
    val matches = (exact?.let(::listOf) ?: eligible).asSequence()
        .mapNotNull { tool ->
            val score = mcpToolMatchScore(tool, normalized, terms)
            score?.let { tool to it }
        }
        .sortedWith(compareByDescending<Pair<McpToolDescriptor, Int>> { it.second }
            .thenBy { it.first.publicName })
        .map { it.first }
        .toList()
    val items = mutableListOf<JsonObject>()
    var next = offset.coerceAtMost(matches.size)

    fun envelope(entries: List<JsonObject>, nextOffset: Int): String = buildJsonObject {
        put("tools", JsonArray(entries))
        put("returned", entries.size)
        put("total", matches.size)
        put("next_offset", if (nextOffset < matches.size) JsonPrimitive(nextOffset) else JsonNull)
        put("warning", "Results reflect currently connected and enabled tools. Tool-specific arguments are validated by the MCP server.")
    }.toString()

    for (tool in matches.drop(next).take(limit)) {
        val item = buildJsonObject {
            put("tool_id", tool.publicName)
            put("server_id", tool.serverId)
            put("server_name", tool.serverName)
            put("name", tool.remote.name)
            put("description", tool.remote.description)
            put("input_schema", tool.remote.inputSchema)
        }
        val candidate = items + item
        if (envelope(candidate, next + 1).length <= maxChars) {
            items += item
            next++
        } else if (items.isNotEmpty()) {
            break
        } else {
            // Do not emit a partial schema. Advance past this unusable item so paging can continue.
            val error = buildJsonObject {
                put("tool_id", tool.publicName.take(64))
                put("error", "schema_too_large")
                put("message", "The complete tool definition exceeds the tool-result size limit.")
            }
            items += error
            next++
        }
    }
    return envelope(items, next)
}

private fun mcpToolMatchScore(
    tool: McpToolDescriptor,
    query: String,
    terms: List<String>,
): Int? {
    if (query.isEmpty()) return 0
    val id = tool.publicName.lowercase(Locale.ROOT)
    val name = tool.remote.name.lowercase(Locale.ROOT)
    if (query == id) return 10_000
    if (query == name) return 5_000
    val server = tool.serverName.lowercase(Locale.ROOT)
    val description = tool.remote.description.lowercase(Locale.ROOT)
    val parameters = buildList {
        collectMcpParameterNames(tool.remote.inputSchema, this)
    }.joinToString(" ").lowercase(Locale.ROOT)
    val fields = listOf(id to 100, name to 80, server to 40, parameters to 20, description to 10)
    var score = 0
    for (term in terms) {
        val weight = fields.firstOrNull { (text, _) -> term in text }?.second ?: return null
        score += weight
    }
    return score
}

private fun collectMcpParameterNames(schema: kotlinx.serialization.json.JsonElement, names: MutableList<String>) {
    when (schema) {
        is JsonObject -> schema.forEach { (key, value) ->
            if (key == "properties" && value is JsonObject) names += value.keys
            collectMcpParameterNames(value, names)
        }
        is JsonArray -> schema.forEach { collectMcpParameterNames(it, names) }
        else -> Unit
    }
}
