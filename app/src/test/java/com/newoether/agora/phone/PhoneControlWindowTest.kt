package com.newoether.agora.phone

import android.app.Application
import android.view.View
import android.view.WindowManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneControlWindowTest {
    private val view = View(RuntimeEnvironment.getApplication())
    private val params = WindowManager.LayoutParams().apply { alpha = 0.55f }
    private val manager = mockk<WindowManager>(relaxed = true)

    @Test fun actionRemainsVisibleAndImmediatelyExcludesAllInputUntilCompletion() = runTest {
        val window = PhoneControlWindow(view, params, manager)
        window.beforeOperation()
        assertFalse(window.touchable)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(1f, view.alpha)
        assertEquals(0.55f, params.alpha)
        assertEquals(View.VISIBLE, view.visibility)
        window.afterOperation()
        assertTrue(window.touchable)
        assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertEquals(1f, view.alpha)
        assertEquals(0.55f, params.alpha)
    }

    @Test fun consecutiveActionsHaveNoDelayedRestorationOrOpacityWork() = runTest {
        val window = PhoneControlWindow(view, params, manager)
        repeat(3) {
            window.beforeOperation()
            assertFalse(window.touchable)
            window.afterOperation()
            assertTrue(window.touchable)
        }
        runCurrent()
        assertEquals(1f, view.alpha)
        assertEquals(0.55f, params.alpha)
    }

    @Test fun pendingQuestionRetainsInputAndBlocksDeviceAdmissionUntilAnswered() = runTest {
        val window = PhoneControlWindow(view, params, manager)
        window.showForUser(true)
        val waiting = async { window.beforeOperation() }
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertTrue(window.touchable)
        window.showForUser(false)
        runCurrent()
        waiting.await()
        assertFalse(window.touchable)
        window.afterOperation()
        assertTrue(window.touchable)
    }

    @Test fun questionDuringActionTemporarilyRestoresInputAndSettlingRestoresPassThrough() = runTest {
        val window = PhoneControlWindow(view, params, manager)
        window.beforeOperation()
        window.showForUser(true)
        assertTrue(window.touchable)
        window.showForUser(false)
        assertFalse(window.touchable)
        assertEquals(1f, view.alpha)
        window.afterOperation()
        assertTrue(window.touchable)
    }

    @Test fun closingQuestionUnblocksOldOperationWithoutLaterWindowWrites() = runTest {
        val window = PhoneControlWindow(view, params, manager)
        window.showForUser(true)
        val waiting = async { runCatching { window.beforeOperation() }.exceptionOrNull() }
        runCurrent()
        window.close()
        runCurrent()
        assertTrue(waiting.await() is PhoneFailure)
        val writes = mutableListOf<Int>()
        every { manager.updateViewLayout(any(), any()) } answers { writes += 1 }
        window.afterOperation()
        window.showForUser(true)
        runCurrent()
        assertTrue(writes.isEmpty())
        assertFalse(window.touchable)
    }

    @Test fun questionDuringCaptureCannotMakeTheHiddenWindowInterceptInput() = runTest {
        val window = PhoneControlWindow(view, params, manager)
        window.beforeOperation()
        window.capture(true)
        window.showForUser(true)
        assertFalse(window.touchable)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        window.capture(false)
        assertTrue(window.touchable)
        window.showForUser(false)
        assertFalse(window.touchable)
        window.afterOperation()
        assertTrue(window.touchable)
    }

    @Test fun cancellationAndFailureRestoreInputInTheOperationFinallyBoundary() = runTest {
        val window = PhoneControlWindow(view, params, manager)
        window.showForUser(true)
        val waiting = async {
            try { window.beforeOperation() } finally { window.afterOperation() }
        }
        runCurrent()
        waiting.cancel()
        runCurrent()
        assertTrue(window.touchable)
        window.showForUser(false)
        val error = runCatching {
            try { window.beforeOperation(); error("device failed") } finally { window.afterOperation() }
        }.exceptionOrNull()
        assertEquals("device failed", error?.message)
        assertTrue(window.touchable)
        window.close()
        window.afterOperation()
        assertFalse(window.touchable)
        verify(atLeast = 1) { manager.updateViewLayout(view, params) }
    }
}
