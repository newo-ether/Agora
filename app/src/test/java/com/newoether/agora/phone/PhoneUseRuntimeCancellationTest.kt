package com.newoether.agora.phone

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.viewmodel.ConversationGenerationState
import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.ConversationStateRegistry
import io.mockk.*
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.Shizuku

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneUseRuntimeCancellationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val timeout = mockk<PhoneScreenTimeout>()
    private val identity = RunEffectIdentity("conversation", 1, "run", 0, "batch")

    @Before fun setUp() {
        mockkObject(PhoneScreenTimeout.Companion)
        every { PhoneScreenTimeout.process(context) } returns timeout
        mockkStatic(Shizuku::class)
        every { Shizuku.addBinderDeadListener(any()) } just Runs
        every { Shizuku.addRequestPermissionResultListener(any()) } just Runs
        every { Shizuku.removeBinderDeadListener(any()) } returns true
        every { Shizuku.removeRequestPermissionResultListener(any()) } returns true
    }

    @After fun tearDown() {
        PhoneAccessibilityService.onDisconnected = null
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun TestScope.runtime(): PhoneUseRuntime {
        val settings = mockk<SettingsRepository>()
        every { settings.phoneUse } returns MutableStateFlow(PhoneUseSettings(enabled = true))
        val lease = PhoneControlLease { SystemClock.elapsedRealtime() }
        val claim = lease.claim(identity, "Title") as PhoneControlLease.Decision.Granted
        // Keep the real collector running with a held, paused lease so it cannot restore for us.
        lease.pause(identity.owner(), true)
        val ask = AskUserController()
        ask.open(identity.conversationId, "Cancellation regression", emptyList(), false, false, runId = identity.runId)
        val registry = mockk<ConversationStateRegistry>()
        every { registry.get(identity.conversationId) } returns null
        val runtime = PhoneUseRuntime(
            context, settings, lease, ask, registry, mockk(), backgroundScope,
        )
        runCurrent()
        assertEquals(claim.permit.epoch, lease.held.value?.epoch)
        assertTrue(lease.held.value!!.paused)
        coVerify(exactly = 0) { timeout.release(any()) }
        return runtime
    }

    // These are the callbacks constructed by production PhoneUseRuntime, without copied cleanup code.
    @Suppress("UNCHECKED_CAST")
    private fun releaseControl(runtime: PhoneUseRuntime): suspend (Long) -> Boolean =
        PhoneToolProvider::class.java.getDeclaredField("releaseControl").apply { isAccessible = true }
            .get(runtime.provider) as (suspend (Long) -> Boolean)

    @Suppress("UNCHECKED_CAST")
    private fun showControl(runtime: PhoneUseRuntime): suspend (String) -> Unit =
        PhoneToolProvider::class.java.getDeclaredField("showControl").apply { isAccessible = true }
            .get(runtime.provider) as (suspend (String) -> Unit)

    @Suppress("UNCHECKED_CAST")
    private fun status(runtime: PhoneUseRuntime): suspend (String?) -> Unit =
        PhoneToolProvider::class.java.getDeclaredField("status").apply { isAccessible = true }
            .get(runtime.provider) as (suspend (String?) -> Unit)

    private fun pendingStatus(runtime: PhoneUseRuntime): Any? {
        val status = PhoneUseRuntime::class.java.getDeclaredField("actionStatus").apply { isAccessible = true }
            .get(runtime)
        return PhoneActionStatus::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(status)
    }

    private fun message(name: String, id: String = name, runId: String = identity.runId) = ChatMessage(
        id = "message", text = "", participant = Participant.MODEL, runId = runId,
        segments = listOf(MessageSegment("tool", toolName = name, toolCallId = id,
            toolState = ToolExecutionStates.RUNNING)),
    )

    private fun TestScope.statusRuntime(messages: StateFlow<ChatMessage?>): PhoneUseRuntime {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { testScheduler.currentTime }
        val settings = mockk<SettingsRepository>()
        every { settings.phoneUse } returns MutableStateFlow(PhoneUseSettings(enabled = true))
        val state = mockk<ConversationGenerationState>()
        every { state.streamingMessage } returns messages
        every { state.generating } returns MutableStateFlow(true)
        val registry = mockk<ConversationStateRegistry>()
        every { registry.get(identity.conversationId) } returns state
        val lease = PhoneControlLease { testScheduler.currentTime }
        lease.claim(identity, "Status regression")
        lease.pause(identity.owner(), true)
        val ask = AskUserController()
        ask.open(identity.conversationId, "Status regression", emptyList(), false, false, runId = identity.runId)
        return PhoneUseRuntime(context, settings, lease, ask, registry, mockk(), backgroundScope)
    }

    /** Hold an already captured notification while .value continues to expose the live source. */
    private class DelayedSnapshots<T>(private val source: MutableStateFlow<T>) : StateFlow<T> by source {
        var beforeEmit: suspend (T) -> Unit = {}

        @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
        override suspend fun collect(collector: FlowCollector<T>): Nothing = source.collect(object : FlowCollector<T> {
            override suspend fun emit(value: T) {
                beforeEmit(value)
                collector.emit(value)
            }
        })
    }

    @Test fun synchronousSuccessorStatusSurvivesLateCollectorAndOldOverlayCleanup() = runTest {
        val main = StandardTestDispatcher(testScheduler, "runtime-main")
        Dispatchers.setMain(main)
        val messages = MutableStateFlow<ChatMessage?>(message("shell_exec"))
        val runtime = statusRuntime(messages)
        runCurrent()
        val oldEpoch = runtime.lease.held.value!!.epoch
        PhoneUseRuntime::class.java.getDeclaredField("overlayEpoch").apply { isAccessible = true }
            .set(runtime, oldEpoch)
        val successor = identity.copy(runId = "successor")
        runtime.lease.releaseEpoch(oldEpoch, "released")
        runtime.lease.claim(successor, "Successor")
        messages.value = message("phone_tap", runId = successor.runId)
        // Lease notifications are queued on Standard Main; the synchronous entry arrives first.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val status = status(runtime)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { status("phone_tap") }.exceptionOrNull()
        }
        assertEquals("phone_tap", runtime.actionName.value)
        messages.value = message("phone_swipe", runId = successor.runId)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { status("phone_swipe") }.exceptionOrNull()
        }
        val pending = pendingStatus(runtime)
        assertNotNull(pending)
        PhoneUseRuntime::class.java.getDeclaredMethod("clearResources", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(runtime, false)
        assertSame(pending, pendingStatus(runtime))
        Dispatchers.setMain(main)
        runCurrent()
        assertNull(first.await())
        assertSame(pending, pendingStatus(runtime))
        assertFalse(waiting.isCompleted)
        advanceTimeBy(199)
        runCurrent()
        assertEquals("phone_tap", runtime.actionName.value)
        assertFalse(waiting.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertNull(waiting.await())
        assertEquals("phone_swipe", runtime.actionName.value)
    }

    @Test fun delayedCollectorNotificationCannotReplaceTheLivePhoneTarget() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler, "runtime-main"))
        val source = MutableStateFlow<ChatMessage?>(message("shell_exec"))
        val messages = DelayedSnapshots(source)
        val runtime = statusRuntime(messages)
        runCurrent()
        advanceTimeBy(50)
        val oldCaptured = CompletableDeferred<Unit>()
        val emitOld = CompletableDeferred<Unit>()
        val emitLive = CompletableDeferred<Unit>()
        messages.beforeEmit = { snapshot ->
            if (snapshot?.segments?.single()?.toolCallId == "old") {
                oldCaptured.complete(Unit)
                emitOld.await()
            } else emitLive.await()
        }
        source.value = message("web_search", "old")
        runCurrent()
        assertTrue(oldCaptured.isCompleted)
        source.value = message("phone_tap", "live")
        val waiting = async { runCatching { status(runtime)("phone_tap") }.exceptionOrNull() }
        runCurrent()
        val pending = pendingStatus(runtime)
        assertNotNull(pending)
        emitOld.complete(Unit)
        runCurrent()
        assertSame(pending, pendingStatus(runtime))
        assertFalse(waiting.isCompleted)
        advanceTimeBy(149)
        runCurrent()
        assertEquals("shell_exec", runtime.actionName.value)
        advanceTimeBy(1)
        runCurrent()
        assertNull(waiting.await())
        assertEquals("phone_tap", runtime.actionName.value)
        emitLive.complete(Unit)
        runCurrent()
    }

    @Test fun productionStatusRejectsActualCallWithdrawalDuringDwell() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler, "runtime-main"))
        val messages = MutableStateFlow<ChatMessage?>(message("shell_exec"))
        val runtime = statusRuntime(messages)
        runCurrent()
        messages.value = message("phone_tap", "withdrawn")
        val waiting = async { runCatching { status(runtime)("phone_tap") }.exceptionOrNull() }
        runCurrent()
        messages.value = message("phone_tap", "replacement")
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertTrue(waiting.await() is PhoneFailure)
    }

    @Test fun cancelledReleaseStillRestoresAfterTheMainDispatcherHop() = runTest {
        val main = StandardTestDispatcher(testScheduler, "runtime-main")
        val caller = StandardTestDispatcher(testScheduler, "tool-caller")
        Dispatchers.setMain(main)
        assertNotSame(main, caller)
        val runtime = runtime()
        val held = runtime.lease.held.value!!
        val release = releaseControl(runtime)
        PhoneUseRuntime::class.java.getDeclaredField("timeoutEpoch").apply { isAccessible = true }
            .set(runtime, held.epoch)
        lateinit var callerJob: Job
        var restored = false
        coEvery { timeout.release(any()) } coAnswers {
            assertEquals(held.epoch, firstArg<Long>())
            assertTrue(callerJob.isCancelled)
            assertTrue(currentCoroutineContext()[Job]!!.isActive)
            assertNotSame(callerJob, currentCoroutineContext()[Job])
            assertSame(caller, currentCoroutineContext()[ContinuationInterceptor])
            assertSame(held, runtime.lease.held.value)
            // Suspension and a further Main round trip must remain possible inside the envelope.
            withContext(Dispatchers.Main.immediate) {
                assertTrue(currentCoroutineContext()[Job]!!.isActive)
            }
            currentCoroutineContext()[Job]!!.ensureActive()
            restored = true
            true
        }
        val pending = launch(caller) {
            callerJob = currentCoroutineContext()[Job]!!
            callerJob.cancel(CancellationException("release caller stopped"))
            assertTrue(release(held.epoch))
        }
        runCurrent()
        pending.join()
        assertTrue(pending.isCancelled)
        assertTrue(restored)
        assertSame(held, runtime.lease.held.value)
        coVerify(exactly = 1) { timeout.release(held.epoch) }
        coVerify(exactly = 1) { timeout.release(any()) }
        coVerify(exactly = 0) { timeout.acquire(any(), any()) }
    }

    @Test fun cancelledClaimCatchRestoresAfterMainAndStillThrowsCancellation() = runTest {
        val main = StandardTestDispatcher(testScheduler, "runtime-main")
        val caller = StandardTestDispatcher(testScheduler, "tool-caller")
        Dispatchers.setMain(main)
        assertNotSame(main, caller)
        val runtime = runtime()
        val held = runtime.lease.held.value!!
        val show = showControl(runtime)
        val cancellation = CancellationException("claim caller stopped")
        lateinit var callerJob: Job
        var restored = false
        var observed: CancellationException? = null
        coEvery { timeout.acquire(any(), any()) } coAnswers {
            assertEquals(held.epoch, firstArg<Long>())
            assertTrue(secondArg<() -> Boolean>().invoke())
            assertSame(caller, currentCoroutineContext()[ContinuationInterceptor])
            callerJob = currentCoroutineContext()[Job]!!
            callerJob.cancel(cancellation)
            throw cancellation
        }
        coEvery { timeout.release(any()) } coAnswers {
            assertEquals(held.epoch, firstArg<Long>())
            assertTrue(callerJob.isCancelled)
            assertTrue(currentCoroutineContext()[Job]!!.isActive)
            assertNotSame(callerJob, currentCoroutineContext()[Job])
            assertSame(caller, currentCoroutineContext()[ContinuationInterceptor])
            assertSame(held, runtime.lease.held.value)
            withContext(Dispatchers.Main.immediate) {
                assertTrue(currentCoroutineContext()[Job]!!.isActive)
            }
            currentCoroutineContext()[Job]!!.ensureActive()
            restored = true
            true
        }
        val pending = launch(caller) {
            try {
                show(held.mode)
                fail("Claim cancellation must propagate after restoration")
            } catch (cancelled: CancellationException) {
                observed = cancelled
                throw cancelled
            }
        }
        runCurrent()
        pending.join()
        assertTrue(pending.isCancelled)
        assertSame(cancellation, observed)
        assertTrue(restored)
        assertSame(held, runtime.lease.held.value)
        coVerify(exactly = 1) { timeout.acquire(held.epoch, any()) }
        coVerify(exactly = 1) { timeout.release(held.epoch) }
        coVerify(exactly = 1) { timeout.release(any()) }
    }
}
