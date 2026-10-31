package com.newoether.agora.data.local

import android.app.Application
import android.database.AbstractWindowedCursor
import android.database.CursorWindow
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.util.Constants
import java.util.Collections
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
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
class SemanticReconcilePagingTest {
    private lateinit var database: ChatDatabase
    private lateinit var chatDao: ChatDao
    private lateinit var semanticDao: SemanticIndexDao
    private val selects = Collections.synchronizedList(mutableListOf<Pair<String, List<Any?>>>())

    @Before
    fun setUp() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            ChatDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryCallback({ sql, args ->
                if (sql.trimStart().startsWith("SELECT", ignoreCase = true)) {
                    selects.add(sql to args.toList())
                }
            }, Executor { it.run() })
            .build()
        chatDao = database.chatDao()
        semanticDao = database.semanticIndexDao()
        owner("root")
        owner("child", parent = "root")
        owner("task", task = "scheduled")
        semanticDao.admitModel(MODEL_ID, updatedAt = 0)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun hiddenPhysicalPageStillLeadsToEligibleRootAndChildRows() = runBlocking {
        val hiddenIds = (0 until PAGE_SIZE).map { "a-hidden-%03d".format(it) }
        database.withTransaction {
            hiddenIds.forEach { id -> add(id, "hidden task source", owner = "task") }
            add("compact_hidden", "summary")
            add("result_hidden", "tool result")
            add("tool_hidden", "tool call")
            add("visible-child", "child content remains eligible for reconciliation", owner = "child")
            add("visible-root", "ordinary content")
        }

        val firstIds = semanticDao.getFirstReconcileMessageIdsPage(limit = PAGE_SIZE)
        assertEquals(hiddenIds, firstIds)
        assertTrue(hydrate(firstIds).isEmpty())

        // The physical page owns advancement even when every hydrated row is hidden.
        val nextIds = semanticDao.getReconcileMessageIdsPage(firstIds.last(), PAGE_SIZE)
        assertEquals(
            listOf("compact_hidden", "result_hidden", "tool_hidden", "visible-child", "visible-root"),
            nextIds,
        )
        assertEquals(listOf("visible-child", "visible-root"), hydrate(nextIds).map { it.id })
        assertTrue(semanticDao.getReconcileMessageIdsPage(nextIds.last(), PAGE_SIZE).isEmpty())
    }

    @Test
    fun newerExactWorkCanHideAWholePageWithoutHidingTheNextPhysicalPage() = runBlocking {
        val newerIds = (0 until PAGE_SIZE).map { "a-newer-%03d".format(it) }
        database.withTransaction {
            newerIds.forEach { id ->
                add(id, "newer source")
                work(id, MAX_WORK_REVISION + 1)
            }
            add("z-visible", "available to the admitted reconciliation")
        }

        val firstIds = semanticDao.getFirstReconcileMessageIdsPage(PAGE_SIZE)
        assertEquals(newerIds, firstIds)
        assertTrue(hydrate(firstIds).isEmpty())
        val nextIds = semanticDao.getReconcileMessageIdsPage(firstIds.last(), PAGE_SIZE)
        assertEquals(listOf("z-visible"), nextIds)
        assertEquals(nextIds, hydrate(nextIds).map { it.id })
        assertEquals(MAX_WORK_REVISION + 1, semanticDao.getWork(MODEL_ID, newerIds.last())?.sourceRevision)
    }

