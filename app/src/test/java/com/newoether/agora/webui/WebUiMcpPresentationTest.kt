package com.newoether.agora.webui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.ui.chat.message.toolDetailPresentation
import com.newoether.agora.ui.chat.message.toolDisplayName
import com.newoether.agora.ui.chat.message.toolSummary
import com.newoether.agora.util.appLanguageResources
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WebUiMcpPresentationTest {
    @Test
    fun `router cards share native labels lifecycle and persisted target in all locales`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val segments = listOf(
            MessageSegment(type = "tool", toolName = "mcp_list_servers",
                toolArgs = """{"limit":20,"offset":""", toolState = ToolExecutionStates.CALLING),
            MessageSegment(type = "tool", toolName = "mcp_list_servers",
                toolArgs = "{}", toolResult = """{"returned":1,"total":42,"servers":[{}]}""",
                toolState = ToolExecutionStates.SUCCEEDED),
            MessageSegment(type = "tool", toolName = "mcp_list_servers",
                toolArgs = "{}", toolResult = """{"returned":0,"total":42,"servers":[]}""",
                toolState = ToolExecutionStates.SUCCEEDED),
            MessageSegment(type = "tool", toolName = "mcp_list_servers",
                toolArgs = "{}", toolResult = "{}", toolState = ToolExecutionStates.SUCCEEDED),
            MessageSegment(type = "tool", toolName = "mcp_list_servers",
                toolArgs = "{}", toolResult = "", toolState = ToolExecutionStates.FAILED),
            MessageSegment(type = "tool", toolName = "mcp_search_tools",
                toolArgs = """{"query":"Read Files""", toolState = ToolExecutionStates.CALLING),
            MessageSegment(type = "tool", toolName = "mcp_search_tools",
                toolArgs = "{}", toolResult = """{"returned":1,"tools":[{}]}""",
                toolState = ToolExecutionStates.SUCCEEDED),
            MessageSegment(type = "tool", toolName = "mcp_search_tools",
                toolArgs = "{}", toolResult = """{"returned":0,"tools":[]}""",
                toolState = ToolExecutionStates.SUCCEEDED),
            MessageSegment(type = "tool", toolName = "mcp_call_tool",
                toolArgs = """{"tool_id":"mcp_deleted_read_ab1234","arguments":{}}""",
                toolDisplayName = "filesystem/read_file", toolTarget = "Deleted server",
                toolResult = "done", toolState = ToolExecutionStates.SUCCEEDED),
        )
        for (language in listOf("en", "ar", "de", "es", "fr", "ja", "ko", "pt-BR", "ru", "vi", "zh", "zh-Hant")) {
            val resources = app.appLanguageResources(language)
            for (mode in listOf(ToolCallDisplayModes.GROUPED_TIMELINE, ToolCallDisplayModes.TIMELINE)) {
                val display = WebDisplayContext(
                    resources = resources,
                    toolCallDisplayMode = mode,
                    thinkingSegmentDisplayMode = ThinkingSegmentDisplayModes.DEFAULT,
                    autoExpandActiveGroup = true,
                    parseInlineDollarMath = false,
                    autoWrapCodeBlocks = true,
                )
                for (segment in segments) {
                    val active = segment.toolResult == null
                    val message = ChatMessage(id = "message", text = "", participant = Participant.MODEL,
                        status = if (active) MessageStatus.SENDING else MessageStatus.SUCCESS,
                        segments = listOf(segment))
                    val projected = requireNotNull(webPresentation(message, active, display))
                    val item = projected.thinkingCard?.items?.single() ?: when (val block = projected.blocks.single()) {
                        is WebTimelineBlock.Group -> block.group.items.single()
                        is WebTimelineBlock.Card -> block.item
                        else -> error("Expected an information card")
                    }
                    assertEquals(language, resources.toolDisplayName(segment), item.title)
                    assertEquals(language, resources.toolSummary(segment), item.summary)
                    assertEquals(segment.toolState, item.toolState)
                    val detail = requireNotNull(item.toolDetail)
                    assertEquals(resources.toolDetailPresentation(segment).kind.name, detail.kind)
                    assertEquals(segment.toolTarget, detail.mcpDevice)
                    assertEquals(projected, WebUiSync.json.decodeFromString(WebPresentation.serializer(),
                        WebUiSync.json.encodeToString(WebPresentation.serializer(), projected)))
                }
            }
        }
    }
}
