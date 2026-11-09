package com.newoether.agora.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatDaoRunAdmissionTest {
    @Test
    fun nullModelUpdatePreservesPreferenceWhileTheMessageUsesAnotherModel() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = database.chatDao()
            for ((index, preference) in listOf(null, "provider:chat", "provider:newer-choice").withIndex()) {
                val conversation = ChatEntity(
                    id = "c$index", title = "title", modelId = preference,
                    systemPromptId = "chat-prompt", draftText = "draft",
                    lastUpdated = 1L, dataChangedAt = 1L,
                )
                dao.upsertConversation(conversation)
                val run = RunEntity(
                    id = "r$index", conversationId = conversation.id, parentRunId = null,
                    status = RunStatus.ACTIVE, activeSlot = 1,
                    startedAt = 10L, lastCheckpointAt = 10L,
                )
                val message = MessageEntity(
                    id = "compact_$index", conversationId = conversation.id,
                    text = "", participant = Participant.MODEL, status = MessageStatus.SENDING,
                    timestamp = 10L, modelName = "provider:compact", runId = run.id, runSequence = 0,
                )
                val commit = dao.createRunWithMessages(
                    run, listOf(message), mapOf(null to message.id),
                    conversationModelId = null, at = 10L,
                    touchConversationOnAdmission = index != 0,
                )
                val stored = requireNotNull(dao.getConversation(conversation.id))
                assertEquals(conversation.copy(
                    selectedBranchesJson = stored.selectedBranchesJson,
                    selectedRunBranchesJson = stored.selectedRunBranchesJson,
                    lastUpdated = if (index == 0) 1L else 10L,
                    dataChangedAt = 10L,
                ), stored)
                assertEquals("provider:compact", dao.getMessage(message.id)?.modelName)
                assertEquals(run, dao.getRun(run.id))
                assertEquals(mapOf(null to message.id), commit.messageSelections)
                assertEquals(mapOf(null to run.id), commit.runSelections)
                assertEquals(1, dao.updateConversationForRunAdmission(
                    conversation.id, stored.selectedBranchesJson!!, stored.selectedRunBranchesJson!!,
                    modelId = "provider:next-chat", at = 20L, touchConversationOnAdmission = false,
                ))
                assertEquals("provider:next-chat", dao.getConversation(conversation.id)?.modelId)
                assertEquals(stored.lastUpdated, dao.getConversation(conversation.id)?.lastUpdated)
                assertEquals("provider:compact", dao.getMessage(message.id)?.modelName)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun selectionOnlyQueriesNeverWriteConversationRecency() {
        val dao = sourceFile("data/database/src/main/kotlin/com/newoether/agora/data/local/ChatContextCompactDao.kt")
            .replace("\r\n", "\n")
        listOf(
            "suspend fun updateMessageBranchSelections(",
            "suspend fun updateRunBranchSelections(",
            "suspend fun updateBranchSelections(",
        ).forEach { declaration ->
            val query = dao.substringBefore(declaration).substringAfterLast("@Query(")
            assertFalse(query.contains("lastUpdated"))
        }
        val deletionQuery = dao.substringBefore("suspend fun updateSelectionsForRunDeletion(")
            .substringAfterLast("@Query(")
        assertTrue(deletionQuery.contains("lastUpdated = :at"))
    }

    @Test
    fun automationAdmissionsNeverTouchConversationRecency() {
        val taskEngine = sourceFile(
            "app/src/main/java/com/newoether/agora/automation/TaskExecutionEngine.kt",
        ).replace("\r\n", "\n")
        val taskTransitions = sourceFile(
            "app/src/main/java/com/newoether/agora/automation/TaskGenerationTransitions.kt",
        ).replace("\r\n", "\n")
        val generationController = sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/MessageGenerationController.kt",
        ).replace("\r\n", "\n")
        val automationSend = generationController
            .substringAfter("internal suspend fun sendMessageFromAutomationAwaitingCompletion(")
            .substringBefore("private fun scheduleAutomaticCompactContinuation(")
        val automaticContinuation = generationController
            .substringAfter("private fun scheduleAutomaticCompactContinuation(")
            .substringBefore("fun generateTitle(")

        listOf(taskEngine, taskTransitions).forEach { source ->
            assertEquals(1, Regex("touchConversationOnAdmission = false").findAll(source).count())
            assertFalse(source.contains("touchConversationOnAdmission = true"))
        }
        assertTrue(taskEngine.contains("compactController.continueTaskGenerations("))
        assertTrue(automationSend.contains("touchConversationOnAdmission = false"))
        assertTrue(automaticContinuation.contains("touchConversationOnAdmission = false"))
    }

    @Test
    fun runGraphAdmissionWritesSelectedModelAndHonorsTimestampTouchPolicy() = runTest {
        val dao = mockk<ChatDao>()
        val conversation = ChatEntity(
            id = "conversation",
            title = "title",
            modelId = "provider:old",
        )
        val run = RunEntity(
            id = "run",
            conversationId = conversation.id,
            parentRunId = null,
            status = RunStatus.ACTIVE,
            activeSlot = 1,
            startedAt = 1L,
            lastCheckpointAt = 2L,
        )
        val message = MessageEntity(
            id = "model",
            conversationId = conversation.id,
            text = "",
            status = MessageStatus.SENDING,
            participant = Participant.MODEL,
            timestamp = 2L,
            modelName = "provider:new",
            runId = run.id,
            runSequence = 0,
        )
        val touchPolicies = mutableListOf<Boolean>()
        coEvery {
            dao.createRunWithMessages(any(), any(), any(), any(), any(), any())
        } coAnswers { callOriginal() }
        coEvery { dao.getConversation(conversation.id) } returns conversation
        coEvery { dao.getLiveRun(conversation.id) } returns null
        coEvery { dao.insertRun(run) } just Runs
        coEvery { dao.insertMessage(message) } just Runs
        coEvery {
            dao.updateConversationForRunAdmission(
                conversationId = conversation.id,
                selectedBranchesJson = any(),
                selectedRunBranchesJson = any(),
                modelId = "provider:new",
                at = any(),
                touchConversationOnAdmission = capture(touchPolicies),
            )
        } returns 1

        val result = dao.createRunWithMessages(
            run = run,
            messages = listOf(message),
            messageSelectionUpdates = mapOf(null to message.id),
            conversationModelId = "provider:new",
            at = 10L,
            touchConversationOnAdmission = true,
        )
        val preserved = dao.createRunWithMessages(
            run = run,
            messages = listOf(message),
            messageSelectionUpdates = mapOf(null to message.id),
            conversationModelId = "provider:new",
            at = 11L,
            touchConversationOnAdmission = false,
        )

        assertEquals(listOf(message), result.messages)
        assertEquals(listOf(message), preserved.messages)
        assertEquals(listOf(true, false), touchPolicies)
        coVerifyOrder {
            dao.insertRun(run)
            dao.insertMessage(message)
            dao.updateConversationForRunAdmission(
                conversationId = conversation.id,
                selectedBranchesJson = any(),
                selectedRunBranchesJson = any(),
                modelId = "provider:new",
                at = 10L,
                touchConversationOnAdmission = true,
            )
            dao.insertRun(run)
            dao.insertMessage(message)
            dao.updateConversationForRunAdmission(
                conversationId = conversation.id,
                selectedBranchesJson = any(),
                selectedRunBranchesJson = any(),
                modelId = "provider:new",
                at = 11L,
                touchConversationOnAdmission = false,
            )
        }
    }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relativePath).takeIf(File::isFile)?.let { return it.readText() }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
