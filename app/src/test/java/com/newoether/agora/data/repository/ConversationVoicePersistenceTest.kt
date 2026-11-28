package com.newoether.agora.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConversationVoicePersistenceTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao
    private lateinit var repository: ConversationRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.chatDao()
        repository = ConversationRepository(dao, database, scheduleMaintenance = {})
    }

    @After fun tearDown() = database.close()

    @Test fun persistsVoiceExchangeAsTerminalSelectedRun() = runTest {
        dao.upsertConversation(ChatEntity("c1", "Chat"))

        val result = repository.persistVoiceExchange(
            conversationId = "c1",
            userText = "  hello there  ",
            assistantText = "general kenobi",
            modelName = "gemini-3.8-live",
            at = 1000L,
        )

        val persisted = result as VoicePersistResult.Persisted
        val run = checkNotNull(dao.getRun(persisted.runId))
        assertTrue("run must be terminal", run.status.isTerminal)
        assertEquals(RunStatus.COMPLETED, run.status)
        assertNull("terminal run has null activeSlot", run.activeSlot)
        assertNull("root exchange has no parent run", run.parentRunId)

        val user = checkNotNull(dao.getMessage(persisted.userMessageId))
        val assistant = checkNotNull(dao.getMessage(persisted.assistantMessageId))
        assertEquals("hello there", user.text) // trimmed
        assertEquals(Participant.USER, user.participant)
        assertNull(user.parentId) // first turn in an empty conversation
        assertEquals("general kenobi", assistant.text)
        assertEquals(Participant.MODEL, assistant.participant)
        assertEquals(user.id, assistant.parentId) // assistant is child of user
        assertEquals("gemini-3.8-live", assistant.modelName)
        assertEquals(MessageStatus.SUCCESS, assistant.status)
        assertEquals(run.id, user.runId)
        assertEquals(run.id, assistant.runId)
        assertEquals(0L, user.runSequence)
        assertEquals(1L, assistant.runSequence)

        // Selected onto the current branch so it is visible.
        val conv = checkNotNull(dao.getConversation("c1"))
        val selections = decodeSelectionMap(conv.selectedBranchesJson)
        assertEquals(user.id, selections[null])
        assertEquals(assistant.id, selections[user.id])
        val runSelections = decodeSelectionMap(conv.selectedRunBranchesJson)
        assertEquals(run.id, runSelections[null])
    }

    @Test fun secondExchangeLinksToPreviousTip() = runTest {
        dao.upsertConversation(ChatEntity("c1", "Chat"))
        val first = repository.persistVoiceExchange("c1", "one", "first reply", "m", at = 1000L)
            as VoicePersistResult.Persisted

        val second = repository.persistVoiceExchange("c1", "two", "second reply", "m", at = 2000L)
            as VoicePersistResult.Persisted

        val secondUser = checkNotNull(dao.getMessage(second.userMessageId))
        // The second user turn hangs off the first assistant turn (the previous tip).
        assertEquals(first.assistantMessageId, secondUser.parentId)
        val secondRun = checkNotNull(dao.getRun(second.runId))
        assertEquals(first.runId, secondRun.parentRunId)

        val conv = checkNotNull(dao.getConversation("c1"))
        val runSelections = decodeSelectionMap(conv.selectedRunBranchesJson)
        assertEquals(second.runId, runSelections[first.runId])
    }

    @Test fun noOpWhenLiveRunOwnsConversation() = runTest {
        dao.upsertConversation(ChatEntity("c1", "Chat"))
        dao.insertRun(
            RunEntity("live", "c1", null, RunStatus.ACTIVE, 1, 10L, 10L),
        )

        val result = repository.persistVoiceExchange("c1", "hello", "world", "m", at = 1000L)

        assertEquals(VoicePersistResult.Skipped, result)
        // No voice messages were written.
        assertTrue(dao.getMessagesForRuns(listOf("live")).isEmpty())
    }

    @Test fun emptyExchangeIsNoOp() = runTest {
        dao.upsertConversation(ChatEntity("c1", "Chat"))
        val result = repository.persistVoiceExchange("c1", "   ", "", "m", at = 1000L)
        assertEquals(VoicePersistResult.Empty, result)
    }
}
