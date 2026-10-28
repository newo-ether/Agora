package com.newoether.agora.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Operation
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.data.EmbeddingCacheLocks
import com.google.common.util.concurrent.SettableFuture
import com.newoether.agora.data.local.EmbeddingCacheCounts
import com.newoether.agora.data.local.SemanticIndexLedgerEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.service.EmbeddingCacheWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RagManagerTest {
    private val model = EmbeddingModelConfig(id = "model", name = "Model", type = EmbeddingModelType.REMOTE)
    private val models = MutableStateFlow(listOf(model))
    private val work = MutableStateFlow<List<WorkInfo>>(emptyList())
    private val repository = mockk<ConversationRepository>()
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val ownerJob = SupervisorJob()
    private val scope = CoroutineScope(ownerJob + Dispatchers.Default)
    private lateinit var manager: RagManager

    @Before
    fun setUp() {
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any()) } returns workManager
        every { workManager.getWorkInfosForUniqueWorkFlow(any()) } returns work
        every { settings.embeddingModels } returns models
        every { settings.activeEmbeddingModelId } returns MutableStateFlow(model.id)
        coEvery { settings.awaitInitialLoad() } returns Unit
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers {
            counts(firstArg())
        }
        coEvery { repository.getSemanticLedgers(any()) } returns listOf(
            SemanticIndexLedgerEntity(model.id, state = SemanticIndexLedgerEntity.STATE_CURRENT, updatedAt = 0),
        )
        manager = RagManager(repository, settings, ApplicationProvider.getApplicationContext(), scope) {}
    }

    @After
    fun tearDown() = runBlocking {
        ownerJob.cancelAndJoin()
        unmockkAll()
    }

    @Test
    fun constructorDoesNotCountAndLoadingIsOwnedByAnActiveQuery() = runBlocking {
        assertTrue(manager.cacheRows.value.isEmpty())
        coVerify(exactly = 0) { repository.getEmbeddingCacheCounts(any()) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Int>()
        coEvery { repository.getEmbeddingCacheCounts(any()) } coAnswers {
            entered.complete(Unit)
            counts(firstArg(), total = release.await())
        }
        manager.loadCacheCounts()
        withTimeout(5_000) { entered.await() }
        assertEquals(EmbeddingCacheRowPhase.LOADING, row().phase)
        release.complete(10)
        val known = awaitRow { it.cached == 4 }
        assertEquals(EmbeddingCacheRowPhase.CACHE, known.phase)
        assertFalse(known.countLoading)
    }

    @Test
    fun everyUnfinishedWorkStateReadsAsCachingAndOnlyRunningProjectsProgress() = runBlocking {
        manager.loadCacheCounts()
        awaitRow { it.cached == 4 }
        // Waiting for WorkManager to start the worker is part of caching: a row that claims "not
        // cached" while its work is enqueued makes pressing Cache look like it did nothing.
        for (state in WorkInfo.State.entries.filter { !it.isFinished }) {
            work.value = listOf(info(state))
            val active = awaitRow { it.workActive }
            assertEquals(EmbeddingCacheRowPhase.CACHING, active.phase)
            assertNull(active.progress)
        }
        for (state in WorkInfo.State.entries.filter { it.isFinished }) {
            work.value = listOf(info(WorkInfo.State.RUNNING))
            awaitRow { it.workActive }
            work.value = listOf(info(state))
            assertEquals(EmbeddingCacheRowPhase.CACHE, awaitRow { !it.workActive }.phase)
        }
    }

    @Test
    fun legacyInspectionPayloadIsIgnoredAndExactProgressDoesNotReplaceCounts() = runBlocking {
        manager.loadCacheCounts()
        awaitRow { it.cached == 4 }
        work.value = listOf(info(WorkInfo.State.RUNNING, progress("RECONCILE")))
        assertNull(awaitRow { it.workActive }.progress)
        work.value = listOf(info(WorkInfo.State.RUNNING, progress("EXACT")))
        val running = awaitRow { it.progress != null }
        assertEquals(7, running.progress?.processed)
        assertEquals(4, running.cached)
        assertEquals(10, running.indexableTotal)
    }

    @Test
    fun firstFailureHasOneReplacementAndExplicitPageEntryCanTryAgain() = runBlocking {
        val calls = AtomicInteger()
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers {
            calls.incrementAndGet()
            error("count unavailable")
        }
        manager.loadCacheCounts()
        val failed = awaitRow { it.countFailed }
        assertEquals(2, calls.get())
        assertNull(failed.phase)
        assertNull(failed.cached)
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers { counts(firstArg()) }
        manager.loadCacheCounts()
        assertEquals(EmbeddingCacheRowPhase.CACHE, awaitRow { it.cached == 4 }.phase)
    }

    @Test
    fun replacementCanSucceedWithoutPublishingZero() = runBlocking {
        val calls = AtomicInteger()
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers {
            if (calls.incrementAndGet() == 1) error("first count unavailable")
            counts(firstArg())
        }
        manager.loadCacheCounts()
        assertEquals(4, awaitRow { it.cached != null }.cached)
        assertEquals(2, calls.get())
    }

    @Test
    fun laterFailureRetainsCompleteSnapshotWithoutReplacement() = runBlocking {
        manager.loadCacheCounts()
        awaitRow { it.cached == 4 }
        val calls = AtomicInteger()
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers {
            calls.incrementAndGet()
            error("count unavailable")
        }
        manager.loadCacheCounts()
        val retained = awaitRow { it.countFailed }
        assertEquals(1, calls.get())
        assertEquals(4, retained.cached)
        assertEquals(10, retained.indexableTotal)
        assertEquals(EmbeddingCacheRowPhase.CACHE, retained.phase)
    }

    @Test
    fun countFinishingDuringRunningWorkCannotReplaceProgress() = runBlocking {
        val release = CompletableDeferred<Int>()
        coEvery { repository.getEmbeddingCacheCounts(any()) } coAnswers {
            counts(firstArg(), total = release.await())
        }
        manager.loadCacheCounts()
        awaitRow { it.countLoading }
        work.value = listOf(info(WorkInfo.State.RUNNING))
        awaitRow { it.workActive }
        release.complete(10)
        assertEquals(EmbeddingCacheRowPhase.CACHING, awaitRow { it.cached == 4 }.phase)
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers { counts(firstArg(), cached = 10) }
        work.value = listOf(info(WorkInfo.State.SUCCEEDED))
        assertEquals(EmbeddingCacheRowPhase.RECACHE, awaitRow { it.cached == 10 }.phase)
    }

    @Test
    fun removedModelsCannotBeRepopulatedByAnOldCountResult() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Int>()
        coEvery { repository.getEmbeddingCacheCounts(any()) } coAnswers {
            entered.complete(Unit)
            counts(firstArg(), total = release.await())
        }
        manager.loadCacheCounts()
        withTimeout(5_000) { entered.await() }
        models.value = emptyList()
        manager.loadCacheCounts()
        withTimeout(5_000) { manager.cacheRows.first { it.isEmpty() } }
        val refresh = manager.javaClass.getDeclaredField("cacheCountRefreshJob").apply {
            isAccessible = true
        }.get(manager) as Job
        release.complete(10)
        withTimeout(5_000) { refresh.join() }
        assertTrue(manager.cacheRows.value.isEmpty())
    }

    @Test
    fun cancellingTheOwnerStopsLoadingWithoutFailureOrReplacement() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        coEvery { repository.getEmbeddingCacheCounts(any()) } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        manager.loadCacheCounts()
        withTimeout(5_000) { entered.await() }
        ownerJob.cancelAndJoin()
        assertFalse(row().countLoading)
        assertFalse(row().countFailed)
        assertNull(row().phase)
        coVerify(exactly = 1) { repository.getEmbeddingCacheCounts(any()) }
    }

    private fun row() = checkNotNull(manager.cacheRows.value[model.id])

    @Test
    fun repeatedPageRequestsShareOneCountButCompletionGetsOneSuccessor() = runBlocking {
        mockkObject(EmbeddingCacheWorker.Companion)
        val loaded = Channel<Unit>(Channel.UNLIMITED)
        coEvery { EmbeddingCacheWorker.repairLegacyChain(any(), any()) } coAnswers { loaded.send(Unit) }
        val release = CompletableDeferred<Int>()
        val calls = AtomicInteger()
        coEvery { repository.getEmbeddingCacheCounts(any()) } coAnswers {
            counts(firstArg(), total = if (calls.incrementAndGet() == 1) release.await() else 10)
        }
        manager.loadCacheCounts()
        awaitRow { it.countLoading }
        repeat(20) { manager.loadCacheCounts() }
        withTimeout(5_000) { repeat(21) { loaded.receive() } }
        assertNull(field("pendingRefreshModels"))
        assertEquals(1, calls.get())
        work.value = listOf(info(WorkInfo.State.RUNNING))
        awaitRow { it.workActive }
        val observation = field("cacheWorkObservationJob") as Job
        work.value = listOf(info(WorkInfo.State.SUCCEEDED))
        awaitRow { !it.workActive }
        observation.cancelAndJoin()
        assertNotNull(field("pendingRefreshModels"))
        val firstRefresh = field("cacheCountRefreshJob") as Job
        release.complete(10)
        withTimeout(5_000) { firstRefresh.join() }
        withTimeout(5_000) { (field("cacheCountRefreshJob") as Job?)?.join() }
        assertEquals(2, calls.get())
    }

    @Test
    fun modelSetChangeDuringCountingLoadsOnlyTheLatestSetAfterward() = runBlocking {
        mockkObject(EmbeddingCacheWorker.Companion)
        val loaded = Channel<Unit>(Channel.UNLIMITED)
        coEvery { EmbeddingCacheWorker.repairLegacyChain(any(), any()) } coAnswers { loaded.send(Unit) }
        val release = CompletableDeferred<Int>()
        val calls = AtomicInteger()
        coEvery { repository.getEmbeddingCacheCounts(any()) } coAnswers {
            counts(firstArg(), total = if (calls.incrementAndGet() == 1) release.await() else 10)
        }
        manager.loadCacheCounts()
        awaitRow { it.countLoading }
        withTimeout(5_000) { loaded.receive() }
        models.value = listOf(model.copy(id = "replacement"))
        manager.loadCacheCounts()
        withTimeout(5_000) { loaded.receive() }
        release.complete(10)
        val rows = withTimeout(5_000) { manager.cacheRows.first { it["replacement"]?.cached == 4 } }
        assertEquals(setOf("replacement"), rows.keys)
        assertEquals(2, calls.get())
    }

    @Test
    fun backgroundWorkTransitionsDoNotRestartAnExhaustedInitialCount() = runBlocking {
        val calls = AtomicInteger()
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers {
            calls.incrementAndGet()
            error("count unavailable")
        }
        manager.loadCacheCounts()
        awaitRow { it.countFailed }
        (field("cacheCountRefreshJob") as Job?)?.join()
        work.value = listOf(info(WorkInfo.State.RUNNING))
        awaitRow { it.workActive }
        val observation = field("cacheWorkObservationJob") as Job
        work.value = listOf(info(WorkInfo.State.FAILED))
        assertNull(awaitRow { !it.workActive }.phase)
        observation.cancelAndJoin()
        assertEquals(2, calls.get())
        assertNull(field("cacheCountRefreshJob"))
    }

    @Test
    fun manualSchedulingWaitAllowsDeletionAndCannotRecreateModelState() = runBlocking {
        val scheduled = CompletableDeferred<Unit>()
        val operationResult = SettableFuture.create<Operation.State.SUCCESS>()
        val operation = mockk<Operation>()
        every { operation.result } returns operationResult
        every { workManager.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>()) } answers {
            scheduled.complete(Unit)
            operation
        }
        coEvery { repository.getOrAdmitSemanticLedgerState(model.id) } returns SemanticIndexLedgerEntity.STATE_PENDING
        coEvery { repository.deleteSemanticModel(model.id) } returns Unit
        val deleted = CompletableDeferred<Unit>()
        coEvery { settings.saveEmbeddingModels(any()) } coAnswers {
            models.value = firstArg()
            deleted.complete(Unit)
        }
        val beforeScheduling = ownerJob.children.toSet()
        manager.cacheMessagesForModel(model.id)
        withTimeout(5_000) { scheduled.await() }
        withTimeout(5_000) { EmbeddingCacheLocks.forModel(model.id).withLock {} }
        val scheduling = ownerJob.children.filter { it !in beforeScheduling }.toList()
        manager.deleteEmbeddingModel(model.id)
        withTimeout(5_000) { deleted.await() }
        operationResult.set(Operation.SUCCESS)
        withTimeout(5_000) { scheduling.forEach { it.join() } }
        assertTrue(models.value.isEmpty())
        assertTrue(manager.cacheRows.value.isEmpty())
        assertTrue((field("scheduledCacheWorkIds") as Map<*, *>).isEmpty())
        coVerify(exactly = 1) { repository.getOrAdmitSemanticLedgerState(model.id) }
        coVerify(exactly = 0) { repository.getEmbeddingCacheCounts(any()) }
    }

    @Test
    fun automaticAdmissionDoesNotHoldModelLockWhileScheduling() = runBlocking {
        mockkObject(EmbeddingCacheWorker.Companion)
        val scheduled = CompletableDeferred<Unit>()
        coEvery { EmbeddingCacheWorker.schedule(any(), any(), any()) } coAnswers {
            scheduled.complete(Unit)
            awaitCancellation()
        }
        coEvery { repository.getOrAdmitSemanticLedgerState(model.id) } returns SemanticIndexLedgerEntity.STATE_PENDING
        coEvery { settings.getAutoCacheEnabled() } returns true
        manager.startPostList()
        withTimeout(5_000) { scheduled.await() }
        withTimeout(5_000) { EmbeddingCacheLocks.forModel(model.id).withLock {} }
        coVerify(exactly = 0) { repository.getEmbeddingCacheCounts(any()) }
    }

    @Test
    fun recacheDuringSchedulingPersistsInvalidationAndUsesOneFollower() = runBlocking {
        val enqueued = Channel<Unit>(Channel.UNLIMITED)
        val firstResult = SettableFuture.create<Operation.State.SUCCESS>()
        val secondResult = SettableFuture.create<Operation.State.SUCCESS>().apply { set(Operation.SUCCESS) }
        val policies = CopyOnWriteArrayList<ExistingWorkPolicy>()
        every { workManager.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>()) } answers {
            policies += secondArg<ExistingWorkPolicy>()
            enqueued.trySend(Unit)
            val result = if (policies.size == 1) firstResult else secondResult
            mockk<Operation> { every { this@mockk.result } returns result }
        }
        coEvery { repository.getOrAdmitSemanticLedgerState(model.id) } returns SemanticIndexLedgerEntity.STATE_PENDING
        val invalidated = CompletableDeferred<Unit>()
        coEvery { repository.invalidateSemanticModel(model.id, any()) } coAnswers { invalidated.complete(Unit) }
        manager.cacheMessagesForModel(model.id)
        withTimeout(5_000) { enqueued.receive() }
        work.value = listOf(info(WorkInfo.State.RUNNING))
        manager.cacheMessagesForModel(model.id, recache = true)
        withTimeout(5_000) { invalidated.await() }
        withTimeout(5_000) { EmbeddingCacheLocks.forModel(model.id).withLock {} }
        firstResult.set(Operation.SUCCESS)
        withTimeout(5_000) { enqueued.receive() }
        assertEquals(listOf(ExistingWorkPolicy.KEEP, ExistingWorkPolicy.APPEND_OR_REPLACE), policies)
        coVerify(exactly = 1) { repository.invalidateSemanticModel(model.id, any()) }
    }

    @Test
    fun runningChainWithFollowerDoesNotEnqueueAnotherWorker() = runBlocking {
        coEvery { repository.getOrAdmitSemanticLedgerState(model.id) } returns SemanticIndexLedgerEntity.STATE_PENDING
        work.value = listOf(info(WorkInfo.State.RUNNING), info(WorkInfo.State.BLOCKED))
        manager.cacheMessagesForModel(model.id)
        awaitRow { it.cached == 4 }
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>())
        }
    }

    @Test
    fun invalidAggregateCannotBecomeACompleteSnapshot() = runBlocking {
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers { counts(firstArg(), cached = 11) }
        manager.loadCacheCounts()
        val failed = awaitRow { it.countFailed }
        assertNull(failed.cached)
        assertNull(failed.phase)
        coVerify(exactly = 2) { repository.getEmbeddingCacheCounts(any()) }
    }

    private fun counts(ids: List<String>, total: Int = 10, cached: Int = 4) =
        EmbeddingCacheCounts(total, ids.associateWith { cached })

    private fun field(name: String): Any? = manager.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(manager)

    private suspend fun awaitRow(predicate: (EmbeddingCacheRowSnapshot) -> Boolean) =
        withTimeout(5_000) {
            manager.cacheRows.first { rows -> rows[model.id]?.let(predicate) == true }.getValue(model.id)
        }

    private fun info(state: WorkInfo.State, data: Data = Data.EMPTY) =
        WorkInfo(UUID.randomUUID(), state, emptySet(), progress = data)

    private fun progress(kind: String) = workDataOf(
        EmbeddingCacheWorker.KEY_GENERATION_KIND to kind,
        EmbeddingCacheWorker.KEY_GENERATION_REVISION to 1L,
        EmbeddingCacheWorker.KEY_PROCESSED to 7,
        EmbeddingCacheWorker.KEY_WORK_TOTAL to 10,
        EmbeddingCacheWorker.KEY_REMAINING to 3,
        EmbeddingCacheWorker.KEY_PROGRESS_PERMILLE to 700,
    )
}
