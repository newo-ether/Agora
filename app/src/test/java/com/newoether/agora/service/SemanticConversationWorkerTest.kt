package com.newoether.agora.service

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
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
import com.newoether.agora.data.local.semanticModelSnapshot
import com.newoether.agora.data.local.withSemanticSourceMutation
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Production Worker and real Room writes, with query barriers for deterministic mutation races. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SemanticConversationWorkerTest {
    private lateinit var database: ChatDatabase
    private lateinit var context: Context
    private val databaseName = "sc-${UUID.randomUUID().toString().take(8)}"
    private val settings = mockk<SettingsManager>()
    private val model = EmbeddingModelConfig(
        id = "model", name = "Test", type = EmbeddingModelType.REMOTE,
        remoteModelName = "test-model", remoteApiKey = "test-key",
        remoteBaseUrl = "https://embedding.invalid/v1", batchSize = 4,
    )
    private val messages = mutableMapOf<String, MessageEntity>()
    private val selects = Collections.synchronizedList(mutableListOf<Pair<String, List<Any?>>>())
    private val providerInputs = Collections.synchronizedList(mutableListOf<List<String>>())
    private val queryGate = AtomicReference<QueryGate?>()
    private val providerEntered = CompletableDeferred<Unit>()
    private val providerRelease = CompletableDeferred<Unit>()
    @Volatile private var invalidText: String? = null
    @Volatile private var blockedText: String? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.databaseBuilder(context, ChatDatabase::class.java, databaseName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .setQueryCallback({ sql, args ->
                if (sql.trimStart().startsWith("SELECT", ignoreCase = true)) {
                    selects.add(sql to args.toList())
                }
                val gate = queryGate.get()
                if (gate != null && gate.matches(sql, args) && queryGate.compareAndSet(gate, null)) {
                    gate.entered.complete(Unit)
                    check(gate.release.await(10, TimeUnit.SECONDS)) { "Test query barrier timed out" }
                }
            }, Executor { it.run() })
            .build()
        every { settings.embeddingModels } returns flowOf(listOf(model))
        every { settings.customProviders } returns flowOf(emptyList())
        mockkObject(EmbeddingClient)
        coEvery {
            EmbeddingClient.computeEmbeddings(any(), model.remoteApiKey,
                model.remoteModelName, model.remoteBaseUrl)
        } coAnswers {
            val texts = firstArg<List<String>>()
            providerInputs.add(texts.toList())
            if (texts.any { it == blockedText }) {
                providerEntered.complete(Unit)
                providerRelease.await()
            }
            texts.map { if (it == invalidText) null else floatArrayOf(1f, 0f) }
        }
        assertTrue(database.semanticMetadataDao().ensureMetadataState().complete)
        val dao = database.chatDao()
        for (owner in listOf("a", "b")) {
            dao.upsertConversation(ChatEntity(id = owner, title = owner, lastUpdated = 1))
            dao.upsertRun(RunEntity(id = "run-$owner", conversationId = owner, parentRunId = null,
                status = RunStatus.ACTIVE, activeSlot = 1, startedAt = 1, lastCheckpointAt = 1))
            val message = MessageEntity(id = "message-$owner", conversationId = owner,
                text = "Searchable source for $owner", participant = Participant.USER,
                timestamp = 1, runId = "run-$owner")
            messages[owner] = message
            dao.insertMessage(message)
        }
        database.semanticIndexDao().admitModel(model.id, 1)
        resetObservations()
    }

    @After
    fun tearDown() {
        queryGate.getAndSet(null)?.release?.countDown()
        providerRelease.complete(Unit)
        unmockkObject(EmbeddingClient)
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun initialPassIndexesBothConversationsAndEditingAReusesBWithoutReadingItsSources() = runBlocking {
        assertTrue(cache() is Result.Success)
        assertEquals(setOf("a", "b"), sourcePageOwners().toSet())
        assertEquals(listOf(message("a").text, message("b").text), requestedTexts())
        assertEquals(listOf("message-a", "message-b"), cachedMessageIds())
        assertTrue(dirtyOwners().isEmpty())
        val bIndexedRevision = indexedRevision("b")
        val bEmbeddingId = embeddingId("b")
        val generation = database.semanticIndexDao().getLedger(model.id)!!.reconcileRevision

        editA("Changed searchable source for a")
        val pending = database.semanticIndexDao().getLedger(model.id)!!
        assertEquals(SemanticIndexLedgerEntity.STATE_PENDING, pending.state)
        assertEquals(generation, pending.reconcileRevision)
        assertEquals(listOf("a"), dirtyOwners())
        assertEquals(0, database.semanticIndexDao().getWorkCountThroughRevision(model.id, Long.MAX_VALUE))
        resetObservations()

        assertTrue(cache() is Result.Success)
        assertEquals(setOf("a"), sourcePageOwners().toSet())
        assertEquals(listOf(message("a").text), requestedTexts())
        assertEquals(bIndexedRevision, indexedRevision("b"))
        assertEquals(bEmbeddingId, embeddingId("b"))
        assertEquals(listOf("message-a", "message-b"), cachedMessageIds())
        assertTrue(dirtyOwners().isEmpty())
        assertNoMessageBodyReads()
    }

    @Test
    fun unchangedCompletedRunReadsNeitherSourcePagesNorProviderOnRestart() = runBlocking {
        assertTrue(cache() is Result.Success)
        resetObservations()
        assertTrue(cache() is Result.Success)
        assertTrue(sourcePageOwners().isEmpty())
        assertTrue(requestedTexts().isEmpty())
        assertNoMessageBodyReads()
    }

    @Test
    fun failedConversationRemainsDirtyAndReplaySkipsPreviouslyCompletedConversation() = runBlocking {
        invalidText = message("b").text
        assertTrue(cache() is Result.Retry)
        assertEquals(listOf("b"), dirtyOwners())
        assertEquals(listOf("message-a"), cachedMessageIds())
        assertEquals(database.semanticMetadataDao().getSemanticConversation("a")!!.sourceRevision,
            indexedRevision("a"))
        assertNotEquals(database.semanticMetadataDao().getSemanticConversation("b")!!.sourceRevision,
            indexedRevision("b"))
        val aEmbeddingId = embeddingId("a")

        invalidText = null
        resetObservations()
        assertTrue(cache() is Result.Success)
        assertEquals(setOf("b"), sourcePageOwners().toSet())
        assertEquals(listOf(message("b").text), requestedTexts())
        assertEquals(aEmbeddingId, embeddingId("a"))
        assertTrue(dirtyOwners().isEmpty())
        assertNoMessageBodyReads()
    }

    @Test
    fun cancellationDuringSecondConversationKeepsFirstCompleteForNextWorker() = runBlocking {
        blockedText = message("b").text
        val caching = async(Dispatchers.IO) { cache() }
        try {
            withTimeout(5_000) { providerEntered.await() }
            assertEquals(listOf("b"), dirtyOwners())
            assertEquals(listOf("message-a"), cachedMessageIds())
            assertFalse(caching.isCompleted)
            withTimeout(5_000) { caching.cancelAndJoin() }
        } finally {
            caching.cancelAndJoin()
        }
        assertEquals(listOf("b"), dirtyOwners())
        val aEmbeddingId = embeddingId("a")
        blockedText = null
        resetObservations()

        assertTrue(cache() is Result.Success)
        assertEquals(setOf("b"), sourcePageOwners().toSet())
        assertEquals(listOf(message("b").text), requestedTexts())
        assertEquals(aEmbeddingId, embeddingId("a"))
        assertTrue(dirtyOwners().isEmpty())
    }

    @Test
    fun newerExactWorkBeforeDirtyPageRetrievalRetriesWithoutMarkingConversationIndexed() = runBlocking {
        val gate = pauseNextQuery { sql, _ -> sql.contains("SELECT c.* FROM semantic_conversations") }
        val caching = async(Dispatchers.IO) { cache() }
        try {
            withTimeout(5_000) { gate.entered.await() }
            withTimeout(5_000) { editA("Changed before conversation snapshot", exactWork = true) }
            gate.release.countDown()
            assertTrue(withTimeout(5_000) { caching.await() } is Result.Retry)
        } finally {
            gate.release.countDown()
            caching.cancelAndJoin()
        }
        assertTrue(sourcePageOwners().isEmpty())
        assertTrue(requestedTexts().isEmpty())
        assertNull(indexedRevision("a"))
        assertEquals(listOf("a", "b"), dirtyOwners())
        assertEquals(1, database.semanticIndexDao().getWorkCountThroughRevision(model.id, Long.MAX_VALUE))
        resetObservations()

        assertTrue(cache() is Result.Success)
        assertEquals(listOf(message("a").text, message("b").text), requestedTexts())
        assertTrue(dirtyOwners().isEmpty())
        assertEquals(0, database.semanticIndexDao().getWorkCountThroughRevision(model.id, Long.MAX_VALUE))
        assertNoMessageBodyReads()
    }

    @Test
    fun sourceChangeAfterConversationSnapshotCannotMarkFilteredNewExactWorkIndexed() = runBlocking {
        val gate = pauseNextQuery { sql, args ->
            isSourcePage(sql) && args.firstOrNull() == "a"
        }
        val caching = async(Dispatchers.IO) { cache() }
        try {
            withTimeout(5_000) { gate.entered.await() }
            withTimeout(5_000) { editA("Changed after conversation snapshot", exactWork = true) }
            gate.release.countDown()
            assertTrue(withTimeout(5_000) { caching.await() } is Result.Retry)
        } finally {
            gate.release.countDown()
            caching.cancelAndJoin()
        }
        assertEquals(listOf(message("b").text), requestedTexts())
        assertEquals(listOf("message-b"), cachedMessageIds())
        assertEquals(listOf("a"), dirtyOwners())
        assertNotEquals(database.semanticMetadataDao().getSemanticConversation("a")!!.sourceRevision,
            indexedRevision("a"))
        val bEmbeddingId = embeddingId("b")
        resetObservations()

        assertTrue(cache() is Result.Success)
        assertEquals(setOf("a"), sourcePageOwners().toSet())
        assertEquals(listOf(message("a").text), requestedTexts())
        assertEquals(bEmbeddingId, embeddingId("b"))
        assertTrue(dirtyOwners().isEmpty())
        assertEquals(0, database.semanticIndexDao().getWorkCountThroughRevision(model.id, Long.MAX_VALUE))
        assertNoMessageBodyReads()
    }

    @Test
    fun emptyExactQueueCanFinishPendingLedgerAfterConversationDeletion() = runBlocking {
        assertTrue(cache() is Result.Success)
        database.chatDao().deleteConversation("a")
        assertEquals(SemanticIndexLedgerEntity.STATE_PENDING,
            database.semanticIndexDao().getLedger(model.id)!!.state)
        assertTrue(dirtyOwners().isEmpty())
        resetObservations()

        assertTrue(cache() is Result.Success)
        assertEquals(SemanticIndexLedgerEntity.STATE_CURRENT,
            database.semanticIndexDao().getLedger(model.id)!!.state)
        assertTrue(sourcePageOwners().isEmpty())
        assertTrue(requestedTexts().isEmpty())
        assertEquals(listOf("message-b"), cachedMessageIds())
    }

    @Test
    fun incompleteBootstrapDoesNotAdmitSourceReadsOrProviderWork() = runBlocking {
        database.semanticMetadataDao().advanceLegacyMetadata(null, false)
        resetObservations()
        assertTrue(cache() is Result.Retry)
        assertTrue(sourcePageOwners().isEmpty())
        assertTrue(requestedTexts().isEmpty())
        assertTrue(cachedMessageIds().isEmpty())
        assertEquals(listOf("a", "b"), dirtyOwners())
    }

    private suspend fun cache(): Result = worker().cacheModel(model.id, database, settings)

    private fun message(owner: String): MessageEntity = requireNotNull(messages[owner])

    private suspend fun editA(text: String, exactWork: Boolean = false) {
        val changed = message("a").copy(text = text)
        if (exactWork) {
            database.withSemanticSourceMutation(
                snapshot = semanticModelSnapshot(model.id, listOf(model.id)),
                messageIds = listOf(changed.id), updatedAt = 2,
            ) { database.chatDao().upsertMessage(changed) }
        } else {
            database.chatDao().upsertMessage(changed)
        }
        messages["a"] = changed
    }

    private suspend fun dirtyOwners(): List<String> = database.semanticMetadataDao()
        .getDirtyConversations(model.id, null, 16).map { it.conversationId }

    private fun indexedRevision(owner: String): Long? = database.openHelper.readableDatabase.query(
        "SELECT indexedRevision FROM semantic_model_conversations WHERE modelId = ? AND conversationId = ?",
        arrayOf(model.id, owner),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    private fun embeddingId(owner: String): Long? = database.openHelper.readableDatabase.query(
        "SELECT id FROM embeddings WHERE modelId = ? AND messageId = ?", arrayOf(model.id, message(owner).id),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    private fun cachedMessageIds(): List<String> = database.openHelper.readableDatabase.query(
        "SELECT messageId FROM embeddings WHERE modelId = ? ORDER BY messageId", arrayOf(model.id),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun isSourcePage(sql: String): Boolean =
        sql.contains("SELECT messageId FROM semantic_sources WHERE conversationId")

    private fun sourcePageOwners(): List<String> = synchronized(selects) {
        selects.filter { isSourcePage(it.first) }.map { it.second.first().toString() }
    }

    private fun requestedTexts(): List<String> = synchronized(providerInputs) { providerInputs.flatten() }

    private fun assertNoMessageBodyReads() {
        val bodyReads = synchronized(selects) {
            selects.filter { Regex("\\bFROM\\s+messages\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.first) }
        }
        assertTrue("Worker must use derived source metadata: $bodyReads", bodyReads.isEmpty())
    }

    private fun resetObservations() {
        selects.clear()
        providerInputs.clear()
    }

    private fun pauseNextQuery(matches: (String, List<Any?>) -> Boolean): QueryGate =
        QueryGate(matches).also { check(queryGate.compareAndSet(null, it)) }

    private class QueryGate(val matches: (String, List<Any?>) -> Boolean) {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
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
