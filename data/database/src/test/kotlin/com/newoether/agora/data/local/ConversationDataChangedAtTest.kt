package com.newoether.agora.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Incremental backups reuse a conversation item while dataChangedAt is unchanged, so every write
 * to an exported field must move it.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class ConversationDataChangedAtTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.chatDao()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun conversation(id: String, modelId: String? = null) {
        dao.upsertConversation(
            ChatEntity(id = id, title = id, lastUpdated = 1L, dataChangedAt = 1L, modelId = modelId),
        )
    }

    private suspend fun changedAt(id: String): Long = requireNotNull(dao.getConversation(id)).dataChangedAt

    private fun loop(conversationId: String, cycleCount: Int = 0) = LoopEntity(
        conversationId = conversationId,
        intervalMs = 60_000L,
        nextFireAt = 0L,
        prompt = "tick",
        cycleCount = cycleCount,
        maxCycles = 3,
        active = true,
        revision = 1L,
    )

    @Test
    fun touchAlwaysMovesForwardEvenWithinTheSameMillisecond() = runTest {
        conversation("c")
        dao.touchConversationData("c", 1L)
        assertEquals(2L, changedAt("c"))
        dao.touchConversationData("c", 1L)
        assertEquals(3L, changedAt("c"))
    }

    @Test
    fun loopWritesMarkTheConversationChanged() = runTest {
        conversation("c")
        dao.upsertLoop(loop("c"))
        val afterCreate = changedAt("c")
        assertTrue(afterCreate > 1L)
        dao.upsertLoop(loop("c", cycleCount = 1))
        val afterCycle = changedAt("c")
        assertTrue(afterCycle > afterCreate)
        dao.deleteLoop("c")
        assertTrue(changedAt("c") > afterCycle)
    }

    @Test
    fun modelReferenceRewritesMarkTheirConversationsChanged() = runTest {
        conversation("configured", modelId = "old:model")
        conversation("renamed", modelId = "p:model")
        conversation("message", modelId = null)
        conversation("untouched", modelId = "other:model")
        dao.insertRun(
            RunEntity(
                id = "r",
                conversationId = "message",
                parentRunId = null,
                status = RunStatus.COMPLETED,
                activeSlot = null,
                startedAt = 1L,
                lastCheckpointAt = 1L,
                endedAt = 1L,
                endReason = RunEndReason.entries.first(),
            ),
        )
        dao.upsertMessage(
            MessageEntity(
                id = "m",
                conversationId = "message",
                text = "hi",
                participant = Participant.MODEL,
                timestamp = 1L,
                runId = "r",
                modelName = "p:model",
            ),
        )
        dao.replaceConfiguredModelReferences("old:model", "new:model")
        assertTrue(changedAt("configured") > 1L)
        dao.renameConfiguredProviderModelReferences("p", "q")
        assertTrue(changedAt("renamed") > 1L)
        assertTrue(changedAt("message") > 1L)
        assertEquals(1L, changedAt("untouched"))
    }
}
