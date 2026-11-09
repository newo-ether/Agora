package com.newoether.agora.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class ChatSubagentDaoTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.chatDao()
    }
    @After fun tearDown() = database.close()

    private suspend fun seed(id: String, parent: String? = null) {
        dao.upsertConversation(ChatEntity(id, id, parentConversationId = parent, dataChangedAt = 5L))
    }
    private fun run(id: String) = RunEntity(
        id = "r-$id", conversationId = id, parentRunId = null,
        status = RunStatus.ACTIVE, activeSlot = 1, currentPass = 2,
        startedAt = 1L, lastCheckpointAt = 1L,
    )

    @Test fun authorizationRequiresExactActiveRootRunAndPass() = runTest {
        seed("parent")
        seed("child", "parent")
        dao.insertRun(run("parent"))
        dao.insertRun(run("child"))
        assertTrue(dao.isSubagentToolOwnerActive("parent", "r-parent", 2))
        assertFalse(dao.isSubagentToolOwnerActive("child", "r-child", 2))
        assertFalse(dao.isSubagentToolOwnerActive("parent", "r-parent", 1))
        assertFalse(dao.isSubagentToolOwnerActive("parent", "r-child", 2))
        assertFalse(dao.isSubagentToolOwnerActive("missing", "r-parent", 2))
        dao.markRunStopping("r-parent", 10L)
        assertFalse(dao.isSubagentToolOwnerActive("parent", "r-parent", 2))
        dao.terminalizeLiveRun("r-parent", RunStatus.STOPPED, RunEndReason.USER_STOPPED, 11L)
        assertFalse(dao.isSubagentToolOwnerActive("parent", "r-parent", 2))
    }

    @Test fun outboxAndCapsulesAreParentScopedAndRunningResultsCannotBePublished() = runTest {
        seed("parent")
        seed("other")
        seed("child", "parent")
        seed("foreign", "other")
        assertEquals(1, dao.setSubagentResult("child", "parent", "{}", "result", "completed", 10L))
        assertEquals(1, dao.setSubagentResult("foreign", "other", "{}", "other-result", "failed", 10L))
        val before = dao.getConversation("child")
        assertEquals(0, dao.setSubagentResult("child", "other", "{}", "bad", "failed", 20L))
        assertEquals(before, dao.getConversation("child"))
        assertEquals(listOf("child"), dao.pendingSubagentResults("parent").map { it.id })
        assertEquals("parent", dao.observeParentConversationId("child").first())
        assertEquals("completed", dao.observeSubagents("parent").first().single().status)
        dao.insertRun(run("child"))
        assertTrue(dao.pendingSubagentResults("parent").isEmpty())
        assertEquals("running", dao.observeSubagents("parent").first().single().status)
        assertEquals(0, dao.setSubagentResult("child", "parent", "{}", "bad", "failed", 20L))
        assertEquals(before, dao.getConversation("child"))
    }

    @Test fun capsulesKeepCreationOrderAcrossUpdatesResultsAndNewChildren() = runTest {
        seed("parent")
        seed("other")
        val first = ChatEntity("z-first", "First", parentConversationId = "parent",
            createdAt = 10L, lastUpdated = 900L)
        val tiedB = ChatEntity("b-tied", "B", parentConversationId = "parent",
            createdAt = 20L, lastUpdated = 1L)
        val tiedA = tiedB.copy(id = "a-tied", title = "A", lastUpdated = 500L)
        listOf(tiedB, first, tiedA).forEach { dao.upsertConversation(it) }
        dao.upsertConversation(first.copy(id = "foreign", parentConversationId = "other", createdAt = 0L))
        val expected = listOf("z-first", "a-tied", "b-tied")
        suspend fun assertOrder(ids: List<String>) {
            assertEquals(ids, dao.observeSubagents("parent").first().map { it.id })
            assertEquals(ids, dao.getSubagentIds("parent"))
        }
        assertOrder(expected)
        dao.updateConversationTitle("z-first", "Renamed", 2000L)
        dao.upsertConversation(checkNotNull(dao.getConversation("b-tied")).copy(lastUpdated = 5000L))
        dao.setSubagentResult("a-tied", "parent", "{}", "result", "failed", 6000L)
        dao.insertRun(run("z-first"))
        assertOrder(expected)
        assertEquals("running", dao.observeSubagents("parent").first().first().status)
        assertEquals(10L, dao.getConversation("z-first")?.createdAt)
        dao.upsertConversation(ChatEntity("new", "New", parentConversationId = "parent",
            createdAt = 30L, lastUpdated = 0L))
        assertOrder(expected + "new")
    }

    @Test fun navigationListsHideChildrenBeforePaginationButPreserveTheirStoredState() = runTest {
        dao.upsertConversation(ChatEntity("parent", "Parent", lastUpdated = 1L))
        dao.upsertConversation(ChatEntity("other", "Other", lastUpdated = 2L))
        val child = ChatEntity("child", "Worker", parentConversationId = "parent",
            lastUpdated = 100L, isPinned = true, draftText = "Preserved child draft")
        dao.upsertConversation(child)
        dao.upsertTask(TaskEntity("task", "Task", "Prompt", "", cronExpr = "0 * * * *", nextRunAt = 0L))
        dao.upsertConversation(ChatEntity("execution", "Execution", taskId = "task", lastUpdated = 200L))

        assertEquals(listOf("other", "parent"), dao.getAllConversations().first().map { it.id })
        assertEquals(listOf("other"), dao.observeDrawerConversations(1).first().map { it.id })
        assertEquals(listOf("other", "parent"), dao.observeDrawerConversations(10).first().map { it.id })
        assertEquals(setOf("parent", "other"),
            dao.getDrawerConversations(listOf("parent", "child", "other", "execution")).map { it.id }.toSet())
        assertTrue(dao.getDrawerConversations(listOf("child")).isEmpty())
        assertEquals(child, dao.getConversation("child"))
        assertEquals(child, dao.observeConversation("child").first())
        assertEquals(listOf("child"), dao.observeSubagents("parent").first().map { it.id })
        assertEquals(setOf("parent", "other", "child", "execution"), dao.getAllConversationsList().map { it.id }.toSet())
    }

    @Test fun structuredTerminalProjectionClearsMessageBackedFailedCacheWithoutTouchingOutbox() = runTest {
        seed("parent")
        seed("child", "parent")
        seed("other")
        val failed = run("child").copy(id = "failed", status = RunStatus.FAILED, activeSlot = null,
            endedAt = 2L, endReason = RunEndReason.PROVIDER_ERROR)
        dao.insertRun(failed)
        dao.upsertMessage(MessageEntity(id = "failed-message", conversationId = "child", text = "Old error",
            participant = com.newoether.agora.model.Participant.MODEL,
            status = com.newoether.agora.model.MessageStatus.ERROR, timestamp = 1000L,
            runId = failed.id, runSequence = 1L))
        dao.setSubagentResult("child", "parent", "preserve-json", "failed-message", "failed", 10L)
        dao.acknowledgeSubagentResult("child", "parent", "failed-message", 11L)
        val completed = failed.copy(id = "recovered", status = RunStatus.COMPLETED, startedAt = 3L,
            lastCheckpointAt = 4L, endedAt = 4L, endReason = RunEndReason.MODEL_COMPLETED)
        dao.insertRun(completed)
        dao.upsertMessage(MessageEntity(id = "recovered-message", conversationId = "child", text = "Done",
            participant = com.newoether.agora.model.Participant.MODEL,
            status = com.newoether.agora.model.MessageStatus.SUCCESS, timestamp = 2L,
            runId = completed.id, runSequence = 1L))
        dao.upsertMessage(MessageEntity(id = "newer-user", conversationId = "child", text = "Tool result",
            participant = com.newoether.agora.model.Participant.USER, timestamp = Long.MAX_VALUE,
            runId = completed.id, runSequence = 2L))
        val before = dao.getConversation("child")
        assertEquals("completed", dao.observeSubagents("parent").first().single().status)
        assertEquals(before, dao.getConversation("child"))
        assertTrue(dao.pendingSubagentResults("parent").isEmpty())
        assertNull(dao.getConversation("other")?.subagentResultStatus)
        // A later synthetic admission failure has no structured execution ordering. Keep it.
        dao.setSubagentResult("child", "parent", "synthetic-json", "admission-failed", "failed", 20L)
        assertEquals("failed", dao.observeSubagents("parent").first().single().status)
        assertEquals("admission-failed", dao.pendingSubagentResults("parent").single().resultId)
    }

    @Test fun recoveredRunProjectsErrorAndExplicitStopStaysStoppedWithoutCreatingOutbox() = runTest {
        seed("parent")
        seed("child", "parent")
        dao.insertRun(run("child"))
        dao.upsertMessage(MessageEntity(id = "in-flight", conversationId = "child", text = "Partial answer",
            participant = com.newoether.agora.model.Participant.MODEL,
            status = com.newoether.agora.model.MessageStatus.SENDING, timestamp = 2L,
            runId = "r-child", runSequence = 1L))
        assertEquals("running", dao.observeSubagents("parent").first().single().status)
        dao.recoverConversationRuntime("child", 10L)
        val before = dao.getConversation("child")
        assertEquals("failed", dao.observeSubagents("parent").first().single().status)
        assertEquals(RunStatus.STOPPED, dao.getRun("r-child")?.status)
        assertEquals(RunEndReason.PROCESS_RECOVERED, dao.getRun("r-child")?.endReason)
        assertEquals(before, dao.getConversation("child"))
        assertTrue(dao.pendingSubagentResults("parent").isEmpty())
        val stopped = checkNotNull(dao.getRun("r-child"))
        dao.upsertRun(stopped.copy(endReason = RunEndReason.USER_STOPPED))
        assertEquals("stopped", dao.observeSubagents("parent").first().single().status)
    }

    @Test fun projectionRejectsAmbiguousRunOrderingAndCompactCannotChangeTaskStatus() = runTest {
        seed("parent")
        seed("child", "parent")
        val success = run("child").copy(status = RunStatus.COMPLETED, activeSlot = null,
            endedAt = 2L, endReason = RunEndReason.MODEL_COMPLETED)
        dao.insertRun(success)
        val answer = MessageEntity(id = "answer", conversationId = "child", text = "Done",
            participant = com.newoether.agora.model.Participant.MODEL,
            status = com.newoether.agora.model.MessageStatus.SUCCESS, timestamp = 2L,
            runId = success.id, runSequence = 1L)
        dao.upsertMessage(answer)
        assertEquals("completed", dao.observeSubagents("parent").first().single().status)
        val compact = success.copy(id = "compact", startedAt = 3L, endedAt = 4L)
        dao.insertRun(compact)
        dao.upsertMessage(answer.copy(id = "compact_summary", runId = compact.id,
            status = com.newoether.agora.model.MessageStatus.ERROR))
        assertEquals("completed", dao.observeSubagents("parent").first().single().status)
        dao.upsertRun(success.copy(legacyAmbiguous = true))
        assertEquals("idle", dao.observeSubagents("parent").first().single().status)
        dao.upsertRun(success)
        dao.insertRun(success.copy(id = "tied"))
        dao.upsertMessage(answer.copy(id = "tied-answer", runId = "tied"))
        assertEquals("idle", dao.observeSubagents("parent").first().single().status)
    }

    @Test fun exactReceiptsAreOneShotAndCannotConsumeReplacementOrOtherParent() = runTest {
        seed("parent")
        seed("child", "parent")
        dao.setSubagentResult("child", "parent", "{}", "one", "completed", 10L)
        assertEquals(0, dao.acknowledgeSubagentResult("child", "other", "one", 11L))
        assertEquals(0, dao.acknowledgeSubagentResult("child", "parent", "wrong", 11L))
        assertEquals(1, dao.acknowledgeSubagentResult("child", "parent", "one", 11L))
        assertEquals(0, dao.acknowledgeSubagentResult("child", "parent", "one", 12L))
        assertTrue(dao.pendingSubagentResults("parent").isEmpty())
        dao.setSubagentResult("child", "parent", "{}", "two", "stopped", 13L)
        assertEquals(0, dao.acknowledgeSubagentResult("child", "parent", "one", 14L))
        assertEquals("two", dao.pendingSubagentResults("parent").single().resultId)
        assertEquals("stopped", dao.observeSubagents("parent").first().single().status)
    }
}
