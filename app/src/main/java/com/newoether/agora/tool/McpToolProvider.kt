package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.mcp.McpRegistry
import com.newoether.agora.mcp.mcpToolIdForPresentation
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

class McpToolProvider(
    private val registry: McpRegistry,
) : ToolProvider {
    companion object {
        const val LIST = "mcp_list_servers"
        const val SEARCH = "mcp_search_tools"
        const val CALL = "mcp_call_tool"
        private val json = Json

        private const val MAX_LISTED_SERVERS = 32
        private const val MAX_SERVER_NAME = 80

        // Never derive these definitions from live runtime state (status, counts, catalog): that
        // would rewrite the cached tools prefix on every reconnect. Only the configured enabled
        // server names are appended to mcp_search_tools; discovery data stays in tool results.
        private val definitions = listOf(
            definition(
                LIST,
                "List enabled MCP servers with exact server IDs, current local connection status and available tool counts. Includes unavailable servers and servers with no tools. Does not connect or refresh. Optional when global mcp_search_tools is sufficient; use an exact server ID to scope a later search. Results are paginated.",
                """{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":50,"default":20},"offset":{"type":"integer","minimum":0,"default":0}},"required":[],"additionalProperties":false}""",
            ),
            definition(
                SEARCH,
                "Search tools from user-connected MCP servers, which extend Agora with external capabilities. When a request needs a capability that other tools do not provide, search here before saying it is unavailable. Returns exact tool IDs, full descriptions and input schemas. Use an empty query to browse, an exact tool ID to retrieve its schema, or keywords matching tool/server names, descriptions and parameter names. Optional exact server_id scopes the search; invalid or unavailable servers return an error without widening to global search. Results are paginated; keyword search is not semantic translation.",
                """{"type":"object","properties":{"query":{"type":"string","description":"Tool ID or search keywords; empty string browses available tools."},"server_id":{"type":"string","description":"Optional exact server ID from mcp_list_servers or a previous search."},"limit":{"type":"integer","minimum":1,"maximum":10,"default":5},"offset":{"type":"integer","minimum":0,"default":0}},"required":["query"],"additionalProperties":false}""",
            ),
            definition(
                CALL,
                "Call one MCP tool using its exact tool_id and the input schema returned by mcp_search_tools. Pass arguments as a JSON object, not a JSON-encoded string. Availability is checked again at dispatch; the MCP server validates tool-specific arguments.",
                """{"type":"object","properties":{"tool_id":{"type":"string","description":"Exact tool_id returned by mcp_search_tools."},"arguments":{"type":"object","description":"Arguments matching the discovered input_schema.","additionalProperties":true}},"required":["tool_id","arguments"],"additionalProperties":false}""",
            ),
        )

        private fun definition(name: String, description: String, schema: String) = ToolDefinition(
            function = ToolFunction(
                name = name,
                description = description,
                parameters = ToolParameters(
                    properties = emptyMap(),
                    schema = json.parseToJsonElement(schema) as JsonObject,
                ),
            ),
        )
    }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        val servers = registry.enabledServerNames()
        if (servers.isEmpty()) return definitions
        val note = serverNote(servers)
        return definitions.map { definition ->
            if (definition.function.name != SEARCH) definition
            else definition.copy(function = definition.function.copy(description = definition.function.description + note))
        }
    }

    private fun serverNote(servers: List<Pair<String, String>>): String {
        val listed = servers.take(MAX_LISTED_SERVERS).map { (id, name) ->
            buildJsonObject {
                put("id", id)
                put("name", name.take(MAX_SERVER_NAME))
            }
        }
        val more = (servers.size - listed.size).takeIf { it > 0 }?.let { " (+$it more; use mcp_list_servers)" }.orEmpty()
        return " Enabled MCP servers (configuration only; call mcp_list_servers for live status): " +
            JsonArray(listed).toString() + more
    }

    override fun handles(name: String): Boolean = name == LIST || name == SEARCH || name == CALL

    override fun presentationMetadata(name: String, arguments: String?): ToolPresentationMetadata? =
        when (name) {
            CALL -> mcpToolIdForPresentation(arguments)?.let(registry::descriptor)
            else -> null
        }?.let { ToolPresentationMetadata(displayName = it.remote.name, target = it.serverName) }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        executeOneShotResult(name, arguments, ctx).text

    override suspend fun executeOneShotResult(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): ToolExecutionResult = route(name, arguments)

    override fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        // Parse once at execution, including when a large arguments object precedes tool_id.
        val root = parseObject(arguments)
        if (name == CALL) {
            root?.string("tool_id")?.let(registry::descriptor)?.let { descriptor ->
                emit(ToolExecutionEvent.TargetResolved(descriptor.serverName, descriptor.remote.name))
            }
        }
        emit(ToolExecutionEvent.Completed(route(name, arguments, root)))
    }

    private suspend fun route(
        name: String,
        arguments: String,
        root: JsonObject? = parseObject(arguments),
    ): ToolExecutionResult {
        if (root == null) return error("MCP tools require a complete JSON object")
        return when (name) {
            LIST -> {
                val limit = root.integer("limit", 20)
                val offset = root.integer("offset", 0)
                if (root.keys.any { it !in setOf("limit", "offset") } ||
                    limit == null || limit !in 1..50 || offset == null || offset < 0
                ) return error("Invalid list arguments: limit must be 1..50 and offset >= 0")
                ToolExecutionResult(registry.listServers(limit, offset))
            }
            SEARCH -> {
                val query = root.string("query")
                val server = root.string("server_id")
                val limit = root.integer("limit", 5)
                val offset = root.integer("offset", 0)
                if (root.keys.any { it !in setOf("query", "server_id", "limit", "offset") } ||
                    query == null || ("server_id" in root && server == null) ||
                    limit == null || limit !in 1..10 || offset == null || offset < 0
                ) return error("Invalid search arguments: query must be a string, server_id an optional string, limit 1..10 and offset >= 0")
                registry.searchTools(query, server, limit, offset)
            }
            CALL -> {
                val id = root.string("tool_id")?.takeIf(String::isNotBlank)
                val inner = root["arguments"] as? JsonObject
                if (root.keys != setOf("tool_id", "arguments") || id == null || inner == null) {
                    return error("mcp_call_tool requires an exact tool_id and an arguments JSON object")
                }
                registry.execute(id, inner.toString())
            }
            else -> error("Unknown MCP entry: $name")
        }
    }

    private fun parseObject(arguments: String): JsonObject? =
        runCatching { json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.integer(key: String, default: Int): Int? =
        if (key !in this) default else (this[key] as? JsonPrimitive)
            ?.takeUnless { it.isString }?.intOrNull

    private fun error(message: String) = ToolExecutionResult(message, isError = true)
}
