package com.newoether.agora.phone

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.viewmodel.AskUserController
import io.mockk.*
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
        val runtime = PhoneUseRuntime(
            context, settings, lease, ask, mockk(), mockk(), backgroundScope,
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
