package com.newoether.agora.data.local

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import java.util.Collections
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class SemanticCacheCountPagingTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao
    private val queries = Collections.synchronizedList(mutableListOf<Pair<String, List<Any?>>>())

    @Before
    fun setUp() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().setQueryCallback({ sql, args ->
            queries.add(sql to args.toList())
        }, Executor { it.run() }).build()
        dao = database.chatDao()
        owner("root")
        owner("child", parent = "root")
        owner("task", task = "scheduled")
        queries.clear()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun repeatedCountsUseOnlyConversationSummariesForAllRequestedModels() = runBlocking {
        database.withTransaction {
            repeat(260) { add("a-hidden-%03d".format(it), owner = "task") }
            add("compact_summary")
            add("tool_call")
            add("result_reply")
            add("z-empty", text = "")
            add("z-error", participant = Participant.ERROR)
            add("z-child", owner = "child")
            add("z-root")
            add("z-whitespace", text = " ")
            add("z-short", text = "x")
            listOf("a-hidden-000", "compact_summary", "z-empty", "z-error", "z-child", "z-root")
                .forEachIndexed { index, id -> embedding(id, "first", index + 1L) }
            embedding("z-child", "second", 1000L)
            embedding("z-root", "unrequested", 1001L)
        }
        queries.clear()
        repeat(3) {
            assertEquals(
                EmbeddingCacheCounts(4, mapOf("first" to 2, "second" to 1, "missing" to 0)),
                dao.getEmbeddingCacheCounts(listOf("first", "second", "missing", "first")),
            )
        }
        assertEquals(4, dao.getIndexableMessageCount())
        assertEquals(2, dao.getEmbeddingCountByModel("first"))
        assertEquals(0, dao.getEmbeddingCountByModel("missing"))
        assertEquals(listOf(EmbeddingModelCount("second", 1)),
            dao.getEmbeddingCountsByModels(listOf("second", "missing")))
        assertSummaryOnlyReads()
        assertFalse(database.inTransaction())
    }

    @Test
    fun emptyLegacyIdUnicodeEmbeddedNulAndLargeBodiesKeepTheirCacheEligibility() = runBlocking {
        val largeText = "x".repeat(3 * 1024 * 1024)
        database.withTransaction {
            add("", text = "legacy")
            add("\uE000", text = "\u0000")
            add("\uD800\uDC00", text = "nonempty")
            add("large", text = largeText)
            embedding("", "first", -1L)
            embedding("\uE000", "first", 1L)
            embedding("large", "first", 2L)
        }
        queries.clear()
        repeat(3) {
            assertEquals(EmbeddingCacheCounts(4, mapOf("first" to 3)),
                dao.getEmbeddingCacheCounts(listOf("first")))
        }
        assertSummaryOnlyReads()
        val metadata = database.semanticMetadataDao()
        assertEquals(0, metadata.getSemanticSource("\uE000")?.searchLength)
        assertEquals(8001, metadata.getSemanticSource("large")?.sourcePrefix?.size)
        // Verify exact preservation without projecting the body into CursorWindow.
        database.openHelper.readableDatabase.query(
            "SELECT text = ? FROM messages WHERE id = ?", arrayOf(largeText, "large"),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertFalse(cursor.moveToNext())
        }
    }

    @Test
    fun incompleteBootstrapReturnsUnknownWithoutScanningSourcesOrPublishingSummaryCounts() = runBlocking {
        add("existing")
        embedding("existing", "first", 1L)
        val metadata = database.semanticMetadataDao()
        metadata.advanceLegacyMetadata(null, exhausted = false)
        queries.clear()
        repeat(2) {
            assertEquals(EmbeddingCacheCounts(0, mapOf("first" to 0, "missing" to 0), complete = false),
                dao.getEmbeddingCacheCounts(listOf("first", "missing")))
        }
        assertNoSourceReads()
        assertTrue(querySnapshot().none { (sql, _) ->
            Regex("\\bFROM\\s+semantic_(?:model_)?conversations\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(sql)
        })
        metadata.advanceLegacyMetadata(null, exhausted = true)
        queries.clear()
        assertEquals(EmbeddingCacheCounts(1, mapOf("first" to 1)),
            dao.getEmbeddingCacheCounts(listOf("first")))
        assertSummaryOnlyReads()
    }

    @Test
    fun owningTransactionRollbackRestoresSourcesEmbeddingsAndCountsTogether() = runBlocking {
        add("edited")
        add("deleted")
        embedding("edited", "first", 1L)
        embedding("deleted", "first", 2L)
        val before = database.semanticMetadataDao().getSemanticConversation("root")
        val abort = IllegalStateException("rollback fixture")
        try {
            database.withTransaction {
                dao.upsertMessage(requireNotNull(dao.getMessage("edited")).copy(text = "changed source"))
                dao.deleteMessagesByIds(listOf("deleted"))
                add("new")
                embedding("new", "first", 3L)
                throw abort
            }
        } catch (error: IllegalStateException) {
            assertTrue("Expected the intentional rollback, got $error",
                generateSequence<Throwable>(error) { it.cause }.any { it === abort })
        }
        assertEquals("ordinary", dao.getMessage("edited")?.text)
        assertNotNull(dao.getMessage("deleted"))
        assertNull(dao.getMessage("new"))
        assertEquals(before, database.semanticMetadataDao().getSemanticConversation("root"))
        assertEquals(listOf("deleted", "edited"),
            dao.getEmbeddingSearchPage("first", 0L, 1, 10).map { it.messageId }.sorted())
        queries.clear()
        assertEquals(EmbeddingCacheCounts(2, mapOf("first" to 2)),
            dao.getEmbeddingCacheCounts(listOf("first")))
        assertSummaryOnlyReads()
    }

    @Test
    fun productionMessageRunAndConversationDeletesMaintainSummaryMembership() = runBlocking {
        add("root-first")
        add("root-second")
        add("child-first", owner = "child")
        embedding("root-first", "first", 1L)
        embedding("root-first", "second", 2L)
        embedding("root-second", "first", 3L)
        embedding("child-first", "second", 4L)
        dao.deleteMessagesByIds(listOf("root-first", "root-first"))
        assertEquals(EmbeddingCacheCounts(2, mapOf("first" to 1, "second" to 1)),
            dao.getEmbeddingCacheCounts(listOf("first", "second")))
        dao.deleteRun("root-run")
        assertEquals(EmbeddingCacheCounts(1, mapOf("first" to 0, "second" to 1)),
            dao.getEmbeddingCacheCounts(listOf("first", "second")))
        dao.deleteConversation("child")
        queries.clear()
        assertEquals(EmbeddingCacheCounts(0, mapOf("first" to 0, "second" to 0)),
            dao.getEmbeddingCacheCounts(listOf("first", "second")))
        assertEquals(0, dao.getEmbeddingCacheCounts(emptyList()).total)
        assertEquals(emptyList<EmbeddingModelCount>(), dao.getEmbeddingCountsByModels(emptyList()))
        assertSummaryOnlyReads()
        assertNull(database.semanticMetadataDao().getSemanticConversation("child"))
        assertNull(dao.getMessage("root-first"))
        assertNull(dao.getMessage("root-second"))
        assertNull(dao.getMessage("child-first"))
    }

    @Test
    fun semanticRankingSkipsEntireHiddenPhysicalPagesWithoutEndingEarly() = runBlocking {
        database.withTransaction {
            repeat(257) { index ->
                val id = "hidden-%04d".format(index)
                add(id, owner = "child")
                embedding(id, "first", index + 1L)
            }
            add("visible", text = "searchable source")
            embedding("visible", "first", 258L)
        }
        queries.clear()
        assertEquals(listOf("visible"), dao.getEmbeddingSearchPage("first", 0L, 1, 2).map { it.messageId })
        val statements = querySnapshot()
        val physical = statements.filter { "SELECT id FROM embeddings WHERE" in it.first }
        assertTrue(physical.size > 128)
        assertTrue(physical.all { (_, args) -> (args.last() as Number).toInt() <= 2 })
        assertTrue(statements.filter { "SELECT e.id, e.messageId, e.embedding" in it.first }.all {
            "e.id IN (" in it.first
        })
        assertFalse(database.inTransaction())
    }

    @Test
    fun rankingUsesFullPhysicalPagesNearTheEligibleLimitWithoutSkippingOverflow() = runBlocking {
        database.withTransaction {
            add("visible-first", text = "searchable first")
            embedding("visible-first", "first", 1L)
            repeat(258) { index ->
                val id = "hidden-%04d".format(index)
                add(id, owner = "child")
                embedding(id, "first", index + 2L)
            }
            add("visible-next", text = "searchable next")
            add("visible-overflow", text = "searchable overflow")
            embedding("visible-next", "first", 260L)
            embedding("visible-overflow", "first", 261L)
        }
        queries.clear()
        val page = dao.getEmbeddingSearchPage("first", 0L, 1, 3)
        assertEquals(listOf(1L, 260L, 261L), page.map { it.id })
        val physical = querySnapshot().filter { "SELECT id FROM embeddings WHERE" in it.first }
        assertEquals(87, physical.size)
        assertTrue(physical.all { (_, args) -> (args.last() as Number).toInt() == 3 })
        val first = dao.getEmbeddingSearchPage("first", 0L, 1, 2)
        assertEquals(listOf(1L, 260L), first.map { it.id })
        assertEquals(listOf(261L), dao.getEmbeddingSearchPage("first", first.last().id, 1, 2).map { it.id })
    }

    private fun querySnapshot(): List<Pair<String, List<Any?>>> = synchronized(queries) {
        queries.toList()
    }

    private fun assertSummaryOnlyReads() {
        val statements = querySnapshot()
        assertNoSourceReads(statements)
        assertTrue(statements.any { (sql, _) -> "SUM(eligibleCount)" in sql })
        assertTrue(statements.any { (sql, _) -> "SUM(m.cachedCount)" in sql })
    }

    private fun assertNoSourceReads(statements: List<Pair<String, List<Any?>>> = querySnapshot()) {
        // Include INSERT ... SELECT and nested subqueries; a SELECT-only callback misses them.
        val forbiddenTables = Regex(
            "\\b(?:FROM|JOIN)\\s+[`\\\"]?(?:messages|semantic_sources|embeddings)\\b",
            RegexOption.IGNORE_CASE,
        )
        assertEquals("Count refresh must not visit message, source or embedding rows", emptyList<String>(),
            statements.map { it.first }.filter(forbiddenTables::containsMatchIn))
    }

    private suspend fun owner(id: String, task: String? = null, parent: String? = null) {
        dao.upsertConversation(ChatEntity(id = id, title = id, lastUpdated = 1L,
            taskId = task, parentConversationId = parent))
        dao.upsertRun(RunEntity(id = "$id-run", conversationId = id, parentRunId = null,
            status = RunStatus.ACTIVE, activeSlot = 1, startedAt = 1L, lastCheckpointAt = 1L))
    }

    private suspend fun add(
        id: String, text: String = "ordinary", owner: String = "root",
        participant: Participant = Participant.USER,
    ) = dao.insertMessage(MessageEntity(id = id, conversationId = owner, text = text,
        participant = participant, timestamp = 1L, runId = "$owner-run"))

    private suspend fun embedding(messageId: String, modelId: String, id: Long) =
        dao.insertEmbeddings(listOf(EmbeddingEntity(id = id, messageId = messageId,
            modelId = modelId, embedding = ByteArray(4), chunkText = "cached", dimension = 1)))
}
