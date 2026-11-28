package com.newoether.agora.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.migration.MIGRATION_40_41
import com.newoether.agora.data.repository.boundedCitationTitleMatches
import com.newoether.agora.data.repository.boundedKeywordMatches
import com.newoether.agora.data.repository.searchConversationMessages
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.Participant
import com.newoether.agora.model.toMessageSegment
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureNanoTime
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Synthetic corpus only. Tests production pages on native SQLite and the prior query verbatim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KeywordSearchBenchmarkTest {
    @Test fun fiftyThousandMessagesCompareLatencyAndConcurrentWriteAdmission() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "keyword-search-synthetic-benchmark"
        context.deleteDatabase(name)
        val queries = Executors.newFixedThreadPool(4)
        val transactions = Executors.newSingleThreadExecutor()
        val pendingRead = AtomicReference<CompletableDeferred<Unit>?>(null)
        val database = Room.databaseBuilder(context, ChatDatabase::class.java, name)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback({ statement, _ ->
                if (statement.contains("FROM messages")) pendingRead.get()?.complete(Unit)
            }, java.util.concurrent.Executor { it.run() })
            .setQueryExecutor(queries).setTransactionExecutor(transactions).build()
        try {
            withContext(Dispatchers.IO) { seed(database) }
            val dao = database.chatDao()
            val sql = database.openHelper.writableDatabase
            sql.execSQL("DROP INDEX index_messages_timestamp_id")
            val oldHits = mutableListOf<Double>()
            val oldMisses = mutableListOf<Double>()
            var oldIds = emptyList<String>()
            repeat(3) {
                oldHits += elapsed { oldIds = legacySearch(database, "needle", 10).map { it.id } }
                oldMisses += elapsed { assertTrue(legacySearch(database, "absent", 10).isEmpty()) }
            }
            val oldWrite = concurrentWrite(dao, pendingRead) { legacySearch(database, "absent", 10) }
            val migrationMs = elapsed { withContext(Dispatchers.IO) { MIGRATION_40_41.migrate(sql) } }
            val newHits = mutableListOf<Double>()
            val newMisses = mutableListOf<Double>()
            repeat(3) {
                newHits += elapsed { assertEquals(oldIds,
                    searchConversationMessages(dao, "needle", 10).map { it.id }) }
                newMisses += elapsed { assertTrue(searchConversationMessages(dao, "absent", 10).isEmpty()) }
            }
            val newWrite = concurrentWrite(dao, pendingRead) { searchConversationMessages(dao, "absent", 10) }
            var maxPageMs = 0.0
            var pages = 0
            val misses = boundedKeywordMatches("absent", 10) { before, size ->
                lateinit var page: List<KeywordSearchRow>
                val duration = elapsed {
                    page = if (before == null) dao.getKeywordSearchFirstPage("absent", size)
                    else dao.getKeywordSearchNextPage("absent", before.timestamp, before.id, size)
                }
                maxPageMs = maxOf(maxPageMs, duration)
                pages++
                assertTrue(page.size <= 128)
                page
            }
            assertTrue(misses.isEmpty())
            assertTrue(pages >= 391)
            val entered = CompletableDeferred<Unit>()
            val search = async(Dispatchers.IO) {
                boundedKeywordMatches("absent", 10) { before, size ->
                    entered.complete(Unit)
                    if (before == null) dao.getKeywordSearchFirstPage("absent", size)
                    else dao.getKeywordSearchNextPage("absent", before.timestamp, before.id, size)
                }
            }
            entered.await()
            val cancelMs = elapsed { search.cancelAndJoin() }
            assertTrue(search.isCancelled)
            println("KEYWORD_BENCHMARK corpus=50000 owners=20 WAL=true repeats=3")
            println("KEYWORD_BENCHMARK old_hit_ms=${oldHits.sorted()[1]} new_hit_ms=${newHits.sorted()[1]}")
            println("KEYWORD_BENCHMARK old_miss_ms=${oldMisses.sorted()[1]} new_miss_ms=${newMisses.sorted()[1]}")
            println("KEYWORD_BENCHMARK old_write_ms=$oldWrite new_write_ms=$newWrite")
            println("KEYWORD_BENCHMARK pages=$pages max_page_ms=$maxPageMs cancel_ms=$cancelMs index_ms=$migrationMs")
            assertTrue("Newest hits should avoid the corpus and citation scan", newHits.sorted()[1] < oldHits.sorted()[1])
            assertTrue("Writes must stay responsive while a miss scans pages", newWrite < 2_000)
            assertTrue("Cancellation must not wait for the whole corpus", cancelMs < 2_000)
        } finally {
            database.close()
            queries.shutdownNow()
            transactions.shutdownNow()
            context.deleteDatabase(name)
        }
    }

    private suspend fun concurrentWrite(
        dao: ChatDao,
        pendingRead: AtomicReference<CompletableDeferred<Unit>?>,
        search: suspend () -> Any,
    ): Double = coroutineScope {
        val entered = CompletableDeferred<Unit>()
        pendingRead.set(entered)
        try {
            val scanning = async(Dispatchers.IO) { search() }
            withTimeout(10_000) { entered.await() }
            assertFalse("Search must be active when the observed SQL admits the write", scanning.isCompleted)
            val result = elapsed {
                assertEquals(1, dao.updateConversationTitle("owner-0", "Synthetic benchmark", 60_000))
            }
            assertFalse("Write must finish before the corpus scan ends", scanning.isCompleted)
            scanning.await()
            result
        } finally { pendingRead.set(null) }
    }

    private suspend fun legacySearch(database: ChatDatabase, query: String, limit: Int): List<MessageEntity> =
        withContext(Dispatchers.IO) {
            val direct = database.openHelper.readableDatabase.query(
                """SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId = c.id
                    WHERE c.taskId IS NULL AND c.parentConversationId IS NULL
                    AND (m.text LIKE '%' || ? || '%' ESCAPE '\' OR c.title LIKE '%' || ? || '%' ESCAPE '\')
                    AND m.participant IN ('USER', 'MODEL') AND m.text != ''
                    AND substr(m.id, 1, 5) != 'tool_' AND substr(m.id, 1, 7) != 'result_'
                    AND substr(m.id, 1, 8) != 'compact_'
                    ORDER BY m.timestamp DESC, m.id DESC LIMIT ?""",
                arrayOf<Any>(query, query, limit),
            ).use { cursor ->
                val id = cursor.getColumnIndexOrThrow("id")
                val owner = cursor.getColumnIndexOrThrow("conversationId")
                val text = cursor.getColumnIndexOrThrow("text")
                val time = cursor.getColumnIndexOrThrow("timestamp")
                val json = cursor.getColumnIndexOrThrow("toolCallJson")
                buildList {
                    while (cursor.moveToNext()) add(MessageEntity(id = cursor.getString(id),
                        conversationId = cursor.getString(owner), text = cursor.getString(text),
                        participant = Participant.MODEL, timestamp = cursor.getLong(time),
                        runId = "fixture", toolCallJson = cursor.getString(json)))
                }
            }
            val citations = boundedCitationTitleMatches(query, limit) { after, size ->
                database.chatDao().getMessagesWithCitationSegmentsPage(after, size)
            }
            (direct + citations).distinctBy { it.id }.sortedWith(
                compareByDescending<MessageEntity> { it.timestamp }.thenByDescending { it.id },
            ).take(limit)
        }

    private fun seed(database: ChatDatabase) {
        val sql = database.openHelper.writableDatabase
        val citation = requireNotNull(MessagePersistenceGuard.encodeSegmentsBounded(listOf(requireNotNull(
            CitationPolicy.create(provider = "test", kind = "url", title = "Synthetic source",
                url = "https://example.com/fixture"),
        ).toMessageSegment())))
        sql.beginTransaction()
        try {
            repeat(20) { index ->
                sql.execSQL("""INSERT INTO conversations
                    (id,title,lastUpdated,dataChangedAt,isPinned,origin,graduated,draftText,hasUnreadGeneration,
                     createdAt,parentConversationId,taskId) VALUES (?, 'Synthetic benchmark',0,0,0,'user',0,'',0,0,?,?)""",
                    arrayOf<Any?>("owner-$index", if (index >= 18) "owner-0" else null,
                        if (index in 16..17) "task" else null))
                sql.execSQL("""INSERT INTO runs
                    (id,conversationId,status,activeSlot,currentPass,startedAt,lastCheckpointAt,endedAt,endReason,legacyAmbiguous)
                    VALUES (?,?,'COMPLETED',NULL,0,0,0,0,'MODEL_COMPLETED',0)""", arrayOf("run-$index", "owner-$index"))
            }
            val insert = sql.compileStatement("""INSERT INTO messages
                (id,conversationId,text,images,tokenCount,status,participant,timestamp,runId,runSequence,toolCallJson)
                VALUES (?,?,?,'',0,'SUCCESS','MODEL',?,?,0,?)""")
            insert.use {
                repeat(50_000) { index ->
                    val owner = index % 20
                    it.clearBindings()
                    it.bindString(1, "message-%05d".format(index))
                    it.bindString(2, "owner-$owner")
                    it.bindString(3, "Synthetic 中文 history ${if (index >= 49_900) "needle" else "ordinary"} ".padEnd(512, 'x'))
                    it.bindLong(4, index.toLong())
                    it.bindString(5, "run-$owner")
                    if (index % 4 == 0) it.bindString(6, citation) else it.bindNull(6)
                    it.executeInsert()
                }
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
    }

    private suspend fun elapsed(block: suspend () -> Unit): Double = measureNanoTime { block() } / 1_000_000.0
}
