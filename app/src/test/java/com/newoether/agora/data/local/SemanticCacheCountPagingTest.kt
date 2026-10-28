package com.newoether.agora.data.local

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SemanticCacheCountPagingTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao
    private val selects = Collections.synchronizedList(mutableListOf<Pair<String, List<Any?>>>())
    private val cancelOnCount = AtomicReference<Job?>()

    @Before
    fun setUp() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().setQueryCallback({ sql, args ->
            if (sql.trimStart().startsWith("SELECT", ignoreCase = true)) {
                selects.add(sql to args.toList())
                if ("AS messageId, CASE WHEN" in sql) cancelOnCount.getAndSet(null)?.cancel()
            }
        }, Executor { it.run() }).build()
        dao = database.chatDao()
        owner("root")
        owner("child", parent = "root")
        owner("task", task = "scheduled")
        selects.clear()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun hiddenPagesAdvanceAndOnePassCountsAllRequestedModels() = runBlocking {
        database.withTransaction {
            repeat(CACHE_COUNT_PAGE_SIZE * 2) { add("a-hidden-%03d".format(it), owner = "task") }
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
        selects.clear()
        assertEquals(
            EmbeddingCacheCounts(4, mapOf("first" to 2, "second" to 1, "missing" to 0)),
            dao.getEmbeddingCacheCounts(listOf("first", "second", "missing", "first")),
        )
        val pages = selects.filter { "SELECT id FROM messages" in it.first }
        assertEquals(4, pages.size)
        assertTrue(pages.all { (sql, args) ->
            "LIMIT" in sql && args.filterIsInstance<Number>().single().toInt() == CACHE_COUNT_PAGE_SIZE
        })
        assertTrue(selects.none { "COUNT(*)" in it.first || "e.embedding" in it.first })
        assertFalse(database.inTransaction())
        assertEquals(4, dao.getIndexableMessageCount())
        assertEquals(2, dao.getEmbeddingCountByModel("first"))
        assertEquals(0, dao.getEmbeddingCountByModel("missing"))
        assertEquals(listOf(EmbeddingModelCount("second", 1)),
            dao.getEmbeddingCountsByModels(listOf("second", "missing")))
    }

    @Test
    fun emptyLegacyIdUnicodeAndEmbeddedNulRemainNonemptyWithoutPayloadHydration() = runBlocking {
        database.withTransaction {
            add("", text = "legacy")
            add("\uE000", text = "\u0000")
            add("\uD800\uDC00", text = "nonempty")
            add("large", text = "x".repeat(3 * 1024 * 1024))
            embedding("", "first", -1L)
            embedding("\uE000", "first", 1L)
            embedding("large", "first", 2L)
        }
        selects.clear()
        assertEquals(EmbeddingCacheCounts(4, mapOf("first" to 3)),
            dao.getEmbeddingCacheCounts(listOf("first")))
        assertTrue(selects.none { (sql, _) ->
            "SELECT m.*" in sql || "SELECT m.text" in sql || "SELECT e.embedding" in sql
        })
        database.openHelper.readableDatabase.query(
            "SELECT LENGTH(CAST(text AS BLOB)) FROM messages WHERE id = 'large'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3 * 1024 * 1024, cursor.getInt(0))
        }
    }

    @Test
    fun cancellationStopsBeforeAnotherPageAndLeavesForegroundReadsAndWritesAvailable() = runBlocking {
        database.withTransaction {
            repeat(CACHE_COUNT_PAGE_SIZE * 3) { add("row-%04d".format(it)) }
        }
        selects.clear()
        var completed = false
        val counting = launch {
            dao.getEmbeddingCacheCounts(listOf("first"))
            completed = true
        }
        cancelOnCount.set(counting)
        counting.join()
        assertTrue(counting.isCancelled)
        assertFalse(completed)
        assertEquals(1, selects.count { "SELECT id FROM messages" in it.first })
        assertFalse(database.inTransaction())
        add("foreground")
        assertEquals("ordinary", dao.getMessage("foreground")?.text)
        assertEquals(1, dao.getAllConversationsList().count { it.id == "root" })
    }

    @Test
    fun physicalPageLimitPrecedesModelFanoutAndDeletionDoesNotLeaveCachedRows() = runBlocking {
        database.withTransaction {
            repeat(CACHE_COUNT_PAGE_SIZE + 1) { index ->
                val id = "row-%04d".format(index)
                add(id)
                embedding(id, "first", index * 2L + 1)
                embedding(id, "second", index * 2L + 2)
            }
        }
        val first = dao.getFirstEmbeddingCacheCountPage(listOf("first", "second"), CACHE_COUNT_PAGE_SIZE)
        assertEquals(CACHE_COUNT_PAGE_SIZE * 2, first.size)
        assertEquals(CACHE_COUNT_PAGE_SIZE, first.map { it.messageId }.distinct().size)
        assertEquals("row-0127", first.last().messageId)
        database.openHelper.writableDatabase.execSQL("DELETE FROM messages WHERE id = 'row-0128'")
        assertTrue(dao.getEmbeddingCacheCountPage(listOf("first", "second"), first.last().messageId,
            CACHE_COUNT_PAGE_SIZE).isEmpty())
        assertEquals(EmbeddingCacheCounts(128, mapOf("first" to 128, "second" to 128)),
            dao.getEmbeddingCacheCounts(listOf("first", "second")))
        assertEquals(128, dao.getEmbeddingCacheCounts(emptyList()).total)
        assertEquals(emptyList<EmbeddingModelCount>(), dao.getEmbeddingCountsByModels(emptyList()))
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
        selects.clear()
        assertEquals(listOf("visible"), dao.getEmbeddingSearchPage("first", 0L, 1, 2).map { it.messageId })
        val physical = selects.filter { "SELECT id FROM embeddings WHERE" in it.first }
        assertTrue(physical.size > 128)
        assertTrue(physical.all { (_, args) -> (args.last() as Number).toInt() <= 2 })
        assertTrue(selects.filter { "SELECT e.id, e.messageId, e.embedding" in it.first }.all {
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
        selects.clear()
        val page = dao.getEmbeddingSearchPage("first", 0L, 1, 3)
        assertEquals(listOf(1L, 260L, 261L), page.map { it.id })
        val physical = selects.filter { "SELECT id FROM embeddings WHERE" in it.first }
        assertEquals(87, physical.size)
        assertTrue(physical.all { (_, args) -> (args.last() as Number).toInt() == 3 })
        val first = dao.getEmbeddingSearchPage("first", 0L, 1, 2)
        assertEquals(listOf(1L, 260L), first.map { it.id })
        assertEquals(listOf(261L), dao.getEmbeddingSearchPage("first", first.last().id, 1, 2).map { it.id })
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
