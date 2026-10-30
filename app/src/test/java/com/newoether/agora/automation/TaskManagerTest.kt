package com.newoether.agora.automation

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.ProviderContextTopologySnapshot
import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.TaskRepository
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskManagerTest {
    @Test
    fun scheduledIncompleteTaskIsDisabledWithoutCallingModel() = runTest {
        val repository = mockk<TaskRepository>()
        val conversations = mockk<ConversationRepository>()
        val engine = mockk<TaskExecutionEngine>()
        var stored = task(prompt = "")
        every { repository.getAllTasks() } returns MutableStateFlow(listOf(stored))
        coEvery { repository.getTask(stored.id) } coAnswers { stored }
        coEvery { repository.upsertTask(any()) } coAnswers { stored = firstArg() }
        val manager = TaskManager(repository, conversations, engine, backgroundScope)

        val result = manager.executeById(stored.id, "execution", stored.nextRunAt)

        assertTrue(result is TaskManager.ExecutionResult.Skipped)
        assertFalse(stored.enabled)
        assertEquals(0L, stored.nextRunAt)
        coVerify(exactly = 0) { engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun incompleteDraftIsNeverPersisted() = runTest {
        val repository = mockk<TaskRepository>()
        every { repository.getAllTasks() } returns MutableStateFlow(emptyList())
        coEvery { repository.upsertTask(any()) } returns Unit
        val manager = TaskManager(
            repository,
            mockk(),
            mockk(),
            backgroundScope,
        )

        manager.saveTask(task(name = "", prompt = "Prompt"))
        manager.saveTask(task(name = "Task", prompt = ""))

        coVerify(exactly = 0) { repository.upsertTask(any()) }
    }

    @Test
    fun incompleteRunNowDraftIsNeverPersistedOrExecuted() = runTest {
        val repository = mockk<TaskRepository>()
        val engine = mockk<TaskExecutionEngine>()
        every { repository.getAllTasks() } returns MutableStateFlow(emptyList())
        coEvery { repository.upsertTask(any()) } returns Unit
        val manager = TaskManager(
            repository,
            mockk(),
            engine,
            backgroundScope,
        )

        manager.runNow(task(name = "", prompt = "Prompt"))
        manager.runNow(task(name = "Task", prompt = ""))

        coVerify(exactly = 0) { repository.upsertTask(any()) }
        coVerify(exactly = 0) { engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun listRunNowPreservesPersistedEnabledState() = runTest {
        val repository = mockk<TaskRepository>()
        val conversations = mockk<ConversationRepository>()
        val engine = mockk<TaskExecutionEngine>()
        var stored = task().copy(enabled = false, nextRunAt = 0L)
        every { repository.getAllTasks() } returns MutableStateFlow(listOf(stored))
        coEvery { repository.getTask(stored.id) } coAnswers { stored }
        coEvery { repository.upsertTask(any()) } coAnswers { stored = firstArg() }
        coEvery { conversations.recoverConversationRuntime(any(), any()) } returns 0
        coEvery { conversations.getConversation(any()) } returns null
        coEvery { conversations.upsertConversation(any()) } returns Unit
        coEvery { engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any()) } returns
            TaskExecutionEngine.Result.Busy()
        val manager = TaskManager(repository, conversations, engine, backgroundScope)

        manager.runNow(stored.copy(enabled = true))
        runCurrent()

        coVerify(atLeast = 1) { repository.upsertTask(any()) }
        assertFalse(stored.enabled)
    }

    @Test
    fun editorRunNowPersistsCurrentEnabledState() = runTest {
        val repository = mockk<TaskRepository>()
        val conversations = mockk<ConversationRepository>()
        val engine = mockk<TaskExecutionEngine>()
        var stored = task().copy(enabled = false, nextRunAt = 0L)
        every { repository.getAllTasks() } returns MutableStateFlow(listOf(stored))
        coEvery { repository.getTask(stored.id) } coAnswers { stored }
        coEvery { repository.upsertTask(any()) } coAnswers { stored = firstArg() }
        coEvery { conversations.recoverConversationRuntime(any(), any()) } returns 0
        coEvery { conversations.getConversation(any()) } returns null
        coEvery { conversations.upsertConversation(any()) } returns Unit
        coEvery { engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any()) } returns
            TaskExecutionEngine.Result.Busy()
        val manager = TaskManager(repository, conversations, engine, backgroundScope)

        manager.runNow(
            stored.copy(enabled = true),
            preservePersistedEnabled = false,
        )
        runCurrent()

        coVerify(atLeast = 1) { repository.upsertTask(any()) }
        assertTrue(stored.enabled)
    }

    @Test
    fun busyConversationIsReturnedAsAReplaySafeDeferredOccurrence() = runTest {
        val repository = mockk<TaskRepository>()
        val conversations = mockk<ConversationRepository>()
        val engine = mockk<TaskExecutionEngine>()
        val stored = task()
        every { repository.getAllTasks() } returns MutableStateFlow(listOf(stored))
        coEvery { repository.getTask(stored.id) } returns stored
        coEvery { conversations.recoverConversationRuntime(any(), any()) } returns 0
        coEvery { conversations.getConversation("execution") } returns null
        coEvery { conversations.upsertConversation(any()) } returns Unit
        coEvery {
            engine.runOnceWithAutomationGuardsHeld("execution", stored.prompt, stored.modelId, "", true, any(), "task")
        } returns TaskExecutionEngine.Result.Busy()
        val manager = TaskManager(repository, conversations, engine, backgroundScope)

        val result = manager.executeById(stored.id, "execution")

        val deferred = result as TaskManager.ExecutionResult.Deferred
        assertEquals("execution", deferred.conversationId)
        assertEquals("Conversation is already generating", deferred.reason)
        coVerifyOrder {
            conversations.recoverConversationRuntime("execution", any())
            conversations.getConversation("execution")
            conversations.upsertConversation(any())
            engine.runOnceWithAutomationGuardsHeld(
                "execution", stored.prompt, stored.modelId, "", true, any(), "task",
            )
        }
    }

    @Test
    fun exactRecoveryFailureStopsBeforeDeterministicExecutionReadsOrGeneration() = runTest {
        val repository = mockk<TaskRepository>()
        val conversations = mockk<ConversationRepository>()
        val engine = mockk<TaskExecutionEngine>()
        val stored = task()
        every { repository.getAllTasks() } returns MutableStateFlow(listOf(stored))
        coEvery { repository.getTask(stored.id) } returns stored
        coEvery { conversations.recoverConversationRuntime("execution", any()) } throws
            IllegalStateException("recovery failed")
        val manager = TaskManager(repository, conversations, engine, backgroundScope)

        val failure = runCatching {
            manager.executeById(stored.id, "execution")
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { conversations.getConversation(any()) }
        coVerify(exactly = 0) {
            engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun successfulRecoveryLoadsOnlyTheTerminalAssistantPayload() = runTest {
        val repository = mockk<TaskRepository>()
        val conversations = mockk<ConversationRepository>()
        val engine = mockk<TaskExecutionEngine>()
        val stored = task()
        val assistant = MessageEntity(
            id = "assistant",
            conversationId = "execution",
            text = "done",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
            timestamp = 2L,
            runId = "run",
            runSequence = 1L,
        )
        every { repository.getAllTasks() } returns MutableStateFlow(listOf(stored))
        coEvery { repository.getTask(stored.id) } returns stored
        coEvery { conversations.recoverConversationRuntime(any(), any()) } returns 0
        coEvery { conversations.getConversation("execution") } returns ChatEntity(
            id = "execution",
            title = "Task",
            taskId = stored.id,
        )
        val reader = mockk<com.newoether.agora.data.local.ChatDao>()
        coEvery {
            reader.getProviderContextTopologySnapshot("execution")
        } returns ProviderContextTopologySnapshot(
            selectedBranchesJson = null,
            messages = listOf(
                MessageContextTopology(
                    id = assistant.id,
                    conversationId = assistant.conversationId,
                    parentId = null,
                    status = assistant.status,
                    participant = assistant.participant,
                    timestamp = assistant.timestamp,
                    modelName = assistant.modelName,
                    runId = assistant.runId,
                    runSequence = assistant.runSequence,
                    consumedAtPass = assistant.consumedAtPass,
                ),
            ),
        )
        coEvery { reader.getMessage(assistant.id) } returns assistant
        coEvery {
            conversations.withReadSnapshot<Any?>(any())
        } coAnswers {
            firstArg<suspend (com.newoether.agora.data.local.ChatDao) -> Any?>().invoke(reader)
        }
        val manager = TaskManager(repository, conversations, engine, backgroundScope)

        val result = manager.executeById(stored.id, "execution")

        val success = result as TaskManager.ExecutionResult.Success
        assertEquals("execution", success.conversationId)
        assertEquals("done", success.response)
        coVerify(exactly = 0) { engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun successReleasesRunningPresentationBeforeTitleGenerationFinishes() = runTest {
        val repository = mockk<TaskRepository>()
        val conversations = mockk<ConversationRepository>()
        val engine = mockk<TaskExecutionEngine>()
        var stored = task().copy(nextRunAt = 0L)
        coEvery { repository.getTask(stored.id) } coAnswers { stored }
        coEvery { repository.upsertTask(any()) } coAnswers { stored = firstArg() }
        coEvery { conversations.recoverConversationRuntime(any(), any()) } returns 0
        coEvery { conversations.getConversation(any()) } returns null
        coEvery { conversations.upsertConversation(any()) } returns Unit
        coEvery { engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any()) } returns
            TaskExecutionEngine.Result.Success("assistant", "Done")
        val title = kotlinx.coroutines.CompletableDeferred<Unit>()
        var titleStarted = false
        val manager = TaskManager(repository, conversations, engine, backgroundScope,
            titleExecutionConversation = { _, _ -> titleStarted = true; title.await() })

        manager.runNow(stored)
        assertTrue(stored.id in manager.runningTaskIds.value)
        runCurrent()
        assertTrue(titleStarted)
        assertFalse(stored.id in manager.runningTaskIds.value)
        assertTrue((stored.lastRunAt ?: 0L) > 0L)
        title.complete(Unit)
        runCurrent()
        assertFalse(stored.id in manager.runningTaskIds.value)
    }

    private fun task(
        name: String = "Task",
        prompt: String = "Prompt",
    ) = TaskEntity(
        id = "task",
        name = name,
        prompt = prompt,
        cronExpr = "* * * * *",
        nextRunAt = 123L,
        enabled = true,
    )
}
