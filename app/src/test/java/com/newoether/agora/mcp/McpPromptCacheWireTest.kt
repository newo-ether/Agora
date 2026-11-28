package com.newoether.agora.mcp

import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.anthropic.AnthropicProvider
import com.newoether.agora.api.gemini.GeminiProvider
import com.newoether.agora.api.ollama.OllamaProvider
import com.newoether.agora.api.openai.OpenAiProvider
import com.newoether.agora.api.util.tokens.ContextCostModel
import com.newoether.agora.api.util.tokens.CostModelContextEstimator
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.tool.McpToolProvider
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.CloudDebugLog
import com.newoether.agora.viewmodel.GenerationContext
import com.sun.net.httpserver.HttpServer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Captures production requests; the registry is the only mocked discovery boundary. */
class McpPromptCacheWireTest {
    private val registry = mockk<McpRegistry>()
    private val context = mockk<GenerationContext>(relaxed = true)
    private val mcp = McpToolProvider(registry)
    private var catalog = emptyList<McpToolDescriptor>()
    private var directory = EMPTY_DIRECTORY
    private val wireJson = Json { encodeDefaults = true; explicitNulls = false }

    @Before
    fun setUp() {
        mockkObject(DebugLog, CloudDebugLog)
        every { DebugLog.d(any(), any()) } just Runs
        every { DebugLog.e(any(), any()) } just Runs
        every { DebugLog.w(any(), any()) } just Runs
        every { CloudDebugLog.d(any(), any()) } just Runs
        every { CloudDebugLog.e(any(), any()) } just Runs
        every { CloudDebugLog.w(any(), any()) } just Runs
        // Configured names stay fixed while runtime catalog/directory state changes below.
        every { registry.enabledServerNames() } returns listOf("config" to "Config Server")
        every { registry.listServers(any(), any()) } answers { directory }
        every { registry.searchTools(any(), any(), any(), any()) } answers {
            ToolExecutionResult(searchMcpTools(catalog, firstArg(), secondArg(), thirdArg(), arg(3)))
        }
        every { registry.descriptor(any()) } answers {
            catalog.firstOrNull { it.publicName == firstArg<String>() }
        }
    }

    @After
    fun tearDown() {
        unmockkObject(DebugLog, CloudDebugLog)
    }

    @Test
    fun openAiChatKeepsWirePrefixAcrossServerChangesAndDiscovery() =
        assertStableWire(Transport.CHAT)

    @Test
    fun openAiResponsesKeepsWirePrefixAcrossServerChangesAndDiscovery() =
        assertStableWire(Transport.RESPONSES)

    @Test
    fun anthropicKeepsWirePrefixAndSelectedCacheTtlAcrossServerChangesAndDiscovery() {
        for (ttl in listOf("5m", "1h")) assertStableWire(Transport.ANTHROPIC, ttl = ttl)
    }

    @Test
    fun anthropicDisabledCacheStaysDisabledAcrossServerChangesAndDiscovery() =
        assertStableWire(Transport.ANTHROPIC, cacheEnabled = false)

    @Test
    fun geminiKeepsFunctionSchemaAndHistoryPrefixAcrossServerChangesAndDiscovery() =
        assertStableWire(Transport.GEMINI)

    @Test
    fun ollamaKeepsFunctionSchemaAndHistoryPrefixAcrossServerChangesAndDiscovery() =
        assertStableWire(Transport.OLLAMA)

