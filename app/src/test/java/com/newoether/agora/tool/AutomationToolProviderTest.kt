package com.newoether.agora.tool

import com.newoether.agora.automation.LoopManager
import com.newoether.agora.automation.TaskManager
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.data.local.LoopEntity
import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AutomationToolProviderTest {
    private val taskManager = mockk<TaskManager>()
    private val loopManager = mockk<LoopManager>()
    private val provider = AutomationToolProvider(taskManager, loopManager)
    private val enabledContext = GenerationContext(
        conversationId = "conversation-1",
        automationToolsEnabled = true,
    )

    @Test
    fun definitions_areFeatureGatedAndComplete() {
        assertTrue(provider.definitions(enabledContext.copy(automationToolsEnabled = false)).isEmpty())

        val definitions = provider.definitions(enabledContext)
        assertEquals(
            setOf("create_task", "list_tasks", "delete_task", "start_loop", "stop_loop"),
            definitions.map { it.function.name }.toSet(),
        )
        assertEquals(
            listOf("name", "prompt", "cron"),
            definitions.single { it.function.name == "create_task" }.function.parameters.required,
        )
        assertEquals(
            listOf("interval_seconds"),
            definitions.single { it.function.name == "start_loop" }.function.parameters.required,
        )
    }

    @Test
    fun handles_onlyAutomationTools() {
        assertTrue(provider.handles("create_task"))
        assertTrue(provider.handles("list_tasks"))
        assertTrue(provider.handles("delete_task"))
        assertTrue(provider.handles("start_loop"))
        assertTrue(provider.handles("stop_loop"))
        assertFalse(provider.handles("web_search"))
    }

    @Test
    fun execute_rechecksFeatureGate() = runTest {
        val result = provider.execute(
            "create_task",
            """{"name":"Morning","prompt":"Summarize","cron":"0 9 * * *"}""",
            enabledContext.copy(automationToolsEnabled = false),
        )

        assertTrue(result.startsWith("Error:"))
        coVerify(exactly = 0) { taskManager.createTask(any(), any(), any(), any()) }
    }

    @Test
    fun execute_rechecksLiveFeatureGateAfterGenerationStarted() = runTest {
        val liveDisabled = AutomationToolProvider(taskManager, loopManager) { false }

        val result = liveDisabled.execute("list_tasks", "{}", enabledContext)

        assertTrue(result.startsWith("Error:"))
        coVerify(exactly = 0) { taskManager.listTasksSnapshot() }
    }

    @Test
    fun execute_malformedJsonReturnsError() = runTest {
        val result = provider.execute("list_tasks", "{", enabledContext)
        assertTrue(result.startsWith("Error:"))
    }

    @Test
    fun execute_doesNotSwallowCancellation() = runTest {
        coEvery { taskManager.listTasksSnapshot() } throws CancellationException("cancelled")

        try {
            provider.execute("list_tasks", "{}", enabledContext)
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Expected: cancellation must unwind the generation rather than become tool output.
        }
    }

    @Test
    fun createTask_validatesCronAndReturnsStructuredJson() = runTest {
        val task = task(id = "task-1", name = "Morning", prompt = "Summarize", cron = "0 9 * * *")
        coEvery { taskManager.createTask(any(), any(), any(), any()) } returns task

        val invalid = provider.execute(
            "create_task",
            """{"name":"Morning","prompt":"Summarize","cron":"not cron"}""",
            enabledContext,
        )
        assertTrue(invalid.startsWith("Error:"))
        coVerify(exactly = 0) { taskManager.createTask(any(), any(), any(), any()) }

        val result = provider.execute(
            "create_task",
            """{"name":"Morning","prompt":"Summarize","cron":"0 9 * * *"}""",
            enabledContext,
        )
        val json = result.json()
        assertEquals("create_task", json["type"]?.jsonPrimitive?.content)
        assertEquals("task-1", json["task"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
        coVerify(exactly = 1) {
            taskManager.createTask("Morning", "Summarize", "0 9 * * *", null)
        }
    }

    @Test
    fun listTasks_returnsValidEscapedJson() = runTest {
        coEvery { taskManager.listTasksSnapshot() } returns listOf(
            task(id = "task-1", name = "Quote \"test\"", prompt = "Line 1\nLine 2"),
        )

        val json = provider.execute("list_tasks", "{}", enabledContext).json()
        val tasks = json["tasks"]!!.jsonArray
        assertEquals(1, tasks.size)
        assertEquals("Quote \"test\"", tasks[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("Line 1\nLine 2", tasks[0].jsonObject["prompt"]?.jsonPrimitive?.content)
    }

    @Test
    fun deleteTask_handlesDeletedMissingAndAmbiguous() = runTest {
        val first = task(id = "task-1", name = "Daily")
        val second = task(id = "task-2", name = "Daily")
        coEvery { taskManager.deleteTaskByIdOrName("task-1") } returns
            TaskManager.DeleteResult.Deleted(first)
        coEvery { taskManager.deleteTaskByIdOrName("missing") } returns
            TaskManager.DeleteResult.NotFound
        coEvery { taskManager.deleteTaskByIdOrName("Daily") } returns
            TaskManager.DeleteResult.Ambiguous(listOf(first, second))

        val deleted = provider.execute(
            "delete_task", """{"id_or_name":"task-1"}""", enabledContext,
        ).json()
        assertEquals("true", deleted["deleted"]?.jsonPrimitive?.content)

        assertTrue(
            provider.execute("delete_task", """{"id_or_name":"missing"}""", enabledContext)
                .startsWith("Error:"),
        )
        val ambiguous = provider.execute(
            "delete_task", """{"id_or_name":"Daily"}""", enabledContext,
        )
        assertTrue(ambiguous.startsWith("Error:"))
        assertTrue(ambiguous.contains("task-1"))
        assertTrue(ambiguous.contains("task-2"))
    }

    @Test
    fun startLoop_appliesSafeDefaultAndReturnsJson() = runTest {
        val loop = LoopEntity(
            conversationId = "conversation-1",
            intervalMs = 60_000L,
            prompt = null,
            nextFireAt = 123_456L,
            maxCycles = 10,
        )
        coEvery {
            loopManager.startLoop("conversation-1", 60_000L, null, 10)
        } returns LoopManager.StartResult.Started(loop)

        val json = provider.execute(
            "start_loop", """{"interval_seconds":60,"prompt":"  "}""", enabledContext,
        ).json()
        assertEquals("started", json["status"]?.jsonPrimitive?.content)
        assertEquals("10", json["loop"]?.jsonObject?.get("max_cycles")?.jsonPrimitive?.content)
        coVerify(exactly = 1) {
            loopManager.startLoop("conversation-1", 60_000L, null, 10)
        }
    }

    @Test
    fun startLoop_rejectsUnsafeRangesWithoutCallingManager() = runTest {
        val shortInterval = provider.execute(
            "start_loop", """{"interval_seconds":5}""", enabledContext,
        )
        val tooManyCycles = provider.execute(
            "start_loop", """{"interval_seconds":60,"max_cycles":101}""", enabledContext,
        )

        assertTrue(shortInterval.startsWith("Error:"))
        assertTrue(tooManyCycles.startsWith("Error:"))
        coVerify(exactly = 0) { loopManager.startLoop(any(), any(), any(), any()) }
    }

    @Test
    fun startLoop_reportsConflictAndMissingConversation() = runTest {
        val existing = LoopEntity(
            conversationId = "conversation-1",
            intervalMs = 60_000L,
            nextFireAt = 123L,
            maxCycles = 10,
        )
        coEvery {
            loopManager.startLoop("conversation-1", 60_000L, null, 10)
        } returns LoopManager.StartResult.Conflict(existing)

        val conflict = provider.execute(
            "start_loop", """{"interval_seconds":60}""", enabledContext,
        )
        assertTrue(conflict.startsWith("Error:"))

        val missingContext = provider.execute(
            "start_loop",
            """{"interval_seconds":60}""",
            enabledContext.copy(conversationId = null),
        )
        assertTrue(missingContext.startsWith("Error:"))
    }

    @Test
    fun stopLoop_isIdempotentForAlreadyStopped() = runTest {
        coEvery { loopManager.stopLoop("conversation-1") } returns
            LoopManager.StopResult.AlreadyStopped

        val json = provider.execute("stop_loop", "{}", enabledContext).json()
        assertEquals("already_stopped", json["status"]?.jsonPrimitive?.content)
        assertEquals("conversation-1", json["conversation_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun stopLoop_notFoundReturnsError() = runTest {
        coEvery { loopManager.stopLoop("conversation-1") } returns LoopManager.StopResult.NotFound

        val result = provider.execute("stop_loop", "{}", enabledContext)
        assertTrue(result.startsWith("Error:"))
    }

    @Test
    fun createTask_offersOptionalSystemPromptParameter() {
        val createTask = provider.definitions(enabledContext)
            .single { it.function.name == "create_task" }

        assertTrue(createTask.function.parameters.properties.containsKey("system_prompt"))
        assertFalse(createTask.function.parameters.required.contains("system_prompt"))
    }

    @Test
    fun createTask_resolvesSavedSystemPromptByTitleAndFailsClosedWhenMissing() = runTest {
        val prompts = listOf(
            SystemPromptEntry(id = "prompt-1", title = "Research"),
            SystemPromptEntry(id = "prompt-2", title = "Ops"),
        )
        val withPrompts = AutomationToolProvider(taskManager, loopManager, { prompts })
        coEvery { taskManager.createTask(any(), any(), any(), any(), any()) } returns
            task(id = "task-1", name = "Morning").copy(systemPromptId = "prompt-1")

        val json = withPrompts.execute(
            "create_task",
            """{"name":"Morning","prompt":"Summarize","cron":"0 9 * * *","system_prompt":"research"}""",
            enabledContext,
        ).json()
        assertEquals(
            "prompt-1",
            json["task"]?.jsonObject?.get("system_prompt_id")?.jsonPrimitive?.content,
        )
        coVerify(exactly = 1) {
            taskManager.createTask("Morning", "Summarize", "0 9 * * *", null, "prompt-1")
        }

        val missing = withPrompts.execute(
            "create_task",
            """{"name":"Morning","prompt":"Summarize","cron":"0 9 * * *","system_prompt":"Unknown"}""",
            enabledContext,
        )
        assertTrue(missing.startsWith("Error:"))
        // The unresolved prompt must not reach the manager as a silent app-default fallback.
        coVerify(exactly = 1) { taskManager.createTask(any(), any(), any(), any(), any()) }
    }

    private fun task(
        id: String,
        name: String,
        prompt: String = "Prompt",
        cron: String = "0 9 * * *",
    ) = TaskEntity(
        id = id,
        name = name,
        prompt = prompt,
        cronExpr = cron,
        nextRunAt = 1_000L,
    )

    private fun String.json() = Json.parseToJsonElement(this).jsonObject
}
