package com.newoether.agora.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.repository.boundedKeywordMatches
import com.newoether.agora.data.repository.searchConversationMessages
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.toMessageSegment
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureNanoTime
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Long synthetic CJK history, including persisted streaming segments; never reads user data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KeywordLongHistoryBenchmarkTest {
    @Test fun longChineseMissWithStreamingWrites() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "keyword-long-history-benchmark"
        context.deleteDatabase(name)
        val queries = Executors.newFixedThreadPool(4)
        val transactions = Executors.newSingleThreadExecutor()
        val pendingRead = AtomicReference<CompletableDeferred<Unit>?>(null)
        val statements = AtomicInteger()
        val database = Room.databaseBuilder(context, ChatDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryExecutor(queries).setTransactionExecutor(transactions)
            .setQueryCallback({ statement, _ ->
                if (statement.contains("FROM messages")) {
                    statements.incrementAndGet()
                    pendingRead.get()?.complete(Unit)
                }
            }, java.util.concurrent.Executor { it.run() }).build()
        try {
            withContext(Dispatchers.IO) { seed(database) }
            val dao = database.chatDao()
            val misses = mutableListOf<Double>()
            repeat(3) {
                misses += elapsed { assertTrue(searchConversationMessages(dao, "啊啊", 20).isEmpty()) }
            }
            val entered = CompletableDeferred<Unit>()
            pendingRead.set(entered)
            val scanning = async(Dispatchers.IO) { searchConversationMessages(dao, "啊啊", 20) }
            withTimeout(10_000) { entered.await() }
            assertFalse(scanning.isCompleted)
            val writes = mutableListOf<Double>()
            repeat(12) { index ->
                writes += elapsed {
                    withContext(Dispatchers.IO) {
                        database.openHelper.writableDatabase.execSQL(
                            "UPDATE messages SET text = ?, toolCallJson = ? WHERE id = 'message-19999'",
                            arrayOf("生成中的中文消息".repeat(600) + index,
                                """[{"type":"answer","content":"${"生成中的中文消息".repeat(600)}$index"}]"""),
                        )
                    }
                }
                yield()
            }
            val overlapped = !scanning.isCompleted
            assertTrue(scanning.await().isEmpty())
            pendingRead.set(null)
            var maxPageMs = 0.0
            var pages = 0
            var sqlMs = 0.0
            val pagedMs = elapsed { boundedKeywordMatches("啊啊", 20) { before, size ->
                lateinit var page: List<KeywordSearchRow>
                val duration = elapsed {
                    page = if (before == null) dao.getKeywordSearchFirstPage("啊啊", size, "啊啊")
                    else dao.getKeywordSearchNextPage("啊啊", before.timestamp, before.id, size, "啊啊")
                }
                maxPageMs = maxOf(maxPageMs, duration)
                sqlMs += duration
                pages++
                page
            } }
            val cancelEntered = CompletableDeferred<Unit>()
            pendingRead.set(cancelEntered)
            val cancelled = async(Dispatchers.IO) { searchConversationMessages(dao, "啊啊", 20) }
            withTimeout(10_000) { cancelEntered.await() }
            val cancelMs = elapsed { cancelled.cancelAndJoin() }
            assertTrue(cancelled.isCancelled)
            val hitMs = elapsed {
                assertEquals(20, searchConversationMessages(dao, "近期命中", 20).size)
            }
            println("KEYWORD_LONG_BENCHMARK messages=20000 owners=1000 chars=4096 long_chars=32768 WAL=true")
            println("KEYWORD_LONG_BENCHMARK miss_ms=${misses.sorted()[1]} hit_ms=$hitMs max_page_ms=$maxPageMs pages=$pages sql_ms=$sqlMs paged_ms=$pagedMs")
            println("KEYWORD_LONG_BENCHMARK stream_write_max_ms=${writes.max()} stream_write_median_ms=${writes.sorted()[6]} overlap=$overlapped cancel_ms=$cancelMs sql_count=${statements.get()}")
            assertTrue("Streaming updates must finish during the scan", overlapped)
            assertTrue("A bounded scan must admit streaming writes", writes.max() < 2_000)
            assertTrue("Cancellation must release the in-flight search", cancelMs < 2_000)
        } finally {
            database.close()
            queries.shutdownNow()
            transactions.shutdownNow()
            context.deleteDatabase(name)
        }
    }

    private fun seed(database: ChatDatabase) {
        val sql = database.openHelper.writableDatabase
        val text = "合成中文长消息内容测试历史搜索性能。".repeat(230).take(4096)
        val longText = text.repeat(8)
        val citationJson = requireNotNull(MessagePersistenceGuard.encodeSegmentsBounded(listOf(requireNotNull(
            CitationPolicy.create(provider = "test", kind = "url", title = "合成来源",
                url = "https://example.com/fixture"),
        ).toMessageSegment()))).removePrefix("[").removeSuffix("]")
        sql.beginTransaction()
        try {
            repeat(1_000) { owner ->
                sql.execSQL("""INSERT INTO conversations
                    (id,title,lastUpdated,dataChangedAt,isPinned,origin,graduated,draftText,hasUnreadGeneration,createdAt)
                    VALUES (?, '合成会话',0,0,0,'user',0,'',0,0)""", arrayOf("owner-$owner"))
                sql.execSQL("""INSERT INTO runs
                    (id,conversationId,status,activeSlot,currentPass,startedAt,lastCheckpointAt,endedAt,endReason,legacyAmbiguous)
                    VALUES (?,?,'COMPLETED',NULL,0,0,0,0,'MODEL_COMPLETED',0)""",
                    arrayOf("run-$owner", "owner-$owner"))
            }
            sql.compileStatement("""INSERT INTO messages
                (id,conversationId,text,images,tokenCount,status,participant,timestamp,runId,runSequence,toolCallJson)
                VALUES (?,?,?,'',0,'SUCCESS','MODEL',?,?,0,?)""").use { insert ->
                repeat(20_000) { index ->
                    val body = (if (index % 20 == 0) longText else text) +
                        if (index >= 19_960) "近期命中" else ""
                    val citation = if (index % 4 == 0) ",$citationJson" else ""
                    insert.clearBindings()
                    insert.bindString(1, "message-%05d".format(index))
                    insert.bindString(2, "owner-${index % 1_000}")
                    insert.bindString(3, body)
                    insert.bindLong(4, index.toLong())
                    insert.bindString(5, "run-${index % 1_000}")
                    insert.bindString(6, """[{"type":"answer","content":"$body"}$citation]""")
                    insert.executeInsert()
                }
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
    }

    private suspend fun elapsed(block: suspend () -> Unit): Double = measureNanoTime { block() } / 1_000_000.0
}
