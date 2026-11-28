package com.newoether.agora.mcp

import android.content.Context
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.tool.ToolImageStore
import com.newoether.agora.util.DebugLog
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpServerDirectoryTest {
    private val server = McpServerConfig(
        id = "server-a", name = "Duplicate name", url = "https://private-endpoint.invalid/mcp",
        headers = mapOf("Authorization" to "private-header-value"),
    )
    private val remote = McpRemoteTool("fixture_tool", "Fixture", JsonObject(emptyMap()))

    @Before fun interceptRuntime() {
        mockkConstructor(McpProtocolClient::class, ToolImageStore::class)
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any(), any()) } just Runs
        every { anyConstructed<McpProtocolClient>().close() } just Runs
        coEvery { anyConstructed<McpProtocolClient>().listTools() } returns listOf(remote)
    }

    @After fun releaseRuntime() {
        unmockkConstructor(McpProtocolClient::class, ToolImageStore::class)
        unmockkObject(DebugLog)
    }

    @Test fun `enabled idle directory is sorted paginated and read only before reconcile`() = runTest {
        val configs = MutableStateFlow(listOf(
            server.copy(id = "server-z"), server.copy(id = "disabled", enabled = false), server,
        ))
        val registry = registry(configs)
        val before = registry.snapshots.value
        val page = parse(registry.listServers(limit = 1))
        assertEquals(2, page.number("total"))
        assertEquals(1, page.number("returned"))
        assertEquals(1, page.number("next_offset"))
        assertEquals("server-a", page.servers().single().text("server_id"))
        assertEquals("idle", page.servers().single().text("status"))
        assertEquals(0, page.servers().single().number("available_tool_count"))
        assertEquals("server-z", parse(registry.listServers(1, 1)).servers().single().text("server_id"))
        assertTrue(parse(registry.listServers(offset = Int.MAX_VALUE)).servers().isEmpty())
        assertSame(before, registry.snapshots.value)
        assertEquals(3, configs.value.size)
        coVerify(exactly = 0) { anyConstructed<McpProtocolClient>().listTools() }
        coVerify(exactly = 0) { anyConstructed<McpProtocolClient>().callTool(any(), any()) }
        verify(exactly = 0) { anyConstructed<McpProtocolClient>().close() }
    }

    @Test fun `connected duplicate name servers stay distinct and exact scope never widens`() = runTest {
        val configs = MutableStateFlow(listOf(server.copy(id = "server-z"), server))
        val registry = registry(configs)
        runCurrent()
        val before = registry.snapshots.value
        val raw = registry.listServers()
        val entries = parse(raw).servers()
        assertEquals(listOf("server-a", "server-z"), entries.map { it.text("server_id") })
        entries.forEach {
            assertEquals(setOf("server_id", "server_name", "status", "available_tool_count"), it.keys)
            assertEquals("connected", it.text("status"))
            assertEquals(1, it.number("available_tool_count"))
        }
        listOf("private-endpoint", "Authorization", "private-header-value", "input_schema").forEach {
            assertFalse(raw.contains(it))
        }
        assertEquals(2, parse(registry.searchTools("").text).number("total"))
        val scoped = registry.searchTools("", "server-z")
        assertFalse(scoped.isError)
        assertEquals("server-z", parse(scoped.text)["tools"]!!.jsonArray.single().jsonObject.text("server_id"))
        val crossServerId = publicMcpToolName("server-a", remote.name)
        assertEquals(0, parse(registry.searchTools(crossServerId, "server-z").text).number("returned"))
        assertEquals(0, parse(registry.searchTools("does-not-match", "server-z").text).number("returned"))
        val invalid = registry.searchTools("", "missing")
        assertTrue(invalid.isError)
        assertEquals("invalid_server", parse(invalid.text).text("error"))
        assertSame(before, registry.snapshots.value)
        coVerify(exactly = 2) { anyConstructed<McpProtocolClient>().listTools() }
        coVerify(exactly = 0) { anyConstructed<McpProtocolClient>().callTool(any(), any()) }
        verify(exactly = 0) { anyConstructed<McpProtocolClient>().close() }
    }

    @Test fun `connecting and connection error states expose no retained tools or raw exception`() = runTest {
        val waiting = CompletableDeferred<List<McpRemoteTool>>()
        coEvery { anyConstructed<McpProtocolClient>().listTools() } coAnswers { waiting.await() }
        val registry = registry(MutableStateFlow(listOf(server)))
        runCurrent()
        val connecting = parse(registry.listServers()).servers().single()
        assertEquals("connecting", connecting.text("status"))
        assertEquals(0, connecting.number("available_tool_count"))
        assertEquals("server_unavailable", parse(registry.searchTools("", server.id).text).text("error"))
        waiting.completeExceptionally(IOException("private-error-detail private-header-value"))
        runCurrent()
        val before = registry.snapshots.value
        val raw = registry.listServers()
        assertEquals("error", parse(raw).servers().single().text("status"))
        assertEquals(0, parse(raw).servers().single().number("available_tool_count"))
        assertFalse(raw.contains("private-error-detail"))
        val error = registry.searchTools("", server.id)
        assertTrue(error.isError)
        assertEquals("server_unavailable", parse(error.text).text("error"))
        assertEquals("error", parse(error.text).text("status"))
        assertFalse(error.text.contains("private-header-value"))
        assertSame(before, registry.snapshots.value)
        coVerify(exactly = 1) { anyConstructed<McpProtocolClient>().listTools() }
    }

    @Test fun `refresh publishes connecting before closing the previous runtime`() = runTest {
        val registry = registry(MutableStateFlow(listOf(server)))
        runCurrent()
        assertEquals("connected", parse(registry.listServers()).servers().single().text("status"))
        var closeObserved = false
        every { anyConstructed<McpProtocolClient>().close() } answers {
            closeObserved = true
            val entry = parse(registry.listServers()).servers().single()
            assertEquals("connecting", entry.text("status"))
            assertEquals(0, entry.number("available_tool_count"))
            val scoped = registry.searchTools("", server.id)
            assertTrue(scoped.isError)
            assertEquals("server_unavailable", parse(scoped.text).text("error"))
            assertEquals("connecting", parse(scoped.text).text("status"))
            assertEquals(0, parse(registry.searchTools("").text).number("returned"))
        }

        registry.refresh(server.id)
        runCurrent()

        assertTrue(closeObserved)
        verify(exactly = 1) { anyConstructed<McpProtocolClient>().close() }
        coVerify(exactly = 2) { anyConstructed<McpProtocolClient>().listTools() }
        val connected = parse(registry.listServers()).servers().single()
        assertEquals("connected", connected.text("status"))
        assertEquals(1, connected.number("available_tool_count"))
        assertFalse(registry.searchTools("", server.id).isError)
    }

    @Test fun `connected server with zero tools is a valid empty search scope`() = runTest {
        coEvery { anyConstructed<McpProtocolClient>().listTools() } returns emptyList()
        val registry = registry(MutableStateFlow(listOf(server)))
        runCurrent()
        val entry = parse(registry.listServers()).servers().single()
        assertEquals("connected", entry.text("status"))
        assertEquals(0, entry.number("available_tool_count"))
        val result = registry.searchTools("", server.id)
        assertFalse(result.isError)
        assertEquals(0, parse(result.text).number("total"))
    }

    @Test fun `all disabled remote tools leave connected scope with successful empty page`() = runTest {
        val registry = registry(MutableStateFlow(listOf(server.copy(disabledTools = setOf(remote.name)))))
        runCurrent()
        assertEquals(0, parse(registry.listServers()).servers().single().number("available_tool_count"))
        val result = registry.searchTools("", server.id)
        assertFalse(result.isError)
        assertEquals(0, parse(result.text).number("returned"))
    }

    @Test fun `current rename endpoint disable and removal override stale connected snapshot`() = runTest {
        val configs = MutableStateFlow(listOf(server))
        val registry = registry(configs)
        runCurrent()
        val oldSnapshot = registry.snapshots.value[server.id]
        configs.value = listOf(server.copy(name = "Renamed", url = "https://replacement.invalid/mcp"))
        val entry = parse(registry.listServers()).servers().single()
        assertEquals("Renamed", entry.text("server_name"))
        assertEquals("idle", entry.text("status"))
        assertEquals(0, entry.number("available_tool_count"))
        assertEquals("server_unavailable", parse(registry.searchTools("", server.id).text).text("error"))
        assertSame(oldSnapshot, registry.snapshots.value[server.id])
        configs.value = listOf(server.copy(enabled = false))
        assertTrue(parse(registry.listServers()).servers().isEmpty())
        assertEquals("invalid_server", parse(registry.searchTools("", server.id).text).text("error"))
        configs.value = emptyList()
        assertTrue(parse(registry.listServers()).servers().isEmpty())
        assertEquals("invalid_server", parse(registry.searchTools("", server.id).text).text("error"))
        coVerify(exactly = 1) { anyConstructed<McpProtocolClient>().listTools() }
        verify(exactly = 0) { anyConstructed<McpProtocolClient>().close() }
    }

    @Test fun `constructor failure has current error provenance and blank names never reveal URL`() = runTest {
        val configs = MutableStateFlow(listOf(server.copy(name = "", url = "invalid-scheme://private.invalid")))
        val registry = registry(configs)
        runCurrent()
        val raw = registry.listServers()
        val entry = parse(raw).servers().single()
        assertEquals("MCP server", entry.text("server_name"))
        assertEquals("error", entry.text("status"))
        assertFalse(raw.contains("private.invalid"))
        assertEquals("error", parse(registry.searchTools("", server.id).text).text("status"))
        configs.value = listOf(server)
        assertEquals("idle", parse(registry.listServers()).servers().single().text("status"))
        runCurrent()
        assertEquals("connected", parse(registry.listServers()).servers().single().text("status"))
    }

    @Test fun `directory serialization measures escaped complete entries and advances oversized items`() {
        fun entry(id: String, name: String) = JsonObject(mapOf(
            "server_id" to kotlinx.serialization.json.JsonPrimitive(id),
            "server_name" to kotlinx.serialization.json.JsonPrimitive(name),
        ))
        val entries = listOf(entry("a", "\"\\\n".repeat(120)), entry("b", "small"))
        val boundary = listMcpServers(entries.take(1)).length
        val first = listMcpServers(entries, maxChars = boundary)
        assertTrue(first.length <= boundary)
        assertEquals(entries.first(), parse(first).servers().single())
        assertEquals(1, parse(first).number("next_offset"))
        assertEquals(entries.last(), parse(listMcpServers(entries, offset = 1)).servers().single())
        val huge = listOf(entry("x".repeat(110_000), "huge"), entries.last())
        val raw = listMcpServers(huge, limit = 1)
        assertTrue(raw.length <= 100_000)
        val error = parse(raw).servers().single()
        assertEquals("server_entry_too_large", error.text("error"))
        assertFalse(error.containsKey("server_id"))
        assertEquals(1, parse(raw).number("next_offset"))
        assertEquals(entries.last(), parse(listMcpServers(huge, offset = 1)).servers().single())
    }

    private fun TestScope.registry(configs: MutableStateFlow<List<McpServerConfig>>): McpRegistry {
        val settings = mockk<SettingsRepository>()
        every { settings.mcpServers } returns configs
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"))
        return McpRegistry(context, settings, backgroundScope, StandardTestDispatcher(testScheduler))
    }

    private fun parse(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun JsonObject.servers() = this["servers"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.number(key: String) = this[key]!!.jsonPrimitive.int
}