    @Test
    fun localTemplateParameterSerializerKeepsOnlyTheThreeFixedSchemas() {
        val original = descriptor("alpha", "old-catalog-marker")
        val changed = descriptor("beta", "new-catalog-marker")
        catalog = listOf(original)
        directory = directoryResult(original, "old-directory-marker")
        val definitions = mcp.definitions(context)
        assertEntryDefinitions(definitions)
        // LocalProvider passes precisely ToolParameters' production serializer to applyTemplate.
        // This verifies that schema boundary without fabricating a Local HTTP request or JNI run.
        val parameters = definitions.map {
            wireJson.encodeToString(ToolParameters.serializer(), it.function.parameters)
        }
        val functions = definitions.map {
            wireJson.encodeToString(ToolDefinition.serializer(), it)
        }
        for ((label, next) in catalogChanges(original, changed)) {
            catalog = next
            val current = mcp.definitions(context)
            assertEntryDefinitions(current)
            assertEquals(label, parameters, current.map {
                wireJson.encodeToString(ToolParameters.serializer(), it.function.parameters)
            })
            assertEquals(label, functions, current.map {
                wireJson.encodeToString(ToolDefinition.serializer(), it)
            })
        }
        for ((label, next) in directoryChanges(changed)) {
            directory = next
            listServers()
            search()
            val current = mcp.definitions(context)
            assertEntryDefinitions(current)
            assertEquals(label, parameters, current.map {
                wireJson.encodeToString(ToolParameters.serializer(), it.function.parameters)
            })
            assertEquals(label, functions, current.map {
                wireJson.encodeToString(ToolDefinition.serializer(), it)
            })
        }
    }

    @Test
    fun directoryAndDiscoveredSchemasIncreaseHistoryCostWithoutChangingFixedToolCost() {
        val original = descriptor("alpha", "old-catalog-marker")
        catalog = listOf(original)
        directory = directoryResult(original, "old-directory-marker")
        val estimator = CostModelContextEstimator(ContextCostModel.Default)
        val definitions = mcp.definitions(context)
        assertEntryDefinitions(definitions)
        val fixed = estimator.estimateFixedComposition(SYSTEM_PROMPT, definitions)
        assertTrue(fixed.toolTokens > 0)
        val user = ChatMessage(id = "user", text = "List servers and search shared tools", participant = Participant.USER)
        val history = listOf(user) + listRound("cost-list", listServers()) + searchRound("cost-search", search())
        for (name in listOf("mcp_list_servers", "mcp_search_tools")) {
            val withoutResult = history.map { message ->
                val toolCall = message.toolCall
                if (toolCall?.toolName == name) message.copy(toolCall = toolCall.copy(result = ""))
                else message
            }
            assertTrue(
                "$name results must be charged to ordinary tool-result history",
                estimator.estimate(history) > estimator.estimate(withoutResult),
            )
        }
        catalog = emptyList()
        directory = EMPTY_DIRECTORY
        assertEquals(fixed, estimator.estimateFixedComposition(SYSTEM_PROMPT, mcp.definitions(context)))
        val changed = descriptor("beta", "new-catalog-marker")
        catalog = listOf(changed)
        directory = directoryResult(changed, "new-directory-marker")
        listServers()
        search()
        assertEquals(fixed, estimator.estimateFixedComposition(SYSTEM_PROMPT, mcp.definitions(context)))
    }

