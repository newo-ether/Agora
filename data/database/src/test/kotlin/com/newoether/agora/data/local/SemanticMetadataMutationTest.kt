package com.newoether.agora.data.local

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SemanticMetadataMutationTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao
    private lateinit var semantic: SemanticIndexDao
    private val statements = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(ChatDatabase.DB_NAME)
        database = ChatDatabase.build(context, queryCallback = { sql, _ -> statements += sql })
        dao = database.chatDao()
        semantic = database.semanticIndexDao()
        owner("a")
        owner("b")
        semantic.admitModel("model", 1)
    }

    @After fun tearDown() = database.close()

    @Test
    fun unchangedSourceAndNonsemanticWritesDoNotDirtyCompletedConversation() = runBlocking {
        val original = message("a", "message", "x".repeat(8000) + "suffix")
        dao.insertMessage(original)
        cached(original.id)
        finishOwners()
        val before = dao.getSemanticConversation("a")
        val ledger = semantic.getLedger("model")
        statements.clear()
        dao.upsertMessage(original.copy(text = "x".repeat(8000) + "different suffix"))
        dao.updateConversationTitle("a", "changed title", 4)
        dao.setConversationPinned("a", true, 5)
        dao.updateDraft("a", "draft", null, 6)
        dao.insertMessage(message("a", "tool_call", "synthetic body"))
        dao.insertMessage(message("a", "placeholder", ""))
        assertEquals(before, dao.getSemanticConversation("a"))
        assertEquals(ledger, semantic.getLedger("model"))
        assertEquals(EmbeddingCacheCounts(1, mapOf("model" to 1)), dao.getEmbeddingCacheCounts(listOf("model")))
        assertTrue(dao.getDirtyConversations("model", null, 10).isEmpty())
        assertFalse(statements.any { sql -> sql.trimStart().startsWith("SELECT") &&
            ("m.text" in sql || "CAST(text" in sql || "SELECT * FROM messages" in sql) })
    }

    @Test
    fun changedSourceDirtiesOnlyItsOwnerAndPreservesOtherOwnersExactWork() = runBlocking {
        val first = message("a", "first", "first source")
        val second = message("b", "second", "second source")
        dao.insertMessage(first)
        dao.insertMessage(second)
        cached(first.id)
        cached(second.id)
        finishOwners()
        val b = dao.getSemanticConversation("b")
        val pending = semantic.enqueueExactWork("model", "second", semanticSourceFingerprint(second.text), 10)
        database.withSemanticSourceMutation("model" to setOf("model"), listOf(first.id), 11) {
            dao.upsertMessage(first.copy(text = "first changed"))
        }
        assertEquals(b, dao.getSemanticConversation("b"))
        assertEquals(listOf("a"), dao.getDirtyConversations("model", null, 10).map { it.conversationId })
        assertEquals(pending.sourceRevision, semantic.getWork("model", "second")?.sourceRevision)
        assertNotNull(semantic.getWork("model", "first"))
        assertEquals(EmbeddingCacheCounts(2, mapOf("model" to 1)), dao.getEmbeddingCacheCounts(listOf("model")))
        assertFalse(database.commitSemanticEmbedding(vector("first"), semanticSourceFingerprint(first.text), 12))
        assertEquals(EmbeddingCacheCounts(2, mapOf("model" to 1)), dao.getEmbeddingCacheCounts(listOf("model")))
    }

    @Test
    fun rollbackRestoresSourcesCountsVectorsAndDirtyRevisionTogether() = runBlocking {
        val original = message("a", "original", "keep source")
        dao.insertMessage(original)
        cached(original.id)
        finishOwners()
        val before = dao.getSemanticConversation("a")
        try {
            database.withTransaction {
                dao.upsertMessage(original.copy(text = "rolled back"))
                dao.insertMessage(message("b", "rolled-back", "new"))
                error("abort")
            }
            fail("Expected rollback")
        } catch (_: IllegalStateException) { }
        assertEquals(before, dao.getSemanticConversation("a"))
        assertNull(dao.getSemanticSource("rolled-back"))
        assertEquals(semanticSourceFingerprint(original.text), semantic.getSearchableMessageFingerprint(original.id))
        assertEquals(EmbeddingCacheCounts(1, mapOf("model" to 1)), dao.getEmbeddingCacheCounts(listOf("model")))
    }

    @Test
    fun recursiveRunDeleteRemovesOnlyItsMessagesAndTheirCounts() = runBlocking {
        dao.upsertRun(run("a-child", "a", "a-run"))
        dao.insertMessage(message("a", "parent", "parent source"))
        dao.insertMessage(message("a", "child", "child source").copy(runId = "a-child"))
        dao.insertMessage(message("b", "retained", "retained source"))
        listOf("parent", "child", "retained").forEach { cached(it) }
        assertEquals(1, dao.deleteRun("a-run"))
        assertNull(dao.getSemanticSource("parent"))
        assertNull(dao.getSemanticSource("child"))
        assertNotNull(dao.getSemanticSource("retained"))
        assertEquals(EmbeddingCacheCounts(1, mapOf("model" to 1)), dao.getEmbeddingCacheCounts(listOf("model")))
        assertEquals(listOf("retained"), dao.getAllEmbeddings().map { it.messageId })
    }

    @Test
    fun forkCopiesCountsAndGraphWrapperDoesNotDiscardClonedVectors() = runBlocking {
        val original = message("a", "original", "source")
        dao.insertMessage(original)
        cached(original.id)
        database.withSemanticGraphMutation("model" to setOf("model"), updatedAt = 10) {
            dao.createForkGraph(
                ChatEntity("fork", "fork", lastUpdated = 10),
                listOf(run("fork-run", "fork")),
                listOf(message("fork", "copied", original.text)),
                mapOf("original" to "copied"),
            )
        }
        assertEquals(EmbeddingCacheCounts(2, mapOf("model" to 2)), dao.getEmbeddingCacheCounts(listOf("model")))
        assertNotNull(dao.getEmbedding("copied"))
        dao.deleteConversation("fork")
        assertEquals(EmbeddingCacheCounts(1, mapOf("model" to 1)), dao.getEmbeddingCacheCounts(listOf("model")))
        assertNull(dao.getEmbedding("copied"))
    }

    @Test
    fun taskEligibilityChangesOnlyItsOwnSummaryAndRestoresMissingWork() = runBlocking {
        dao.insertMessage(message("a", "one", "source"))
        dao.insertMessage(message("b", "two", "source"))
        cached("one")
        cached("two")
        finishOwners()
        val b = dao.getSemanticConversation("b")
        val owner = requireNotNull(dao.getConversation("a"))
        dao.upsertConversation(owner.copy(taskId = "task"))
        assertEquals(EmbeddingCacheCounts(1, mapOf("model" to 1)), dao.getEmbeddingCacheCounts(listOf("model")))
        assertNull(dao.getEmbedding("one"))
        dao.upsertConversation(owner)
        assertEquals(EmbeddingCacheCounts(2, mapOf("model" to 1)), dao.getEmbeddingCacheCounts(listOf("model")))
        assertEquals(b, dao.getSemanticConversation("b"))
        assertEquals(listOf("a"), dao.getDirtyConversations("model", null, 10).map { it.conversationId })
    }

    @Test
    fun bulkOwnerDeletionDoesNotReadIndividualSourcePrefixesOrTouchOtherOwner() = runBlocking {
        repeat(40) { index ->
            dao.insertMessage(message("a", "source-$index", "x".repeat(8000)))
            cached("source-$index")
        }
        dao.insertMessage(message("b", "retained", "keep source"))
        cached("retained")
        finishOwners()
        val retainedOwner = dao.getSemanticConversation("b")
        statements.clear()
        dao.deleteMessagesByConversation("a")
        assertFalse(statements.any { sql -> sql.trimStart().startsWith("SELECT") &&
            ("FROM semantic_sources" in sql || "FROM messages" in sql) })
        assertEquals(EmbeddingCacheCounts(1, mapOf("model" to 1)), dao.getEmbeddingCacheCounts(listOf("model")))
        assertEquals(retainedOwner, dao.getSemanticConversation("b"))
        assertEquals(0, dao.getSemanticConversation("a")?.eligibleCount)
        assertEquals(listOf("a"), dao.getDirtyConversations("model", null, 10).map { it.conversationId })
        assertEquals(listOf("retained"), dao.getAllEmbeddings().map { it.messageId })
    }

    @Test
    fun multiPageForkBoundsVectorReadsAndRollsBackEveryEarlierBatchOnFailure() = runBlocking {
        val sources = (0 until 130).map { message("a", "source-%03d".format(it), "source $it") }
        sources.forEach { dao.insertMessage(it); cached(it.id) }
        val ids = sources.associate { it.id to "fork-${it.id}" }
        val copied = sources.map { it.copy(id = ids.getValue(it.id), conversationId = "fork", runId = "fork-run") }
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_last_fork BEFORE INSERT ON embeddings
            WHEN NEW.messageId = 'fork-source-129' BEGIN SELECT RAISE(ABORT, 'reject last batch'); END
        """)
        try {
            dao.createForkGraph(ChatEntity("fork", "fork"), listOf(run("fork-run", "fork")), copied, ids)
            fail("Expected the last vector batch to fail")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertNull(dao.getConversation("fork"))
        assertNull(dao.getSemanticConversation("fork"))
        assertNull(dao.getEmbedding("fork-source-000"))
        assertEquals(130, dao.getEmbeddingCountByModel("model"))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_last_fork")
        statements.clear()
        dao.createForkGraph(ChatEntity("fork", "fork"), listOf(run("fork-run", "fork")), copied, ids)
        val vectorReads = statements.filter { "SELECT e.* FROM embeddings" in it }
        assertEquals(3, vectorReads.size)
        assertTrue(vectorReads.all { query -> query.count { it == '?' } <= 64 })
        assertEquals(260, dao.getEmbeddingCountByModel("model"))
        assertNotEquals(dao.getEmbedding("source-129")?.id, dao.getEmbedding("fork-source-129")?.id)
        dao.deleteConversation("fork")
        assertEquals(130, dao.getEmbeddingCountByModel("model"))
        assertNotNull(dao.getMessage("source-129"))
    }

    private suspend fun finishOwners() {
        val ledger = requireNotNull(semantic.getLedger("model"))
        dao.getDirtyConversations("model", null, 100).forEach {
            assertTrue(dao.markConversationIndexed("model", it.conversationId, it.sourceRevision, ledger.reconcileRevision))
        }
        assertTrue(semantic.completeReconcile("model", ledger.reconcileRevision, 2))
    }

    private suspend fun owner(id: String) {
        dao.upsertConversation(ChatEntity(id, id, lastUpdated = 1))
        dao.upsertRun(run("$id-run", id))
    }

    private fun run(id: String, owner: String, parent: String? = null) = RunEntity(
        id, owner, parentRunId = parent, status = RunStatus.COMPLETED, activeSlot = null,
        startedAt = 1, lastCheckpointAt = 1, endedAt = 1,
        endReason = com.newoether.agora.model.RunEndReason.MODEL_COMPLETED,
    )

    private fun message(owner: String, id: String, text: String) = MessageEntity(
        id = id, conversationId = owner, text = text, participant = Participant.USER,
        timestamp = 1, runId = "$owner-run",
    )

    private fun vector(id: String) = EmbeddingEntity(
        messageId = id, modelId = "model", embedding = byteArrayOf(0, 0, 0, 0), chunkText = "", dimension = 1,
    )

    private suspend fun cached(id: String) { dao.insertEmbeddings(listOf(vector(id))) }
}
