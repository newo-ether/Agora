package com.newoether.agora.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import java.util.Collections
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class SemanticLegacySearchTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao
    private lateinit var metadata: SemanticMetadataDao
    private val queries = Collections.synchronizedList(mutableListOf<Pair<String, List<Any?>>>())

    @Before
    fun setUp() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().setQueryCallback({ sql, args ->
            queries.add(sql to args.toList())
        }, Executor { it.run() }).build()
        dao = database.chatDao()
        metadata = database.semanticMetadataDao()
        for (owner in listOf("root", "task", "child")) {
            dao.upsertConversationRow(ChatEntity(id = owner, title = owner, lastUpdated = 1,
                taskId = if (owner == "task") "scheduled" else null,
                parentConversationId = if (owner == "child") "root" else null))
            dao.upsertRun(RunEntity(id = "$owner-run", conversationId = owner, parentRunId = null,
                status = RunStatus.ACTIVE, activeSlot = 1, startedAt = 1, lastCheckpointAt = 1))
        }
        metadata.ensureMetadataState()
        metadata.advanceLegacyMetadata(null, false)
        queries.clear()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun legacyAndPartialResultsSurviveBootstrapAndCompletedSearchNeverReopensMessages() = runBlocking {
        val root = legacy("a-root", 1)
        legacy("b-model", 2, participant = Participant.MODEL)
        legacy("c-task", 3, owner = "task")
        legacy("d-child", 4, owner = "child")
        legacy("tool_call", 5)
        legacy("result_reply", 6)
        legacy("compact_summary", 7)
        legacy("short", 8, text = "abc")
        legacy("empty", 9, text = "")
        legacy("nul-short", 10, text = "ab\u0000long trailing source")
        legacy("z-nul-valid", 11, text = "abcd\u0000long trailing source")
        legacy("error", 12, participant = Participant.ERROR)
        legacy("nul-first", 13, text = "\u0000long trailing source")
        vector(root, 99, "other-model")
        assertNull(metadata.getSemanticSource(root.id))
        queries.clear()
        assertEquals(listOf("a-root", "b-model", "z-nul-valid"),
            search().map { it.messageId })
        assertTrue(querySnapshot().any { "LEFT JOIN messages" in it.first })

        bootstrapPage(4, exhausted = false)
        assertTrue(metadata.getSemanticSource(root.id) != null)
        assertNull(metadata.getSemanticSource("z-nul-valid"))
        assertFalse(metadata.ensureMetadataState().complete)
        val fresh = MessageEntity(id = "z-new", conversationId = "root", text = "New searchable source",
            participant = Participant.USER, timestamp = 2, runId = "root-run")
        dao.insertMessage(fresh)
        dao.upsertEmbedding(embedding(fresh, 14))
        assertFalse(metadata.ensureMetadataState().complete)
        val partial = search()
        assertEquals(listOf("a-root", "b-model", "z-nul-valid", "z-new"), partial.map { it.messageId })

        bootstrapPage(16, exhausted = true)
        assertTrue(metadata.ensureMetadataState().complete)
        queries.clear()
        val completed = search()
        assertEquals(partial.map { it.id to it.messageId }, completed.map { it.id to it.messageId })
        assertEquals(partial.map { it.embedding.toList() }, completed.map { it.embedding.toList() })
        assertEquals(partial.map { it.dimension }, completed.map { it.dimension })
        val statements = querySnapshot().map { it.first }
        val originalRead = Regex("\\b(?:FROM|JOIN)\\s+messages\\b", RegexOption.IGNORE_CASE)
        assertFalse(statements.any(originalRead::containsMatchIn))
        assertFalse(statements.any { "m.text" in it || "sourcePrefix" in it })
        assertTrue(statements.any { "CROSS JOIN semantic_sources" in it })
        assertFalse(database.inTransaction())
    }

    @Test
    fun existingIneligibleMetadataCannotFallBackToEligibleOriginalText() = runBlocking {
        val hidden = legacy("hidden-by-metadata", 1)
        val short = legacy("short-by-metadata", 2)
        legacy("missing-metadata", 3)
        // Deliberately disagreeing metadata proves CASE authority rather than an OR fallback.
        metadata.maintainSemanticSource(source(hidden).copy(historyEligible = false), legacy = true)
        metadata.maintainSemanticSource(source(short).copy(searchLength = 1), legacy = true)
        assertFalse(metadata.ensureMetadataState().complete)
        assertEquals(listOf("missing-metadata"), search().map { it.messageId })
        assertEquals(hidden.text, dao.getMessage(hidden.id)?.text)
        assertEquals(short.text, dao.getMessage(short.id)?.text)
        assertEquals(3, dao.getAllEmbeddings().size)
    }

    @Test
    fun hiddenPhysicalPageAdvancesWithin256IdBoundAndCallerCursorDoesNotSkipOverflow() = runBlocking {
        repeat(256) { index -> legacy("hidden-$index", index + 1L, owner = "child") }
        repeat(4) { index -> legacy("visible-$index", index + 257L) }
        queries.clear()
        assertEquals(listOf(257L, 258L, 259L, 260L), search(limit = 300).map { it.id })
        val physical = querySnapshot().filter { "SELECT id FROM embeddings WHERE" in it.first }
        assertEquals(listOf(0L, 256L, 260L), physical.map { (_, args) -> (args[1] as Number).toLong() })
        assertTrue(physical.all { (_, args) -> (args.last() as Number).toInt() == 256 })
        val hydration = querySnapshot().filter { "SELECT e.id, e.messageId, e.embedding" in it.first }
        assertEquals(2, hydration.size)
        hydration.forEachIndexed { index, (sql, args) ->
            assertTrue("e.id IN (" in sql)
            // CASE binds the minimum separately for metadata and original-text branches.
            val ids = args.dropLast(3).map { (it as Number).toLong() }
            assertTrue("Legacy hydration must keep the 256-ID cap", ids.size <= 256)
            assertEquals(if (index == 0) (1L..256L).toList() else (257L..260L).toList(), ids)
            assertEquals(listOf("model", 4L, 4L), args.takeLast(3))
        }
        assertTrue(hydration.all { "LEFT JOIN messages" in it.first })

        val first = search(limit = 3)
        assertEquals(listOf(257L, 258L, 259L), first.map { it.id })
        val next = search(after = first.last().id, limit = 3)
        assertEquals(listOf(260L), next.map { it.id })
        assertTrue(search(after = next.last().id, limit = 3).isEmpty())
        assertEquals(SemanticMetadataStateEntity(complete = false), metadata.getMetadataState())
        assertNull(metadata.getSemanticSource("visible-0"))
    }

    @Test
    fun cancellingPendingSearchPreservesBootstrapCursorMessagesAndVectors() = runBlocking {
        val originals = listOf(legacy("a-child", 1, owner = "child"),
            legacy("b-task", 2, owner = "task"), legacy("c-root", 3), legacy("d-root", 4))
        bootstrapPage(1, exhausted = false)
        val beforeState = metadata.getMetadataState()
        val beforeSource = requireNotNull(metadata.getSemanticSource("a-child"))
        val beforeVectors = vectorSnapshot()
        val entered = CompletableDeferred<Unit>()
        val intercepted = object : ChatSearchDao by dao {
            // Explicitly run the production default on this receiver so the query override is used.
            override suspend fun getEmbeddingSearchPage(
                modelId: String, afterId: Long, minimumTextLength: Int, limit: Int,
            ): List<EmbeddingSearchRow> =
                super<ChatSearchDao>.getEmbeddingSearchPage(modelId, afterId, minimumTextLength, limit)

            override suspend fun getLegacyEmbeddingSearchRowsForIds(
                modelId: String, ids: List<Long>, minimumTextLength: Int,
            ): List<EmbeddingSearchRow> {
                val rows = dao.getLegacyEmbeddingSearchRowsForIds(modelId, ids, minimumTextLength)
                if (ids.first() > 2L) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                return rows
            }
        }
        queries.clear()
        val searching = async(Dispatchers.IO) { intercepted.getEmbeddingSearchPage("model", 0, 4, 2) }
        try {
            withTimeout(5_000) { entered.await() }
            withTimeout(5_000) { searching.cancelAndJoin() }
            assertTrue(searching.isCancelled)
        } finally {
            searching.cancelAndJoin()
        }
        val searchQueries = querySnapshot()
        assertEquals(2, searchQueries.count { "SELECT id FROM embeddings WHERE" in it.first })
        assertTrue(searchQueries.none { (sql, _) ->
            Regex("^\\s*(INSERT|UPDATE|DELETE|REPLACE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql)
        })
        assertEquals(beforeState, metadata.getMetadataState())
        val afterSource = requireNotNull(metadata.getSemanticSource("a-child"))
        assertEquals(beforeSource, afterSource.copy(sourcePrefix = beforeSource.sourcePrefix))
        assertEquals(beforeSource.sourcePrefix.toList(), afterSource.sourcePrefix.toList())
        assertEquals("a-child", metadata.getMetadataState()?.afterMessageId)
        assertEquals(originals.sortedBy { it.id }, dao.getMessagesByIds(originals.map { it.id }).sortedBy { it.id })
        assertEquals(beforeVectors, vectorSnapshot())
        assertNull(metadata.getSemanticSource("c-root"))
        assertFalse(database.inTransaction())
        assertEquals(listOf("c-root", "d-root"), search(limit = 2).map { it.messageId })
    }

    private suspend fun search(after: Long = 0, limit: Int = 64): List<EmbeddingSearchRow> =
        dao.getEmbeddingSearchPage("model", after, 4, limit)

    private suspend fun legacy(
        id: String, vectorId: Long, owner: String = "root", text: String = "Searchable legacy source",
        participant: Participant = Participant.USER,
    ): MessageEntity {
        val message = MessageEntity(id = id, conversationId = owner, text = text,
            participant = participant, timestamp = 1, runId = "$owner-run")
        // Raw writes model schema-41 rows with no derived metadata.
        dao.insertMessageRow(message)
        vector(message, vectorId)
        return message
    }

    private fun source(message: MessageEntity): SemanticSourceEntity =
        semanticSource(message.id, message.conversationId, message.participant, message.text)

    private fun embedding(message: MessageEntity, id: Long, model: String = "model") = EmbeddingEntity(
        id = id, messageId = message.id, modelId = model,
        embedding = byteArrayOf(0x3f, 0x80.toByte(), 0, id.toByte()), chunkText = "Retained ${message.id}",
        dimension = 1, sourceFingerprint = null,
    )

    private suspend fun vector(message: MessageEntity, id: Long, model: String = "model") =
        dao.upsertEmbeddingRow(embedding(message, id, model))

    private suspend fun bootstrapPage(limit: Int, exhausted: Boolean) {
        val state = metadata.ensureMetadataState()
        val ids = metadata.getLegacyMessageIds(state.afterMessageId, limit)
        val sources = ids.mapNotNull { id ->
            if (metadata.getSemanticSource(id) == null) metadata.getLegacySource(id) else null
        }
        assertTrue(metadata.commitLegacyPage(state.afterMessageId, sources, ids.lastOrNull(), exhausted))
    }

    private suspend fun vectorSnapshot(): List<List<Any?>> = dao.getAllEmbeddings().sortedBy { it.id }.map {
        listOf(it.id, it.messageId, it.modelId, it.embedding.toList(), it.chunkText, it.dimension, it.sourceFingerprint)
    }

    private fun querySnapshot(): List<Pair<String, List<Any?>>> = synchronized(queries) { queries.toList() }
}