    private fun assertStableWire(
        transport: Transport,
        ttl: String = "1h",
        cacheEnabled: Boolean = true,
    ) = RecordingServer(transport).use { server ->
        val original = descriptor("alpha", "old-catalog-marker")
        val changed = descriptor("beta", "new-catalog-marker")
        catalog = listOf(original)
        directory = directoryResult(original, "old-directory-marker")
        val oldDirectory = listServers()
        val oldResult = search()
        assertEquals(directory, oldDirectory)
        assertTrue("Search must return the complete nested schema", containsSchema(
            Json.parseToJsonElement(oldResult), original.remote.inputSchema,
        ))
        val history = listOf(ChatMessage(id = "user-1", text = "List servers and find shared tools", participant = Participant.USER)) +
            listRound("first-list", oldDirectory) + searchRound("first-search", oldResult) + listOf(
                ChatMessage(id = "answer-1", text = "Found the shared tool.", participant = Participant.MODEL),
                ChatMessage(id = "user-2", text = "List servers and search again", participant = Participant.USER),
            )
        val savedHistory = history.toList()
        val config = ProviderConfig(
            apiKey = "",
            modelId = transport.model,
            baseUrl = server.baseUrl,
            systemPrompt = SYSTEM_PROMPT,
            maxContextWindow = 32_768,
            thinkingEnabled = false,
            responsesApiEnabled = transport == Transport.RESPONSES,
            promptCacheKey = CACHE_KEY,
            anthropicCacheEnabled = cacheEnabled,
            anthropicCacheTtl = ttl,
        )
        val baseline = capture(server, transport, config, history)
        val baselineBody = Json.parseToJsonElement(baseline.decodeToString()).jsonObject
        assertWireDefinitions(transport, baselineBody)
        assertCacheSettings(transport, baselineBody, ttl, cacheEnabled)
        assertToolResultOnWire(transport, baselineBody, oldDirectory)
        assertToolResultOnWire(transport, baselineBody, oldResult)
        val baselineTools = baselineBody.getValue("tools").toString()
        assertFalse(baselineTools.contains("old-catalog-marker"))
        assertFalse(baselineTools.contains("old-directory-marker"))
        assertFalse(baselineTools.contains(original.publicName))
        assertFalse(baselineTools.contains(original.serverId))
        val baselineSystem = systemProjection(transport, baselineBody)
        assertTrue(baselineSystem.contains(SYSTEM_PROMPT))
        assertFalse(baselineSystem.contains("old-catalog-marker"))
        assertFalse(baselineSystem.contains("old-directory-marker"))
        assertFalse(baselineSystem.contains(original.serverId))

        for ((label, next) in catalogChanges(original, changed)) {
            catalog = next
            search()
            assertArrayEquals(
                "${transport.name}: $label must not rewrite persisted discovery history or the request",
                baseline,
                capture(server, transport, config, history),
            )
            assertEquals(savedHistory, history)
        }
        for ((label, next) in directoryChanges(changed)) {
            directory = next
            assertEquals("$label must be observable only in a new list result", next, listServers())
            assertArrayEquals(
                "${transport.name}: $label must not change fixed definitions, system or stored results",
                baseline,
                capture(server, transport, config, history),
            )
            assertEquals(savedHistory, history)
        }

        catalog = listOf(changed)
        directory = directoryResult(changed, "new-directory-marker")
        val newDirectory = listServers()
        assertFalse("New listing must observe the current directory", oldDirectory == newDirectory)
        val withNewList = history + listRound("second-list", newDirectory)
        val afterListWire = capture(server, transport, config, withNewList)
        assertAppendOnlyWire(transport, baseline, afterListWire)
        val afterList = Json.parseToJsonElement(afterListWire.decodeToString()).jsonObject
        assertToolResultOnWire(transport, afterList, newDirectory)
        assertToolResultOnWire(transport, afterList, oldDirectory)
        assertToolResultOnWire(transport, afterList, oldResult)

        val newResult = search()
        assertFalse("New discovery must observe the new catalog", oldResult == newResult)
        assertTrue(containsSchema(Json.parseToJsonElement(newResult), changed.remote.inputSchema))
        val appended = withNewList + searchRound("second-search", newResult)
        val afterWire = capture(server, transport, config, appended)
        assertAppendOnlyWire(transport, afterListWire, afterWire)
        val after = Json.parseToJsonElement(afterWire.decodeToString()).jsonObject
        assertEquals(baselineTools, after.getValue("tools").toString())
        assertEquals(baselineSystem, systemProjection(transport, after))
        assertCacheSettings(transport, after, ttl, cacheEnabled)
        for (result in listOf(oldDirectory, oldResult, newDirectory, newResult)) {
            assertToolResultOnWire(transport, after, result)
        }
        val beforeHistory = baselineBody.getValue(transport.historyField).jsonArray
        val afterHistory = after.getValue(transport.historyField).jsonArray
        val appendedText = JsonArray(afterHistory.drop(beforeHistory.size)).toString()
        assertTrue(appendedText.contains("new-catalog-marker"))
        assertTrue(appendedText.contains("new-directory-marker"))
        assertFalse(appendedText.contains("old-catalog-marker"))
        assertFalse(appendedText.contains("old-directory-marker"))
        assertEquals(savedHistory, history)
        assertEquals(oldDirectory, history.first { it.id == Constants.RESULT_MSG_PREFIX + "first-list" }.toolCall!!.result)
        assertEquals(oldResult, history.first { it.id == Constants.RESULT_MSG_PREFIX + "first-search" }.toolCall!!.result)
    }