    @Test
    fun hydrationRechecksVisibilityAndPreservesModelAndRevisionFences() = runBlocking {
        owner("changing")
        semanticDao.admitModel("other-model", updatedAt = 0)
        database.withTransaction {
            add("at-revision", "allowed exact work", participant = Participant.USER)
            add("child-visible", "eligible child", owner = "child")
            add("deleted", "deleted after ID selection")
            add("empty", "")
            add("error", "error body", participant = Participant.ERROR)
            add("later-hidden", "owner changes after ID selection", owner = "changing")
            add("newer", "newer work owns this source")
            add("other-model-work", "another model cannot exclude this source")
            add("plain", "no work or embedding")
            add("task-hidden", "task body", owner = "task")
            add("compact_hidden", "summary")
            add("result_hidden", "result")
            add("tool_hidden", "tool")
            work("at-revision", MAX_WORK_REVISION)
            work("newer", MAX_WORK_REVISION + 1)
            work("other-model-work", MAX_WORK_REVISION + 1, modelId = "other-model")
            semanticDao.upsertEmbedding(EmbeddingEntity(
                id = 41, messageId = "at-revision", modelId = MODEL_ID,
                embedding = ByteArray(12), chunkText = "cached source", dimension = 3,
                sourceFingerprint = "expected-fingerprint",
            ))
            semanticDao.upsertEmbedding(EmbeddingEntity(
                id = 42, messageId = "at-revision", modelId = "other-model",
                embedding = ByteArray(28), chunkText = "unrelated source", dimension = 7,
                sourceFingerprint = "other-fingerprint",
            ))
        }
        val ids = semanticDao.getFirstReconcileMessageIdsPage(PAGE_SIZE)
        chatDao.upsertConversation(chatDao.getConversation("changing")!!.copy(taskId = "scheduled"))
        chatDao.deleteMessagesByIds(listOf("deleted"))

        val rows = hydrate(ids.reversed())
        assertEquals(listOf("at-revision", "child-visible", "other-model-work", "plain"), rows.map { it.id })
        val cached = rows.first()
        assertEquals(41L, cached.embeddingId)
        assertEquals("expected-fingerprint", cached.embeddingFingerprint)
        assertEquals(3, cached.dimension)
        assertEquals(12, cached.embeddingBytes)
        val plain = rows.last()
        assertNull(plain.embeddingId)
        assertNull(plain.embeddingFingerprint)
        assertNull(plain.dimension)
        assertNull(plain.embeddingBytes)
        assertNull(semanticDao.getSearchableMessageText("later-hidden"))
        assertNull(semanticDao.getSearchableMessageText("deleted"))
        assertTrue(hydrate(emptyList()).isEmpty())
    }

    @Test
    fun firstAndNextIdPagesIncludeEmptyIdAndFollowSqliteBinaryUnicodeOrder() = runBlocking {
        val bmp = "\uE000"
        val supplementary = "\uD800\uDC00"
        val expected = listOf("", bmp, supplementary)
        database.withTransaction {
            expected.reversed().forEach { id -> add(id, "ordinary source") }
        }

        val first = semanticDao.getFirstReconcileMessageIdsPage(limit = 1)
        assertEquals(listOf(""), first)
        assertEquals(first, hydrate(first).map { it.id })
        val second = semanticDao.getReconcileMessageIdsPage(afterId = first.single(), limit = 1)
        assertEquals(listOf(bmp), second)
        val third = semanticDao.getReconcileMessageIdsPage(afterId = second.single(), limit = 1)
        assertEquals(listOf(supplementary), third)
        assertTrue(semanticDao.getReconcileMessageIdsPage(third.single(), 1).isEmpty())
        assertEquals(expected, first + second + third)
        assertEquals(expected, semanticDao.getFirstReconcileMessageIdsPage(limit = PAGE_SIZE))
        assertEquals(listOf(bmp, supplementary),
            semanticDao.getReconcileMessageIdsPage(afterId = "", limit = PAGE_SIZE))
        assertEquals(expected, hydrate(expected.reversed()).map { it.id })
        // UTF-16 String order differs here; pagination must retain SQLite's byte order.
        assertTrue(supplementary < bmp)
    }

