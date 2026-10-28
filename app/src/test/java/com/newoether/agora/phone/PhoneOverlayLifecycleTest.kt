package com.newoether.agora.phone

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.viewmodel.AskUserController
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneOverlayLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private val context = RuntimeEnvironment.getApplication()
    private val runtime = mockk<PhoneUseRuntime>(relaxed = true)
    private val manager = mockk<WindowManager>(relaxed = true)
    private val control = ComposeView(context)
    private val effect = ComposeView(context)
    private val params = WindowManager.LayoutParams().apply { alpha = 1f }
    private val window = PhoneControlWindow(control, params, manager, operationAlpha = 1f)
    private val overlay = PhoneOverlay(context, runtime, 1)
    private var writes = 0
    private var removals = 0

    @Before fun attachWindowFixture() {
        Dispatchers.setMain(dispatcher)
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        every { manager.updateViewLayout(any(), any()) } answers { writes++ }
        every { manager.removeViewImmediate(any()) } answers { removals++ }
        mapOf("manager" to manager, "control" to control, "effect" to effect,
            "controlParams" to params, "controlWindow" to window).forEach { (name, value) ->
            PhoneOverlay::class.java.getDeclaredField(name).apply { isAccessible = true }.set(overlay, value)
        }
    }

    @After fun cleanup() {
        overlay.remove()
        Dispatchers.resetMain()
    }

    @Test fun actualWindowAttachmentRequestsHardwareRenderingAndPreservesInputFlags() = runTest(dispatcher) {
        val attached = mutableListOf<WindowManager.LayoutParams>()
        val settings = mockk<SettingsRepository> {
            every { appLanguage } returns MutableStateFlow("system")
        }
        every { runtime.settings } returns settings
        every { runtime.lease } returns PhoneControlLease { 0L }
        every { runtime.askUser } returns AskUserController()
        every { manager.addView(any(), any()) } answers {
            attached += WindowManager.LayoutParams().apply { copyFrom(secondArg()) }
        }
        val host = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.WINDOW_SERVICE) manager else super.getSystemService(name)
        }
        val actual = PhoneOverlay(host, runtime, 2)
        try {
            actual.show("root")
            runCurrent()
            assertEquals(2, attached.size)
            attached.forEach { params ->
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED != 0)
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
            }
            assertTrue(attached.first().flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertEquals(0, attached.last().flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertTrue(attached.last().flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            assertEquals(1f, attached.last().alpha)
        } finally {
            actual.remove()
        }
    }

    @Test fun hideImmediatelyClosesInputButRetainsBothViewsForTheExit() = runTest(dispatcher) {
        val exiting = launch { overlay.hide() }
        runCurrent()
        assertFalse(overlay.visible.value)
        assertFalse(window.touchable)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(View.VISIBLE, control.visibility)
        assertEquals(0, removals)
        val writesAfterClose = writes
        PhoneOverlay::class.java.getDeclaredMethod("focusable", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(overlay, true)
        PhoneOverlay::class.java.getDeclaredMethod("focusable", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(overlay, false)
        window.afterOperation()
        window.showForUser(true)
        window.capture(false)
        assertEquals(writesAfterClose, writes)
        advanceTimeBy(279)
        runCurrent()
        assertFalse(exiting.isCompleted)
        assertEquals(0, removals)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(exiting.isCompleted)
        assertEquals(2, removals)
    }

    @Test fun hideRejectsAnAdmissionWaitingForAQuestion() = runTest(dispatcher) {
        window.showForUser(true)
        val admission = async { runCatching { window.beforeOperation() }.exceptionOrNull() }
        runCurrent()
        assertFalse(admission.isCompleted)
        val exiting = launch { overlay.hide() }
        runCurrent()
        assertTrue(admission.await() is PhoneFailure)
        assertFalse(window.readyToOperate)
        advanceTimeBy(280)
        runCurrent()
        exiting.join()
        assertEquals(2, removals)
    }

    private fun frames(millis: Long = 320) {
        repeat(((millis + 15) / 16).toInt()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test fun captureRunsOnlyAtZeroAlphaAfterFramesAndRestoresAfterFailure() = runTest(dispatcher) {
        var captures = 0
        val capture = async {
            runCatching { overlay.capture {
                captures++
                assertEquals(0f, params.alpha)
                assertFalse(window.touchable)
                assertTrue(overlay.capturing.value)
                assertEquals(View.VISIBLE, control.visibility)
                error("capture failed")
            } }.exceptionOrNull()
        }
        runCurrent()
        frames(80)
        assertEquals(0, captures)
        assertFalse(capture.isCompleted)
        frames(320)
        assertEquals("capture failed", capture.await()?.message)
        assertEquals(1, captures)
        assertFalse(overlay.capturing.value)
        assertFalse(window.readyToOperate)
        assertTrue(window.touchable)
        frames()
        assertEquals(1f, params.alpha)
    }

    @Test fun cancellingCaptureFadeRestoresWithoutInvokingTheBitmapBlock() = runTest(dispatcher) {
        var captures = 0
        val capture = launch { overlay.capture { captures++; error("unexpected capture") } }
        runCurrent()
        frames(80)
        capture.cancel()
        runCurrent()
        capture.join()
        assertFalse(overlay.capturing.value)
        assertFalse(window.readyToOperate)
        frames()
        assertEquals(0, captures)
        assertEquals(1f, params.alpha)
        assertTrue(window.touchable)
    }

    @Test fun cancellingCaptureWhileQuestionWaitsNeverHidesOrBlocksItsInput() = runTest(dispatcher) {
        window.showForUser(true)
        var captures = 0
        val capture = launch { overlay.capture { captures++; error("unexpected") } }
        runCurrent(); frames()
        assertFalse(capture.isCompleted)
        assertFalse(overlay.capturing.value)
        assertEquals(1f, params.alpha)
        assertTrue(window.touchable)
        capture.cancel(); runCurrent(); capture.join()
        window.showForUser(false)
        frames()
        assertEquals(0, captures)
        assertEquals(1f, params.alpha)
        assertTrue(window.touchable)
        assertFalse(window.readyToOperate)
    }

    @Test fun removingCaptureWaitingForQuestionRejectsWithoutLaterWrites() = runTest(dispatcher) {
        window.showForUser(true)
        var captures = 0
        val capture = async { runCatching { overlay.capture { captures++; error("unexpected") } }.exceptionOrNull() }
        runCurrent()
        assertFalse(overlay.capturing.value)
        overlay.remove()
        val writesAfterRemove = writes
        runCurrent()
        assertTrue(capture.await() is PhoneFailure)
        frames()
        assertEquals(0, captures)
        assertEquals(writesAfterRemove, writes)
        assertFalse(overlay.capturing.value)
    }

    @Test fun removingDuringCaptureFadeRejectsOldWorkWithoutLaterWindowWrites() = runTest(dispatcher) {
        var captures = 0
        val capture = async { runCatching { overlay.capture { captures++; error("unexpected") } }.exceptionOrNull() }
        runCurrent()
        frames(80)
        overlay.remove()
        val writesAfterRemove = writes
        runCurrent()
        assertTrue(capture.await() is PhoneFailure)
        frames()
        assertEquals(0, captures)
        assertEquals(writesAfterRemove, writes)
        assertFalse(overlay.capturing.value)
    }

    @Test fun synchronousReplacementRemovalMakesTheOldDelayedExitInert() = runTest(dispatcher) {
        val exiting = launch { overlay.hide() }
        runCurrent()
        overlay.remove()
        val writesAfterRemove = writes
        assertEquals(2, removals)
        advanceTimeBy(280)
        runCurrent()
        exiting.join()
        overlay.remove()
        assertEquals(writesAfterRemove, writes)
        assertEquals(2, removals)
    }
}
