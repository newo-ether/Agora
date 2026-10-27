package com.newoether.agora.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.ProgressUpdater
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ReconcileIndexableMessage
import com.newoether.agora.data.local.SemanticIndexDao
import com.newoether.agora.data.local.SemanticIndexLedgerEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EmbeddingCacheWorkerTest {
    private val database = mockk<ChatDatabase>()
    private val dao = mockk<SemanticIndexDao>()
    private val settings = mockk<SettingsManager>()
    private val model = EmbeddingModelConfig("model", "Test", EmbeddingModelType.REMOTE)
    private var ledger = SemanticIndexLedgerEntity(
        modelId = "model", sourceRevision = 1, reconcileRevision = 1, updatedAt = 1,
    )
    private var rows = emptyList<ReconcileIndexableMessage>()
    private var pageReads = 0
    private var publications = 0
    private var afterPage: () -> Unit = {}

    @Before
    fun setUp() {
        every { database.semanticIndexDao() } returns dao
        every { settings.embeddingModels } returns flowOf(listOf(model))
        every { settings.customProviders } returns flowOf(emptyList())
        coEvery { dao.getLedger(model.id) } answers { ledger }
        coEvery { dao.getFirstReconcileMessageIdsPage(any()) } answers {
            pageReads++
            val page = rows.map { it.id }.take(firstArg())
            afterPage()
            page
        }
        coEvery { dao.getReconcileMessageIdsPage(any(), any()) } answers {
            pageReads++
            val after = firstArg<String>()
            val ids = rows.map { it.id }
            val page = ids.drop(ids.indexOf(after) + 1).take(secondArg())
            afterPage()
            page
        }
        coEvery { dao.getReconcileMessagesByIds(any(), any(), any(), any()) } answers {
            val ids = thirdArg<List<String>>()
            rows.filter { it.id in ids }
        }
        coEvery { dao.completeReconcile(any(), any(), any()) } answers {
            secondArg<Long>() == ledger.reconcileRevision
        }
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun cachedCorpusReadsToExhaustionWithoutInspectionProgressWrites() = runTest {
        rows = cachedRows(257)
        assertEquals(Result.success(androidx.work.workDataOf(EmbeddingCacheWorker.KEY_FAILED to 0)),
            worker().cacheModel(model.id, database, settings))
        assertEquals(4, pageReads)
        assertEquals(0, publications)
        coVerify(exactly = 0) { dao.upsertEmbedding(any()) }
        coVerify(exactly = 1) { dao.completeReconcile(model.id, 1, any()) }
    }

    @Test
    fun emptyCorpusCompletesAfterOneEmptyPage() = runTest {
        worker().cacheModel(model.id, database, settings)
        assertEquals(1, pageReads)
        assertEquals(0, publications)
        coVerify(exactly = 1) { dao.completeReconcile(model.id, 1, any()) }
    }

    @Test
    fun deletingUnvisitedRowsDoesNotRetryAgainstAnObsoleteCount() = runTest {
        rows = cachedRows(257)
        afterPage = { rows = emptyList() }
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Success)
        assertEquals(2, pageReads)
        coVerify(exactly = 1) { dao.completeReconcile(model.id, 1, any()) }
    }

    @Test
    fun recacheDuringPageStopsTheObsoleteGeneration() = runTest {
        rows = cachedRows(257)
        afterPage = { ledger = ledger.copy(sourceRevision = 2, reconcileRevision = 2) }
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Success)
        assertEquals(1, pageReads)
        coVerify(exactly = 0) { dao.completeReconcile(any(), any(), any()) }
    }

    @Test
    fun cancellationLeavesReconcilePendingAndNewWorkerCanReplay() = runTest {
        rows = cachedRows(257)
        afterPage = { throw kotlinx.coroutines.CancellationException("Process stopped") }
        try {
            worker().cacheModel(model.id, database, settings)
            org.junit.Assert.fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            coVerify(exactly = 0) { dao.completeReconcile(any(), any(), any()) }
        }
        afterPage = {}
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Success)
        coVerify(exactly = 1) { dao.completeReconcile(model.id, 1, any()) }
    }

    @Test
    fun mutationBehindCursorKeepsItsNewWorkWhileScanCompletesOldRevision() = runTest {
        rows = cachedRows(257)
        afterPage = { ledger = ledger.copy(sourceRevision = 2) }
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Success)
        coVerify(exactly = 1) { dao.completeReconcile(model.id, 1, any()) }
        coVerify(exactly = 0) { dao.completeExactWork(any(), any()) }
        assertEquals(2, ledger.sourceRevision)
    }

    @Test
    fun unavailableProviderDoesNotCompleteRequiredEmbeddings() = runTest {
        rows = cachedRows(1).map { it.copy(embeddingId = null) }
        every { settings.apiKeys } returns flowOf(emptyList())
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Failure)
        coVerify(exactly = 0) { dao.completeReconcile(any(), any(), any()) }
    }

    @Test
    fun filteredPageStillAdvancesBeforeAnEligiblePage() = runTest {
        rows = cachedRows(129)
        coEvery { dao.getReconcileMessagesByIds(any(), any(), any(), any()) } answers {
            val ids = thirdArg<List<String>>()
            rows.filter { it.id in ids && it.id == rows.last().id }
        }
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Success)
        assertEquals(3, pageReads)
        coVerify(exactly = 1) { dao.completeReconcile(model.id, 1, any()) }
    }

    @Test
    fun brokenIdentityPageFailsWithoutCompletingOrLooping() = runTest {
        coEvery { dao.getFirstReconcileMessageIdsPage(any()) } returns listOf("b", "a")
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Failure)
        coVerify(exactly = 0) { dao.completeReconcile(any(), any(), any()) }
        coVerify(exactly = 0) { dao.getReconcileMessagesByIds(any(), any(), any(), any()) }
    }

    @Test
    fun emptyLegacyIdAndSqliteUnicodeOrderCompleteAcrossPageBoundary() = runTest {
        val ids = listOf("") + List(126) { "a%03d".format(it) } +
            listOf("\uE000", "\uD800\uDC00")
        rows = cachedRows(ids.size).mapIndexed { index, row -> row.copy(id = ids[index]) }
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Success)
        assertEquals(3, pageReads)
        coVerify(exactly = 1) { dao.getFirstReconcileMessageIdsPage(128) }
        coVerify(exactly = 1) { dao.getReconcileMessageIdsPage("\uE000", 128) }
        coVerify(exactly = 1) { dao.completeReconcile(model.id, 1, any()) }
    }

    @Test
    fun repeatedPageFailsWithoutCompletingOrLooping() = runTest {
        rows = cachedRows(128)
        coEvery { dao.getReconcileMessageIdsPage(any(), any()) } returns rows.map { it.id }
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Failure)
        coVerify(exactly = 0) { dao.completeReconcile(any(), any(), any()) }
        coVerify(exactly = 1) { dao.getReconcileMessagesByIds(any(), any(), any(), any()) }
    }

    private fun worker(): EmbeddingCacheWorker {
        val updater = mockk<ProgressUpdater>()
        every { updater.updateProgress(any(), any(), any()) } answers {
            publications++
            SettableFuture.create<Void>().apply { set(null) }
        }
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.id } returns UUID.randomUUID()
        every { params.workerContext } returns Dispatchers.Unconfined
        every { params.progressUpdater } returns updater
        return EmbeddingCacheWorker(ApplicationProvider.getApplicationContext<Context>(), params)
    }

    private fun cachedRows(size: Int) = List(size) { index ->
        ReconcileIndexableMessage(
            id = index.toString().padStart(5, '0'),
            sourcePrefix = "Cached searchable text $index".toByteArray(Charsets.UTF_8),
            embeddingId = index.toLong() + 1, embeddingFingerprint = null,
            dimension = 3, embeddingBytes = 12,
        )
    }
}
