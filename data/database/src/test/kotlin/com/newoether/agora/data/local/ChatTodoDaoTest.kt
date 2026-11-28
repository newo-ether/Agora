package com.newoether.agora.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.ConversationTodo
import com.newoether.agora.model.ConversationTodoItem
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class ChatTodoDaoTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao
    private val original = ConversationTodo.encode(listOf(ConversationTodoItem("Original")))
    private val updated = ConversationTodo.encode(listOf(ConversationTodoItem("Updated", "completed")))

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.chatDao()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun seed(id: String = "c", run: RunEntity? = activeRun(id)) {
        dao.upsertConversation(ChatEntity(
            id = id, title = id, lastUpdated = 5L, dataChangedAt = 10L,
            draftText = "Keep draft", selectedBranchesJson = "{}", todoJson = original,
        ))
        run?.let { dao.insertRun(it) }
    }

    @Test
    fun exactLivePassUpdatesOnlyTodoAndWatermarkAndEmptyListClearsIt() = runTest {
        seed()
        seed("other")
        val before = requireNotNull(dao.getConversation("c"))
        val other = dao.getConversation("other")
        assertEquals(1, dao.updateConversationTodo("c", updated, "r-c", 2, 10L))
        assertEquals(before.copy(todoJson = updated, dataChangedAt = 11L), dao.getConversation("c"))
        assertEquals(updated, dao.observeConversationTodo("c").first())
        assertEquals(other, dao.getConversation("other"))
        assertEquals(1, dao.updateConversationTodo("c", ConversationTodo.encode(emptyList()), "r-c", 2, 10L))
        assertNull(dao.getConversationTodo("c"))
        assertEquals(12L, dao.getConversation("c")!!.dataChangedAt)
    }

    @Test
    fun staleRunPassOwnerAndEveryStoppingOrTerminalStateRejectWithoutMutation() = runTest {
        val invalidRuns = listOf(
            activeRun("stopping").copy(status = RunStatus.STOPPING, stopRequestedAt = 12L),
            activeRun("stop-requested").copy(stopRequestedAt = 12L),
            activeRun("completed").copy(status = RunStatus.COMPLETED, activeSlot = null,
                endedAt = 12L, endReason = com.newoether.agora.model.RunEndReason.MODEL_COMPLETED),
            activeRun("stopped").copy(status = RunStatus.STOPPED, activeSlot = null,
                endedAt = 12L, endReason = com.newoether.agora.model.RunEndReason.USER_STOPPED),
            activeRun("failed").copy(status = RunStatus.FAILED, activeSlot = null,
                endedAt = 12L, endReason = com.newoether.agora.model.RunEndReason.PROVIDER_ERROR),
        )
        for (run in invalidRuns) {
            seed(run.conversationId, run)
            val before = dao.getConversation(run.conversationId)
            assertEquals(0, dao.updateConversationTodo(run.conversationId, updated, run.id, 2, 100L))
            assertEquals(before, dao.getConversation(run.conversationId))
        }
        seed()
        seed("other")
        seed("no-run", run = null)
        val before = dao.getConversation("c")
        assertEquals(0, dao.updateConversationTodo("c", updated, "r-c", 1, 100L))
        assertEquals(0, dao.updateConversationTodo("c", updated, "r-c", 3, 100L))
        assertEquals(0, dao.updateConversationTodo("c", updated, "missing", 2, 100L))
        assertEquals(0, dao.updateConversationTodo("c", updated, "r-other", 2, 100L))
        assertEquals(0, dao.updateConversationTodo("no-run", updated, "r-c", 2, 100L))
        assertEquals(0, dao.updateConversationTodo("missing", updated, "r-c", 2, 100L))
        assertEquals(before, dao.getConversation("c"))
        assertEquals(original, dao.getConversationTodo("no-run"))
    }

    @Test
    fun stopCommittedBeforeLateToolWriteCannotChangeTodoOrWatermark() = runTest {
        seed()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE runs SET stopRequestedAt = 20, status = 'STOPPING' WHERE id = 'r-c'",
        )
        val before = dao.getConversation("c")
        assertEquals(0, dao.updateConversationTodo("c", updated, "r-c", 2, 30L))
        assertEquals(before, dao.getConversation("c"))
    }

    private fun activeRun(id: String) = RunEntity(
        id = "r-$id", conversationId = id, parentRunId = null,
        status = RunStatus.ACTIVE, activeSlot = 1, currentPass = 2,
        startedAt = 1L, lastCheckpointAt = 1L,
    )
}
