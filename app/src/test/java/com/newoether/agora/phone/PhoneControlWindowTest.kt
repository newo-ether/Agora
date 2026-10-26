package com.newoether.agora.phone

import android.animation.ValueAnimator
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.WindowManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PhoneControlWindowTest {
    private val view = View(RuntimeEnvironment.getApplication())
    private val params = WindowManager.LayoutParams().apply { alpha = 1f }
    private val safeAlpha = phoneOverlayWindowAlpha(0.8f)
    private val manager = mockk<WindowManager>(relaxed = true)

    @Before fun pauseVsyncAutoAdvance() {
        // PAUSED loopers alone still let Robolectric auto-advance every requested vsync.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    private fun window(alpha: Float = safeAlpha) = PhoneControlWindow(view, params, manager, alpha)
    private fun frames(millis: Long = 240) {
        repeat(((millis + 15) / 16).toInt()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
    }
    private fun assertPassThrough(window: PhoneControlWindow) {
        assertFalse(window.touchable)
        assertTrue(window.readyToOperate)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(safeAlpha, params.alpha, 0.00001f)
        assertTrue(1f - (1f - safeAlpha) * (1f - params.alpha) <= 0.8f)
        assertEquals(1f, view.alpha)
        assertEquals(View.VISIBLE, view.visibility)
    }

    @Test fun admissionWaitsForTheActualOpacityFadeAndThenExcludesAllInput() = runTest {
        val window = window()
        val waiting = async { window.beforeOperation() }
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertTrue(window.touchable)
        assertEquals(1f, params.alpha)
        frames(80)
        runCurrent()
        assertTrue("fade alpha=${params.alpha} safe=$safeAlpha enabled=${ValueAnimator.areAnimatorsEnabled()}",
            params.alpha < 1f && params.alpha > safeAlpha)
        assertFalse(waiting.isCompleted)
        assertTrue(window.touchable)
        frames()
        runCurrent()
        waiting.await()
        assertPassThrough(window)
        window.afterOperation()
        assertTrue(window.touchable)
        assertFalse(window.readyToOperate)
        frames()
        assertEquals(1f, params.alpha)
        window.close()
    }

    @Test fun consecutiveActionsRebaseTheCurrentAlphaWithoutAnOpaqueJump() = runTest {
        val window = window()
        val first = async { window.beforeOperation() }
        runCurrent(); frames(); runCurrent(); first.await()
        window.afterOperation()
        frames(64)
        val restoring = params.alpha
        assertTrue("restore alpha=$restoring safe=$safeAlpha", restoring > safeAlpha && restoring < 1f)
        val next = async { window.beforeOperation() }
        runCurrent()
        assertEquals(restoring, params.alpha)
        assertFalse(next.isCompleted)
        frames(); runCurrent(); next.await()
        assertPassThrough(window)
        window.afterOperation(); frames()
        assertEquals(1f, params.alpha)
        window.close()
    }

    @Test fun repeatedAdmissionKeepsTheOriginalFadeDeadline() = runTest {
        val window = window()
        val first = async { window.beforeOperation() }
        runCurrent(); frames(80); runCurrent()
        val middle = params.alpha
        assertTrue(middle > safeAlpha && middle < 1f)
        val second = async { window.beforeOperation() }
        runCurrent()
        assertEquals(middle, params.alpha)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        frames(128); runCurrent()
        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
        first.await(); second.await()
        assertPassThrough(window)
        window.afterOperation(); frames(); window.close()
    }

    @Test fun pendingQuestionStaysOpaqueAndBlocksAdmissionUntilAnsweredAndFaded() = runTest {
        val window = window()
        window.showForUser(true)
        val waiting = async { window.beforeOperation() }
        runCurrent(); frames(); runCurrent()
        assertFalse(waiting.isCompleted)
        assertTrue(window.touchable)
        assertEquals(1f, params.alpha)
        window.showForUser(false)
        runCurrent()
        assertFalse(waiting.isCompleted)
        frames(); runCurrent(); waiting.await()
        assertPassThrough(window)
        window.afterOperation(); frames(); window.close()
    }

    @Test fun questionDuringFadeReversesItAndNeverAdmitsADeviceEffect() = runTest {
        val window = window()
        val waiting = async { window.beforeOperation() }
        runCurrent(); frames(64)
        val fading = params.alpha
        assertTrue(fading > safeAlpha && fading < 1f)
        window.showForUser(true)
        assertEquals(fading, params.alpha)
        frames(); runCurrent()
        assertEquals(1f, params.alpha)
        assertFalse(waiting.isCompleted)
        assertTrue(window.touchable)
        window.showForUser(false)
        frames(); runCurrent(); waiting.await()
        assertPassThrough(window)
        window.afterOperation(); frames(); window.close()
    }

    @Test fun questionDuringActionRestoresInputAndSettlingRefadesBeforePassThrough() = runTest {
        val window = window()
        val waiting = async { window.beforeOperation() }
        runCurrent(); frames(); runCurrent(); waiting.await()
        window.showForUser(true)
        assertTrue(window.touchable)
        assertFalse(window.readyToOperate)
        frames()
        assertEquals(1f, params.alpha)
        window.showForUser(false)
        assertTrue(window.touchable)
        frames()
        assertPassThrough(window)
        window.afterOperation(); frames(); window.close()
    }

    @Test fun closeCancelsFadeUnblocksOldAdmissionAndPreventsLaterWrites() = runTest {
        val window = window()
        val waiting = async { runCatching { window.beforeOperation() }.exceptionOrNull() }
        runCurrent(); frames(64)
        window.close()
        runCurrent()
        assertTrue(waiting.await() is PhoneFailure)
        var writes = 0
        every { manager.updateViewLayout(any(), any()) } answers { writes++ }
        window.afterOperation(); window.showForUser(true); window.capture(false)
        frames(); runCurrent()
        assertEquals(0, writes)
        assertFalse(window.touchable)
        assertFalse(window.readyToOperate)
    }

    @Test fun questionDuringCaptureCannotMakeTheHiddenWindowInterceptInput() = runTest {
        val window = window()
        val waiting = async { window.beforeOperation() }
        runCurrent(); frames(); runCurrent(); waiting.await()
        window.capture(true)
        window.showForUser(true)
        frames()
        assertFalse(window.touchable)
        assertEquals(safeAlpha, params.alpha)
        window.capture(false)
        assertTrue(window.touchable)
        frames()
        assertEquals(1f, params.alpha)
        window.showForUser(false); frames()
        assertPassThrough(window)
        window.afterOperation(); frames(); window.close()
    }

    @Test fun cancellationDuringFadeRestoresInputAndFadesBackFromTheCurrentAlpha() = runTest {
        val window = window()
        val waiting = async {
            try { window.beforeOperation() } finally { window.afterOperation() }
        }
        runCurrent(); frames(64)
        val interrupted = params.alpha
        assertTrue(interrupted > safeAlpha && interrupted < 1f)
        waiting.cancel(); runCurrent()
        assertTrue(window.touchable)
        assertEquals(interrupted, params.alpha)
        frames()
        assertEquals(1f, params.alpha)
        assertFalse(window.readyToOperate)
        window.close()
    }

    @Test fun deviceFailureRestoresInputBeforeTheOpacityRestorationCompletes() = runTest {
        val window = window()
        val operation = async {
            runCatching {
                try { window.beforeOperation(); error("device failed") } finally { window.afterOperation() }
            }.exceptionOrNull()
        }
        runCurrent(); frames(); runCurrent()
        assertEquals("device failed", operation.await()?.message)
        assertTrue(window.touchable)
        assertEquals(safeAlpha, params.alpha)
        frames()
        assertEquals(1f, params.alpha)
        window.close()
    }

    @Test fun trustedWindowKeepsFullOpacityWithoutAnAdmissionAnimation() = runTest {
        val window = window(alpha = 1f)
        window.beforeOperation()
        assertFalse(window.touchable)
        assertTrue(window.readyToOperate)
        assertEquals(1f, params.alpha)
        window.afterOperation()
        assertTrue(window.touchable)
        assertEquals(1f, params.alpha)
        window.close()
    }

    @Test fun disabledSystemAnimationsSnapToTheSafeOpacityBeforeAdmission() = runTest {
        val durationScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
        durationScale.invoke(null, 0f)
        try {
            val window = window()
            window.beforeOperation()
            assertPassThrough(window)
            window.afterOperation()
            assertTrue(window.touchable)
            assertEquals(1f, params.alpha)
            window.close()
        } finally {
            durationScale.invoke(null, 1f)
        }
    }
}
