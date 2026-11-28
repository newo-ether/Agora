package com.newoether.agora.mcp

import com.newoether.agora.data.McpServerConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class McpToolSearchTest {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"fields":{"type":"array","items":{"type":"object","properties":{"kind":{"enum":["text","checkbox"]}},"required":["kind"]}},"data":{"type":"object","additionalProperties":{"type":"string"}}},"required":["fields"]}""").jsonObject

    private fun tool(id: String, server: String = "a", name: String = "fill_form", description: String = "Edit browser form") =
        McpToolDescriptor(id, server, "Browser $server", McpRemoteTool(name, description, schema))

    private fun result(tools: List<McpToolDescriptor>, query: String = "", limit: Int = 5, offset: Int = 0, maxChars: Int = 100_000) =
        Json.parseToJsonElement(searchMcpTools(tools, query, limit = limit, offset = offset, maxChars = maxChars)).jsonObject

    @Test fun `browse pages and ties have stable ID order across duplicate names`() {
        val tools = listOf(tool("mcp_c", "c"), tool("mcp_a"), tool("mcp_b", "b"))
        val first = result(tools, limit = 2)
        assertEquals(listOf("mcp_a", "mcp_b"), first["tools"]!!.jsonArray.map { it.jsonObject["tool_id"]!!.jsonPrimitive.content })
        assertEquals(3, first["total"]!!.jsonPrimitive.int)
        assertEquals(2, first["next_offset"]!!.jsonPrimitive.int)
        val next = result(tools.reversed(), limit = 2, offset = 2)
        assertEquals("mcp_c", next["tools"]!!.jsonArray.single().jsonObject["tool_id"]!!.jsonPrimitive.content)
        assertEquals("null", next["next_offset"].toString())
    }

    @Test fun `exact ID isolates one definition and retains full nested schema`() {
        val page = result(listOf(tool("mcp_a"), tool("mcp_b", description = "mcp_a helper")), "mcp_a")
        assertEquals(1, page["total"]!!.jsonPrimitive.int)
        assertEquals(schema, page["tools"]!!.jsonArray.single().jsonObject["input_schema"])
    }

    @Test fun `keywords match parameter names and exact raw name ranks first`() {
        val tools = listOf(tool("mcp_a", name = "other"), tool("mcp_z"))
        assertEquals(2, result(tools, "kind") ["total"]!!.jsonPrimitive.int)
        assertEquals("mcp_z", result(tools, "fill_form")["tools"]!!.jsonArray.first().jsonObject["tool_id"]!!.jsonPrimitive.content)
        assertEquals(0, result(tools, "unmatched")["returned"]!!.jsonPrimitive.int)
    }

    @Test fun `server filter disabled tools empty and exhausted pages are safe`() {
        val tools = listOf(tool("mcp_a"), tool("mcp_b", "b"), tool("mcp_c").copy(enabled = false))
        val page = Json.parseToJsonElement(searchMcpTools(tools, "", serverId = "b")).jsonObject
        assertEquals("mcp_b", page["tools"]!!.jsonArray.single().jsonObject["tool_id"]!!.jsonPrimitive.content)
        assertEquals(0, result(emptyList())["returned"]!!.jsonPrimitive.int)
        assertEquals(0, result(tools, offset = Int.MAX_VALUE)["returned"]!!.jsonPrimitive.int)
        assertEquals(2, result(tools)["total"]!!.jsonPrimitive.int)
    }

    @Test fun `escaped complete item size is measured before generic clipping`() {
        val tools = listOf(tool("mcp_a", description = "\"\\\n".repeat(150)), tool("mcp_b"))
        val single = searchMcpTools(tools.take(1), "")
        val page = result(tools, maxChars = single.length)
        assertEquals(1, page["returned"]!!.jsonPrimitive.int)
        assertEquals(1, page["next_offset"]!!.jsonPrimitive.int)
        assertEquals(schema, page["tools"]!!.jsonArray.single().jsonObject["input_schema"])
        assertTrue(page.toString().length <= single.length)
        assertEquals("mcp_b", result(tools, offset = 1)["tools"]!!.jsonArray.single().jsonObject["tool_id"]!!.jsonPrimitive.content)
    }

    @Test fun `oversized schema produces explicit error and advances without partial JSON`() {
        val huge = tool("mcp_a").copy(remote = McpRemoteTool("huge", "x".repeat(110_000), schema))
        val raw = searchMcpTools(listOf(huge, tool("mcp_b")), "", limit = 1)
        val page = Json.parseToJsonElement(raw).jsonObject
        assertTrue(raw.length <= 100_000)
        val error = page["tools"]!!.jsonArray.single().jsonObject
        assertEquals("schema_too_large", error["error"]!!.jsonPrimitive.content)
        assertFalse(error.containsKey("input_schema"))
        assertEquals(1, page["next_offset"]!!.jsonPrimitive.int)
    }

    @Test fun `dispatch policy rejects current disable deletion and stale runtime before reconcile`() {
        val config = McpServerConfig(id = "a", name = "Browser", url = "https://example.test/mcp")
        val tool = tool("mcp_a")
        fun allowed(current: McpServerConfig? = config, runtime: McpServerConfig? = config, status: McpConnectionStatus = McpConnectionStatus.CONNECTED) =
            isMcpToolAvailable(tool, current, runtime, status)
        assertTrue(allowed())
        assertFalse(allowed(current = null))
        assertFalse(allowed(current = config.copy(enabled = false)))
        assertFalse(allowed(current = config.copy(disabledTools = setOf("fill_form"))))
        assertFalse(allowed(current = config.copy(url = "https://other.test/mcp")))
        assertFalse(allowed(current = config.copy(headers = mapOf("Authorization" to "new"))))
        assertFalse(allowed(runtime = null))
        McpConnectionStatus.entries.filter { it != McpConnectionStatus.CONNECTED }.forEach {
            assertFalse(allowed(status = it))
        }
    }
}
