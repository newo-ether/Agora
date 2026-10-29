package com.newoether.agora.service

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Exercises the production Worker while its provider is stalled, using Room's default executors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EmbeddingDatabaseConcurrencyTest {
    private lateinit var database: ChatDatabase
    private lateinit var context: Context
    private val name = "embedding-test"
    private val model = EmbeddingModelConfig(
        id = "model", name = "Test", type = EmbeddingModelType.REMOTE,
        remoteModelName = "test-model", remoteApiKey = "test-key",
        remoteBaseUrl = "https://embedding.invalid/v1", batchSize = 4,
    )
    private val settings = mockk<SettingsManager>()

    @Before
    fun setUp() = runBlocking<Unit> {
        context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.databaseBuilder(context, ChatDatabase::class.java, name).build()
        every { settings.embeddingModels } returns flowOf(listOf(model))
        mockkObject(EmbeddingClient)
        val dao = database.chatDao()
        dao.upsertConversation(ChatEntity(id = "root", title = "Root", lastUpdated = 1))
        dao.upsertRun(RunEntity(id = "run", conversationId = "root", parentRunId = null,
            status = RunStatus.ACTIVE, activeSlot = 1, startedAt = 1, lastCheckpointAt = 1))
        dao.insertMessage(MessageEntity(id = "source", conversationId = "root",
            text = "Searchable source", participant = Participant.USER, timestamp = 1, runId = "run"))
        database.semanticIndexDao().admitModel(model.id, 1)
    }

    @After
    fun tearDown() {
        unmockkObject(EmbeddingClient)
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun stalledReconciliationDoesNotPreventForegroundReadWriteAndCancellationPreservesWork() = runBlocking {
        assertForegroundProgress(exactWork = false)
    }

    @Test
    fun stalledExactWorkDoesNotPreventForegroundReadWriteAndCancellationPreservesWork() = runBlocking {
        assertForegroundProgress(exactWork = true)
    }

    private suspend fun assertForegroundProgress(exactWork: Boolean) = kotlinx.coroutines.coroutineScope {
        val semantic = database.semanticIndexDao()
        if (exactWork) {
            semantic.getDirtyConversations(model.id, null, 10).forEach { owner ->
                assertTrue(semantic.markConversationIndexed(model.id, owner.conversationId, owner.sourceRevision, 0))
            }
            assertTrue(semantic.completeReconcile(model.id, 0, 2))
            semantic.enqueueExactWork(model.id, "source", semanticSourceFingerprint("Searchable source"), 3)
        }
        val before = requireNotNull(semantic.getLedger(model.id))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { EmbeddingClient.computeEmbeddings(any(), model.remoteApiKey,
            model.remoteModelName, model.remoteBaseUrl) } coAnswers {
            entered.complete(Unit)
            release.await()
            firstArg<List<String>>().map { floatArrayOf(1f, 0f) }
        }
        val caching = async(Dispatchers.IO) { worker().cacheModel(model.id, database, settings) }
        try {
            withTimeout(5_000) { entered.await() }
            val repository = ConversationRepository(database.chatDao(), database)
            withTimeout(5_000) {
                repeat(8) { index ->
                    assertEquals("Searchable source", repository.getMessage("source")?.text)
                    assertTrue(repository.updateConversationTitle("root", "Foreground $index"))
                    repository.upsertMessage(MessageEntity(id = "foreground-$index", conversationId = "root",
                        text = "Foreground checkpoint $index", participant = Participant.MODEL,
                        timestamp = index + 2L, runId = "run", runSequence = index + 1L))
                    assertEquals("Foreground checkpoint $index", repository.getMessage("foreground-$index")?.text)
                }
            }
            assertFalse("Provider remains stalled throughout foreground work", caching.isCompleted)
            assertFalse(release.isCompleted)
            withTimeout(5_000) { caching.cancelAndJoin() }
            withTimeout(5_000) {
                assertTrue(repository.updateConversationTitle("root", "After cancellation"))
                assertEquals("After cancellation", repository.getConversation("root")?.title)
                assertEquals("Searchable source", repository.getMessage("source")?.text)
                assertEquals(0, database.chatDao().getEmbeddingCountByModel(model.id))
                val after = requireNotNull(semantic.getLedger(model.id))
                assertEquals(before.state, after.state)
                assertEquals(before.reconcileRevision, after.reconcileRevision)
                assertEquals(before.completedRevision, after.completedRevision)
                assertEquals(before.sourceRevision + 8, after.sourceRevision)
                assertEquals(listOf("root"), semantic.getDirtyConversations(model.id, null, 10).map { it.conversationId })
                if (exactWork) {
                    assertEquals(1, semantic.getWorkCountThroughRevision(model.id, Long.MAX_VALUE))
                    assertEquals(semanticSourceFingerprint("Searchable source"),
                        semantic.getWork(model.id, "source")?.sourceFingerprint)
                } else assertEquals(SemanticIndexLedgerEntity.STATE_NEEDS_RECONCILE, after.state)
            }
        } finally {
            caching.cancelAndJoin()
        }
    }

    private fun worker(): EmbeddingCacheWorker {
        val updater = mockk<ProgressUpdater>()
        every { updater.updateProgress(any(), any(), any()) } returns
            SettableFuture.create<Void>().apply { set(null) }
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.id } returns UUID.randomUUID()
        every { params.workerContext } returns Dispatchers.Unconfined
        every { params.progressUpdater } returns updater
        return EmbeddingCacheWorker(context, params)
    }
}
