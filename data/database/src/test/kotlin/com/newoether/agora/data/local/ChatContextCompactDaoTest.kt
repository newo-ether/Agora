package com.newoether.agora.data.local

import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.RunEndReason
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatContextCompactDaoTest {

    @Test
    fun recompactSubstitutesAFreshRunAndChangesOnlyTheTargetMessageRow() = runTest {
        val dao = mockk<ChatDao>()
        val conversation = conversation().copy(
            selectedRunBranchesJson =
                "{\"source-run\":\"original-run\",\"original-run\":\"suffix-run\"}",
        )
        val source = message(
            id = "source",
            parentId = null,
            runId = "source-run",
            sequence = 0,
            participant = Participant.MODEL,
        )
        val compact = message(
            id = "compact_boundary",
            parentId = source.id,
            runId = "original-run",
            sequence = 7,
            participant = Participant.MODEL,
        ).copy(text = "old summary", modelName = "old/model")
        val oldRun = activeRun().copy(
            id = compact.runId,
            parentRunId = source.runId,
            status = RunStatus.COMPLETED,
            activeSlot = null,
            endedAt = 10,
            endReason = RunEndReason.MODEL_COMPLETED,
        )
        val replacementRun = activeRun().copy(
            id = "fresh-run",
            parentRunId = source.runId,
            startedAt = 20,
            lastCheckpointAt = 20,
        )
        val selectedRuns = slot<String>()
        coEvery {
            dao.beginRecompactContextCompact(any(), any(), any(), any())
        } coAnswers { callOriginal() }
        coEvery { dao.getMessage(compact.id) } returns compact
        coEvery { dao.getMessage(source.id) } returns source
        coEvery { dao.getLiveRun(conversation.id) } returns null
        coEvery { dao.getConversation(conversation.id) } returns conversation
        coEvery { dao.getRun(compact.runId) } returns oldRun
        coEvery { dao.isOnlyMessageInRun(compact.runId, compact.id) } returns true
        coEvery { dao.insertRun(replacementRun) } just Runs
        coEvery {
            dao.replaceContextCompactMessageRun(
                compact.id,
                oldRun.id,
                replacementRun.id,
                "new/model",
            )
        } returns 1
        coEvery { dao.reparentRunChildren(oldRun.id, replacementRun.id) } returns 1
        coEvery {
            dao.updateSelectionsForRunDeletion(
                conversation.id,
                conversation.selectedBranchesJson!!,
                capture(selectedRuns),
                replacementRun.startedAt,
            )
        } returns 1
        coEvery { dao.deleteRun(oldRun.id) } returns 1

        val restarted = dao.beginRecompactContextCompact(
            replacementRun = replacementRun,
            messageId = compact.id,
            modelName = "new/model",
            expectedSelectedBranchesJson = "{}",
        )

        assertEquals(compact.id, restarted.id)
        assertEquals(compact.parentId, restarted.parentId)
        assertEquals(replacementRun.id, restarted.runId)
        assertEquals(0L, restarted.runSequence)
        assertEquals("", restarted.text)
        assertEquals(MessageStatus.SENDING, restarted.status)
        assertEquals("new/model", restarted.modelName)
        assertTrue(selectedRuns.captured.contains("\"source-run\":\"fresh-run\""))
        assertTrue(selectedRuns.captured.contains("\"fresh-run\":\"suffix-run\""))
        coVerifyOrder {
            dao.insertRun(replacementRun)
            dao.replaceContextCompactMessageRun(
                compact.id,
                oldRun.id,
                replacementRun.id,
                "new/model",
            )
            dao.reparentRunChildren(oldRun.id, replacementRun.id)
            dao.updateSelectionsForRunDeletion(any(), any(), any(), any())
            dao.deleteRun(oldRun.id)
        }
        coVerify(exactly = 0) { dao.getMessagesForRuns(any()) }
        coVerify(exactly = 0) { dao.insertMessage(any()) }

        coVerify(exactly = 0) { dao.reparentMessageChildren(any(), any()) }
        coVerify(exactly = 0) { dao.deleteMessagesByIds(any()) }
    }

    @Test
    fun recompactRejectsASharedLegacyRunBeforeAnyMutation() = runTest {
        val dao = mockk<ChatDao>()
        val compact = message(
            id = "compact_boundary",
            parentId = "source",
            runId = "shared-run",
            sequence = 1,
            participant = Participant.MODEL,
        )
        val oldRun = activeRun().copy(
            id = compact.runId,
            status = RunStatus.COMPLETED,
            activeSlot = null,
            endedAt = 10,
            endReason = RunEndReason.MODEL_COMPLETED,
        )
        val replacementRun = activeRun().copy(id = "fresh-run")
        coEvery {
            dao.beginRecompactContextCompact(any(), any(), any(), any())
        } coAnswers { callOriginal() }
        coEvery { dao.getMessage(compact.id) } returns compact
        coEvery { dao.getLiveRun(compact.conversationId) } returns null
        coEvery { dao.getRun(oldRun.id) } returns oldRun
        coEvery { dao.isOnlyMessageInRun(oldRun.id, compact.id) } returns false

        try {
            dao.beginRecompactContextCompact(
                replacementRun = replacementRun,
                messageId = compact.id,
                modelName = "new/model",
                expectedSelectedBranchesJson = "{}",
            )
            fail("Expected shared legacy Run rejection")
        } catch (_: IllegalStateException) {
            // Fail closed: substituting this Run would affect the sibling message.
        }

        coVerify(exactly = 0) { dao.getMessagesForRuns(any()) }
        coVerify(exactly = 0) { dao.insertRun(any()) }
        coVerify(exactly = 0) {
            dao.replaceContextCompactMessageRun(any(), any(), any(), any())
        }
        coVerify(exactly = 0) { dao.reparentRunChildren(any(), any()) }
        coVerify(exactly = 0) {
            dao.updateSelectionsForRunDeletion(any(), any(), any(), any())
        }
        coVerify(exactly = 0) { dao.deleteRun(any()) }
    }

    @Test
    fun deletingAutomaticCompactNeverDeletesItsOwningRun() = runTest {
        val dao = mockk<ChatDao>()
        val conversation = conversation().copy(
            selectedBranchesJson = "{\"user\":\"compact_boundary\"}",
        )
        val run = activeRun().copy(
            status = RunStatus.COMPLETED,
            activeSlot = null,
            endedAt = 10,
            endReason = RunEndReason.MODEL_COMPLETED,
        )
        val user = message("user", null, run.id, 0, Participant.USER)
        val compact = message("compact_boundary", user.id, run.id, 1, Participant.MODEL)
        coEvery { dao.removeContextCompact(compact.id) } coAnswers { callOriginal() }
        coEvery { dao.getMessage(compact.id) } returns compact
        coEvery { dao.getConversation(conversation.id) } returns conversation
        coEvery { dao.getRun(run.id) } returns run
        coEvery { dao.isOnlyMessageInRun(run.id, compact.id) } returns false
        coEvery { dao.reparentMessageChildren(compact.id, user.id) } returns 1
        coEvery { dao.deleteEmbeddingsByMessageIds(listOf(compact.id)) } just Runs
        coEvery { dao.deleteMessagesByIds(listOf(compact.id)) } just Runs
        coEvery { dao.updateSelectionsForRunDeletion(any(), any(), any(), any()) } returns 1

        assertEquals(true, dao.removeContextCompact(compact.id))
        coVerify(exactly = 0) { dao.getMessagesForRuns(any()) }

        coVerify(exactly = 0) { dao.deleteRun(run.id) }
        coVerify(exactly = 0) { dao.reparentRunChildren(any(), any()) }
    }

    @Test
    fun deletingDedicatedCompactReparentsItsChildAndDeletesOnlyTheCompactRow() = runTest {
        val dao = mockk<ChatDao>()
        val conversation = conversation().copy(
            selectedBranchesJson =
                "{\"source\":\"compact_boundary\",\"compact_boundary\":\"suffix\"}",
            selectedRunBranchesJson =
                "{\"source-run\":\"5f5a-run\",\"5f5a-run\":\"suffix-run\"}",
        )
        val compactRun = activeRun().copy(
            id = "5f5a-run",
            parentRunId = "source-run",
            status = RunStatus.COMPLETED,
            activeSlot = null,
            endedAt = 10,
            endReason = RunEndReason.MODEL_COMPLETED,
        )
        val compact = message(
            "compact_boundary",
            "source",
            compactRun.id,
            0,
            Participant.MODEL,
        )
        coEvery { dao.removeContextCompact(compact.id) } coAnswers { callOriginal() }
        coEvery { dao.getMessage(compact.id) } returns compact
        coEvery { dao.getConversation(conversation.id) } returns conversation
        coEvery { dao.getRun(compactRun.id) } returns compactRun
        coEvery { dao.isOnlyMessageInRun(compactRun.id, compact.id) } returns true
        coEvery { dao.reparentMessageChildren(compact.id, compact.parentId) } returns 1
        coEvery { dao.deleteEmbeddingsByMessageIds(listOf(compact.id)) } just Runs
        coEvery { dao.deleteMessagesByIds(listOf(compact.id)) } just Runs
        coEvery { dao.updateSelectionsForRunDeletion(any(), any(), any(), any()) } returns 1
        coEvery {
            dao.reparentRunChildren(compactRun.id, compactRun.parentRunId)
        } returns 1
        coEvery { dao.deleteRun(compactRun.id) } returns 1

        assertEquals(true, dao.removeContextCompact(compact.id))
        coVerify(exactly = 0) { dao.getMessagesForRuns(any()) }

        coVerify(exactly = 1) {
            dao.reparentMessageChildren(compact.id, compact.parentId)
            dao.deleteMessagesByIds(listOf(compact.id))
            dao.reparentRunChildren(compactRun.id, compactRun.parentRunId)
            dao.deleteRun(compactRun.id)
        }

    }

    private fun conversation() = ChatEntity(
        id = "conversation",
        title = "title",
        selectedBranchesJson = "{}",
        selectedRunBranchesJson = "{}",
    )

    private fun activeRun() = RunEntity(
        id = "run",
        conversationId = "conversation",
        parentRunId = null,
        status = RunStatus.ACTIVE,
        activeSlot = 1,
        startedAt = 1,
        lastCheckpointAt = 2,
        currentPass = 0,
    )

    private fun message(
        id: String,
        parentId: String?,
        runId: String,
        sequence: Long,
        participant: Participant,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = id,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        runId = runId,
        runSequence = sequence,
    )
}
