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
import com.newoether.agora.data.local.SemanticConversationEntity
import com.newoether.agora.data.local.SemanticIndexDao
import com.newoether.agora.data.local.SemanticIndexLedgerEntity
import com.newoether.agora.data.local.SemanticMetadataDao
import com.newoether.agora.data.local.SemanticMetadataStateEntity
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
import org.junit.Assert.assertNull
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
    private val metadata = mockk<SemanticMetadataDao>()
    private val settings = mockk<SettingsManager>()
    private val model = EmbeddingModelConfig("model", "Test", EmbeddingModelType.REMOTE)
    private var ledger = SemanticIndexLedgerEntity(
        modelId = "model", sourceRevision = 1, reconcileRevision = 1, updatedAt = 1,
    )
    private var rows = emptyList<ReconcileIndexableMessage>()
    private var metadataComplete = true
    private var conversationRevision = 1L
    private var indexedRevision: Long? = null
    private var pageReads = 0
    private var publications = 0
    private var afterPage: () -> Unit = {}

    @Before
    fun setUp() {
        every { database.semanticIndexDao() } returns dao
        every { database.semanticMetadataDao() } returns metadata
        every { settings.embeddingModels } returns flowOf(listOf(model))
        every { settings.customProviders } returns flowOf(emptyList())
        coEvery { dao.getLedger(model.id) } answers { ledger }
        coEvery { dao.getWorkCountThroughRevision(model.id, any()) } returns 0
        coEvery { dao.markCurrentAfterExactWork(model.id, any(), any()) } returns 0
        coEvery { metadata.ensureMetadataState() } answers {
            SemanticMetadataStateEntity(complete = metadataComplete)
        }
        coEvery { metadata.getDirtyConversations(model.id, any(), any()) } answers {
            val after = secondArg<String?>()
            if (indexedRevision != conversationRevision && (after == null || after < "root")) {
                listOf(SemanticConversationEntity("root", conversationRevision, rows.size, true))
            } else {
                emptyList()
            }
        }
        coEvery { metadata.getConversationSourceIds("root", any(), any()) } answers {
            pageReads++
            val after = secondArg<String?>()
            val ids = rows.map { it.id }
            val page = if (after == null) ids.take(thirdArg()) else {
                ids.drop(ids.indexOf(after) + 1).take(thirdArg())
            }
            afterPage()
            page
        }
        coEvery { dao.getReconcileMessagesByIds(any(), any(), any(), any()) } answers {
            val ids = thirdArg<List<String>>()
            rows.filter { it.id in ids }
        }
        coEvery { metadata.markConversationIndexed(model.id, "root", any(), any()) } answers {
            val matches = thirdArg<Long>() == conversationRevision &&
                arg<Long>(3) == ledger.reconcileRevision
            if (matches) indexedRevision = conversationRevision
            matches
        }
        coEvery { dao.completeReconcile(any(), any(), any()) } answers {
            val matches = secondArg<Long>() == ledger.reconcileRevision &&
                indexedRevision == conversationRevision
            if (matches) {
                ledger = ledger.copy(state = SemanticIndexLedgerEntity.STATE_CURRENT,
                    completedRevision = ledger.sourceRevision)
            }
            matches
        }
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun cachedConversationReadsToExhaustionWithoutInspectionProgressWrites() = runTest {
        rows = cachedRows(257)
        assertEquals(Result.success(androidx.work.workDataOf(EmbeddingCacheWorker.KEY_FAILED to 0)),
            worker().cacheModel(model.id, database, settings))
        assertEquals(4, pageReads)
        assertEquals(0, publications)
        assertEquals(1L, indexedRevision)
        coVerify(exactly = 0) { dao.upsertEmbedding(any()) }
        coVerify(exactly = 1) { dao.completeReconcile(model.id, 1, any()) }
    }

    @Test
    fun emptyConversationCompletesWithoutReadingSources() = runTest {
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Success)
        assertEquals(0, pageReads)
        assertEquals(0, publications)
        coVerify(exactly = 1) { metadata.markConversationIndexed(model.id, "root", 1, 1) }
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
        assertNull(indexedRevision)
        coVerify(exactly = 0) { metadata.markConversationIndexed(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.completeReconcile(any(), any(), any()) }
    }

    @Test
    fun cancellationLeavesConversationDirtyAndNewWorkerCanReplay() = runTest {
        rows = cachedRows(257)
        afterPage = { throw kotlinx.coroutines.CancellationException("Process stopped") }
        try {
            worker().cacheModel(model.id, database, settings)
            org.junit.Assert.fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertNull(indexedRevision)
            coVerify(exactly = 0) { dao.completeReconcile(any(), any(), any()) }
        }
        afterPage = {}
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Success)
        coVerify(exactly = 1) { dao.completeReconcile(model.id, 1, any()) }
    }

    @Test
    fun mutationBehindCursorKeepsConversationDirtyAndRetriesWithoutCompletingOldRevision() = runTest {
        rows = cachedRows(257)
        afterPage = {
            ledger = ledger.copy(sourceRevision = 2)
            conversationRevision = 2
        }
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Retry)
        assertNull(indexedRevision)
        coVerify(exactly = 1) { metadata.markConversationIndexed(model.id, "root", 1, 1) }
        coVerify(exactly = 0) { dao.completeReconcile(any(), any(), any()) }
        coVerify(exactly = 0) { dao.completeExactWork(any(), any()) }
        assertEquals(2L, ledger.sourceRevision)
    }

    @Test
    fun unavailableProviderDoesNotCompleteRequiredEmbeddings() = runTest {
        rows = cachedRows(1).map { it.copy(embeddingId = null) }
        every { settings.apiKeys } returns flowOf(emptyList())
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Failure)
        assertNull(indexedRevision)
        coVerify(exactly = 0) { dao.completeReconcile(any(), any(), any()) }
    }

    @Test
    fun filteredSourcePageStillAdvancesBeforeAnEligiblePage() = runTest {
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
    fun brokenSourceIdentityPageFailsWithoutCompletingOrLooping() = runTest {
        rows = cachedRows(2)
        coEvery { metadata.getConversationSourceIds("root", null, any()) } returns listOf("b", "a")
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Failure)
        coVerify(exactly = 0) { metadata.markConversationIndexed(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.completeReconcile(any(), any(), any()) }
        coVerify(exactly = 0) { dao.getReconcileMessagesByIds(any(), any(), any(), any()) }
    }

    @Test
    fun emptyLegacyIdAndSqliteUnicodeOrderCompleteAcrossSourcePageBoundary() = runTest {
        val ids = listOf("") + List(126) { "a%03d".format(it) } +
            listOf("\uE000", "\uD800\uDC00")
        rows = cachedRows(ids.size).mapIndexed { index, row -> row.copy(id = ids[index]) }
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Success)
        assertEquals(3, pageReads)
        coVerify(exactly = 1) { metadata.getConversationSourceIds("root", null, 128) }
        coVerify(exactly = 1) { metadata.getConversationSourceIds("root", "\uE000", 128) }
        coVerify(exactly = 1) { dao.completeReconcile(model.id, 1, any()) }
    }

    @Test
    fun repeatedSourcePageFailsWithoutCompletingOrLooping() = runTest {
        rows = cachedRows(128)
        coEvery { metadata.getConversationSourceIds("root", rows.last().id, any()) } returns rows.map { it.id }
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Failure)
        coVerify(exactly = 0) { metadata.markConversationIndexed(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.completeReconcile(any(), any(), any()) }
        coVerify(exactly = 1) { dao.getReconcileMessagesByIds(any(), any(), any(), any()) }
    }

    @Test
    fun pendingWithoutExactWorkStillReconcilesDirtyConversations() = runTest {
        ledger = ledger.copy(state = SemanticIndexLedgerEntity.STATE_PENDING)
        rows = cachedRows(1)
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Success)
        assertEquals(2, pageReads)
        assertEquals(SemanticIndexLedgerEntity.STATE_CURRENT, ledger.state)
        coVerify(exactly = 1) { dao.markCurrentAfterExactWork(model.id, 1, any()) }
        coVerify(exactly = 1) { metadata.markConversationIndexed(model.id, "root", 1, 1) }
    }

    @Test
    fun incompleteMetadataRetriesBeforeReadingWorkOrConversationSources() = runTest {
        metadataComplete = false
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Retry)
        assertEquals(0, pageReads)
        coVerify(exactly = 0) { dao.getWorkCountThroughRevision(any(), any()) }
        coVerify(exactly = 0) { metadata.getDirtyConversations(any(), any(), any()) }
        coVerify(exactly = 0) { dao.completeReconcile(any(), any(), any()) }
    }

    @Test
    fun currentLedgerDoesNotReadWorkOrDirtyConversations() = runTest {
        ledger = ledger.copy(state = SemanticIndexLedgerEntity.STATE_CURRENT, completedRevision = 1)
        assertTrue(worker().cacheModel(model.id, database, settings) is Result.Success)
        coVerify(exactly = 0) { dao.getWorkCountThroughRevision(any(), any()) }
        coVerify(exactly = 0) { metadata.getDirtyConversations(any(), any(), any()) }
        assertEquals(0, publications)
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
