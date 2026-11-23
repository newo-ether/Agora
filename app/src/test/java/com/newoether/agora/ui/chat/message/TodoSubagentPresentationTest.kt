package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Checklist
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.util.appLanguageResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TodoSubagentPresentationTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val names = listOf(
        "read_todo", "update_todo", "spawn_agent", "send_agent_message",
        "wait_agents", "list_agents", "stop_agent",
    )
    private val kinds = listOf(
        ToolKind.TODO_READ, ToolKind.TODO_UPDATE, ToolKind.SUBAGENT_SPAWN, ToolKind.SUBAGENT_SEND,
        ToolKind.SUBAGENT_WAIT, ToolKind.SUBAGENT_LIST, ToolKind.SUBAGENT_STOP,
    )
    private val titles = listOf(
        "Read Todo List", "Update Todo List", "Start Subagent", "Send Message to Subagent",
        "Wait for Subagents", "List Subagents", "Stop Subagent",
    )

    @Test
    fun builtinsUseExplicitKindsAndAuthoredTitleCase() {
        val resources = app.appLanguageResources("en")
        names.forEachIndexed { index, name ->
            val call = MessageSegment(type = "tool", toolName = name, toolArgs = "{}")
            assertEquals(kinds[index], ToolPresentationResolver.kindForToolName(name))
            assertEquals(kinds[index], ToolPresentationResolver.resolve(call).kind)
            assertEquals(titles[index], resources.toolDisplayName(call))
            assertEquals(if (index < 2) Icons.Rounded.Checklist else Icons.Rounded.AccountTree,
                toolHeaderIcon(kinds[index]))
        }
        assertEquals(ToolKind.UNKNOWN, ToolPresentationResolver.kindForToolName("external_tool"))
        assertEquals(Icons.Rounded.Build, toolHeaderIcon(ToolKind.UNKNOWN))
    }

    @Test
    fun everySupportedLocaleHasItsOwnTitlesAndLifecycleSummaries() {
        val english = app.appLanguageResources("en")
        for (language in listOf("ar", "de", "es", "fr", "ja", "ko", "pt-BR", "ru", "vi", "zh", "zh-Hant")) {
            val resources = app.appLanguageResources(language)
            for (name in names) {
                val call = MessageSegment(type = "tool", toolName = name, toolArgs = "{}")
                assertNotEquals("$language $name", english.toolDisplayName(call), resources.toolDisplayName(call))
                assertNotEquals("$language $name", english.toolSummary(call), resources.toolSummary(call))
                assertTrue(resources.toolSummary(call).endsWith("\u2026"))
                val done = call.copy(toolResult = "{}", toolState = ToolExecutionStates.SUCCEEDED)
                assertNotEquals("$language $name", english.toolSummary(done), resources.toolSummary(done))
                assertFalse(resources.toolSummary(done).endsWith("."))
            }
        }
        val zh = app.appLanguageResources("zh")
        val expected = listOf("读取待办清单", "更新待办清单", "启动子代理", "向子代理发送消息", "等待子代理", "列出子代理", "停止子代理")
        names.forEachIndexed { index, name ->
            assertEquals(expected[index], zh.toolDisplayName(MessageSegment(type = "tool", toolName = name)))
        }
    }

    @Test
    fun toolCompletionDescribesTheActionNotTheChildLifecycle() {
        val resources = app.appLanguageResources("en")
        val active = listOf(
            "Reading todo list\u2026", "Updating todo list\u2026", "Starting subagent\u2026",
            "Queuing message for subagent\u2026", "Waiting for subagents\u2026", "Listing subagents\u2026", "Stopping subagent\u2026",
        )
        val completed = listOf(
            "Read todo list", "Updated todo list", "Started subagent", "Queued message for subagent",
            "Finished waiting for subagents", "Listed subagents", "Stopped subagent",
        )
        val results = listOf(
            "[{\"content\":\"Task\",\"status\":\"pending\"}]", "[]", "{\"status\":\"started\"}",
            "{\"status\":\"queued\"}", "{\"results\":[{\"status\":\"failed\"}]}", "[{\"status\":\"running\"}]", "Subagent stopped",
        )
        names.forEachIndexed { index, name ->
            val call = MessageSegment(type = "tool", toolName = name, toolArgs = "{}")
            assertEquals(active[index], resources.toolSummary(call))
            assertEquals(active[index], resources.toolSummary(call.copy(toolState = ToolExecutionStates.RUNNING)))
            val done = call.copy(toolResult = results[index], toolState = ToolExecutionStates.SUCCEEDED)
            assertEquals(ToolPresentationState.COMPLETED, ToolPresentationResolver.resolve(done).state)
            assertEquals(completed[index], resources.toolSummary(done))
            assertEquals("Tool execution stopped", resources.toolSummary(call.copy(toolResult = "{}", toolState = ToolExecutionStates.STOPPED)))
            assertEquals("Command timeout", resources.toolSummary(call.copy(toolResult = "Error: command timeout", toolState = ToolExecutionStates.FAILED)))
        }
    }

    @Test
    fun emptyReadsAndTimedOutWaitsDoNotClaimChildCompletion() {
        val resources = app.appLanguageResources("en")
        for ((name, result, expected) in listOf(
            Triple("read_todo", "[]", "Todo list is empty"),
            Triple("list_agents", "[]", "No subagents"),
            Triple("wait_agents", "{\"results\":[]}", "No subagent results returned"),
        )) {
            val segment = MessageSegment(type = "tool", toolName = name, toolResult = result, toolState = ToolExecutionStates.SUCCEEDED)
            assertEquals(ToolPresentationState.EMPTY, ToolPresentationResolver.resolve(segment).state)
            assertEquals(expected, resources.toolSummary(segment))
        }
    }

    @Test
    fun localizedTitlesAndDetailsNeverTranslateProtocolPayloads() {
        val resources = app.appLanguageResources("zh")
        val arguments = "{\"todos\":[{\"content\":\"Keep Case\",\"status\":\"in_progress\"}]}"
        val result = "[{\"content\":\"Keep Case\",\"status\":\"in_progress\"}]"
        val segment = MessageSegment(type = "tool", toolName = "update_todo", toolArgs = arguments,
            toolResult = result, toolState = ToolExecutionStates.SUCCEEDED)
        val presentation = ToolPresentationResolver.resolve(segment)
        assertEquals(arguments, presentation.rawArguments)
        assertEquals(result, presentation.rawResult)
        assertEquals("todo_update", presentation.toolName)
        assertEquals("update_todo", segment.toolName)
        val detail = resources.toolDetailPresentation(segment)
        assertEquals(arguments, detail.arguments)
        assertEquals(listOf(result), (detail.body as ToolDetailBody.JsonContent).values)
        val malformed = segment.copy(toolName = "agora_malformed_tool_call",
            toolArgs = "{\"original_name\":\"read_todo\",\"original_arguments\":\"[]\"}",
            toolResult = "Error: malformed arguments", toolState = ToolExecutionStates.FAILED)
        assertEquals("读取待办清单", resources.toolDisplayName(malformed))
        assertEquals(ToolKind.UNKNOWN, ToolPresentationResolver.resolve(malformed).kind)
        assertEquals("Malformed arguments", resources.toolSummary(malformed))
    }
}