    private fun assertAppendOnlyWire(transport: Transport, before: ByteArray, after: ByteArray) {
        val beforeBody = Json.parseToJsonElement(before.decodeToString()).jsonObject
        val afterBody = Json.parseToJsonElement(after.decodeToString()).jsonObject
        val beforeHistory = beforeBody.getValue(transport.historyField).jsonArray
        val afterHistory = afterBody.getValue(transport.historyField).jsonArray
        assertTrue(afterHistory.size > beforeHistory.size)
        assertEquals(beforeHistory.toString(), JsonArray(afterHistory.take(beforeHistory.size)).toString())
        // Confirm these are unchanged bytes in the actual compact wire array, not just equal JSON values.
        assertTrue("${transport.name}: baseline history must appear verbatim", before.decodeToString().contains(beforeHistory.toString()))
        assertTrue(
            "${transport.name}: appending discovery must retain the previous wire history bytes",
            after.decodeToString().contains(beforeHistory.toString().dropLast(1) + ","),
        )
        assertEquals(
            beforeBody.filterKeys { it != transport.historyField },
            afterBody.filterKeys { it != transport.historyField },
        )
    }

    private fun capture(
        server: RecordingServer,
        transport: Transport,
        config: ProviderConfig,
        history: List<ChatMessage>,
    ): ByteArray {
        val definitions = mcp.definitions(context)
        assertEntryDefinitions(definitions)
        val events = runBlocking {
            withTimeout(5_000L) {
                transport.provider().generateResponse(history, config.copy(tools = definitions)).toList()
            }
        }
        assertTrue("${transport.name}: $events", events.none { it is StreamEvent.Error })
        assertTrue(events.any { it is StreamEvent.TextChunk && it.text == "ok" })
        return server.takeRequest()
    }

    private fun listServers(): String = runBlocking {
        mcp.execute("mcp_list_servers", LIST_ARGUMENTS, context)
    }

    private fun search(): String = runBlocking {
        mcp.execute("mcp_search_tools", SEARCH_ARGUMENTS, context)
    }

    private fun listRound(id: String, result: String) =
        discoveryRound(id, "mcp_list_servers", LIST_ARGUMENTS, result)

    private fun searchRound(id: String, result: String) =
        discoveryRound(id, "mcp_search_tools", SEARCH_ARGUMENTS, result)

    private fun discoveryRound(id: String, name: String, arguments: String, result: String): List<ChatMessage> {
        val call = ToolCallData(
            toolName = name,
            arguments = arguments,
            result = result,
            toolCallId = "call-$id",
        )
        return listOf(
            ChatMessage(
                id = Constants.TOOL_MSG_PREFIX + id, text = "", participant = Participant.MODEL,
                toolCall = call.copy(result = ""),
            ),
            ChatMessage(id = Constants.RESULT_MSG_PREFIX + id, text = "", participant = Participant.MODEL, toolCall = call),
        )
    }

    private fun assertEntryDefinitions(definitions: List<ToolDefinition>) {
        assertEquals(listOf("mcp_list_servers", "mcp_search_tools", "mcp_call_tool"), definitions.map { it.function.name })
    }

