package com.newoether.agora.mcp

import android.content.Context
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ToolImageAttachment
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpRegistryDispatchTest {
    private val server = McpServerConfig(
        id = "dispatch-fixture",
        name = "Dispatch fixture",
        url = "https://mcp-fixture.invalid/mcp",
    )
    private val remote = McpRemoteTool(
        name = "fixture_tool",
        description = "A fixture tool",
        inputSchema = Json.parseToJsonElement("""{"type":"object","properties":{}}""").jsonObject,
    )
    private val publicName = publicMcpToolName(server.id, remote.name)

    @Before
    fun interceptClientAndImageStore() {
        mockkConstructor(McpProtocolClient::class, ToolImageStore::class)
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any(), any()) } just Runs
        every { anyConstructed<McpProtocolClient>().close() } just Runs
        coEvery { anyConstructed<McpProtocolClient>().listTools() } returns listOf(remote)
        coEvery { anyConstructed<McpProtocolClient>().callTool(any(), any()) } returns McpCallPayload(
            textParts = listOf("Completed"),
            images = emptyList(),
            structuredContent = null,
            isError = false,
        )
    }

    @After
    fun releaseInterceptions() {
        unmockkConstructor(McpProtocolClient::class, ToolImageStore::class)
        unmockkObject(DebugLog)
    }

    @Test
    fun `connected target dispatches exact remote name and nested arguments`() = runTest {
        val (registry) = connectedRegistry()
        val arguments = """{"fields":[{"enabled":true,"value":null}],"data":{"text/plain":"hello"}}"""

        val result = registry.execute(publicName, arguments)

        assertFalse(result.isError)
        assertEquals("Completed", result.text)
        coVerify(exactly = 1) {
            anyConstructed<McpProtocolClient>().callTool(
                remote.name,
                Json.parseToJsonElement(arguments).jsonObject,
            )
        }
    }

    @Test
    fun `disabled server rejects stale connected target before reconcile`() =
        rejectsSettingsChangeBeforeReconcile { listOf(it.copy(enabled = false)) }

    @Test
    fun `removed server rejects stale connected target before reconcile`() =
        rejectsSettingsChangeBeforeReconcile { emptyList() }

    @Test
    fun `disabled remote tool rejects stale connected target before reconcile`() =
        rejectsSettingsChangeBeforeReconcile { listOf(it.copy(disabledTools = setOf(remote.name))) }

    @Test
    fun `replacement endpoint rejects stale connected target before reconcile`() =
        rejectsSettingsChangeBeforeReconcile {
            listOf(it.copy(url = "https://replacement-fixture.invalid/mcp"))
        }

    @Test
    fun `unknown target and malformed arguments never dispatch to connected client`() = runTest {
        val (registry) = connectedRegistry()

        val unknown = registry.execute(publicMcpToolName(server.id, "unknown_tool"), "{}")
        assertTrue(unknown.isError)
        assertTrue(unknown.text.contains("Unknown, disabled or disconnected"))
        listOf("{", "[]", "null", "true", "\"{}\"").forEach { arguments ->
            val result = registry.execute(publicName, arguments)
            assertTrue("Should reject $arguments", result.isError)
            assertTrue(result.text.contains("requires a complete JSON object"))
        }

        coVerify(exactly = 0) { anyConstructed<McpProtocolClient>().callTool(any(), any()) }
    }

    @Test
    fun `failed connection keeps catalog but rejects another call while offline`() = runTest {
        val (registry) = connectedRegistry()
        coEvery { anyConstructed<McpProtocolClient>().callTool(any(), any()) } throws
            IOException("Fixture connection is offline")

        val failure = registry.execute(publicName, "{}")
        assertTrue(failure.isError)
        assertTrue(failure.text.contains("Fixture connection is offline"))
        assertEquals(McpConnectionStatus.ERROR, registry.snapshots.value[server.id]?.status)
        assertEquals(publicName, registry.snapshots.value[server.id]?.tools?.single()?.publicName)

        // The retry is queued on the test dispatcher; dispatch must reject the retained descriptor.
        val offline = registry.execute(publicName, "{}")
        assertTrue(offline.isError)
        assertTrue(offline.text.contains("Unknown, disabled or disconnected"))
        coVerify(exactly = 1) { anyConstructed<McpProtocolClient>().callTool(any(), any()) }
        coVerify(exactly = 1) { anyConstructed<McpProtocolClient>().listTools() }
    }

    @Test
    fun `client cancellation propagates without marking connection failed or retrying`() = runTest {
        val (registry) = connectedRegistry()
        val cancellation = CancellationException("Fixture Stop")
        coEvery { anyConstructed<McpProtocolClient>().callTool(any(), any()) } throws cancellation

        try {
            registry.execute(publicName, "{}")
            fail("Cancellation must leave McpRegistry.execute")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        runCurrent()
        assertEquals(McpConnectionStatus.CONNECTED, registry.snapshots.value[server.id]?.status)
        coVerify(exactly = 1) { anyConstructed<McpProtocolClient>().callTool(any(), any()) }
        coVerify(exactly = 1) { anyConstructed<McpProtocolClient>().listTools() }
        verify(exactly = 0) { anyConstructed<McpProtocolClient>().close() }
    }

    @Test
    fun `dispatch preserves structured content persisted images display text and remote error flag`() = runTest {
        val structured = Json.parseToJsonElement("""{"items":[true,{"count":2}]}""")
        val image = ToolImageAttachment(
            path = "fixture.png",
            mimeType = "image/png",
            sizeBytes = 10,
            width = 1,
            height = 1,
        )
        every {
            anyConstructed<ToolImageStore>().persistBase64("aW1hZ2U=", "image/png", "mcp")
        } returns image
        val (registry) = connectedRegistry()
        coEvery { anyConstructed<McpProtocolClient>().callTool(any(), any()) } returns McpCallPayload(
            textParts = listOf("", "Fixture explanation"),
            images = listOf(McpImagePayload("aW1hZ2U=", "image/png")),
            structuredContent = structured,
            isError = true,
        )

        val result = registry.execute(publicName, "{}")

        assertTrue(result.isError)
        assertEquals(result.text, listOf(image), result.images)
        assertEquals(structured.toString(), result.structuredContent)
        assertEquals("Fixture explanation", result.displayText)
        assertEquals(
            "Fixture explanation\n\n$structured\n\n" +
                "[The tool returned one image. It is attached as visual context.]",
            result.text,
        )
        assertEquals(McpConnectionStatus.CONNECTED, registry.snapshots.value[server.id]?.status)
        verify(exactly = 1) {
            anyConstructed<ToolImageStore>().persistBase64("aW1hZ2U=", "image/png", "mcp")
        }
        coVerify(exactly = 1) { anyConstructed<McpProtocolClient>().callTool(remote.name, JsonObject(emptyMap())) }
    }

    @Test
    fun `equivalent structured text is kept for protocol but omitted from display text`() = runTest {
        val (registry) = connectedRegistry()
        val structured = Json.parseToJsonElement("""{"first":1,"second":2}""")
        val repeatedText = """{ "second": 2, "first": 1 }"""
        coEvery { anyConstructed<McpProtocolClient>().callTool(any(), any()) } returns McpCallPayload(
            textParts = listOf(repeatedText),
            images = emptyList(),
            structuredContent = structured,
            isError = false,
        )

        val result = registry.execute(publicName, "{}")

        assertFalse(result.isError)
        assertNull(result.displayText)
        assertEquals(structured.toString(), result.structuredContent)
        assertEquals("$repeatedText\n\n$structured", result.text)
    }

    private fun rejectsSettingsChangeBeforeReconcile(
        change: (McpServerConfig) -> List<McpServerConfig>,
    ) = runTest {
        val (registry, configs) = connectedRegistry()
        val connectedSnapshot = registry.snapshots.value[server.id]

        configs.value = change(server)
        // Do not advance the dispatcher: the old runtime and connected catalog still exist.
        assertSame(connectedSnapshot, registry.snapshots.value[server.id])
        assertEquals(publicName, connectedSnapshot?.tools?.single()?.publicName)
        val result = registry.execute(publicName, "{}")

        assertTrue(result.isError)
        assertTrue(result.text.contains("Unknown, disabled or disconnected"))
        coVerify(exactly = 0) { anyConstructed<McpProtocolClient>().callTool(any(), any()) }
        verify(exactly = 0) { anyConstructed<McpProtocolClient>().close() }
    }

    private fun TestScope.connectedRegistry(): Pair<McpRegistry, MutableStateFlow<List<McpServerConfig>>> {
        val configs = MutableStateFlow(listOf(server))
        val settings = mockk<SettingsRepository>()
        every { settings.mcpServers } returns configs
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"))
        val registry = McpRegistry(
            context = context,
            settings = settings,
            scope = backgroundScope,
            workDispatcher = StandardTestDispatcher(testScheduler),
        )

        runCurrent()
        assertEquals(McpConnectionStatus.CONNECTED, registry.snapshots.value[server.id]?.status)
        assertEquals(publicName, registry.enabledTools().single().publicName)
        coVerify(exactly = 1) { anyConstructed<McpProtocolClient>().listTools() }
        return registry to configs
    }
}
