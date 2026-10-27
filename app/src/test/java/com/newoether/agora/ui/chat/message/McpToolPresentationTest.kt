package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.util.appLanguageResources
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class McpToolPresentationTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `search uses a localized action and bounded partial query without parsing large arguments`() {
        val segment = MessageSegment(
            type = "tool",
            toolName = "mcp_search_tools",
            toolArgs = """{"query":"Read Files""",
            toolState = ToolExecutionStates.CALLING,
        )
        val presentation = ToolPresentationResolver.resolve(segment)
        assertEquals(ToolKind.MCP_SEARCH, presentation.kind)
        assertEquals("Read Files", presentation.subject)
        assertNull(presentation.arguments)
        assertEquals("Search MCP Tools", app.appLanguageResources("en").toolDisplayName(segment))
        assertEquals("Searching MCP tools for Read Files…", app.appLanguageResources("en").toolSummary(segment))
        assertEquals("搜索 MCP 工具", app.appLanguageResources("zh").toolDisplayName(segment))
        assertEquals("正在搜索与 Read Files 相关的 MCP 工具…", app.appLanguageResources("zh").toolSummary(segment))

        val huge = segment.copy(toolArgs = """{"query":"""" + "x".repeat(100_000))
        val hugePresentation = ToolPresentationResolver.resolve(huge)
        assertNull(hugePresentation.arguments)
        assertEquals(120, hugePresentation.subject?.length)
        assertTrue(hugePresentation.subject.orEmpty().endsWith("…"))
    }

    @Test
    fun `search page counts distinguish completion empty unknown and failure`() {
        val resources = app.appLanguageResources("en")
        val complete = MessageSegment(
            type = "tool",
            toolName = "mcp_search_tools",
            toolArgs = """{"query":"files"}""",
            toolResult = """{"returned":1,"total":42,"tools":[{"tool_id":"one"}]}""",
            toolState = ToolExecutionStates.SUCCEEDED,
        )
        assertEquals(1, ToolPresentationResolver.resolve(complete).count)
        assertEquals("Found 1 MCP tool", resources.toolSummary(complete))
        assertEquals("Found 2 MCP tools", resources.toolSummary(complete.copy(
            toolResult = """{"returned":2,"total":42,"tools":[{},{}]}""",
        )))
        val empty = complete.copy(toolResult = """{"returned":0,"total":0,"tools":[]}""")
        assertEquals(ToolPresentationState.EMPTY, ToolPresentationResolver.resolve(empty).state)
        assertEquals("No matching MCP tools found", resources.toolSummary(empty))
        assertEquals("Searched MCP tools", resources.toolSummary(complete.copy(toolResult = "{}")))
        val failed = empty.copy(
            toolResult = """{"error":"unavailable","message":"catalog unavailable"}""",
            toolState = ToolExecutionStates.FAILED,
        )
        assertEquals(ToolPresentationState.FAILED, ToolPresentationResolver.resolve(failed).state)
        assertEquals("Catalog unavailable", resources.toolSummary(failed))
    }

    @Test
    fun `server listing has its own localized title and streaming action without a target`() {
        val segment = MessageSegment(
            type = "tool",
            toolName = "mcp_list_servers",
            toolArgs = """{"limit":20,"offset":""",
            toolState = ToolExecutionStates.CALLING,
        )
        for (state in listOf(ToolExecutionStates.CALLING, ToolExecutionStates.RUNNING)) {
            val active = segment.copy(toolState = state)
            val presentation = ToolPresentationResolver.resolve(active)
            assertEquals(ToolKind.MCP_LIST, presentation.kind)
            assertNull(presentation.arguments)
            assertNull(presentation.subject)
            assertNull(presentation.device)
            assertNull(presentation.count)
            assertEquals("List MCP Servers", app.appLanguageResources("en").toolDisplayName(active))
            assertEquals("Listing MCP servers…", app.appLanguageResources("en").toolSummary(active))
            assertEquals("列出 MCP 服务器", app.appLanguageResources("zh").toolDisplayName(active))
            assertEquals("正在列出 MCP 服务器…", app.appLanguageResources("zh").toolSummary(active))
        }
    }

    @Test
    fun `server listing counts only the returned page and preserves ordinary JSON details`() {
        val resources = app.appLanguageResources("en")
        val result = """{"returned":1,"total":42,"servers":[{"server_id":"offline","status":"error","available_tool_count":0}]}"""
        val complete = MessageSegment(
            type = "tool", toolName = "mcp_list_servers",
            toolArgs = """{"limit":20,"offset":0}""",
            toolResult = result, toolState = ToolExecutionStates.SUCCEEDED,
        )
        val presentation = ToolPresentationResolver.resolve(complete)
        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
        assertEquals(1, presentation.count)
        assertEquals("Listed 1 MCP server", resources.toolSummary(complete))
        assertEquals("Listed 2 MCP servers", resources.toolSummary(complete.copy(
            toolResult = """{"returned":2,"total":42,"servers":[{},{}]}""",
        )))
        assertEquals("Listed 1 MCP server", resources.toolSummary(complete.copy(
            toolResult = """{"total":42,"servers":[{}]}""",
        )))
        assertEquals("Listed MCP servers", resources.toolSummary(complete.copy(
            toolResult = """{"total":42}""",
        )))
        assertNull(ToolPresentationResolver.resolve(complete.copy(toolResult = "{}")).count)
        assertEquals(ToolDetailBody.JsonContent(listOf(result)), resources.toolDetailPresentation(complete).body)
        assertEquals("已列出 1 个 MCP 服务器", app.appLanguageResources("zh").toolSummary(complete))
    }

    @Test
    fun `empty server page failure and stop retain their distinct shared lifecycle`() {
        val resources = app.appLanguageResources("en")
        val empty = MessageSegment(
            type = "tool", toolName = "mcp_list_servers", toolArgs = """{"offset":50}""",
            toolResult = """{"returned":0,"total":42,"servers":[]}""",
            toolState = ToolExecutionStates.SUCCEEDED,
        )
        assertEquals(ToolPresentationState.EMPTY, ToolPresentationResolver.resolve(empty).state)
        assertEquals("No MCP servers returned", resources.toolSummary(empty))
        val failed = empty.copy(
            toolResult = """{"error":"unavailable","message":"directory unavailable"}""",
            toolState = ToolExecutionStates.FAILED,
        )
        assertEquals(ToolPresentationState.FAILED, ToolPresentationResolver.resolve(failed).state)
        assertEquals("Directory unavailable", resources.toolSummary(failed))
        assertEquals("Failed to list MCP servers", resources.toolSummary(failed.copy(toolResult = "")))
        val stopped = empty.copy(toolResult = null, toolState = ToolExecutionStates.STOPPED)
        assertEquals(ToolPresentationState.STOPPED, ToolPresentationResolver.resolve(stopped).state)
        assertTrue(resources.toolDetailPresentation(stopped).body is ToolDetailBody.Stopped)
    }

    @Test
    fun `server listing uses localized Russian quantity forms`() {
        val resources = app.appLanguageResources("ru")
        for ((count, expected) in listOf(
            1 to "Получен 1 сервер MCP", 2 to "Получено 2 сервера MCP",
            5 to "Получено 5 серверов MCP", 21 to "Получен 21 сервер MCP",
        )) {
            val segment = MessageSegment(
                type = "tool", toolName = "mcp_list_servers",
                toolResult = """{"returned":$count,"total":100}""",
                toolState = ToolExecutionStates.SUCCEEDED,
            )
            assertEquals(expected, resources.toolSummary(segment))
        }
    }

    @Test
    fun `routed call replays resolved metadata without registry and keeps images errors and stop`() {
        val image = com.newoether.agora.model.ToolImageAttachment(
            path = "/private/result.png", mimeType = "image/png", sizeBytes = 12,
        )
        val segment = MessageSegment(
            type = "tool",
            toolName = "mcp_call_tool",
            toolArgs = """{"tool_id":"mcp_deleted_read_ab1234","arguments":{}}""",
            toolDisplayName = "filesystem/read_file",
            toolTarget = "Deleted filesystem server",
            toolResult = "protocol result",
            toolResultText = "read result",
            toolStructuredResult = """{"value":"data"}""",
            toolImages = listOf(image),
            toolState = ToolExecutionStates.SUCCEEDED,
        )
        val restored = Json.decodeFromString(MessageSegment.serializer(),
            Json.encodeToString(MessageSegment.serializer(), segment))
        val resources = app.appLanguageResources("en")
        assertEquals("mcp_call_tool", restored.toolName)
        assertEquals(segment.toolArgs, restored.toolArgs)
        assertEquals("Filesystem/Read File", resources.toolDisplayName(restored))
        assertEquals("Deleted filesystem server", ToolPresentationResolver.resolve(restored).device)
        assertEquals(listOf(image), restored.toolImages)
        assertEquals(ToolDetailBody.JsonContent(listOf("read result", """{"value":"data"}""")),
            resources.toolDetailPresentation(restored).body)
        val failed = restored.copy(toolState = ToolExecutionStates.FAILED, toolResult = "request denied",
            toolResultText = null, toolStructuredResult = null)
        assertEquals(ToolDetailBody.Failed("request denied", null), resources.toolDetailPresentation(failed).body)
        val stopped = restored.copy(toolResult = null, toolResultText = null, toolStructuredResult = null,
            toolState = ToolExecutionStates.STOPPED)
        assertEquals(ToolPresentationState.STOPPED, ToolPresentationResolver.resolve(stopped).state)
        assertTrue(resources.toolDetailPresentation(stopped).body is ToolDetailBody.Stopped)
        assertEquals("MCP", resources.toolDisplayName(restored.copy(toolDisplayName = null)))
        assertEquals(resources.getString(R.string.tool_search_mcp_tools), resources.toolDisplayName(
            restored.copy(toolName = "mcp_search_tools")))
    }
}
