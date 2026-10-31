package com.newoether.agora.automation

import android.content.Context
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunGraphCommit
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.phone.PhoneControlLease
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.AutomaticCompactContinuationRequest
import com.newoether.agora.viewmodel.BoundRunGenerationRequest
import com.newoether.agora.viewmodel.ContextCompactor
import com.newoether.agora.viewmodel.ConversationCompactController
import com.newoether.agora.viewmodel.ConversationGenerationState
import com.newoether.agora.viewmodel.GenerationCallbacks
import com.newoether.agora.viewmodel.GenerationExecutionResult
import com.newoether.agora.viewmodel.GenerationManager
import com.newoether.agora.viewmodel.GenerationTerminalSettlementController
import com.newoether.agora.viewmodel.StandardCompactLaunch
import com.newoether.agora.viewmodel.testGenerationAdmissionSnapshot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskGenerationTransitionsTest {
    @Before
    fun stubDiagnosticLogging() {
        mockkObject(DebugLog)
        every { DebugLog.d(any(), any()) } just Runs
        every { DebugLog.e(any(), any()) } just Runs
    }

    @After
    fun restoreDiagnosticLogging() {
        unmockkObject(DebugLog)
    }

    @Test
    fun cancellationDuringNormalFinalizingWaitsForLateCallbackAndClearsItsHandoff() = runBlocking {
        val fixture = Fixture()
        var task: Job? = null
        try {
            fixture.prepareCompact()
            task = fixture.start(this)
            fixture.runTask()
            val assistant = withTimeout(5_000L) { fixture.assistantReady.await() }
            assertEquals("Finalizing", fixture.state.runtimeTraceSnapshot().last().newState)
            val stopped = fixture.state.stop()
            assertNull(stopped.finalizationEffect)
            assertFalse(fixture.state.stopping.value)
            assertFalse(assistant.job.isCancelled)
            assertEquals(fixture.compactEpoch, fixture.lease.held.value?.epoch)
            assertEquals(assistant.identity.runId, fixture.lease.held.value?.owner?.runId)

            task.cancel(CancellationException("Task cancelled during terminal persistence"))
            fixture.runTask()
            withTimeout(5_000L) { fixture.assistantCancellation.await() }
            assertFalse(task.isCompleted)
            assertFalse(assistant.job.isCompleted)
            assertFalse(fixture.assistantCleanup.isCompleted)
            assertFalse(fixture.drainProcessed.isCompleted)
            assertEquals(0, fixture.finishes.count { it.token == assistant.identity.ownerToken })

            // The real BoundRun launcher publishes its callback only after this return gate opens.
            fixture.returnGeneration.complete(Unit)
            withTimeout(5_000L) {
                assistant.job.join()
                fixture.state.awaitGenerationSettled(assistant.identity.ownerToken)
                fixture.drainProcessed.await()
            }
            assertFalse(fixture.assistantCleanup.isCompleted)
            assertFalse(fixture.drainAllowed.await())
            // Retention must already come from the real late callback before Task cleanup runs.
            fixture.state.finishRunHandoff(assistant.identity.ownerToken, queuedDrainOnly = true)
            assertEquals(0, fixture.finishes.count { it.token == assistant.identity.ownerToken })
            fixture.runTask()
            fixture.awaitTask(task)
            assertTrue(task.isCancelled)
            assertTrue(assistant.job.isCancelled)
            assertTrue(assistant.job.isCompleted)
            assertFalse(fixture.state.generating.value)
            assertTrue(fixture.state.consumeQueueDrainPermission())
            assertNull(fixture.lease.held.value)
            assertEquals("run_ended", fixture.lease.recentReleases().single().reason)

            // One finish comes from the catch and one from the callback's pending request in finally.
            val cleanup = withTimeout(5_000L) { fixture.assistantCleanup.await() }
            assertTrue(cleanup.jobCompleted)
            assertEquals(2, fixture.finishes.count { it.token == assistant.identity.ownerToken })
            fixture.assertRetentionCleared(assistant.identity.ownerToken)
            fixture.assertOnlyOneAssistantLaunched()
        } finally {
            fixture.close(task)
        }
    }

    @Test
    fun cancellationAfterCallbackAndJobSettlementClearsHandoffBeforePersistenceSettlement() = runBlocking {
        val fixture = Fixture(deferPersistence = true)
        var task: Job? = null
        try {
            fixture.prepareCompact()
            task = fixture.start(this)
            fixture.runTask()
            val assistant = withTimeout(5_000L) { fixture.assistantReady.await() }
            fixture.returnGeneration.complete(Unit)
            withTimeout(5_000L) { assistant.job.join() }
            fixture.runTask()
            assertFalse(assistant.job.isCancelled)
            assertTrue(fixture.state.generating.value)
            assertFalse(fixture.drainProcessed.isCompleted)
            assertNotNull(fixture.lease.held.value)
            val finishedBeforeProbe = fixture.finishes.size
            fixture.state.finishRunHandoff(assistant.identity.ownerToken, queuedDrainOnly = true)
            assertEquals(finishedBeforeProbe, fixture.finishes.size)

            task.cancel(CancellationException("Task cancelled while awaiting durable settlement"))
            fixture.runTask()
            fixture.awaitTask(task)
            assertTrue(task.isCancelled)
            val cleanup = withTimeout(5_000L) { fixture.assistantCleanup.await() }
            assertTrue(cleanup.jobCompleted)
            assertNull(fixture.lease.held.value)
            assertEquals(2, fixture.finishes.count { it.token == assistant.identity.ownerToken })
            fixture.assertRetentionCleared(assistant.identity.ownerToken)

            assertTrue(fixture.state.finishRunFinalization(assistant.identity, true).accepted)
            withTimeout(5_000L) {
                fixture.state.awaitGenerationSettled(assistant.identity.ownerToken)
                fixture.drainProcessed.await()
            }
            assertFalse(fixture.state.generating.value)
            assertFalse(fixture.drainAllowed.await())
            assertTrue(fixture.state.consumeQueueDrainPermission())
            fixture.assertOnlyOneAssistantLaunched()
        } finally {
            fixture.close(task)
        }
    }

    @Test
    fun cancellationCleanupAfterSettlementPreservesNewOwnerAndItsRetention() = runBlocking {
        val fixture = Fixture()
        var task: Job? = null
        try {
            fixture.prepareCompact()
            task = fixture.start(this)
            fixture.runTask()
            val assistant = withTimeout(5_000L) { fixture.assistantReady.await() }
            val successorToken = AtomicReference<Long?>()
            fixture.afterDrain = {
                val token = requireNotNull(fixture.state.tryAcquireForReplacement())
                fixture.state.bindRun(token, "new-owner")
                fixture.state.retainRunHandoff(token)
                successorToken.set(token)
            }
            task.cancel(CancellationException("Task cancelled before successor admission"))
            fixture.runTask()
            withTimeout(5_000L) { fixture.assistantCancellation.await() }
            assertFalse(task.isCompleted)

            // The Task scheduler stays parked until IO settles and admits the new owner.
            fixture.returnGeneration.complete(Unit)
            withTimeout(5_000L) { fixture.drainProcessed.await() }
            assertFalse(fixture.assistantCleanup.isCompleted)
            fixture.runTask()
            fixture.awaitTask(task)
            val token = requireNotNull(successorToken.get())
            assertTrue(assistant.job.isCompleted)
            assertTrue(assistant.job.isCancelled)
            assertFalse(fixture.drainAllowed.await())
            assertEquals(fixture.compactEpoch, fixture.lease.held.value?.epoch)
            assertEquals("new-owner", fixture.lease.held.value?.owner?.runId)
            assertTrue(fixture.lease.recentReleases().isEmpty())
            val cleanup = withTimeout(5_000L) { fixture.assistantCleanup.await() }
            assertTrue(cleanup.jobCompleted)
            assertEquals("new-owner", cleanup.heldOwner?.runId)
            assertEquals(2, fixture.finishes.count { it.token == assistant.identity.ownerToken })

            val finishedBeforeProbe = fixture.finishes.size
            fixture.state.finishRunHandoff(token, queuedDrainOnly = true)
            assertEquals(finishedBeforeProbe, fixture.finishes.size)
            fixture.state.finishRunHandoff(assistant.identity.ownerToken)
            fixture.state.finishRunHandoff(token, queuedDrainOnly = true)
            assertEquals("new-owner", fixture.lease.held.value?.owner?.runId)
            assertEquals(finishedBeforeProbe + 1, fixture.finishes.size)
            fixture.assertOnlyOneAssistantLaunched()
        } finally {
            fixture.close(task)
        }
    }

    private data class Assistant(
        val identity: RunEffectIdentity,
        val job: Job,
    )

    private data class Finish(
        val token: Long,
        val jobCompleted: Boolean,
        val heldOwner: PhoneControlLease.Owner?,
    )

    private class Fixture(
        private val deferPersistence: Boolean = false,
    ) {
        private val taskScheduler = TestCoroutineScheduler()
        private val taskDispatcher = StandardTestDispatcher(taskScheduler)
        private val conversations = mockk<ConversationRepository>()
        private val compactController = mockk<ConversationCompactController>()
        private val manager = mockk<GenerationManager>()
        private val contextCompactor = mockk<ContextCompactor>()
        private val terminalSettlement = mockk<GenerationTerminalSettlementController>()
        private val coordinator = ConversationExecutionCoordinator()
        private val appContext = mockk<Context>()
        private val snapshot = testGenerationAdmissionSnapshot()
        private val messages = ConcurrentHashMap<String, MessageEntity>()
        private val observedAssistant = AtomicReference<Assistant?>()
        val assistantReady = CompletableDeferred<Assistant>()
        val assistantCancellation = CompletableDeferred<Unit>()
        val returnGeneration = CompletableDeferred<Unit>()
        val assistantCleanup = CompletableDeferred<Finish>()
        val drainAllowed = CompletableDeferred<Boolean>()
        val drainProcessed = CompletableDeferred<Unit>()
        val finishes = CopyOnWriteArrayList<Finish>()
        val lease = PhoneControlLease { 0L }
        val state = ConversationGenerationState(
            conversationId = "conversation",
            onRunStateChanged = lease::onRunStateChanged,
            onRunHandoffFinished = { source, token ->
                lease.finishHandoff(source, token)
                val assistant = observedAssistant.get()
                val finish = Finish(token, assistant?.job?.isCompleted == true, lease.held.value?.owner)
                finishes += finish
                if (assistant?.identity?.ownerToken == token) assistantCleanup.complete(finish)
            },
            reclaimQueuedAttachments = {},
        )
        var afterDrain: () -> Unit = {}
        var compactEpoch: Long = -1L
            private set
        private lateinit var initialRequest: AutomaticCompactContinuationRequest

        init {
            coEvery { conversations.getMessage(any()) } answers { messages[firstArg<String>()] }
            coEvery { conversations.restoreBranchSelections("conversation") } returns emptyMap()
            coEvery {
                conversations.createRunWithMessages(any(), any(), any(), any(), any(), any())
            } answers {
                val created = arg<List<MessageEntity>>(1)
                created.forEach { messages[it.id] = it }
                RunGraphCommit(created, arg(2), emptyMap())
            }
            coEvery { manager.resolvedFixedContextTokenCost(any(), any()) } returns 0
            every { manager.includesAssistantReasoning(any(), any()) } returns false
            coEvery {
                manager.generate(
                    any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(),
                )
            } coAnswers {
                val identity = RunEffectIdentity(
                    conversationId = arg(0), ownerToken = arg(6), runId = arg(4),
                    pass = arg(5), effectId = "finalize-${arg<String>(4)}-0",
                )
                val callbacks = arg<GenerationCallbacks>(11)
                checkNotNull(callbacks.onRunFinalizationRequested(
                    identity, RunStatus.COMPLETED, RunEndReason.CONTINUATION_PENDING, false,
                ))
                val assistant = Assistant(identity, requireNotNull(currentCoroutineContext()[Job]))
                observedAssistant.set(assistant)
                assistantReady.complete(assistant)
                try {
                    returnGeneration.await()
                } catch (_: CancellationException) {
                    assistantCancellation.complete(Unit)
                }
                // Model an already chosen terminal write that survives cancellation and returns late.
                withContext(NonCancellable) {
                    returnGeneration.await()
                    if (!deferPersistence) check(callbacks.onRunFinalizationCompleted(identity, true))
                }
                // The production BoundRun launcher, not the fixture, retains and publishes the request.
                GenerationExecutionResult(followUpParentMessageId = "next-tool-boundary")
            }
        }

        suspend fun prepareCompact() {
            val token = requireNotNull(state.tryAcquireForReplacement())
            state.bindRun(token, "compact-run")
            assertTrue(lease.claim(
                RunEffectIdentity("conversation", token, "compact-run", 0, "phone-claim"), "Task",
            ) is PhoneControlLease.Decision.Granted)
            compactEpoch = requireNotNull(lease.held.value).epoch
            val identity = RunEffectIdentity("conversation", token, "compact-run", 0, "finalize-compact")
            checkNotNull(state.commands.requestRunFinalization(
                identity, RunStatus.COMPLETED, RunEndReason.CONTINUATION_PENDING, false,
            ))
            state.retainRunHandoff(token)
            check(state.finishRunFinalization(identity, true).accepted)
            check(state.endGeneration(token))
            messages["compact-message"] = MessageEntity(
                id = "compact-message", conversationId = "conversation", text = "summary",
                participant = Participant.MODEL, status = MessageStatus.SUCCESS,
                timestamp = 100L, runId = "compact-run", runSequence = 0,
            )
            val compactJob = Job().apply { complete() }
            coEvery {
                compactController.startAutomaticStandard(
                    "conversation", snapshot.config.maxContextWindow, any(), state,
                    alreadyHoldsConversationLock = true, continuesAfterSuccess = true,
                )
            } returns StandardCompactLaunch("compact-message", compactJob, token)
            initialRequest = AutomaticCompactContinuationRequest(
                generationRequest = BoundRunGenerationRequest(
                    conversationId = "conversation", modelMessageId = "initial-assistant",
                    startTime = 0L, snapshot = snapshot, uiToken = token, persistId = 1L,
                    runId = snapshot.runId, pass = 0, requestKind = "task",
                ),
                parentMessageId = "initial-tool-boundary", config = snapshot.automaticCompact,
            )
            state.onQueueDrainRequested = { source ->
                val allowed = source.consumeQueueDrainPermission()
                drainAllowed.complete(allowed)
                try {
                    afterDrain()
                    drainProcessed.complete(Unit)
                } catch (error: Throwable) {
                    drainProcessed.completeExceptionally(error)
                    throw error
                }
            }
        }

        fun start(scope: CoroutineScope): Job = scope.launch(
            context = taskDispatcher,
            start = CoroutineStart.UNDISPATCHED,
        ) {
            compactController.continueTaskGenerations(
                initialRequest = initialRequest,
                state = state,
                convRepo = conversations,
                executionCoordinator = coordinator,
                terminalSettlement = terminalSettlement,
                generationManager = manager,
                contextCompactor = contextCompactor,
                appContext = appContext,
            )
        }

        fun runTask() = taskScheduler.runCurrent()

        suspend fun awaitTask(task: Job) = withTimeout(5_000L) {
            // IO completion can enqueue Task cleanup after the previous runCurrent returns.
            while (!task.isCompleted) {
                runTask()
                yield()
            }
            task.join()
        }

        fun assertRetentionCleared(token: Long) {
            val count = finishes.size
            state.finishRunHandoff(token, queuedDrainOnly = true)
            assertEquals(count + 1, finishes.size)
            assertEquals(token, finishes.last().token)
        }

        fun assertOnlyOneAssistantLaunched() {
            coVerify(exactly = 1) {
                compactController.startAutomaticStandard(any(), any(), any(), any(), any(), any())
            }
            coVerify(exactly = 1) {
                manager.generate(
                    any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(),
                )
            }
        }

        suspend fun close(task: Job?) {
            withContext(NonCancellable) {
                returnGeneration.complete(Unit)
                task?.cancel()
                observedAssistant.get()?.job?.cancel()
                try {
                    runTask()
                    withTimeout(5_000L) { observedAssistant.get()?.job?.join() }
                    runTask()
                    task?.let { awaitTask(it) }
                } finally {
                    state.dispose()
                }
            }
        }
    }
}
