package com.newoether.agora.data.local

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Checks plans captured from production DAO calls, not a second copy of the SQL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScopedDatabaseQueryPlanTest {
    private data class Statement(val sql: String, val arguments: List<Any?>)
    private val statements = CopyOnWriteArrayList<Statement>()
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao

    @Before
    fun setUp() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), ChatDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryCallback({ sql, arguments ->
                statements += Statement(sql, arguments.toList())
            }, Executor { it.run() })
            .build()
        dao = database.chatDao()
        dao.upsertConversation(ChatEntity("target", "target", lastUpdated = 1))
        dao.upsertConversation(ChatEntity("other", "other", lastUpdated = 1))
        dao.insertRun(run("root", "target"))
        dao.insertRun(run("child", "target", "root"))
        dao.insertRun(run("other-run", "other"))
    }

    @After fun tearDown() = database.close()

    @Test
    fun cascadeReadSeeksRunIndexAndPreservesUnrelatedLargeMessages() = runBlocking {
        dao.insertMessage(message("parent", "target", "root"))
        dao.insertMessage(message("child-message", "target", "child"))
        repeat(24) { index ->
            dao.insertMessage(message("other-$index", "other", "other-run", "x".repeat(262_144)))
        }
        statements.clear()

        assertEquals(setOf("parent", "child-message"), dao.getRunCascadeMessageIds("root").toSet())
        val sql = statements.last { it.sql.startsWith("WITH RECURSIVE removed") }
        val plan = explain(sql)

        assertTrue(plan.toString(), plan.any {
            it.contains("SEARCH m USING") && it.contains("index_messages_runId")
        })
        assertFalse(plan.toString(), plan.any { it.matches(Regex(".*SCAN (m|messages)( .*)?")) })
        assertEquals(1, dao.deleteRun("root"))
        assertNull(dao.getSemanticSource("parent"))
        assertNull(dao.getSemanticSource("child-message"))
        assertNotNull(dao.getSemanticSource("other-23"))
        assertNotNull(dao.getRun("other-run"))
        assertEquals(24, dao.getSemanticConversation("other")?.eligibleCount)
    }

    @Test
    fun emptyRunCleanupStillUsesIndexAndRollbackRetainsItsGraph() = runBlocking {
        dao.insertMessage(message("parent", "target", "root"))
        statements.clear()
        assertTrue(dao.getRunCascadeMessageIds("child").isEmpty())
        val query = statements.last { it.sql.startsWith("WITH RECURSIVE removed") }
        assertFalse(explain(query).toString(), explain(query).any { it.startsWith("SCAN m") })

        try {
            database.withTransaction {
                dao.deleteRun("root")
                error("rollback")
            }
            fail("Expected rollback")
        } catch (_: IllegalStateException) {
            // The production transaction must restore the Run, message and derived source.
        }
        assertNotNull(dao.getRun("root"))
        assertNotNull(dao.getRun("child"))
        assertNotNull(dao.getMessage("parent"))
        assertNotNull(dao.getSemanticSource("parent"))
    }

    @Test
    fun legacyAndConversationContinuationPagesUseRangeSeeks() = runBlocking {
        val ids = listOf("", "a", "b", "z", "\uE000", "\uD800\uDC00")
        ids.forEach { id -> dao.insertMessage(message(id, "target", "root")) }
        val metadata = database.semanticMetadataDao()
        assertEquals(listOf("", "a"), metadata.getLegacyMessageIds(null, 2))
        statements.clear()

        assertEquals(listOf("b", "z"), metadata.getLegacyMessageIds("a", 2))
        val legacy = statements.last { it.sql.startsWith("SELECT id FROM messages WHERE id >") }
        assertTrue(explain(legacy).toString(), explain(legacy).any {
            it.contains("SEARCH messages USING COVERING INDEX") && it.contains("id>?")
        })
        assertEquals(listOf("b", "z"), metadata.getConversationSourceIds("target", "a", 2))
        val sources = statements.last { it.sql.startsWith("SELECT messageId FROM semantic_sources WHERE") }
        assertTrue(explain(sources).toString(), explain(sources).any {
            it.contains("SEARCH semantic_sources USING COVERING INDEX") && it.contains("messageId>?")
        })
        assertEquals(listOf("\uE000", "\uD800\uDC00"), metadata.getLegacyMessageIds("z", 2))
    }

    @Test
    fun runSequenceAndOwnerPagesUseCoveringAndCompositeIndexes() = runBlocking {
        dao.insertMessage(message("first", "target", "root").copy(runSequence = 17))
        dao.insertMessage(message("last", "target", "root").copy(runSequence = 3))
        statements.clear()
        assertEquals(18L, dao.nextRunSequence("root"))
        val sequence = statements.last { "MAX(runSequence)" in it.sql }
        assertTrue(explain(sequence).toString(), explain(sequence).any {
            "COVERING INDEX index_messages_runId_runSequence" in it
        })
        assertEquals(listOf("last"), dao.getConversationMessagesPage("target", "first", 2).map { it.id })
        val page = statements.last { "FROM messages" in it.sql && "id >" in it.sql }
        assertTrue(explain(page).toString(), explain(page).any {
            "index_messages_conversationId_id" in it && "id>?" in it
        })
        assertEquals(0L, dao.nextRunSequence("child"))
    }

    @Test
    fun providerMigrationCommitsBoundedIndexedPagesAndResumesWithoutChangingNeighborReferences() = runBlocking {
        val legacy = "旧供应商😀"
        val replacement = "stable-provider-id"
        dao.upsertConversation(requireNotNull(dao.getConversation("target")).copy(modelId = "$legacy:model"))
        repeat(130) { i ->
            dao.insertMessage(message("legacy-%03d".format(i), "target", "root").copy(modelName = "$legacy:model:$i"))
        }
        dao.insertMessage(message("neighbor", "other", "other-run").copy(modelName = "${legacy}x:model"))
        statements.clear()
        assertTrue(dao.renameProviderReferencePage(legacy, replacement))
        assertTrue(dao.renameProviderReferencePage(legacy, replacement))
        assertEquals(66, dao.getProviderMessageReferences(legacy, 200).size)
        val identityQueries = statements.filter {
            it.sql.trimStart().startsWith("SELECT") && ("modelName >=" in it.sql || "modelId >=" in it.sql)
        }
        assertTrue(identityQueries.isNotEmpty())
        identityQueries.forEach { query ->
            assertTrue(explain(query).toString(), explain(query).any { "SEARCH" in it && "INDEX" in it })
        }
        assertFalse(statements.any { "SELECT * FROM messages" in it.sql })
        try {
            database.withTransaction {
                dao.renameProviderReferencePage(legacy, replacement)
                error("rollback")
            }
            fail("Expected rollback")
        } catch (_: IllegalStateException) { }
        assertEquals(66, dao.getProviderMessageReferences(legacy, 200).size)
        dao.renameConfiguredProviderModelReferences(legacy, replacement)
        dao.renameConfiguredProviderModelReferences(legacy, replacement)
        assertTrue(dao.getProviderMessageReferences(legacy, 1).isEmpty())
        assertEquals(130, dao.getProviderMessageReferences(replacement, 200).size)
        assertEquals("$replacement:model", dao.getConversation("target")?.modelId)
        assertEquals("${legacy}x:model", dao.getMessage("neighbor")?.modelName)
        assertEquals("$replacement:model:0", dao.getMessage("legacy-000")?.modelName)
    }

    private fun explain(statement: Statement): List<String> =
        database.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN " + statement.sql,
            statement.arguments.toTypedArray(),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
        }

    private fun run(id: String, conversationId: String, parentId: String? = null) = RunEntity(
        id = id, conversationId = conversationId, parentRunId = parentId,
        status = RunStatus.COMPLETED, activeSlot = null, startedAt = 1, lastCheckpointAt = 1,
        endedAt = 1, endReason = RunEndReason.MODEL_COMPLETED,
    )

    private fun message(id: String, owner: String, runId: String, text: String = "source") =
        MessageEntity(
            id = id, conversationId = owner, text = text, participant = Participant.USER,
            timestamp = 1, runId = runId, runSequence = 0,
        )
}