    @Test
    fun actualIdPageQueriesUseCoveringIndexWithoutWholeTableScanOrTemporarySort() = runBlocking {
        val ids = (0 until 260).map { "row-%04d".format(it) }
        database.withTransaction { ids.forEach { add(it, "source") } }
        for ((afterId, expected) in listOf<Pair<String?, List<String>>>(
            null to ids.take(PAGE_SIZE),
            ids[127] to ids.drop(128).take(PAGE_SIZE),
            ids[255] to ids.drop(256),
            ids.last() to emptyList(),
        )) {
            selects.clear()
            val page = if (afterId == null) {
                semanticDao.getFirstReconcileMessageIdsPage(PAGE_SIZE)
            } else {
                semanticDao.getReconcileMessageIdsPage(afterId, PAGE_SIZE)
            }
            assertEquals(expected, page)
            val query = recordedSelect { "FROM messages" in it }
            val details = database.openHelper.readableDatabase.query(
                SimpleSQLiteQuery("EXPLAIN QUERY PLAN ${query.first}", query.second.toTypedArray()),
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
            }
            if (afterId == null) {
                // The first page walks only the limited covering index from its beginning.
                assertTrue(details.toString(), details.any {
                    it.startsWith("SCAN messages") && "COVERING INDEX" in it
                })
                assertFalse(details.toString(), details.any {
                    it.startsWith("SCAN ") && "COVERING INDEX" !in it
                })
            } else {
                assertTrue(details.toString(), details.any {
                    it.startsWith("SEARCH messages") && "COVERING INDEX" in it && "id>?" in it
                })
                assertFalse(details.toString(), details.any { it.startsWith("SCAN ") })
            }
            assertFalse(details.toString(), details.any { "TEMP B-TREE" in it })
        }
    }

    @Test
    fun giantSourceAndEmbeddingHydrateThroughASmallNativeCursorWindow() = runBlocking {
        val source = "x".repeat(3 * 1024 * 1024)
        val embedding = ByteArray(3 * 1024 * 1024)
        add("giant", source)
        semanticDao.upsertEmbedding(EmbeddingEntity(
            id = 81, messageId = "giant", modelId = MODEL_ID,
            embedding = embedding, chunkText = source, dimension = embedding.size / 4,
            sourceFingerprint = semanticSourceFingerprint(source),
        ))

        selects.clear()
        val row = hydrate(listOf("giant")).single()
        val hydrationQuery = recordedSelect { "embeddingId" in it }
        assertEquals(source.take(Constants.MAX_EMBEDDING_TEXT_LENGTH + 1), row.text)
        assertEquals(81L, row.embeddingId)
        assertEquals(embedding.size / 4, row.dimension)
        assertEquals(embedding.size, row.embeddingBytes)
        assertEquals(semanticSourceFingerprint(source), row.embeddingFingerprint)
        assertFitsNativeWindow(hydrationQuery, textColumn = 1, expected = row.text)

        selects.clear()
        val exactText = requireNotNull(semanticDao.getSearchableMessageText("giant"))
        val exactQuery = recordedSelect { "FROM semantic_sources s" in it }
        assertEquals(row.text, exactText)
        assertFitsNativeWindow(exactQuery, textColumn = 0, expected = exactText)

        database.openHelper.readableDatabase.query(
            "SELECT LENGTH(CAST(text AS BLOB)) FROM messages WHERE id = 'giant'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(source.length, cursor.getInt(0))
        }
        database.openHelper.readableDatabase.query(
            "SELECT LENGTH(embedding), LENGTH(CAST(chunkText AS BLOB)) FROM embeddings WHERE id = 81",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(embedding.size, cursor.getInt(0))
            assertEquals(source.length, cursor.getInt(1))
        }
    }