    private fun assertWireDefinitions(transport: Transport, body: JsonObject) {
        val tools = body.getValue("tools").jsonArray
        val functions = when (transport) {
            Transport.GEMINI -> tools.single().jsonObject.getValue("function_declarations").jsonArray.map { it.jsonObject }
            Transport.CHAT, Transport.OLLAMA -> tools.map { it.jsonObject.getValue("function").jsonObject }
            else -> tools.map { it.jsonObject }
        }
        assertEquals(listOf("mcp_list_servers", "mcp_search_tools", "mcp_call_tool"), functions.map { it.getValue("name").jsonPrimitive.content })
        functions.forEach { assertTrue(it.getValue("description").jsonPrimitive.content.isNotBlank()) }
        val call = functions.last()
        val schema = (call["parameters"] ?: call["input_schema"] ?: call.getValue("parameters_json_schema")).jsonObject
        val arguments = schema.getValue("properties").jsonObject.getValue("arguments").jsonObject
        assertEquals("object", arguments.getValue("type").jsonPrimitive.content)
    }

    private fun assertCacheSettings(transport: Transport, body: JsonObject, ttl: String, enabled: Boolean) {
        when (transport) {
            Transport.CHAT, Transport.RESPONSES ->
                assertEquals(CACHE_KEY, body.getValue("prompt_cache_key").jsonPrimitive.content)
            Transport.ANTHROPIC -> if (enabled) {
                val control = body.getValue("cache_control").jsonObject
                assertEquals("ephemeral", control.getValue("type").jsonPrimitive.content)
                assertEquals(ttl, control.getValue("ttl").jsonPrimitive.content)
            } else {
                assertFalse(body.containsKey("cache_control"))
            }
            else -> Unit
        }
    }

    private fun assertToolResultOnWire(transport: Transport, body: JsonObject, result: String) {
        val history = body.getValue(transport.historyField).jsonArray
        val resultPayloads = when (transport) {
            Transport.CHAT -> history.filter { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }
                .map { it.jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content }
            Transport.RESPONSES -> history.filter {
                it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
            }.map { it.jsonObject.getValue("output").jsonPrimitive.content }
            Transport.ANTHROPIC -> history.flatMap { it.jsonObject.getValue("content").jsonArray }
                .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_result" }
                .map { it.jsonObject.getValue("content").jsonPrimitive.content }
            Transport.GEMINI -> history.flatMap { it.jsonObject.getValue("parts").jsonArray }
                .mapNotNull { it.jsonObject["functionResponse"]?.jsonObject?.get("response")?.toString() }
            Transport.OLLAMA -> history.filter { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }
                .map { it.jsonObject.getValue("content").jsonPrimitive.content }
        }
        assertTrue(
            "${transport.name}: the exact discovery result must remain an ordinary protocol tool result",
            if (transport == Transport.GEMINI) {
                resultPayloads.any { Json.parseToJsonElement(it) == Json.parseToJsonElement(result) }
            } else result in resultPayloads,
        )
    }

