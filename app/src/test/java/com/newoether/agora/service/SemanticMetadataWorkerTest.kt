package com.newoether.agora.service

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.local.SemanticMetadataDao
import com.newoether.agora.data.local.SemanticSourceEntity
import com.newoether.agora.data.local.semanticSource
import com.newoether.agora.data.local.semanticSourceFingerprint
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import com.newoether.agora.util.Constants
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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

/** Runs the production bootstrap loop and commit boundary against file-backed Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SemanticMetadataWorkerTest {
    private lateinit var context: Context
    private lateinit var database: ChatDatabase
    private val databaseName = "metadata-test"
    private val recordSql = AtomicBoolean(false)
    private val statements = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() = runBlocking<Unit> {
        context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.databaseBuilder(context, ChatDatabase::class.java, databaseName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback({ sql, _ -> if (recordSql.get()) statements.add(sql) }, Executor { it.run() })
            .build()
        // Raw inserts deliberately model the migration's untouched legacy rows.
        database.chatDao().upsertConversationRow(ChatEntity(id = "root", title = "Root", lastUpdated = 1))
        database.chatDao().upsertRun(RunEntity(
            id = "run", conversationId = "root", parentRunId = null,
            status = RunStatus.ACTIVE, activeSlot = 1, startedAt = 1, lastCheckpointAt = 1,
        ))
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun pendingLegacyCountsBecomeReadyWithoutChangingOriginalBodies() = runBlocking {
        val text = "A".repeat(Constants.MAX_EMBEDDING_TEXT_LENGTH + 20) + "\u0000hidden tail"
        database.chatDao().insertMessageRow(message("a", text))
        database.chatDao().insertMessageRow(message("b", "Second searchable source"))
        val metadata = database.semanticMetadataDao()
        assertFalse(metadata.ensureMetadataState().complete)
        val pending = database.chatDao().getEmbeddingCacheCounts(listOf("model"))
        assertFalse(pending.complete)
        assertNull(metadata.getSemanticSource("a"))

        withTimeout(5_000) { worker().backfillMetadata(database) }

        assertTrue(metadata.getMetadataState()!!.complete)
        assertEquals("b", metadata.getMetadataState()!!.afterMessageId)
        val source = requireNotNull(metadata.getSemanticSource("a"))
        assertEquals(Constants.MAX_EMBEDDING_TEXT_LENGTH + 1, source.sourcePrefix.size)
        assertEquals(semanticSourceFingerprint(text.take(Constants.MAX_EMBEDDING_TEXT_LENGTH)), source.sourceFingerprint)
        assertEquals(Constants.MAX_EMBEDDING_TEXT_LENGTH + 20, source.searchLength)
        assertEquals(text, database.chatDao().getMessage("a")?.text)
        val counts = database.chatDao().getEmbeddingCacheCounts(listOf("model"))
        assertTrue(counts.complete)
        assertEquals(2, counts.total)
        assertEquals(mapOf("model" to 0), counts.cachedByModel)
    }

    @Test
    fun legacyBootstrapPreservesSurrogateCutoffLikeNewWrites() = runBlocking {
        val prefix = "A".repeat(Constants.MAX_EMBEDDING_TEXT_LENGTH - 1) + "\uD83D\uDE00"
        val original = prefix + "tail"
        database.chatDao().insertMessageRow(message("a-legacy", original))
        database.chatDao().upsertMessage(message("b-current", original))
        val metadata = database.semanticMetadataDao()
        assertFalse(metadata.getMetadataState()!!.complete)
        val current = requireNotNull(metadata.getSemanticSource("b-current"))

        withTimeout(5_000) { worker().backfillMetadata(database) }

        val legacy = requireNotNull(metadata.getSemanticSource("a-legacy"))
        assertArrayEquals(prefix.toByteArray(Charsets.UTF_8), legacy.sourcePrefix)
        assertArrayEquals(current.sourcePrefix, legacy.sourcePrefix)
        assertEquals(original.take(Constants.MAX_EMBEDDING_TEXT_LENGTH),
            legacy.sourcePrefix.toString(Charsets.UTF_8).take(Constants.MAX_EMBEDDING_TEXT_LENGTH))
        assertEquals(semanticSourceFingerprint(original.take(Constants.MAX_EMBEDDING_TEXT_LENGTH)),
            legacy.sourceFingerprint)
        assertEquals(current.sourceFingerprint, legacy.sourceFingerprint)
        assertEquals(current.searchLength, legacy.searchLength)
        assertEquals(original, database.chatDao().getMessage("a-legacy")?.text)
        assertEquals(original, database.chatDao().getMessage("b-current")?.text)
        assertTrue(metadata.getMetadataState()!!.complete)
        assertEquals(2, database.chatDao().getEmbeddingCacheCounts(emptyList()).total)
    }

    @Test
    fun cancellationAfterOneSixteenMessagePageResumesPersistedCursorWithoutRereadingIt() = runBlocking {
        val ids = (0 until 35).map { "source-${it.toString().padStart(3, '0')}" }
        ids.forEach { database.chatDao().insertMessageRow(message(it)) }
        val metadata = database.semanticMetadataDao()
        val committed = CompletableDeferred<Unit>()
        val firstPassReads = mutableListOf<String>()
        val firstPassPages = mutableListOf<Int>()
        val interrupted = object : SemanticMetadataDao by metadata {
            override suspend fun getLegacySource(messageId: String): SemanticSourceEntity? {
                firstPassReads.add(messageId)
                return metadata.getLegacySource(messageId)
            }

            override suspend fun commitLegacyPage(
                expectedAfterId: String?, sources: List<SemanticSourceEntity>,
                lastMessageId: String?, exhausted: Boolean,
            ): Boolean {
                firstPassPages.add(sources.size)
                val accepted = metadata.commitLegacyPage(expectedAfterId, sources, lastMessageId, exhausted)
                if (accepted && !exhausted) {
                    committed.complete(Unit)
                    awaitCancellation()
                }
                return accepted
            }
        }
        val first = async(Dispatchers.IO) { worker().backfillMetadata(databaseUsing(interrupted)) }
        try {
            withTimeout(5_000) { committed.await() }
            withTimeout(5_000) { first.cancelAndJoin() }
            val checkpoint = requireNotNull(metadata.getMetadataState())
            assertFalse(checkpoint.complete)
            assertEquals(ids[15], checkpoint.afterMessageId)
            assertEquals(listOf(16), firstPassPages)
            assertEquals(ids.take(16), firstPassReads)

            val resumedReads = mutableListOf<String>()
            val resumedPages = mutableListOf<Int>()
            val resumed = object : SemanticMetadataDao by metadata {
                override suspend fun getLegacySource(messageId: String): SemanticSourceEntity? {
                    resumedReads.add(messageId)
                    return metadata.getLegacySource(messageId)
                }

                override suspend fun commitLegacyPage(
                    expectedAfterId: String?, sources: List<SemanticSourceEntity>,
                    lastMessageId: String?, exhausted: Boolean,
                ): Boolean {
                    resumedPages.add(sources.size)
                    return metadata.commitLegacyPage(expectedAfterId, sources, lastMessageId, exhausted)
                }
            }
            withTimeout(5_000) { worker().backfillMetadata(databaseUsing(resumed)) }
            assertEquals(ids.drop(16), resumedReads)
            assertEquals(listOf(16, 3), resumedPages)
            assertTrue(metadata.getMetadataState()!!.complete)
            assertEquals(ids.last(), metadata.getMetadataState()!!.afterMessageId)
            assertEquals(35, database.chatDao().getEmbeddingCacheCounts(emptyList()).total)
        } finally {
            first.cancelAndJoin()
        }
    }

    @Test
    fun concurrentUpdateAndDeletionWinOverAlreadyReadLegacyPage() = runBlocking {
        database.chatDao().insertMessageRow(message("a-update", "Legacy old source"))
        database.chatDao().insertMessageRow(message("b-delete", "Legacy deleted source"))
        val metadata = database.semanticMetadataDao()
        val readPage = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fenced = object : SemanticMetadataDao by metadata {
            override suspend fun commitLegacyPage(
                expectedAfterId: String?, sources: List<SemanticSourceEntity>,
                lastMessageId: String?, exhausted: Boolean,
            ): Boolean {
                readPage.complete(Unit)
                release.await()
                return metadata.commitLegacyPage(expectedAfterId, sources, lastMessageId, exhausted)
            }
        }
        val bootstrap = async(Dispatchers.IO) { worker().backfillMetadata(databaseUsing(fenced)) }
        try {
            withTimeout(5_000) { readPage.await() }
            assertFalse(bootstrap.isCompleted)
            withTimeout(5_000) {
                database.chatDao().upsertMessage(message("a-update", "Concurrent authoritative source"))
                database.chatDao().deleteMessagesByIds(listOf("b-delete"))
            }
            release.complete(Unit)
            withTimeout(5_000) { bootstrap.await() }
            assertTrue(metadata.getMetadataState()!!.complete)
            assertEquals(semanticSourceFingerprint("Concurrent authoritative source"),
                metadata.getSemanticSource("a-update")?.sourceFingerprint)
            assertEquals("Concurrent authoritative source", database.chatDao().getMessage("a-update")?.text)
            assertNull(metadata.getSemanticSource("b-delete"))
            assertNull(database.chatDao().getMessage("b-delete"))
            assertEquals(1, database.chatDao().getEmbeddingCacheCounts(emptyList()).total)
        } finally {
            bootstrap.cancelAndJoin()
        }
    }

    @Test
    fun supersededPageReloadsDurableCursorInsteadOfReplayingLegacyBodies() = runBlocking {
        val ids = (0 until 17).map { "source-${it.toString().padStart(3, '0')}" }
        ids.forEach { database.chatDao().insertMessageRow(message(it)) }
        val metadata = database.semanticMetadataDao()
        val reads = mutableListOf<String>()
        val acceptedPages = mutableListOf<Boolean>()
        val racing = object : SemanticMetadataDao by metadata {
            override suspend fun getLegacySource(messageId: String): SemanticSourceEntity? {
                reads.add(messageId)
                return metadata.getLegacySource(messageId)
            }

            override suspend fun commitLegacyPage(
                expectedAfterId: String?, sources: List<SemanticSourceEntity>,
                lastMessageId: String?, exhausted: Boolean,
            ): Boolean {
                if (expectedAfterId == null) {
                    assertTrue(metadata.commitLegacyPage(expectedAfterId, sources, lastMessageId, exhausted))
                }
                return metadata.commitLegacyPage(expectedAfterId, sources, lastMessageId, exhausted).also {
                    acceptedPages.add(it)
                }
            }
        }

        withTimeout(5_000) { worker().backfillMetadata(databaseUsing(racing)) }

        assertEquals(listOf(false, true), acceptedPages)
        assertEquals(ids, reads)
        assertTrue(metadata.getMetadataState()!!.complete)
        assertEquals(ids.last(), metadata.getMetadataState()!!.afterMessageId)
        assertEquals(17, database.chatDao().getEmbeddingCacheCounts(emptyList()).total)
    }

    @Test
    fun existingMetadataSkipsLegacyBodyAndCompletedBootstrapDoesNotRevisitMessages() = runBlocking {
        val original = "Original body".repeat(10_000)
        database.chatDao().insertMessageRow(message("source", original))
        val metadata = database.semanticMetadataDao()
        assertFalse(metadata.ensureMetadataState().complete)
        metadata.maintainSemanticSource(semanticSource("source", "root", Participant.USER, original))
        statements.clear()
        recordSql.set(true)

        withTimeout(5_000) { worker().backfillMetadata(database) }
        assertTrue(statements.none { it.contains("CAST(text AS BLOB)", ignoreCase = true) })
        assertTrue(metadata.getMetadataState()!!.complete)
        assertEquals(1, database.chatDao().getEmbeddingCacheCounts(emptyList()).total)
        statements.clear()

        withTimeout(5_000) { worker().backfillMetadata(database) }
        assertTrue("Completed bootstrap must not seek messages or source bodies", statements.none {
            it.trimStart().startsWith("SELECT", ignoreCase = true) &&
                (it.contains("FROM messages", ignoreCase = true) || it.contains("FROM semantic_sources", ignoreCase = true))
        })
    }

    private fun databaseUsing(metadata: SemanticMetadataDao): ChatDatabase = mockk {
        every { semanticMetadataDao() } returns metadata
    }

    private fun message(id: String, text: String = "Searchable source $id") = MessageEntity(
        id = id, conversationId = "root", text = text, participant = Participant.USER,
        timestamp = 1, runId = "run",
    )

    private fun worker(): SemanticMetadataWorker {
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.id } returns UUID.randomUUID()
        every { params.workerContext } returns Dispatchers.Unconfined
        return SemanticMetadataWorker(context, params)
    }
}
