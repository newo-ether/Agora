package com.newoether.agora.viewmodel

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.model.ConversationCommand
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.tool.AskUserToolProvider
import com.newoether.agora.tool.SessionToolGroup
import com.newoether.agora.tool.ToolExecutionEvent
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.tool.ToolProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenerationToolExecutorTest {
    @Test
    fun `MCP null absent and blank errors remain successful`() {
        val payloads = listOf(
            """{"ok":true,"error":null,"data":{"fields":3,"methods":9}}""",
            """{"ok":true,"data":{"fields":3,"methods":9}}""",
            """{"ok":true,"error":"","data":{"fields":3,"methods":9}}""",
            """{"ok":true,"error":"  ","data":{"fields":3,"methods":9}}""",
        )
        for (name in listOf("mcp_call_tool", "mcp_mt_read_class_outline_abcdef")) {
            for (payload in payloads) {
                assertEquals(
                    "$name: $payload",
                    ToolExecutionStates.SUCCEEDED,
                    finalToolState(ToolExecutionResult("class outline", structuredContent = payload), name),
                )
            }
        }
    }

    @Test
    fun `MCP string null and nonblank errors remain failures`() {
        for (error in listOf("null", "permission_denied")) {
            val result = ToolExecutionResult(
                "class outline",
                structuredContent = """{"ok":true,"error":"$error"}""",
            )
            assertEquals(error, ToolExecutionStates.FAILED, finalToolState(result, "mcp_call_tool"))
        }
    }

    @Test
    fun `provider error remains failed despite successful structured payload`() {
        val result = ToolExecutionResult(
            "class outline",
            structuredContent = """{"ok":true,"error":null,"data":{"fields":3,"methods":9}}""",
            isError = true,
        )

        assertEquals(ToolExecutionStates.FAILED, finalToolState(result, "mcp_call_tool"))
    }

    @Test
    fun `explicit failed flag remains failed with a null error`() {
        val result = ToolExecutionResult(
            "class outline",
            structuredContent = """{"ok":true,"error":null,"failed":true}""",
        )

        assertEquals(ToolExecutionStates.FAILED, finalToolState(result, "mcp_call_tool"))
    }

    @Test
    fun `nested shell null error preserves successful and running job states`() {
        for ((state, expected) in listOf(
            "succeeded" to ToolExecutionStates.SUCCEEDED,
            "running" to ToolExecutionStates.BACKGROUND_RUNNING,
        )) {
            val result = ToolExecutionResult(
                """{"error":null,"result":{"error":null,"job_id":"job-1","state":"$state"}}""",
            )
            assertEquals(state, expected, finalToolState(result, "shell_job_get"))
        }
    }

    @Test
    fun `nested shell null errors do not hide outer or nested failures`() {
        val payloads = listOf(
            """{"error":"connection_lost","result":{"error":null,"state":"succeeded"}}""",
            """{"error":null,"failed":true,"result":{"error":null,"state":"succeeded"}}""",
            """{"error":null,"result":{"error":"connection_lost","state":"succeeded"}}""",
        )
        for (payload in payloads) {
            assertEquals(
                payload,
                ToolExecutionStates.FAILED,
                finalToolState(ToolExecutionResult(payload), "shell_job_get"),
            )
        }
    }

    @Test
    fun `no results remains empty even when provider marks an error`() {
        for (name in listOf("web_search", "mcp_call_tool")) {
            val result = ToolExecutionResult(
                "no results",
                structuredContent = """{"error":"no_results"}""",
                isError = true,
            )
            assertEquals(name, ToolExecutionStates.EMPTY, finalToolState(result, name))
        }
    }

    @Test
    fun `durable wait timeout remains background running at the completion boundary`() {
        val result =
            """{"type":"wait_for_job","job_id":"same-job","state":"running","timed_out":true}"""

        assertEquals(ToolExecutionStates.BACKGROUND_RUNNING, finalToolState(ToolExecutionResult(result), "wait_for_job"))
    }

    @Test
    fun `completed result retains the authorized batch and call identities`() = runTest {
        val provider = FakeToolProvider()
        val executor = GenerationToolExecutor.forTest(listOf(provider))
        val events = mutableListOf<ToolExecutionEvent>()

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "call-1",
                name = "known_tool",
                arguments = "{}",
                context = GenerationContext(),
                authorizedToolNames = setOf("known_tool"),
            ),
            onEvent = events::add,
        )

        assertEquals(BATCH_IDENTITY, executed.batchIdentity)
        assertEquals("call-1", executed.callId)
        assertEquals("done", executed.result.text)
        assertEquals(1, provider.executionCount)
        assertTrue(events.single() is ToolExecutionEvent.Completed)
    }

    @Test
    fun `incomplete arguments fail before provider execution and retain identity`() = runTest {
        val provider = FakeToolProvider()
        val executor = GenerationToolExecutor.forTest(listOf(provider))

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "call-invalid",
                name = "known_tool",
                arguments = "{",
                context = GenerationContext(),
                authorizedToolNames = setOf("known_tool"),
            ),
            onEvent = {},
        )

        assertEquals(BATCH_IDENTITY, executed.batchIdentity)
        assertEquals("call-invalid", executed.callId)
        assertTrue(executed.result.isError)
        assertTrue(executed.result.text.contains("complete JSON object"))
        assertEquals(0, provider.executionCount)
    }

    @Test
    fun `call omitted from the frozen definition set never reaches a matching provider`() = runTest {
        val provider = FakeToolProvider()
        val executor = GenerationToolExecutor.forTest(listOf(provider))

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "call-disabled",
                name = "known_tool",
                arguments = "{}",
                context = GenerationContext(),
                authorizedToolNames = emptySet(),
            ),
            onEvent = {},
        )

        assertTrue(executed.result.isError)
        assertTrue(executed.result.text.contains("not authorized"))
        assertEquals(0, provider.executionCount)
    }

    @Test
    fun `tool budgets preserve image extension and delegate ask user to its own deadline`() {
        assertEquals(600_000L, toolExecutionTimeoutMs("generate_image", 25L))
        assertEquals(Long.MAX_VALUE, toolExecutionTimeoutMs("ask_user", 25L))
        assertEquals(25L, toolExecutionTimeoutMs("blocking_tool", 25L))
    }

    @Test
    fun `a short generic tool deadline does not preempt the ask user timeout result`() = runTest {
        val controller = AskUserController()
        val executor = GenerationToolExecutor.forTest(listOf(AskUserToolProvider(controller)))
        val events = mutableListOf<ToolExecutionEvent>()
        val call = async {
            executor.execute(
                call = AuthorizedToolCall(
                    batchIdentity = BATCH_IDENTITY,
                    callId = "ask-timeout",
                    name = "ask_user",
                    arguments = """{"question":"Choose?","options":["A"]}""",
                    context = GenerationContext(askUserEnabled = true, askUserTimeoutMinutes = 1, toolTimeoutMs = 25L),
                    authorizedToolNames = setOf("ask_user"),
                ),
                onEvent = events::add,
            )
        }
        runCurrent()
        assertEquals(1, controller.requests.value.size)
        advanceTimeBy(59_999L)
        runCurrent()
        assertFalse(call.isCompleted)
        assertTrue(events.none { it is ToolExecutionEvent.Completed })
        advanceTimeBy(1L)
        runCurrent()

        assertTrue(call.isCompleted)
        val executed = call.await()
        assertEquals(BATCH_IDENTITY, executed.batchIdentity)
        assertEquals("ask-timeout", executed.callId)
        assertFalse(executed.result.isError)
        val answer = Json.parseToJsonElement(executed.result.text).jsonObject
        assertFalse(answer["answered"]!!.jsonPrimitive.boolean)
        assertNull(answer["choices"])
        assertEquals(1, events.count { it is ToolExecutionEvent.Completed })
        assertTrue(controller.requests.value.isEmpty())
        assertEquals(60_000L, testScheduler.currentTime)
    }

    @Test
    fun `cancelling ask user through the executor propagates and cleans the pending group`() = runTest {
        val controller = AskUserController()
        val executor = GenerationToolExecutor.forTest(listOf(AskUserToolProvider(controller)))
        val events = mutableListOf<ToolExecutionEvent>()
        val call = async {
            executor.execute(
                call = AuthorizedToolCall(
                    batchIdentity = BATCH_IDENTITY,
                    callId = "ask-cancelled",
                    name = "ask_user",
                    arguments = """{"questions":[{"question":"First?"},{"question":"Second?"}]}""",
                    context = GenerationContext(askUserEnabled = true, askUserTimeoutMinutes = 1, toolTimeoutMs = 25L),
                    authorizedToolNames = setOf("ask_user"),
                ),
                onEvent = events::add,
            )
        }
        runCurrent()
        val waiting = controller.requests.value
        assertEquals(2, waiting.size)
        advanceTimeBy(30L)
        runCurrent()
        assertFalse(call.isCompleted)

        call.cancelAndJoin()
        runCurrent()

        assertTrue(call.isCancelled)
        assertTrue(controller.requests.value.isEmpty())
        waiting.forEach { assertEquals(AskUserController.Answer.Unanswered, controller.awaitAnswer(it)) }
        advanceTimeBy(60_000L)
        runCurrent()
        assertTrue(events.none { it is ToolExecutionEvent.Completed })
    }

    @Test
    fun `active tool stop releases when durable write settles first`() = runBlocking {
        assertActiveToolStopRelease(persistenceFirst = true)
    }

    @Test
    fun `active tool stop releases when generation coroutine settles first`() = runBlocking {
        assertActiveToolStopRelease(persistenceFirst = false)
    }

    @Test
    fun `tool timeout is a recoverable identified result`() = runTest {
        val provider = object : ToolProvider {
            override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

            override suspend fun execute(
                name: String,
                arguments: String,
                ctx: GenerationContext,
            ): String = awaitCancellation()

            override fun handles(name: String): Boolean = true
        }
        val executor = GenerationToolExecutor.forTest(listOf(provider))

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "call-timeout",
                name = "blocking_tool",
                arguments = "{}",
                context = GenerationContext(toolTimeoutMs = 25L),
                authorizedToolNames = setOf("blocking_tool"),
            ),
            onEvent = {},
        )

        assertEquals(BATCH_IDENTITY, executed.batchIdentity)
        assertEquals("call-timeout", executed.callId)
        assertTrue(executed.result.isError)
        assertTrue(executed.result.text.contains("timed out after 25ms"))
    }

    private suspend fun assertActiveToolStopRelease(persistenceFirst: Boolean) {
        val started = CompletableDeferred<Unit>()
        val provider = object : ToolProvider {
            override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

            override suspend fun execute(
                name: String,
                arguments: String,
                ctx: GenerationContext,
            ): String {
                started.complete(Unit)
                return awaitCancellation()
            }

            override fun handles(name: String): Boolean = name == "blocking_tool"
        }
        val executor = GenerationToolExecutor.forTest(listOf(provider))
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val token = state.acquireForSend()!!
        state.bindRun(token, "run")
        val unwind = CompletableDeferred<Unit>()
        val job = checkNotNull(
            state.launchGenerationJob(token) {
                try {
                    executor.execute(
                        call = AuthorizedToolCall(
                            batchIdentity = BATCH_IDENTITY,
                            callId = "active-call",
                            name = "blocking_tool",
                            arguments = "{}",
                            context = GenerationContext(toolTimeoutMs = 60_000L),
                            authorizedToolNames = setOf("blocking_tool"),
                        ),
                        onEvent = {},
                    )
                } finally {
                    withContext(NonCancellable) { unwind.await() }
                }
            },
        )
        started.await()

        val stopped = state.stop()
        val completion = ConversationCommand.PersistenceSettled(
            identity = requireNotNull(stopped.finalizationEffect).identity,
            success = true,
        )
        if (persistenceFirst) {
            assertEquals(
                ConversationGenerationState.StopFinalizationOutcome.RECORDED,
                state.finishStopFinalization(completion),
            )
            assertTrue(state.stopping.value)
            unwind.complete(Unit)
            job.join()
        } else {
            unwind.complete(Unit)
            job.join()
            withTimeout(5_000L) {
                while (state.runtimeTraceSnapshot().none { it.commandType == "CoroutineSettled" }) {
                    yield()
                }
            }
            assertTrue(state.stopping.value)
            assertEquals(
                ConversationGenerationState.StopFinalizationOutcome.SETTLED,
                state.finishStopFinalization(completion),
            )
        }

        withTimeout(5_000L) { state.stopping.first { stopping -> !stopping } }
        assertFalse(state.generating.value)
        assertFalse(state.stopping.value)
    }

    @Test
    fun `a disabled tool group leaves the request and is refused on execution`() = runTest {
        val controller = AskUserController()
        val executor = GenerationToolExecutor.forTest(listOf(AskUserToolProvider(controller)))
        val enabledContext = GenerationContext(askUserEnabled = true)
        assertTrue(executor.definitions(enabledContext).any { it.function.name == "ask_user" })

        val disabledContext = enabledContext.copy(
            disabledToolGroups = setOf(SessionToolGroup.ASK_USER.id),
        )
        assertTrue(executor.definitions(disabledContext).none { it.function.name == "ask_user" })

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "ask-disabled",
                name = "ask_user",
                arguments = """{"question":"Choose?","options":["A"]}""",
                context = disabledContext,
                authorizedToolNames = setOf("ask_user"),
            ),
            onEvent = {},
        )

        assertTrue(executed.result.isError)
        assertTrue(executed.result.text.contains("disabled for this session"))
        assertTrue(controller.requests.value.isEmpty())
    }

    @Test
    fun `session tool catalog separates switch-off from empty groups`() {
        val definition = ToolDefinition(
            function = ToolFunction(
                name = "web_search",
                description = "Search the web",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        )
        val catalog = SessionToolCatalog.build(
            modelId = "openai:gpt-5",
            context = GenerationContext(webSearchEnabled = false),
            definitionsByGroup = mapOf(SessionToolGroup.WEB to listOf(definition)),
        )

        val web = catalog.groups.first { it.group == SessionToolGroup.WEB }
        assertEquals(SessionToolUnavailable.SWITCH_OFF, web.unavailable)
        assertEquals(1, web.tools.size)
        assertTrue(web.tokens > 0)
        val memory = catalog.groups.first { it.group == SessionToolGroup.MEMORY }
        assertEquals(SessionToolUnavailable.NO_TOOLS, memory.unavailable)

        val memoryRead = ToolDefinition(
            function = ToolFunction(
                name = "memory_read",
                description = "Read a saved memory",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        )
        val disabled = SessionToolCatalog.build(
            modelId = "openai:gpt-5",
            context = GenerationContext(
                accessSavedMemories = true,
                disabledToolGroups = setOf(SessionToolGroup.MEMORY.id),
            ),
            definitionsByGroup = mapOf(SessionToolGroup.MEMORY to listOf(memoryRead)),
        )
        assertEquals(1, disabled.totalToolCount)
        assertEquals(0, disabled.enabledToolCount)
        assertEquals(setOf(SessionToolGroup.MEMORY.id), disabled.disabledIds(emptySet()))
    }

    @Test
    fun `linked groups follow the conversation switch, not the disabled ids`() {
        val definition = ToolDefinition(
            function = ToolFunction(
                name = "web_search",
                description = "Search the web",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        )
        // Conversation turned Web off; the global switch is on, so the probe still lists it.
        val context = GenerationContext(
            webSearchEnabled = false,
            disabledToolGroups = setOf(SessionToolGroup.WEB.id, "retired_group"),
        )
        val catalog = SessionToolCatalog.build(
            modelId = "openai:gpt-5",
            context = context,
            definitionsByGroup = mapOf(SessionToolGroup.WEB to listOf(definition)),
            probe = context.copy(webSearchEnabled = true),
        )
        val web = catalog.groups.first { it.group == SessionToolGroup.WEB }
        assertNull(web.unavailable)
        assertTrue(SessionToolGroup.WEB !in catalog.enabledGroups)
        // A stale linked id is dropped and never written again.
        assertEquals(setOf("retired_group"), catalog.disabledIds(emptySet()))
        assertEquals(setOf("retired_group"), catalog.disabledIds(setOf(SessionToolGroup.WEB)))
        assertTrue(SessionToolGroup.fromIds(context.disabledToolGroups).isEmpty())

        val on = SessionToolCatalog.build(
            modelId = "openai:gpt-5",
            context = GenerationContext(
                webSearchEnabled = true,
                disabledToolGroups = setOf(SessionToolGroup.WEB.id),
            ),
            definitionsByGroup = mapOf(SessionToolGroup.WEB to listOf(definition)),
        )
        assertTrue(SessionToolGroup.WEB in on.enabledGroups)
    }

    @Test
    fun `low context catalog prices only the groups the request still carries`() {
        val web = ToolDefinition(
            function = ToolFunction(
                name = "web_search",
                description = "Search the web",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        )
        val memory = ToolDefinition(
            function = ToolFunction(
                name = "memory_read",
                description = "Read a saved memory",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        )
        val catalog = SessionToolCatalog.build(
            modelId = "openai:gpt-5",
            context = GenerationContext(webSearchEnabled = true, accessSavedMemories = true),
            definitionsByGroup = mapOf(
                SessionToolGroup.WEB to listOf(web),
                SessionToolGroup.MEMORY to listOf(memory),
            ),
            lowContextMode = true,
        )

        val webGroup = catalog.groups.first { it.group == SessionToolGroup.WEB }
        assertNull(webGroup.unavailable)
        assertEquals(1, webGroup.tools.size)
        assertTrue(webGroup.tokens > 0)
        val memoryGroup = catalog.groups.first { it.group == SessionToolGroup.MEMORY }
        assertEquals(SessionToolUnavailable.LOW_CONTEXT, memoryGroup.unavailable)
        assertTrue(memoryGroup.tools.isEmpty())
        assertEquals(0, memoryGroup.tokens)
        assertEquals(1, catalog.totalToolCount)
    }

    @Test
    fun `saving keeps stored ids for groups this session cannot switch`() {
        val todo = ToolDefinition(
            function = ToolFunction(
                name = "todo_read",
                description = "Read the todo list",
                parameters = ToolParameters(properties = emptyMap()),
            ),
        )
        val unavailable = SessionToolCatalog.build(
            modelId = "openai:gpt-5",
            context = GenerationContext(
                todoEnabled = false,
                disabledToolGroups = setOf("mcp", SessionToolGroup.MEMORY.id, "retired_group"),
            ),
            definitionsByGroup = mapOf(SessionToolGroup.TODO to listOf(todo)),
        )
        assertEquals(
            setOf("mcp", SessionToolGroup.MEMORY.id, "retired_group"),
            unavailable.disabledIds(setOf(SessionToolGroup.TODO)),
        )

        val switchable = SessionToolCatalog.build(
            modelId = "openai:gpt-5",
            context = GenerationContext(
                todoEnabled = true,
                disabledToolGroups = setOf("retired_group"),
            ),
            definitionsByGroup = mapOf(SessionToolGroup.TODO to listOf(todo)),
        )
        assertEquals(setOf("retired_group", SessionToolGroup.TODO.id), switchable.disabledIds(emptySet()))
        assertEquals(setOf("retired_group"), switchable.disabledIds(setOf(SessionToolGroup.TODO)))
    }

    private class FakeToolProvider : ToolProvider {
        var executionCount = 0

        override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

        override suspend fun execute(
            name: String,
            arguments: String,
            ctx: GenerationContext,
        ): String {
            executionCount++
            return "done"
        }

        override fun handles(name: String): Boolean = name == "known_tool"
    }

    private companion object {
        val BATCH_IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 1L,
            runId = "run",
            pass = 2,
            effectId = "tool-batch",
        )
    }
}
