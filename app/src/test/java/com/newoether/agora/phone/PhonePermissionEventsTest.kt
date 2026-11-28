package com.newoether.agora.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunState
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.RuntimeRunIdentity
import com.newoether.agora.viewmodel.AskUserController
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
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
@Config(sdk = [35], application = android.app.Application::class)
class PhonePermissionEventsTest {
    private val identity = RunEffectIdentity("conversation", 1, "run", 0, "batch")
    private val config = MutableStateFlow(PhoneUseSettings(enabled = true))
    private val ask = AskUserController()
    private lateinit var context: Context
    private lateinit var receiver: BroadcastReceiver
    private lateinit var dead: Shizuku.OnBinderDeadListener
    private lateinit var permission: Shizuku.OnRequestPermissionResultListener
    private var locked = false
    private var lockReads = 0

    @Before fun setUp() {
        context = spyk(ApplicationProvider.getApplicationContext<Context>())
        every { context.registerReceiver(any<BroadcastReceiver>(), any(), any<Int>()) } answers {
            receiver = firstArg()
            null
        }
        every { context.registerReceiver(any<BroadcastReceiver>(), any(), any(), any(), any<Int>()) } answers {
            receiver = firstArg()
            null
        }
        every { context.unregisterReceiver(any()) } just Runs
        mockkStatic(Shizuku::class)
        every { Shizuku.addBinderDeadListener(any()) } answers { dead = firstArg(); Unit }
        every { Shizuku.addRequestPermissionResultListener(any()) } answers { permission = firstArg(); Unit }
        every { Shizuku.removeBinderDeadListener(any()) } returns true
        every { Shizuku.removeRequestPermissionResultListener(any()) } returns true
    }
    @After fun tearDown() {
        PhoneAccessibilityService.onDisconnected = null
        Dispatchers.resetMain()
        unmockkAll()
    }
    private fun claim(lease: PhoneControlLease, mode: String = "accessibility") {
        config.value = config.value.copy(mode = mode)
        lease.claim(identity, "Title", mode)
    }
    @Test fun noPeriodicChecksAndSettingsEventsReleaseOnlyForEnabledOrModeChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val lease = PhoneControlLease { testScheduler.currentTime }
        val watcher = lease.watchPermissions(context, backgroundScope, config, ask.requests) { lockReads++; locked }
        claim(lease); runCurrent()
        advanceTimeBy(31_000); runCurrent()
        assertNotNull(lease.held.value)
        assertEquals(0, lockReads)
        verify(exactly = 0) { Shizuku.pingBinder() }
        config.value = config.value.copy(allowAutomation = true); runCurrent()
        assertNotNull(lease.held.value)
        config.value = config.value.copy(enabled = false); runCurrent()
        assertEquals("permission_lost:disabled", lease.recentReleases().last().reason)
        config.value = config.value.copy(enabled = true)
        claim(lease); runCurrent()
        config.value = config.value.copy(mode = "root"); runCurrent()
        assertEquals("permission_lost:mode_changed", lease.recentReleases().last().reason)
        watcher.cancelAndJoin()
        verify(exactly = 1) { context.unregisterReceiver(receiver) }
        verify(exactly = 1) { Shizuku.removeBinderDeadListener(dead) }
        assertNull(PhoneAccessibilityService.onDisconnected)
    }
    @Test fun screenAndShizukuCallbacksReleaseTheMatchingModeWithoutPolling() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val lease = PhoneControlLease { testScheduler.currentTime }
        lease.watchPermissions(context, backgroundScope, config, ask.requests) { lockReads++; locked }
        claim(lease, "root"); runCurrent()
        dead.onBinderDead()
        assertNotNull(lease.held.value)
        receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))
        assertEquals("screen_locked", lease.recentReleases().last().reason)
        claim(lease, "shizuku"); runCurrent()
        permission.onRequestPermissionResult(7121, PackageManager.PERMISSION_GRANTED)
        assertNotNull(lease.held.value)
        dead.onBinderDead()
        assertEquals("permission_lost:shizuku_binder_dead", lease.recentReleases().last().reason)
        claim(lease, "shizuku"); runCurrent()
        permission.onRequestPermissionResult(7121, PackageManager.PERMISSION_DENIED)
        assertEquals("permission_lost:shizuku_permission_denied", lease.recentReleases().last().reason)
        claim(lease, "root"); runCurrent()
        locked = true
        receiver.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))
        assertEquals("screen_locked", lease.recentReleases().last().reason)
    }
    @Test fun pendingQuestionPausesSuccessorWhenOwnerChangesWithinTheSameEpoch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val lease = PhoneControlLease { testScheduler.currentTime }
        val watcher = lease.watchPermissions(context, backgroundScope, config, ask.requests) { false }
        claim(lease); runCurrent()
        val epoch = lease.held.value!!.epoch
        val request = ask.open("conversation", "Successor question", emptyList(), false, false, runId = "next")
        runCurrent()
        assertFalse(lease.held.value!!.paused)
        val questions = ask.requests.value

        val source = Any()
        val active = RunState.Active(identity.runIdentity())
        val finalizing = RunState.Finalizing(
            identity = identity.runIdentity(),
            effectIdentity = identity.copy(effectId = "finalize"),
            status = RunStatus.COMPLETED,
            reason = RunEndReason.CONTINUATION_PENDING,
            markConversationUnread = false,
            coroutineSettled = false,
            persistenceSettled = false,
        )
        val settled = finalizing.copy(coroutineSettled = true)
        val idle = RunState.Idle("conversation")
        val successor = RunState.Active(RuntimeRunIdentity("conversation", 2))
        val bound = successor.copy(identity = successor.identity.copy(runId = "next"))
        lease.onRunStateChanged(source, active, finalizing)
        lease.onRunStateChanged(source, finalizing, settled)
        lease.onRunStateChanged(source, settled, idle)
        lease.onRunStateChanged(source, idle, successor)
        lease.onRunStateChanged(source, successor, bound)
        runCurrent()

        assertEquals(epoch, lease.held.value!!.epoch)
        assertEquals(PhoneControlLease.Owner("conversation", "next"), lease.held.value!!.owner)
        assertSame(questions, ask.requests.value)
        assertTrue(lease.held.value!!.paused)
        assertTrue(lease.recentReleases().isEmpty())
        ask.submit(request.id, emptyList(), "Answer"); runCurrent()
        assertFalse(lease.held.value!!.paused)
        watcher.cancelAndJoin()
    }
    @Test fun accessibilityDisconnectIsAnEventAndQuestionPauseUsesExactRun() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val lease = PhoneControlLease { testScheduler.currentTime }
        lease.watchPermissions(context, backgroundScope, config, ask.requests) { false }
        claim(lease); runCurrent()
        val other = ask.open("conversation", "Other run", emptyList(), false, false, runId = "other")
        runCurrent()
        assertFalse(lease.held.value!!.paused)
        val request = ask.open("conversation", "Question", emptyList(), false, false, runId = "run")
        runCurrent()
        assertTrue(lease.held.value!!.paused)
        ask.submit(request.id, emptyList(), "Answer"); runCurrent()
        assertFalse(lease.held.value!!.paused)
        PhoneAccessibilityService.onDisconnected!!.invoke()
        assertEquals("permission_lost:accessibility_disconnected", lease.recentReleases().last().reason)
        assertNotNull(ask.requestById(other.id))
    }
}
