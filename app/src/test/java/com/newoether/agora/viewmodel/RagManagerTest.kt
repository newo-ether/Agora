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
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.SemanticMetadataDao
import com.newoether.agora.data.local.EmbeddingCacheCounts
import com.newoether.agora.data.local.SemanticIndexLedgerEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.service.EmbeddingCacheWorker
import com.newoether.agora.service.SemanticMetadataWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
    private val reminders = Channel<Unit>(Channel.UNLIMITED)
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
        every { repository.database } returns null
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers {
            counts(firstArg())
        }
        coEvery { repository.getSemanticLedgers(any()) } returns listOf(
            SemanticIndexLedgerEntity(model.id, state = SemanticIndexLedgerEntity.STATE_CURRENT, updatedAt = 0),
        )
        manager = RagManager(repository, settings, ApplicationProvider.getApplicationContext(), scope) {
            reminders.send(Unit)
        }
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
        openPage()
        withTimeout(5_000) { entered.await() }
        assertEquals(EmbeddingCacheRowPhase.LOADING, row().phase)
        release.complete(10)
        val known = awaitRow { it.cached == 4 }
        assertEquals(EmbeddingCacheRowPhase.CACHE, known.phase)
        assertFalse(known.countLoading)
    }

    @Test
    fun everyUnfinishedWorkStateReadsAsCachingAndOnlyRunningProjectsProgress() = runBlocking {
        val observed = recordWorkObservations()
        openPage()
        awaitRow { it.cached == 4 }
        // Waiting for WorkManager to start the worker is part of caching: a row that claims "not
        // cached" while its work is enqueued makes pressing Cache look like it did nothing.
        for (state in WorkInfo.State.entries.filter { !it.isFinished }) {
            publishWork(listOf(info(state)), observed)
            assertEquals(EmbeddingCacheRowPhase.CACHING, row().phase)
            assertNull(row().progress)
        }
        for (state in WorkInfo.State.entries.filter { it.isFinished }) {
            publishWork(listOf(info(WorkInfo.State.RUNNING)), observed)
            publishWork(listOf(info(state)), observed)
            assertEquals(EmbeddingCacheRowPhase.CACHE, row().phase)
        }
    }

    @Test
    fun legacyInspectionPayloadIsIgnoredAndExactProgressDoesNotReplaceCounts() = runBlocking {
        val observed = recordWorkObservations()
        openPage()
        awaitRow { it.cached == 4 }
        publishWork(listOf(info(WorkInfo.State.RUNNING, progress("RECONCILE"))), observed)
        assertNull(row().progress)
        publishWork(listOf(info(WorkInfo.State.RUNNING, progress("EXACT"))), observed)
        val running = row()
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
        openPage()
        val failed = awaitRow { it.countFailed }
        awaitCountIdle()
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
        openPage()
        assertEquals(4, awaitRow { it.cached != null }.cached)
        assertEquals(2, calls.get())
    }

    @Test
    fun laterFailureRetainsCompleteSnapshotWithoutReplacement() = runBlocking {
        openPage()
        awaitRow { it.cached == 4 }
        awaitCountIdle()
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
        val observed = recordWorkObservations()
        val release = CompletableDeferred<Int>()
        coEvery { repository.getEmbeddingCacheCounts(any()) } coAnswers {
            counts(firstArg(), total = release.await())
        }
        openPage()
        awaitRow { it.countLoading }
        publishWork(listOf(info(WorkInfo.State.RUNNING)), observed)
        release.complete(10)
        assertEquals(EmbeddingCacheRowPhase.CACHING, awaitRow { it.cached == 4 }.phase)
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers { counts(firstArg(), cached = 10) }
        publishWork(listOf(info(WorkInfo.State.SUCCEEDED)), observed)
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
        openPage()
        withTimeout(5_000) { entered.await() }
        models.value = emptyList()
        manager.loadCacheCounts()
        withTimeout(5_000) { manager.cacheRows.first { it.isEmpty() } }
        val refresh = field("cacheCountRefreshJob") as Job
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
        openPage()
        withTimeout(5_000) { entered.await() }
        ownerJob.cancelAndJoin()
        assertFalse(row().countLoading)
        assertFalse(row().countFailed)
        assertNull(row().phase)
        coVerify(exactly = 1) { repository.getEmbeddingCacheCounts(any()) }
    }

    @Test
    fun repeatedPageRequestsShareOneCountButCompletionGetsOneSuccessor() = runBlocking {
        mockkObject(EmbeddingCacheWorker.Companion)
        val observed = recordWorkObservations()
        val loaded = Channel<Unit>(Channel.UNLIMITED)
        coEvery { EmbeddingCacheWorker.repairLegacyChain(any(), any()) } coAnswers { loaded.send(Unit) }
        val release = CompletableDeferred<Int>()
        val calls = AtomicInteger()
        coEvery { repository.getEmbeddingCacheCounts(any()) } coAnswers {
            counts(firstArg(), total = if (calls.incrementAndGet() == 1) release.await() else 10)
        }
        openPage()
        awaitRow { it.countLoading }
        repeat(20) { manager.loadCacheCounts() }
        withTimeout(5_000) { repeat(21) { loaded.receive() } }
        assertNull(field("pendingRefreshModels"))
        assertEquals(1, calls.get())
        publishWork(listOf(info(WorkInfo.State.RUNNING)), observed)
        publishWork(listOf(info(WorkInfo.State.SUCCEEDED)), observed)
        assertNotNull(field("pendingRefreshModels"))
        release.complete(10)
        awaitCountIdle()
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
        openPage()
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
        val observed = recordWorkObservations()
        val calls = AtomicInteger()
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers {
            calls.incrementAndGet()
            error("count unavailable")
        }
        openPage()
        awaitRow { it.countFailed }
        awaitCountIdle()
        publishWork(listOf(info(WorkInfo.State.RUNNING)), observed)
        publishWork(listOf(info(WorkInfo.State.FAILED)), observed)
        assertNull(row().phase)
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
        coVerify(exactly = 0) { repository.getEmbeddingCacheCounts(any()) }
    }

    @Test
    fun runningChainWithFollowerDoesNotEnqueueAnotherWorker() = runBlocking {
        coEvery { repository.getOrAdmitSemanticLedgerState(model.id) } returns SemanticIndexLedgerEntity.STATE_PENDING
        work.value = listOf(info(WorkInfo.State.RUNNING), info(WorkInfo.State.BLOCKED))
        val before = ownerJob.children.toSet()
        manager.cacheMessagesForModel(model.id)
        awaitNewOwnerChildren(before)
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>())
        }
        coVerify(exactly = 1) { repository.getOrAdmitSemanticLedgerState(model.id) }
        coVerify(exactly = 0) { repository.getEmbeddingCacheCounts(any()) }
    }

    @Test
    fun invalidAggregateCannotBecomeACompleteSnapshot() = runBlocking {
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers { counts(firstArg(), cached = 11) }
        openPage()
        val failed = awaitRow { it.countFailed }
        assertNull(failed.cached)
        assertNull(failed.phase)
        coVerify(exactly = 2) { repository.getEmbeddingCacheCounts(any()) }
    }

    @Test
    fun requestsWithoutAnyPresentationDoNotObserveWorkOrCount() = runBlocking {
        work.value = listOf(info(WorkInfo.State.RUNNING))
        val before = ownerJob.children.toSet()
        manager.loadCacheCounts()
        awaitNewOwnerChildren(before)
        work.value = listOf(info(WorkInfo.State.SUCCEEDED))
        assertEquals(0, work.subscriptionCount.value)
        assertTrue(manager.cacheRows.value.isEmpty())
        verify(exactly = 0) { workManager.getWorkInfosForUniqueWorkFlow(any()) }
        coVerify(exactly = 0) { repository.getEmbeddingCacheCounts(any()) }
    }

    @Test
    fun pageExitCancelsBlockedCountAndDropsItsPendingSuccessor() = runBlocking {
        val observed = recordWorkObservations()
        val page = openPage()
        awaitRow { it.cached == 4 }
        awaitCountIdle()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { repository.getEmbeddingCacheCounts(any()) } coAnswers {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        manager.loadCacheCounts()
        withTimeout(5_000) { entered.await() }
        val scan = field("cacheCountRefreshJob") as Job
        publishWork(listOf(info(WorkInfo.State.RUNNING)), observed)
        publishWork(listOf(info(WorkInfo.State.SUCCEEDED)), observed)
        assertNotNull(field("pendingRefreshModels"))
        page.cancelAndJoin()
        withTimeout(5_000) {
            cancelled.await()
            scan.join()
            work.subscriptionCount.first { it == 0 }
        }
        assertTrue(ownerJob.isActive)
        assertTrue(scan.isCancelled)
        assertFalse(row().countLoading)
        assertFalse(row().countFailed)
        assertEquals(4, row().cached)
        assertEquals(10, row().indexableTotal)
        assertNull(field("pendingRefreshModels"))
        coVerify(exactly = 2) { repository.getEmbeddingCacheCounts(any()) }
    }

    @Test
    fun leavingPageRetainsCountsAndReentryLoadsLatestSnapshotOnce() = runBlocking {
        val calls = AtomicInteger()
        val cached = AtomicInteger(4)
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers {
            calls.incrementAndGet()
            counts(firstArg(), cached = cached.get())
        }
        val firstPage = openPage()
        awaitRow { it.cached == 4 }
        awaitCountIdle()
        firstPage.cancelAndJoin()
        withTimeout(5_000) { work.subscriptionCount.first { it == 0 } }
        assertEquals(4, row().cached)
        assertEquals(10, row().indexableTotal)
        assertFalse(row().countLoading)

        cached.set(7)
        work.value = listOf(info(WorkInfo.State.RUNNING))
        work.value = listOf(info(WorkInfo.State.SUCCEEDED))
        val before = ownerJob.children.toSet()
        manager.loadCacheCounts()
        awaitNewOwnerChildren(before)
        assertEquals(1, calls.get())
        assertEquals(4, row().cached)

        val secondPage = openPage()
        awaitRow { it.cached == 7 }
        awaitCountIdle()
        assertEquals(2, calls.get())
        assertEquals(10, row().indexableTotal)
        secondPage.cancelAndJoin()
    }

    @Test
    fun onlyLastPresentationExitCancelsTheSharedBlockedScan() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { repository.getEmbeddingCacheCounts(any()) } coAnswers {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val firstPage = openPage()
        withTimeout(5_000) { entered.await() }
        val scan = field("cacheCountRefreshJob") as Job
        val secondPage = openPage()
        withTimeout(5_000) { work.subscriptionCount.first { it == 1 } }
        firstPage.cancelAndJoin()
        assertTrue(scan.isActive)
        assertFalse(cancelled.isCompleted)
        assertEquals(1, work.subscriptionCount.value)
        coVerify(exactly = 1) { repository.getEmbeddingCacheCounts(any()) }

        secondPage.cancelAndJoin()
        withTimeout(5_000) {
            cancelled.await()
            scan.join()
            work.subscriptionCount.first { it == 0 }
        }
        assertTrue(scan.isCancelled)
        assertTrue(ownerJob.isActive)
        coVerify(exactly = 1) { repository.getEmbeddingCacheCounts(any()) }
    }

    @Test
    fun completedWorkerWithUnfinishedFollowerDefersRefreshUntilChainIsIdle() = runBlocking {
        val observed = recordWorkObservations()
        val calls = AtomicInteger()
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers {
            calls.incrementAndGet()
            counts(firstArg())
        }
        openPage()
        awaitRow { it.cached == 4 }
        awaitCountIdle()
        val first = UUID.randomUUID()
        val follower = UUID.randomUUID()
        publishWork(listOf(
            info(WorkInfo.State.RUNNING, progress("EXACT"), first),
            info(WorkInfo.State.BLOCKED, id = follower),
        ), observed)
        publishWork(listOf(
            info(WorkInfo.State.SUCCEEDED, id = first),
            info(WorkInfo.State.RUNNING, progress("EXACT", processed = 3), follower),
        ), observed)
        assertTrue(row().workActive)
        assertEquals(3, row().progress?.processed)
        assertEquals(1, calls.get())
        assertNull(field("cacheCountRefreshJob"))

        publishWork(listOf(
            info(WorkInfo.State.SUCCEEDED, id = first),
            info(WorkInfo.State.SUCCEEDED, id = follower),
        ), observed)
        awaitCountIdle()
        assertFalse(row().workActive)
        assertEquals(2, calls.get())
    }

    @Test
    fun disabledAutoCacheReminderUsesLedgerWithoutPresentationCount() = runBlocking {
        coEvery { repository.getOrAdmitSemanticLedgerState(model.id) } returns SemanticIndexLedgerEntity.STATE_PENDING
        coEvery { settings.getAutoCacheEnabled() } returns false
        coEvery { settings.getShowUncachedNotification() } returns true
        val before = ownerJob.children.toSet()
        manager.startPostList()
        withTimeout(5_000) { reminders.receive() }
        awaitNewOwnerChildren(before)
        assertTrue(manager.cacheRows.value.isEmpty())
        coVerify(exactly = 1) { repository.getOrAdmitSemanticLedgerState(model.id) }
        coVerify(exactly = 0) { repository.getEmbeddingCacheCounts(any()) }
        verify(exactly = 0) { workManager.getWorkInfosForUniqueWorkFlow(any()) }
    }

    @Test
    fun pendingMetadataKeepsUnknownCountsLoadingUntilOneSummaryReload() = runBlocking {
        val ready = metadataReadiness()
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers {
            if (ready.value == true) counts(firstArg())
            else EmbeddingCacheCounts(0, firstArg<List<String>>().associateWith { 0 }, complete = false)
        }
        openPage()
        withTimeout(5_000) { ready.subscriptionCount.first { it == 1 } }
        val pending = row()
        assertTrue(pending.countLoading)
        assertFalse(pending.countFailed)
        assertNull(pending.cached)
        assertNull(pending.indexableTotal)
        assertEquals(EmbeddingCacheRowPhase.LOADING, pending.phase)
        coVerify(exactly = 1) { repository.getEmbeddingCacheCounts(any()) }
        coVerify(exactly = 1) { SemanticMetadataWorker.schedule(workManager) }
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>())
        }

        ready.value = true
        awaitRow { it.cached == 4 && !it.countLoading }
        awaitCountIdle()
        assertEquals(10, row().indexableTotal)
        assertFalse(row().countFailed)
        assertEquals(0, ready.subscriptionCount.value)
        coVerify(exactly = 2) { repository.getEmbeddingCacheCounts(any()) }
        coVerify(exactly = 1) { SemanticMetadataWorker.schedule(workManager) }
    }

    @Test
    fun lastPageExitCancelsMetadataWaitButRetainsSnapshotAndDurableBootstrap() = runBlocking {
        val firstPage = openPage()
        awaitRow { it.cached == 4 }
        awaitCountIdle()
        val ready = metadataReadiness()
        coEvery { repository.getEmbeddingCacheCounts(any()) } answers {
            if (ready.value == true) counts(firstArg(), cached = 7)
            else EmbeddingCacheCounts(0, firstArg<List<String>>().associateWith { 0 }, complete = false)
        }
        manager.loadCacheCounts()
        withTimeout(5_000) { ready.subscriptionCount.first { it == 1 } }
        val wait = field("cacheCountRefreshJob") as Job
        val secondPage = openPage()
        firstPage.cancelAndJoin()
        assertTrue(wait.isActive)
        assertTrue(row().countLoading)
        assertEquals(4, row().cached)
        assertEquals(10, row().indexableTotal)
        assertEquals(1, ready.subscriptionCount.value)

        secondPage.cancelAndJoin()
        withTimeout(5_000) {
            wait.join()
            ready.subscriptionCount.first { it == 0 }
            work.subscriptionCount.first { it == 0 }
        }
        assertTrue(wait.isCancelled)
        assertTrue(ownerJob.isActive)
        assertFalse(row().countLoading)
        assertFalse(row().countFailed)
        assertEquals(4, row().cached)
        assertEquals(10, row().indexableTotal)
        coVerify(exactly = 2) { repository.getEmbeddingCacheCounts(any()) }
        coVerify(exactly = 1) { SemanticMetadataWorker.schedule(workManager) }
        verify(exactly = 0) { workManager.cancelUniqueWork(SemanticMetadataWorker.UNIQUE_WORK_NAME) }

        ready.value = true
        val reopened = openPage()
        awaitRow { it.cached == 7 && !it.countLoading }
        awaitCountIdle()
        coVerify(exactly = 3) { repository.getEmbeddingCacheCounts(any()) }
        coVerify(exactly = 1) { SemanticMetadataWorker.schedule(workManager) }
        reopened.cancelAndJoin()
    }

    private fun metadataReadiness(): MutableStateFlow<Boolean?> {
        val ready = MutableStateFlow<Boolean?>(false)
        val database = mockk<ChatDatabase>()
        val metadata = mockk<SemanticMetadataDao>()
        every { repository.database } returns database
        every { database.semanticMetadataDao() } returns metadata
        every { metadata.observeMetadataComplete() } returns ready
        mockkObject(SemanticMetadataWorker.Companion)
        coEvery { SemanticMetadataWorker.schedule(workManager) } returns Unit
        return ready
    }

    private fun openPage(): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        manager.observeCachePresentation()
    }

    private fun recordWorkObservations(): Channel<List<WorkInfo>> {
        val observed = Channel<List<WorkInfo>>(Channel.UNLIMITED)
        every { workManager.getWorkInfosForUniqueWorkFlow(any()) } returns flow {
            work.collect { infos ->
                emit(infos)
                observed.send(infos)
            }
        }
        return observed
    }

    private suspend fun publishWork(infos: List<WorkInfo>, observed: Channel<List<WorkInfo>>) {
        work.value = infos
        withTimeout(5_000) {
            while (observed.receive() != infos) {
                // Wait for this transition's collector to finish.
            }
        }
    }

    private suspend fun awaitCountIdle() = withTimeout(5_000) {
        while (true) {
            val refresh = field("cacheCountRefreshJob") as Job? ?: break
            refresh.join()
        }
    }

    private suspend fun awaitNewOwnerChildren(before: Set<Job>) = withTimeout(5_000) {
        ownerJob.children.filter { it !in before }.toList().forEach { it.join() }
    }

    private fun row() = checkNotNull(manager.cacheRows.value[model.id])

    private fun counts(ids: List<String>, total: Int = 10, cached: Int = 4) =
        EmbeddingCacheCounts(total, ids.associateWith { cached })

    private fun field(name: String): Any? = manager.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(manager)

    private suspend fun awaitRow(predicate: (EmbeddingCacheRowSnapshot) -> Boolean) =
        withTimeout(5_000) {
            manager.cacheRows.first { rows -> rows[model.id]?.let(predicate) == true }.getValue(model.id)
        }

    private fun info(state: WorkInfo.State, data: Data = Data.EMPTY, id: UUID = UUID.randomUUID()) =
        WorkInfo(id, state, emptySet(), progress = data)

    private fun progress(kind: String, processed: Int = 7) = workDataOf(
        EmbeddingCacheWorker.KEY_GENERATION_KIND to kind,
        EmbeddingCacheWorker.KEY_GENERATION_REVISION to 1L,
        EmbeddingCacheWorker.KEY_PROCESSED to processed,
        EmbeddingCacheWorker.KEY_WORK_TOTAL to 10,
        EmbeddingCacheWorker.KEY_REMAINING to 10 - processed,
        EmbeddingCacheWorker.KEY_PROGRESS_PERMILLE to processed * 100,
    )
}
