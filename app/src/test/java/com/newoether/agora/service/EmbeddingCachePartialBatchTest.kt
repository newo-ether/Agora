package com.newoether.agora.service

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.ProgressUpdater
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import com.newoether.agora.api.EmbeddingClient
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.local.SemanticIndexLedgerEntity
import com.newoether.agora.data.local.semanticSourceFingerprint
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EmbeddingCachePartialBatchTest {
    private lateinit var database: ChatDatabase
    private val model = EmbeddingModelConfig(
        id = "model", name = "Test", type = EmbeddingModelType.REMOTE,
        remoteModelName = "test-model", remoteApiKey = "test-key",
        remoteBaseUrl = "https://embedding.invalid/v1", batchSize = 4,
    )
    private val settings = mockk<SettingsManager>()

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        every { settings.embeddingModels } returns flowOf(listOf(model))
        mockkObject(EmbeddingClient)
        database.chatDao().upsertConversation(ChatEntity(id = "root", title = "Root", lastUpdated = 1))
        database.chatDao().upsertRun(RunEntity(
            id = "run", conversationId = "root", parentRunId = null, status = RunStatus.ACTIVE,
            activeSlot = 1, startedAt = 1, lastCheckpointAt = 1,
        ))
        repeat(4) { index ->
            database.chatDao().insertMessage(MessageEntity(
                id = "message-$index", conversationId = "root", text = "Searchable source $index",
                participant = Participant.USER, timestamp = 1, runId = "run",
            ))
        }
        database.semanticIndexDao().admitModel(model.id, 1)
        Unit
    }

    @After
    fun tearDown() {
        unmockkObject(EmbeddingClient)
        database.close()
    }

    @Test
    fun invalidVectorsRemainPendingWhileLaterValidVectorCommitsAndReplayCompletes() = runBlocking {
        assertPartialBatchAndReplay(SemanticIndexLedgerEntity.STATE_NEEDS_RECONCILE)
    }

    @Test
    fun exactWorkRetainsFailedIdentitiesAndReplayDoesNotRegenerateCommittedVector() = runBlocking {
        val dao = database.semanticIndexDao()
        assertTrue(dao.completeReconcile(model.id, 0, 2))
        repeat(4) { index ->
            dao.enqueueExactWork(model.id, "message-$index",
                semanticSourceFingerprint("Searchable source $index"), index + 3L)
        }
        assertPartialBatchAndReplay(SemanticIndexLedgerEntity.STATE_PENDING)
        assertEquals(0, dao.getWorkCountThroughRevision(model.id, Long.MAX_VALUE))
    }

    private suspend fun assertPartialBatchAndReplay(pendingState: String) {
        coEvery { EmbeddingClient.computeEmbeddings(any(), model.remoteApiKey, model.remoteModelName, model.remoteBaseUrl) } returns listOf(
            null, floatArrayOf(), floatArrayOf(Float.NaN, 1f), floatArrayOf(1f, 0f),
        )
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Retry)
        assertEquals(listOf("message-3"), database.chatDao()
            .getEmbeddingSearchPage(model.id, 0, 1, 4).map { it.messageId })
        assertEquals(pendingState, database.semanticIndexDao().getLedger(model.id)?.state)
        if (pendingState == SemanticIndexLedgerEntity.STATE_PENDING) {
            assertEquals(3, database.semanticIndexDao().getWorkCountThroughRevision(model.id, Long.MAX_VALUE))
        }

        coEvery { EmbeddingClient.computeEmbeddings(any(), model.remoteApiKey, model.remoteModelName, model.remoteBaseUrl) } answers {
            firstArg<List<String>>().map { floatArrayOf(0f, 1f) }
        }
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Success)
        assertEquals(4, database.chatDao().getEmbeddingCountByModel(model.id))
        assertEquals(SemanticIndexLedgerEntity.STATE_CURRENT,
            database.semanticIndexDao().getLedger(model.id)?.state)
        coVerify(exactly = 1) { EmbeddingClient.computeEmbeddings(match { it.size == 4 }, model.remoteApiKey,
            model.remoteModelName, model.remoteBaseUrl) }
        coVerify(exactly = 1) { EmbeddingClient.computeEmbeddings(match { it.size == 3 }, model.remoteApiKey,
            model.remoteModelName, model.remoteBaseUrl) }
    }

    private fun worker(): EmbeddingCacheWorker {
        val updater = mockk<ProgressUpdater>()
        every { updater.updateProgress(any(), any(), any()) } returns
            SettableFuture.create<Void>().apply { set(null) }
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.id } returns UUID.randomUUID()
        every { params.workerContext } returns Dispatchers.Unconfined
        every { params.progressUpdater } returns updater
        return EmbeddingCacheWorker(ApplicationProvider.getApplicationContext<Context>(), params)
    }
}