    @Test
    fun bytePrefixPreservesNulsAndUtf16FingerprintWhenUtf8TailIsSplit() = runBlocking {
        val units = Constants.MAX_EMBEDDING_TEXT_LENGTH
        val smile = "\uD83D\uDE42"
        val sources = linkedMapOf(
            "short" to "\u4E2D\u0000\u6587${smile}end",
            "bmp" to "\u6C49".repeat(units + 4000),
            "nul-first" to "\u0000" + "x".repeat(PREFIX_BYTES + 20),
            "nul-middle" to "prefix\u0000" + "\u6C49".repeat(units + 4000),
            "supplementary" to smile.repeat(units + 100),
            "utf16-cut" to "a".repeat(units - 1) + smile + "z".repeat(PREFIX_BYTES),
            "utf8-cut" to "a".repeat(PREFIX_BYTES - 1) + smile + "tail",
        )
        database.withTransaction { sources.forEach { (id, source) -> add(id, source) } }
        val rows = hydrate(sources.keys.toList()).associateBy { it.id }
        assertEquals(sources.keys, rows.keys)
        for ((id, source) in sources) {
            val rowText = rows.getValue(id).text
            val exactText = requireNotNull(semanticDao.getSearchableMessageText(id))
            val expectedPrefix = source.take(units)
            for (text in listOf(rowText, exactText)) {
                assertTrue("$id has a bounded decoded byte prefix", text.length <= PREFIX_BYTES)
                assertEquals("$id retains the exact UTF-16 embedding input", expectedPrefix, text.take(units))
                assertEquals("$id retains the canonical fingerprint",
                    semanticSourceFingerprint(source), semanticSourceFingerprint(text))
            }
            assertEquals("$id uses the same source projection in both reads", rowText, exactText)
        }
        assertEquals(sources.getValue("short"), rows.getValue("short").text)
        assertEquals('\u0000', rows.getValue("nul-first").text.first())
        assertEquals(sources.getValue("nul-middle").indexOf('\u0000'),
            rows.getValue("nul-middle").text.indexOf('\u0000'))
        assertTrue(Character.isHighSurrogate(rows.getValue("utf16-cut").text.take(units).last()))
        assertEquals(smile.repeat(units / 2), rows.getValue("supplementary").text.take(units))
    }

    private fun recordedSelect(predicate: (String) -> Boolean): Pair<String, List<Any?>> =
        synchronized(selects) { selects.last { predicate(it.first) } }

    private fun assertFitsNativeWindow(
        query: Pair<String, List<Any?>>,
        textColumn: Int,
        expected: String,
    ) {
        database.openHelper.readableDatabase.query(
            SimpleSQLiteQuery(query.first, query.second.toTypedArray()),
        ).use { cursor ->
            assertTrue("Exercise the native Android windowed cursor", cursor is AbstractWindowedCursor)
            val windowed = cursor as AbstractWindowedCursor
            windowed.window = CursorWindow("semantic-reconcile-regression", 64L * 1024)
            assertEquals(1, windowed.count)
            assertTrue(windowed.moveToFirst())
            assertEquals(android.database.Cursor.FIELD_TYPE_BLOB, windowed.getType(textColumn))
            assertEquals(expected, windowed.getBlob(textColumn).toString(Charsets.UTF_8))
            assertFalse(windowed.moveToNext())
        }
    }

    private suspend fun hydrate(ids: List<String>) = semanticDao.getReconcileMessagesByIds(
        modelId = MODEL_ID,
        maxWorkRevision = MAX_WORK_REVISION,
        messageIds = ids,
        sourcePrefixBytes = PREFIX_BYTES,
    )

    private suspend fun work(id: String, revision: Long, modelId: String = MODEL_ID) {
        semanticDao.upsertWork(SemanticIndexWorkEntity(
            modelId = modelId, messageId = id, sourceFingerprint = "work-fingerprint",
            sourceRevision = revision, updatedAt = 0,
        ))
    }

    private suspend fun owner(id: String, parent: String? = null, task: String? = null) {
        chatDao.upsertConversation(ChatEntity(id, id, parentConversationId = parent, taskId = task))
        chatDao.upsertRun(RunEntity(
            id = "$id-run", conversationId = id, parentRunId = null,
            status = RunStatus.COMPLETED, activeSlot = null, startedAt = 0, lastCheckpointAt = 0,
            endedAt = 0, endReason = RunEndReason.MODEL_COMPLETED,
        ))
    }

    private suspend fun add(
        id: String,
        text: String,
        owner: String = "root",
        participant: Participant = Participant.MODEL,
    ) {
        chatDao.upsertMessage(MessageEntity(
            id = id, conversationId = owner, text = text, participant = participant,
            timestamp = 0, runId = "$owner-run",
        ))
    }

    companion object {
        private const val MODEL_ID = "model"
        private const val MAX_WORK_REVISION = 5L
        private const val PAGE_SIZE = 128
        private const val PREFIX_BYTES = Constants.MAX_EMBEDDING_TEXT_LENGTH * 4
    }
}
