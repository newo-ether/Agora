package com.newoether.agora.phone

import android.app.Application
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
import org.robolectric.annotation.Config

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
