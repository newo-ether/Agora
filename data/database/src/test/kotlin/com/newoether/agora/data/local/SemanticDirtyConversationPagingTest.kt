package com.newoether.agora.data.local

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SemanticDirtyConversationPagingTest {
    private data class Statement(val sql: String, val args: List<Any?>)
    private val statements = CopyOnWriteArrayList<Statement>()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: ChatDatabase
    private var onCandidatePage: (() -> Unit)? = null

    @Before fun open() {
        context.deleteDatabase(ChatDatabase.DB_NAME)
        database = ChatDatabase.build(context, queryCallback = { sql, args ->
            statements += Statement(sql, args.toList())
            if (sql.startsWith("SELECT conversationId FROM semantic_conversations")) onCandidatePage?.invoke()
        })
    }

    @After fun close() {
        database.close()
        context.deleteDatabase(ChatDatabase.DB_NAME)
    }

    private suspend fun seed(count: Int, dirty: Set<String>) {
        database.withTransaction {
            val dao = database.chatDao()
            for (id in listOf("") + (0 until count).map { "owner-%04d".format(it) }) {
                dao.upsertConversation(ChatEntity(id, id))
                dao.ensureSemanticConversation(id)
                if (id !in dirty) database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO semantic_model_conversations(modelId,conversationId,indexedRevision,cachedCount) " +
                        "SELECT 'model',conversationId,sourceRevision,0 FROM semantic_conversations WHERE conversationId = ?",
                    arrayOf<Any>(id),
                )
            }
        }
        statements.clear()
    }

    @Test fun sparseDirtyOwnersKeepEmptyIdsAndAdvanceAcrossCleanPhysicalPages() = runBlocking {
        seed(260, setOf("", "owner-0255", "owner-0259"))
        val dao = database.semanticMetadataDao()
        assertEquals(listOf(""), dao.getDirtyConversations("model", null, 1).map { it.conversationId })
        statements.clear()
        assertEquals(listOf("owner-0255"), dao.getDirtyConversations("model", "", 1).map { it.conversationId })
        val observed = statements.toList()
        val candidates = observed.filter { it.sql.startsWith("SELECT conversationId FROM semantic_conversations") }
        val filters = observed.filter { it.sql.startsWith("SELECT c.* FROM semantic_conversations") }
        assertEquals(4, candidates.size)
        assertEquals(4, filters.size)
        candidates.forEach {
            assertFalse(it.sql.contains("JOIN"))
            assertEquals(64L, (it.args.last() as Number).toLong())
        }
        filters.forEach {
            assertTrue(it.sql.contains("c.conversationId IN ("))
            assertTrue(it.args.size <= 65)
            val plan = database.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN " + it.sql, it.args.toTypedArray(),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) } }
            assertTrue(plan.toString(), plan.any { detail ->
                detail.startsWith("SEARCH") && "sqlite_autoindex_semantic_conversations_1" in detail
            })
            assertFalse(plan.toString(), plan.any { detail ->
                detail.startsWith("SCAN") && ("semantic_conversations" in detail || detail.startsWith("SCAN c"))
            })
        }
        assertEquals(listOf("owner-0259"), dao.getDirtyConversations("model", "owner-0255", 1).map { it.conversationId })
        assertTrue(dao.getDirtyConversations("model", "owner-0259", 1).isEmpty())
    }

    @Test fun allCleanPagesAreCancellableAndDoNotBlockForegroundWrites() = runBlocking {
        seed(260, emptySet())
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        onCandidatePage = {
            if (entered.complete(Unit)) check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        val reading = async(Dispatchers.IO) {
            database.semanticMetadataDao().getDirtyConversations("model", null, 1)
        }
        try {
            withTimeout(10_000) { entered.await() }
            withTimeout(5_000) { database.chatDao().updateConversationTitle("owner-0000", "Foreground", 2) }
            reading.cancel()
        } finally { release.countDown() }
        reading.cancelAndJoin()
        onCandidatePage = null
        assertEquals("Foreground", database.chatDao().getConversation("owner-0000")?.title)
        assertTrue(statements.count { it.sql.startsWith("SELECT conversationId FROM semantic_conversations") } <= 1)
        assertTrue(database.semanticMetadataDao().getDirtyConversations("model", null, 1).isEmpty())
    }
}
