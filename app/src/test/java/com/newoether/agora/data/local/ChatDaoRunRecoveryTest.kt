package com.newoether.agora.data.local

import java.io.File
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.ToolExecutionStates
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDaoRunRecoveryTest {
    @Test
    fun recoveryExecutesTheExactSnapshotEffectAndStopsItsInFlightModel() = runTest {
        val dao = mockk<ChatDao>()
        val message = MessageEntity(
            id = "message",
            conversationId = CONVERSATION_ID,
            text = "partial",
            status = MessageStatus.THINKING,
            participant = Participant.MODEL,
            timestamp = 2L,
            runId = RUN_ID,
            runSequence = 0,
        )
        val checkpoint = slot<MessageStreamCheckpoint>()
        stubExactOwner(dao, liveRun(RunStatus.ACTIVE))
        coEvery { dao.getMessagesForRuns(listOf(RUN_ID)) } returns listOf(message)
        coEvery { dao.updateMessageCheckpoint(capture(checkpoint)) } returns 1
        coEvery {
            dao.terminalizeLiveRun(
                RUN_ID,
                RunStatus.STOPPED,
                RunEndReason.PROCESS_RECOVERED,
                99L,
            )
        } returns 1

        assertEquals(2, dao.recoverConversationRuntime(CONVERSATION_ID, 99L))
        assertEquals(MessageStatus.STOPPED, checkpoint.captured.status)
        coVerify(exactly = 1) { dao.getConversation(CONVERSATION_ID) }
        coVerify(exactly = 1) { dao.getLiveRun(CONVERSATION_ID) }
        coVerify(exactly = 1) { dao.stopStuckMessagesForConversation(CONVERSATION_ID) }
        coVerify(exactly = 1) { dao.touchConversationData(CONVERSATION_ID, 99L) }
        coVerify(exactly = 0) { dao.getAllConversationsList() }
    }

    @Test
    fun recoveryRejectsALostExactRunUpdate() = runTest {
        val dao = mockk<ChatDao>()
        stubExactOwner(dao, liveRun(RunStatus.STOPPING))
        coEvery { dao.getMessagesForRuns(listOf(RUN_ID)) } returns emptyList()
        coEvery { dao.terminalizeLiveRun(any(), any(), any(), any()) } returns 0

        val failure = runCatching {
            dao.recoverConversationRuntime(CONVERSATION_ID, 99L)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { dao.stopStuckMessagesForConversation(any()) }
    }

    @Test
    fun recoveryRepairsOnlyTheExactOwnersRunBranchesAndStuckRows() = runTest {
        val dao = mockk<ChatDao>()
        coEvery { dao.recoverConversationRuntime(any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.getConversation(CONVERSATION_ID) } returns ChatEntity(
            id = CONVERSATION_ID,
            title = "Conversation",
            selectedRunBranchesJson = "{\"null\":\"missing\"}",
        )
        coEvery { dao.getLiveRun(CONVERSATION_ID) } returns null
        coEvery { dao.getRunsForConversationSnapshot(CONVERSATION_ID) } returns emptyList()
        coEvery {
            dao.compareAndSetRunBranchSelections(
                CONVERSATION_ID,
                "{\"null\":\"missing\"}",
                "{}",
            )
        } returns 1
        coEvery { dao.stopStuckMessagesForConversation(CONVERSATION_ID) } returns 2
        coEvery { dao.touchConversationData(CONVERSATION_ID, 99L) } returns 1

        assertEquals(3, dao.recoverConversationRuntime(CONVERSATION_ID, 99L))

        coVerify(exactly = 1) { dao.getRunsForConversationSnapshot(CONVERSATION_ID) }
        coVerify(exactly = 0) { dao.getConversation(OTHER_CONVERSATION_ID) }
        coVerify(exactly = 0) { dao.getLiveRun(OTHER_CONVERSATION_ID) }
        coVerify(exactly = 0) { dao.stopStuckMessagesForConversation(OTHER_CONVERSATION_ID) }
        coVerify(exactly = 0) { dao.getAllConversationsList() }
        coVerify(exactly = 1) { dao.touchConversationData(CONVERSATION_ID, 99L) }
    }

    @Test
    fun recoveryStopsTheDurableCompactRowInsteadOfRemovingIt() = runTest {
        val dao = mockk<ChatDao>()
        val compact = MessageEntity(
            id = "compact_inflight",
            conversationId = CONVERSATION_ID,
            parentId = "user",
            text = "partial summary",
            status = MessageStatus.SENDING,
            participant = Participant.MODEL,
            timestamp = 2L,
            runId = RUN_ID,
            runSequence = 1,
        )
        val checkpoint = slot<MessageStreamCheckpoint>()
        stubExactOwner(dao, liveRun(RunStatus.ACTIVE))
        coEvery { dao.getMessagesForRuns(listOf(RUN_ID)) } returns listOf(compact)
        coEvery { dao.updateMessageCheckpoint(capture(checkpoint)) } returns 1
        coEvery { dao.terminalizeLiveRun(any(), any(), any(), any()) } returns 1

        assertEquals(2, dao.recoverConversationRuntime(CONVERSATION_ID, 99L))
        assertEquals(compact.id, checkpoint.captured.id)
        assertEquals("partial summary", checkpoint.captured.text)
        assertEquals(MessageStatus.STOPPED, checkpoint.captured.status)
    }

    @Test
    fun recoveryPreservesUnknownToolFieldsWhileStoppingLiveTools() = runTest {
        val dao = mockk<ChatDao>()
        val raw =
            """[{"type":"tool","toolName":"shell","toolState":"running","future":{"v":1}},""" +
                """{"type":"tool","toolName":"background","toolState":"background_running","futureFlag":true}]"""
        val message = MessageEntity(
            id = "message-with-future-fields",
            conversationId = CONVERSATION_ID,
            text = "partial",
            status = MessageStatus.STOPPED,
            participant = Participant.MODEL,
            timestamp = 2L,
            toolCallJson = raw,
            runId = RUN_ID,
            runSequence = 0,
        )
        val checkpoint = slot<MessageStreamCheckpoint>()
        stubExactOwner(dao, liveRun(RunStatus.ACTIVE))
        coEvery { dao.getMessagesForRuns(listOf(RUN_ID)) } returns listOf(message)
        coEvery { dao.updateMessageCheckpoint(capture(checkpoint)) } returns 1
        coEvery { dao.terminalizeLiveRun(any(), any(), any(), any()) } returns 1

        assertEquals(2, dao.recoverConversationRuntime(CONVERSATION_ID, 99L))
        val segments = Json.parseToJsonElement(
            requireNotNull(checkpoint.captured.toolCallJson),
        ).jsonArray
        assertEquals(
            ToolExecutionStates.STOPPED,
            segments[0].jsonObject["toolState"]?.jsonPrimitive?.content,
        )
        assertEquals("{\"v\":1}", segments[0].jsonObject["future"]?.toString())
        assertEquals(
            ToolExecutionStates.BACKGROUND_RUNNING,
            segments[1].jsonObject["toolState"]?.jsonPrimitive?.content,
        )
        assertEquals("true", segments[1].jsonObject["futureFlag"]?.toString())
    }

    @Test
    fun `runtime recovery keeps its transaction boundary on the DAO method`() {
        // Room binds the transaction wrapper from this source declaration at compile time.
        val source = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, "src/main/java/com/newoether/agora/data/local/ChatDao.kt") }
            .firstOrNull(File::exists)
        assertTrue("ChatDao.kt source not located", source != null)
        assertTrue(
            Regex("@Transaction\\s+suspend fun recoverConversationRuntime")
                .containsMatchIn(requireNotNull(source).readText()),
        )
    }

    private fun stubExactOwner(dao: ChatDao, run: RunEntity?) {
        coEvery { dao.recoverConversationRuntime(any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.getConversation(CONVERSATION_ID) } returns ChatEntity(
            id = CONVERSATION_ID,
            title = "Conversation",
        )
        coEvery { dao.getLiveRun(CONVERSATION_ID) } returns run
        coEvery { dao.stopStuckMessagesForConversation(CONVERSATION_ID) } returns 0
        coEvery { dao.touchConversationData(CONVERSATION_ID, any()) } returns 1
    }

    private fun liveRun(status: RunStatus) = RunEntity(
        id = RUN_ID,
        conversationId = CONVERSATION_ID,
        parentRunId = null,
        status = status,
        activeSlot = 1,
        startedAt = 1L,
        lastCheckpointAt = 2L,
        stopRequestedAt = if (status == RunStatus.STOPPING) 2L else null,
        currentPass = 3,
    )

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val OTHER_CONVERSATION_ID = "other"
        const val RUN_ID = "run"
    }
}
