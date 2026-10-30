package com.newoether.agora.data.local

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.Participant
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SemanticEmbeddingGenerationTest {
    private lateinit var db: ChatDatabase
    private lateinit var dao: ChatDao
    private lateinit var ledger: SemanticIndexDao
    private val sql = CopyOnWriteArrayList<String>()
    private val source = MessageEntity("m", "owner", text = "source", participant = Participant.USER,
        timestamp = 1, runId = "run", runSequence = 0)

    @Before fun open() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChatDatabase::class.java)
            .allowMainThreadQueries().setQueryCallback({ query, _ -> sql += query }, Executor { it.run() }).build()
        dao = db.chatDao()
        ledger = db.semanticIndexDao()
        dao.upsertConversation(ChatEntity("owner", "Owner"))
        dao.upsertRun(RunEntity("run", "owner", parentRunId = null, status = RunStatus.ACTIVE,
            activeSlot = 1, startedAt = 1, lastCheckpointAt = 1))
        dao.insertMessage(source)
        ledger.admitModel("model", 1)
        dao.upsertEmbedding(vector())
    }

    @After fun close() = db.close()

    @Test fun invalidationHidesOldRowsWithoutTouchingVectorPayloadAndGcPreservesReplacement() = runBlocking {
        val originalId = requireNotNull(dao.getEmbedding("m")).id
        val revision = requireNotNull(ledger.getLedger("model")).reconcileRevision
        sql.clear()
        db.invalidateSemanticModel("model", 2)
        assertFalse(sql.any { it.trimStart().startsWith("DELETE FROM embeddings", true) })
        assertFalse(sql.any { it.trimStart().startsWith("SELECT", true) && "FROM embeddings" in it })
        assertEquals(1, storedRows())
        assertNull(dao.getEmbedding("m"))
        assertTrue(dao.getAllEmbeddings().isEmpty())
        assertTrue(dao.getEmbeddingSearchPage("model", 0, 1, 10).isEmpty())
        assertEquals(0, dao.getEmbeddingCountByModel("model"))
        assertFalse(db.commitSemanticEmbedding(vector(), semanticSourceFingerprint(source.text), 3,
            expectedReconcileRevision = revision))
        val replacementRevision = requireNotNull(ledger.getLedger("model")).reconcileRevision
        val oldPage = db.maintenanceDebtDao().getModelEmbeddingIdsPage("model", 0, 64)
        assertTrue(db.commitSemanticEmbedding(vector(), semanticSourceFingerprint(source.text), 4,
            expectedReconcileRevision = replacementRevision))
        assertEquals(originalId, dao.getEmbedding("m")?.id)
        assertEquals(1L, dao.getEmbedding("m")?.generation)
        assertEquals(0, db.maintenanceDebtDao().deleteStaleEmbeddingIds(oldPage))
        assertEquals(1, dao.getEmbeddingCountByModel("model"))
        assertNotNull(db.maintenanceDebtDao().getDebt(MaintenanceDebtEntity.KIND_STALE_EMBEDDINGS, "model"))
    }

    @Test fun deletionAndRecreationRejectOldResultsAndStaleMutationSnapshotsCannotReviveModel() = runBlocking {
        val before = requireNotNull(ledger.getLedger("model")).reconcileRevision
        db.deleteSemanticModel("model")
        assertNull(ledger.getLedger("model"))
        assertTrue(dao.getAllEmbeddings().isEmpty())
        db.withSemanticSourceMutation("model" to setOf("model"), listOf("m"), 3) {
            dao.upsertMessage(source.copy(text = "changed"))
        }
        assertNull(ledger.getLedger("model"))
        assertNull(ledger.getWork("model", "m"))
        val revived = ledger.admitModel("model", 4)
        assertTrue(revived.reconcileRevision > before)
        assertEquals(1L, revived.generation)
        assertFalse(db.commitSemanticEmbedding(vector(), semanticSourceFingerprint("changed"), 5,
            expectedReconcileRevision = before))
        assertTrue(db.commitSemanticEmbedding(vector(), semanticSourceFingerprint("changed"), 6,
            expectedReconcileRevision = revived.reconcileRevision))
        assertEquals(1, dao.getEmbeddingCountByModel("model"))
    }

    @Test fun rollbackRestoresGenerationMembershipAndDebtTogether() = runBlocking {
        val before = ledger.getLedger("model")
        try {
            db.withTransaction {
                db.invalidateSemanticModel("model", 2)
                error("rollback")
            }
            fail("Expected rollback")
        } catch (_: IllegalStateException) { }
        assertEquals(before, ledger.getLedger("model"))
        assertNotNull(dao.getEmbedding("m"))
        assertEquals(1, dao.getEmbeddingCountByModel("model"))
        assertNull(db.maintenanceDebtDao().getDebt(MaintenanceDebtEntity.KIND_STALE_EMBEDDINGS, "model"))
    }

    @Test fun reclamationPagesBoundPhysicalWorkAndResumeWithoutDeletingCurrentVectors() = runBlocking {
        repeat(137) { index ->
            dao.insertEmbeddings(listOf(vector().copy(messageId = "orphan-$index")))
        }
        db.invalidateSemanticModel("model", 2)
        dao.upsertEmbedding(vector())
        val maintenance = db.maintenanceDebtDao()
        var cursor = 0L
        var examined = 0
        var removed = 0
        while (true) {
            val ids = maintenance.getModelEmbeddingIdsPage("model", cursor, 17)
            if (ids.isEmpty()) break
            assertTrue(ids.size <= 17)
            examined += ids.size
            removed += maintenance.deleteStaleEmbeddingIds(ids)
            cursor = ids.last()
        }
        assertEquals(138, examined)
        assertEquals(137, removed)
        assertEquals(1, storedRows())
        assertEquals(1, dao.getEmbeddingCountByModel("model"))
    }

    private fun vector() = EmbeddingEntity(messageId = "m", modelId = "model",
        embedding = ByteArray(4), chunkText = "source", dimension = 1,
        sourceFingerprint = semanticSourceFingerprint(source.text))

    private fun storedRows(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM embeddings").use {
        assertTrue(it.moveToFirst())
        it.getInt(0)
    }
}
