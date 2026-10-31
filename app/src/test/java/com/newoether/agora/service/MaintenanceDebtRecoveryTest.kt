package com.newoether.agora.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.newoether.agora.AgoraApplication
import com.newoether.agora.di.AppContainer
import com.newoether.agora.data.local.*
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real Worker/Room lifecycle; only application-container discovery is supplied by the fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MaintenanceDebtRecoveryTest {
    @Test fun cancelledGcReopensAndRenewedDebtCannotLoseCurrentReplacement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(ChatDatabase.DB_NAME)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var pageReads = 0
        var db = ChatDatabase.build(context, queryCallback = { sql, _ ->
            if (sql.startsWith("SELECT id FROM embeddings WHERE modelId") && ++pageReads == 2) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        })
        try {
            val dao = db.chatDao()
            dao.upsertConversation(ChatEntity("owner", "Owner"))
            dao.upsertRun(RunEntity("run", "owner", parentRunId = null, status = RunStatus.ACTIVE,
                activeSlot = 1, startedAt = 1, lastCheckpointAt = 1))
            dao.insertMessage(MessageEntity("m", "owner", text = "source", participant = Participant.USER,
                timestamp = 1, runId = "run"))
            db.semanticIndexDao().admitModel("model", 1)
            fun vector(id: String) = EmbeddingEntity(messageId = id, modelId = "model",
                embedding = ByteArray(4), dimension = 1, chunkText = "source")
            dao.insertEmbeddings((0 until 138).map { vector("stale-$it") })
            db.invalidateSemanticModel("model", 2)
            val running = async(Dispatchers.IO) { worker(db, context).doWork() }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                withTimeout(5_000) { dao.updateConversationTitle("owner", "Foreground progressed", 3) }
                running.cancel()
            } finally { release.countDown() }
            running.cancelAndJoin()
            val abandoned = requireNotNull(db.maintenanceDebtDao().getDebt(KIND, "model"))
            assertEquals(MaintenanceDebtEntity.STATE_CLAIMED, abandoned.state)
            assertEquals(74, storedRows(db))
            db.close()
            db = ChatDatabase.build(context)
            val maintenance = db.maintenanceDebtDao()
            assertTrue(maintenance.claim("too-early", abandoned.claimedAt!! + 1, abandoned.claimedAt - 1, 1).isEmpty())
            val replacementClaim = maintenance.claim("new-lease", abandoned.claimedAt + 1, abandoned.claimedAt, 1).single()
            assertFalse(maintenance.complete(abandoned))
            assertFalse(maintenance.release(abandoned, 5))
            // A new invalidation supersedes both the abandoned and recovered claim.
            db.invalidateSemanticModel("model", 6)
            assertFalse(maintenance.complete(replacementClaim))
            val priorIds = maintenance.getModelEmbeddingIdsPage("model", 0, 64)
            db.chatDao().upsertEmbedding(vector("m"))
            assertEquals(2L, db.chatDao().getEmbedding("m")?.generation)
            maintenance.deleteStaleEmbeddingIds(priorIds)
            assertEquals(ListenableWorker.Result.success(), worker(db, context).doWork())
            assertEquals(1, storedRows(db))
            assertNotNull(db.chatDao().getEmbedding("m"))
            assertNull(maintenance.getDebt(KIND, "model"))
            assertEquals("Foreground progressed", db.chatDao().getConversation("owner")?.title)
        } finally {
            release.countDown()
            db.close()
            context.deleteDatabase(ChatDatabase.DB_NAME)
        }
    }

    private fun worker(database: ChatDatabase, context: Application): MaintenanceDebtWorker {
        val app = mockk<AgoraApplication>()
        val container = mockk<AppContainer>()
        every { app.applicationContext } returns app
        every { app.filesDir } returns context.filesDir
        every { container.database } returns database
        coEvery { app.awaitContainer() } returns container
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.id } returns UUID.randomUUID()
        every { params.workerContext } returns Dispatchers.Unconfined
        return MaintenanceDebtWorker(app, params)
    }

    private fun storedRows(db: ChatDatabase) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM embeddings").use {
        check(it.moveToFirst())
        it.getInt(0)
    }

    private companion object { const val KIND = MaintenanceDebtEntity.KIND_STALE_EMBEDDINGS }
}
