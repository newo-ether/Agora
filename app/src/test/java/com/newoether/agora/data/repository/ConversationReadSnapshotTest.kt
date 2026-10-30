package com.newoether.agora.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConversationReadSnapshotTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: ChatDatabase
    private lateinit var repository: ConversationRepository

    @Before fun open() = runBlocking {
        context.deleteDatabase(ChatDatabase.DB_NAME)
        database = ChatDatabase.build(context)
        repository = ConversationRepository(database.chatDao(), database, snapshotContext = context)
        val dao = database.chatDao()
        dao.upsertConversation(ChatEntity("owner", "Before", selectedBranchesJson = "{\"null\":\"m\"}"))
        dao.upsertRun(RunEntity("run", "owner", parentRunId = null, status = RunStatus.COMPLETED,
            activeSlot = null, startedAt = 1, lastCheckpointAt = 1, endedAt = 1,
            endReason = com.newoether.agora.model.RunEndReason.MODEL_COMPLETED))
        dao.upsertMessage(MessageEntity("m", "owner", text = "before", status = MessageStatus.SUCCESS,
            participant = Participant.USER, timestamp = 1, runId = "run", runSequence = 0))
    }

    @After fun close() {
        database.close()
        context.deleteDatabase(ChatDatabase.DB_NAME)
    }

    @Test fun topologyAndPayloadStayImmutableWhileForegroundWriterCommits() = runBlocking {
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val read = async(Dispatchers.IO) {
            repository.withReadSnapshot { dao ->
                val topology = requireNotNull(dao.getProviderContextTopologySnapshot("owner"))
                captured.complete(Unit)
                release.await()
                Triple(topology, dao.getMessagesByIds(listOf("m")).single(), dao.getConversation("owner"))
            }
        }
        try {
            withTimeout(10_000) { captured.await() }
            withTimeout(5_000) {
                database.withTransaction {
                    val dao = database.chatDao()
                    dao.upsertMessage(requireNotNull(dao.getMessage("m")).copy(text = "after"))
                    dao.upsertConversation(requireNotNull(dao.getConversation("owner")).copy(
                        title = "After", selectedBranchesJson = "{}"))
                }
            }
        } finally { release.complete(Unit) }
        val (topology, message, conversation) = read.await()
        assertEquals("{\"null\":\"m\"}", topology.selectedBranchesJson)
        assertEquals("before", message.text)
        assertEquals("Before", conversation?.title)
        assertEquals("after", database.chatDao().getMessage("m")?.text)
    }

    @Test fun cancellationReleasesReaderAndNullResultsNeverReplayTheBlock() = runBlocking {
        val captured = CompletableDeferred<Unit>()
        val read = async(Dispatchers.IO) {
            repository.withReadSnapshot { dao ->
                dao.getMessage("m")
                captured.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        withTimeout(10_000) { captured.await() }
        read.cancelAndJoin()
        var reads = 0
        assertNull(repository.withReadSnapshot { reads++; null })
        assertEquals(1, reads)
        var writes = 0
        assertNull(repository.withProviderContextSnapshot { writes++; null })
        assertEquals(1, writes)
        withTimeout(5_000) {
            assertTrue(repository.updateConversationTitle("owner", "Still writable"))
        }
    }
}
