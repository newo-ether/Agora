package com.newoether.agora.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RunStatusSemanticIsolationTest {
    @Test
    fun stopAndFailureKeepExactSemanticStateAndAdvanceOnlyChangedOwners() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = database.chatDao()
            val semantic = database.semanticIndexDao()
            val repository = ConversationRepository(dao, database,
                semanticModelSnapshotProvider = { error("Status changes must not admit semantic work") })
            listOf("stop", "fail").forEach { id ->
                dao.upsertConversation(ChatEntity(id = id, title = id, dataChangedAt = 1L))
                dao.upsertRun(RunEntity(id = "$id-run", conversationId = id, parentRunId = null,
                    status = RunStatus.ACTIVE, activeSlot = 1, startedAt = 1L, lastCheckpointAt = 1L))
                dao.insertMessage(MessageEntity(id = "$id-message", conversationId = id, text = "source",
                    participant = Participant.USER, timestamp = 1L, runId = "$id-run"))
            }
            semantic.admitModel("current", 1L)
            assertTrue(semantic.completeReconcile("current", 0L, 1L))
            semantic.enqueueExactWork("pending", "stop-message", semanticSourceFingerprint("source"), 1L)
            dao.insertEmbeddings(listOf(EmbeddingEntity(id = 1L, messageId = "stop-message", modelId = "current",
                embedding = ByteArray(4), dimension = 1, chunkText = "source")))
            val ledgers = semantic.getLedgers(listOf("current", "pending"))
            val work = semantic.getWork("pending", "stop-message")
            val embedding = requireNotNull(dao.getEmbedding("stop-message"))

            assertTrue(repository.requestRunStop("stop-run", 10L))
            assertEquals(RunStatus.STOPPING, dao.getRun("stop-run")?.status)
            assertEquals(10L, dao.getConversation("stop")?.dataChangedAt)
            assertFalse(repository.requestRunStop("stop-run", 11L))
            assertEquals(10L, dao.getConversation("stop")?.dataChangedAt)
            assertTrue(repository.finishRunStopped("stop-run", at = 20L))
            val stopped = requireNotNull(dao.getRun("stop-run"))
            assertEquals(RunStatus.STOPPED, stopped.status)
            assertEquals(RunEndReason.USER_STOPPED, stopped.endReason)
            assertNull(stopped.activeSlot)
            assertEquals(20L, stopped.endedAt)
            assertFalse(repository.finishRunStopped("stop-run", at = 21L))
            assertEquals(20L, dao.getConversation("stop")?.dataChangedAt)
            assertTrue(repository.failRun("fail-run", 30L))
            assertEquals(RunStatus.FAILED, dao.getRun("fail-run")?.status)
            assertEquals(RunEndReason.PROVIDER_ERROR, dao.getRun("fail-run")?.endReason)
            assertFalse(repository.failRun("fail-run", 31L))
            assertFalse(repository.requestRunStop("missing", 40L))
            assertFalse(repository.finishRunStopped("missing", at = 40L))
            assertFalse(repository.failRun("missing", 40L))
            assertEquals(30L, dao.getConversation("fail")?.dataChangedAt)
            assertEquals(ledgers, semantic.getLedgers(listOf("current", "pending")))
            assertEquals(work, semantic.getWork("pending", "stop-message"))
            val retained = requireNotNull(dao.getEmbedding("stop-message"))
            assertEquals(embedding.id, retained.id)
            assertArrayEquals(embedding.embedding, retained.embedding)
            assertEquals("source", dao.getMessage("stop-message")?.text)
        } finally {
            database.close()
        }
    }
}
