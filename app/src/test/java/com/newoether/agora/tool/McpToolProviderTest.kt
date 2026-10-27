package com.newoether.agora.tool

import com.newoether.agora.api.util.validateToolDefinitions
import com.newoether.agora.mcp.McpRegistry
import com.newoether.agora.mcp.McpRemoteTool
import com.newoether.agora.mcp.McpToolDescriptor
import com.newoether.agora.mcp.searchMcpTools
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.Called
import io.mockk.confirmVerified
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class McpToolProviderTest {
    private val registry = mockk<McpRegistry>()
    private val context = mockk<GenerationContext>()
    private val provider = McpToolProvider(registry)
    private val descriptor = McpToolDescriptor(
        "mcp_browser_fill_123456", "browser", "Browser",
        McpRemoteTool("fill_form", "Fill a form", JsonObject(emptyMap())),
    )

    init {
        every { registry.enabledServerNames() } returns emptyList()
    }

    @Test fun `definitions are fixed when no registry state is available and only entries are handled`() {
        val definitions = provider.definitions(context)
        assertEquals(listOf("mcp_list_servers", "mcp_search_tools", "mcp_call_tool"), definitions.map { it.function.name })
        assertTrue(validateToolDefinitions(definitions).isEmpty())
        assertTrue(provider.handles("mcp_list_servers"))
        assertTrue(provider.handles("mcp_search_tools"))
        assertTrue(provider.handles("mcp_call_tool"))
        assertFalse(provider.handles(descriptor.publicName))
        // Only configuration is read; no listing, search, dispatch or runtime state.
        verify { registry.enabledServerNames() }
        confirmVerified(registry)
    }

    @Test fun `enabled server names reach only the search description`() {
        val base = provider.definitions(context)
        every { registry.enabledServerNames() } returns listOf("blender" to "Blender \"MCP\"", "home" to "Home")
        val named = provider.definitions(context)
        assertEquals(base.filter { it.function.name != "mcp_search_tools" }, named.filter { it.function.name != "mcp_search_tools" })
        assertEquals(base.map { it.function.parameters }, named.map { it.function.parameters })
        val search = named.single { it.function.name == "mcp_search_tools" }.function.description
        assertTrue(search.endsWith("""[{"id":"blender","name":"Blender \"MCP\""},{"id":"home","name":"Home"}]"""))
        assertEquals(named, provider.definitions(context))
        assertTrue(validateToolDefinitions(named).isEmpty())
    }

    @Test fun `listing uses bounded defaults and pagination without changing definitions`() = runTest {
        val expected = """{"servers":[],"returned":0,"total":0,"next_offset":null}"""
        every { registry.listServers(20, 0) } returns expected
        every { registry.listServers(2, 4) } returns expected
        val before = provider.definitions(context)
        assertEquals(expected, provider.executeOneShotResult("mcp_list_servers", "{}", context).text)
        assertEquals(expected, provider.executeOneShotResult("mcp_list_servers", """{"limit":2,"offset":4}""", context).text)
        assertEquals(before, provider.definitions(context))
        verify(exactly = 1) { registry.listServers(20, 0) }
        verify(exactly = 1) { registry.listServers(2, 4) }
        coVerify(exactly = 0) { registry.execute(any(), any()) }
    }

    @Test fun `search returns full schema and never mutates exposed definitions`() = runTest {
        every { registry.searchTools("", null, 5, 0) } returns
            ToolExecutionResult(searchMcpTools(listOf(descriptor), ""))
        val before = provider.definitions(context)
        val result = provider.executeOneShotResult("mcp_search_tools", """{"query":""}""", context)
        assertFalse(result.isError)
        assertTrue(result.text.contains(descriptor.publicName))
        assertTrue(result.text.contains("input_schema"))
        assertEquals(before, provider.definitions(context))
        coVerify(exactly = 0) { registry.execute(any(), any()) }
    }

    @Test fun `exact search scope and registry errors pass through unchanged`() = runTest {
        val expected = ToolExecutionResult("""{"error":"server_unavailable","status":"error"}""", isError = true)
        every { registry.searchTools("fill", "browser", 2, 3) } returns expected
        assertEquals(expected, provider.executeOneShotResult(
            "mcp_search_tools", """{"query":"fill","server_id":"browser","limit":2,"offset":3}""", context,
        ))
        verify(exactly = 1) { registry.searchTools("fill", "browser", 2, 3) }
        verify(exactly = 0) { registry.searchTools(any(), null, any(), any()) }
    }

    @Test fun `router preserves nested argument values rich output and errors`() = runTest {
        val arguments = """{"fields":[{"kind":"checkbox","value":true}],"data":{"text/plain":"hello"}}"""
        val image = ToolImageAttachment("image.png", "image/png", 10)
        val expected = ToolExecutionResult("body", listOf(image), "{\"a\":1}", "shown", isError = true)
        coEvery { registry.execute(descriptor.publicName, arguments) } returns expected
        val actual = provider.executeOneShotResult(
            "mcp_call_tool", """{"tool_id":"${descriptor.publicName}","arguments":$arguments}""", context,
        )
        assertEquals(expected, actual)
        coVerify(exactly = 1) { registry.execute(descriptor.publicName, arguments) }
    }

    @Test fun `invalid outer shapes never reach registry`() = runTest {
        val invalid = listOf(
            "{", "[]", "{}", """{"tool_id":7,"arguments":{}}""",
            """{"tool_id":"x","arguments":"{}"}""", """{"tool_id":"x","arguments":[]}""",
            """{"tool_id":"x","arguments":{},"url":"https://example.test"}""",
        )
        invalid.forEach { assertTrue(provider.executeOneShotResult("mcp_call_tool", it, context).isError) }
        listOf("{}", """{"query":null}""", """{"query":"","limit":11}""", """{"query":"","offset":-1}""", """{"query":"","limit":"2"}""", """{"query":"","server_id":null}""").forEach {
            assertTrue(provider.executeOneShotResult("mcp_search_tools", it, context).isError)
        }
        listOf("[]", """{"limit":0}""", """{"limit":51}""", """{"limit":"2"}""", """{"offset":-1}""", """{"server_id":"x"}""", """{"limit":null}""").forEach {
            assertTrue(provider.executeOneShotResult("mcp_list_servers", it, context).isError)
        }
        verify { registry wasNot Called }
    }

    @Test fun `unknown target error passes through and cancellation is never converted to success`() = runTest {
        coEvery { registry.execute("unknown", "{}") } returns ToolExecutionResult("unavailable", isError = true)
        assertTrue(provider.executeOneShotResult("mcp_call_tool", """{"tool_id":"unknown","arguments":{}}""", context).isError)
        coEvery { registry.execute("cancel", "{}") } throws CancellationException("Stop")
        try {
            provider.executeOneShotResult("mcp_call_tool", """{"tool_id":"cancel","arguments":{}}""", context)
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("Stop", expected.message)
        }
    }

    @Test fun `complete reverse ordered large arguments resolve real metadata at execution`() = runTest {
        every { registry.descriptor(descriptor.publicName) } returns descriptor
        coEvery { registry.execute(descriptor.publicName, any()) } returns ToolExecutionResult("done")
        val args = """{"arguments":{"content":"${"x".repeat(12_000)}"},"tool_id":"${descriptor.publicName}"}"""
        val events = provider.executeEvents("mcp_call_tool", args, context).toList()
        assertEquals(ToolExecutionEvent.TargetResolved("Browser", "fill_form"), events.first())
        assertEquals("done", (events.last() as ToolExecutionEvent.Completed).result.text)
        val parsed = Json.parseToJsonElement(args).jsonObject
        coVerify { registry.execute(descriptor.publicName, parsed["arguments"].toString()) }
    }
}
