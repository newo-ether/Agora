package com.newoether.agora.phone

import android.app.Application
import android.view.View
import android.view.WindowManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
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

    @Test fun fadeIsAnExecutionBarrierAndRestorationWaitsBeforeFadingIn() = runTest {
        val window = PhoneControlWindow(view, params, manager, this)
        val hidden = async { window.beforeOperation() }
        runCurrent()
        assertFalse(window.touchable)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        advanceTimeBy(179); runCurrent()
        assertFalse(hidden.isCompleted)
        assertTrue(view.alpha > 0f)
        advanceTimeBy(1); runCurrent(); hidden.await()
        assertEquals(0f, view.alpha)
        assertEquals(0f, params.alpha)
        window.afterOperation()
        advanceTimeBy(299); runCurrent()
        assertEquals(0f, view.alpha)
        advanceTimeBy(181); runCurrent()
        assertEquals(1f, view.alpha)
        assertEquals(0.55f, params.alpha)
        assertTrue(window.touchable)
        assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    @Test fun nextOperationCancelsPendingRestoreWithoutAVisibleFlash() = runTest {
        val window = PhoneControlWindow(view, params, manager, this)
        val first = async { window.beforeOperation() }
        advanceTimeBy(180); runCurrent(); first.await()
        window.afterOperation()
        advanceTimeBy(250); runCurrent()
        val second = async { window.beforeOperation() }
        runCurrent(); second.await()
        advanceTimeBy(600); runCurrent()
        assertEquals(0f, view.alpha)
        assertFalse(window.touchable)
        window.afterOperation()
        advanceTimeBy(480); runCurrent()
        assertTrue(window.touchable)
    }

    @Test fun questionRestoresInputAndDeviceWorkWaitsUntilItIsAnswered() = runTest {
        val window = PhoneControlWindow(view, params, manager, this)
        val hidden = async { window.beforeOperation() }
        advanceTimeBy(80); runCurrent()
        window.showForUser(true)
        advanceTimeBy(200); runCurrent()
        assertFalse(hidden.isCompleted)
        assertEquals(1f, view.alpha)
        assertTrue(window.touchable)
        window.showForUser(false)
        runCurrent()
        assertFalse(window.touchable)
        advanceTimeBy(180); runCurrent(); hidden.await()
        assertEquals(0f, view.alpha)
        window.afterOperation()
        advanceTimeBy(480); runCurrent()
    }

    @Test fun questionThatArrivesDuringAnActionHidesAgainWhenSettled() = runTest {
        val window = PhoneControlWindow(view, params, manager, this)
        val hidden = async { window.beforeOperation() }
        advanceTimeBy(180); runCurrent(); hidden.await()
        window.showForUser(true)
        advanceTimeBy(180); runCurrent()
        assertTrue(window.touchable)
        assertEquals(1f, view.alpha)
        window.showForUser(false)
        assertFalse(window.touchable)
        advanceTimeBy(180); runCurrent()
        assertEquals(0f, view.alpha)
        window.afterOperation()
        advanceTimeBy(480); runCurrent()
        assertTrue(window.touchable)
    }

    @Test fun closingAWaitingQuestionUnblocksTheOldOperationAndCancelsWindowWrites() = runTest {
        val window = PhoneControlWindow(view, params, manager, this)
        window.showForUser(true)
        val waiting = async { runCatching { window.beforeOperation() }.exceptionOrNull() }
        runCurrent()
        window.close()
        runCurrent()
        assertTrue(waiting.await() is PhoneFailure)
        val updates = mutableListOf<Int>()
        every { manager.updateViewLayout(any(), any()) } answers { updates += 1 }
        window.afterOperation()
        advanceTimeBy(1000); runCurrent()
        assertTrue(updates.isEmpty())
        assertFalse(window.touchable)
    }

    @Test fun cancellationRestoresTheSameWindowAndNeverMakesAFadingOutWindowTouchable() = runTest {
        val window = PhoneControlWindow(view, params, manager, this)
        val pending = async {
            try { window.beforeOperation() } finally { window.afterOperation() }
        }
        advanceTimeBy(80); runCurrent()
        pending.cancel(); runCurrent()
        advanceTimeBy(480); runCurrent()
        assertEquals(1f, view.alpha)
        assertTrue(window.touchable)
        val next = async { window.beforeOperation() }
        advanceTimeBy(180); runCurrent(); next.await()
        assertFalse(window.touchable)
        window.close()
        verify(atLeast = 1) { manager.updateViewLayout(view, params) }
    }
}