    private fun systemProjection(transport: Transport, body: JsonObject): String = when (transport) {
        Transport.ANTHROPIC -> body.getValue("system").toString()
        Transport.GEMINI -> body.getValue("systemInstruction").toString()
        else -> JsonArray(body.getValue(transport.historyField).jsonArray.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "system"
        }).toString()
    }

    private fun descriptor(name: String, marker: String) = McpToolDescriptor(
        publicName = "mcp_fixture_$name",
        serverId = "server-$name",
        serverName = "Shared server $name",
        remote = McpRemoteTool(
            name = "shared_$name",
            description = "Shared fixture $marker",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"fields":{"type":"array","items":{"type":"object","properties":{"mode":{"type":"string","enum":["$marker","other"]},"data":{"type":"object","additionalProperties":{"type":"string"}}},"required":["mode","data"],"additionalProperties":false}}},"required":["fields"],"additionalProperties":false}""",
            ).jsonObject,
        ),
    )

    // Discovery API fixtures isolate serialization from the real registry's lifecycle and network.
    private fun catalogChanges(original: McpToolDescriptor, changed: McpToolDescriptor) = listOf(
        "add" to listOf(original, changed),
        "reorder" to listOf(changed, original),
        "remove" to listOf(changed),
        "rename server and tool" to listOf(original.copy(
            serverName = "Renamed server", remote = original.remote.copy(name = "renamed", description = "Renamed tool"),
        )),
        "schema refresh" to listOf(original.copy(remote = original.remote.copy(inputSchema = changed.remote.inputSchema))),
        "disable, disconnect or delete all servers" to emptyList(),
        "enable or reconnect" to listOf(original),
    )

    private fun directoryChanges(tool: McpToolDescriptor) = listOf(
        "no configured or enabled servers" to EMPTY_DIRECTORY,
        "replace configured server" to directoryResult(tool, "changed-directory-marker"),
        "rename configured server" to directoryResult(tool.copy(serverName = "Renamed configured server"), "renamed-directory-marker"),
        "connecting after configuration change" to directoryResult(tool, "connecting-directory-marker", "connecting", 0),
        "connection error" to directoryResult(tool, "error-directory-marker", "error", 0),
        "disconnected server" to directoryResult(tool, "idle-directory-marker", "idle", 0),
        "connected server without available tools" to directoryResult(tool, "empty-directory-marker", "connected", 0),
        "reconnected server" to directoryResult(tool, "reconnected-directory-marker"),
    )

    private fun directoryResult(
        tool: McpToolDescriptor,
        marker: String,
        status: String = "connected",
        availableToolCount: Int = 1,
    ): String {
        val item = buildJsonObject {
            put("server_id", tool.serverId)
            put("server_name", "${tool.serverName} $marker")
            put("status", status)
            put("available_tool_count", availableToolCount)
        }
        return """{"servers":[$item],"returned":1,"total":1,"next_offset":null}"""
    }

    private fun containsSchema(value: JsonElement, schema: JsonObject): Boolean = when (value) {
        is JsonObject -> value == schema || value.values.any { containsSchema(it, schema) }
        is JsonArray -> value.any { containsSchema(it, schema) }
        else -> false
    }

    private enum class Transport(val model: String, val historyField: String) {
        CHAT("gpt-4o", "messages"),
        RESPONSES("gpt-4o", "input"),
        ANTHROPIC("claude-3-5-sonnet-20240620", "messages"),
        GEMINI("gemini-2.5-flash", "contents"),
        OLLAMA("qwen3:8b", "messages");

        fun provider(): LlmProvider = when (this) {
            CHAT, RESPONSES -> OpenAiProvider()
            ANTHROPIC -> AnthropicProvider()
            GEMINI -> GeminiProvider()
            OLLAMA -> OllamaProvider()
        }

        fun response(): String = when (this) {
            CHAT -> "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            RESPONSES -> "data: {\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"delta\":\"ok\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"sequence_number\":2,\"response\":{\"status\":\"completed\"}}\n\n"
            ANTHROPIC -> listOf(
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}",
                "{\"type\":\"message_stop\"}",
            ).joinToString(separator = "\n\n", postfix = "\n\n") { "data: $it" }
            GEMINI -> "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}\n\n"
            OLLAMA -> "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":1,\"eval_count\":1}\n"
        }
    }

    private class RecordingServer(transport: Transport) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val requests = LinkedBlockingQueue<ByteArray>()
        val baseUrl = "http://127.0.0.1:${server.address.port}/v1"

        init {
            server.createContext("/") { exchange ->
                requests.add(exchange.requestBody.use { it.readBytes() })
                val bytes = transport.response().toByteArray()
                exchange.responseHeaders.add("Content-Type", if (transport == Transport.OLLAMA) "application/x-ndjson" else "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun takeRequest(): ByteArray = checkNotNull(requests.poll(1, TimeUnit.SECONDS))
        override fun close() = server.stop(0)
    }

    private companion object {
        const val SYSTEM_PROMPT = "Keep the established conversation prefix."
        const val CACHE_KEY = "mcp-cache-fixture-conversation"
        const val LIST_ARGUMENTS = "{\"limit\":20,\"offset\":0}"
        const val SEARCH_ARGUMENTS = "{\"query\":\"shared\",\"limit\":5}"
        const val EMPTY_DIRECTORY = "{\"servers\":[],\"returned\":0,\"total\":0,\"next_offset\":null}"
    }
}
