package com.newoether.agora.phone

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.AskUserController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneOverlayMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val lease = PhoneControlLease { 0L }
    private val action = MutableStateFlow<String?>("phone_swipe")
    private val questions = AskUserController()
    private val runtime = mockk<PhoneUseRuntime>(relaxed = true) {
        every { lease } returns this@PhoneOverlayMotionTest.lease
        every { askUser } returns questions
        every { actionName } returns action
        every { revoke(any(), any()) } answers {
            lease.revokeByUser(firstArg(), secondArg())
        }
    }
    private var visible by mutableStateOf(true)
    private var epoch = 0L
    private var presentedEpoch by mutableStateOf(0L)
    private val touchBounds = mutableMapOf<String, Rect>()

    private fun start(reduced: Boolean = false, disabled: Boolean = false, pendingQuestion: Boolean = false) {
        lease.claim(RunEffectIdentity("a", 1, "run", 0, "batch"), "A")
        epoch = lease.held.value!!.epoch
        presentedEpoch = epoch
        if (pendingQuestion) questions.open("a", "Keep this question?", listOf("Yes"), false, true, runId = "run")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primaryContainer = Color.Red, onPrimaryContainer = Color.Black)) {
                CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduced || disabled, disabled)) {
                    Box(Modifier.size(360.dp, 500.dp).background(Color.White).testTag("host")) {
                        PhoneControls(runtime, presentedEpoch, {}, { key, rect ->
                            if (rect == null) touchBounds.remove(key) else touchBounds[key] = rect
                        }, visible = visible)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun frames(millis: Long) {
        repeat(((millis + 15) / 16).toInt()) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    private fun stop() = compose.onNode(hasContentDescription("Stop") and hasClickAction())
    private fun duration() = compose.onNodeWithText("0:00").fetchSemanticsNode().boundsInRoot
    private fun pixel(point: Offset): Int {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            bitmap.getPixel(point.x.toInt(), point.y.toInt())
        } finally { bitmap.recycle() }
    }
    private fun stripPoint(): Offset = stop().fetchSemanticsNode().boundsInRoot.let { Offset(it.center.x, it.top + 8f) }
    private fun green(point: Offset) = android.graphics.Color.green(pixel(point))
    private fun release() {
        compose.runOnIdle {
            lease.releaseEpoch(epoch, "released")
            visible = false
            action.value = null
            Snapshot.sendApplyNotifications()
        }
    }

    @Test fun claimAndReleaseHaveRealIntermediateOpacityAndRetainTheLastAction() {
        start()
        assertEquals(255, green(stripPoint()))
        val initialWidth = duration().width
        frames(96)
        assertTrue("entry opacity", green(stripPoint()) in 1..254)
        frames(240)
        val settled = duration()
        assertEquals(initialWidth / 0.96f, settled.width, 0.4f)
        val point = stripPoint()
        assertEquals(0, green(point))
        release()
        frames(80)
        compose.onNodeWithText("Swipe").assertExists()
        compose.onNodeWithText("Thinking").assertDoesNotExist()
        stop().assertIsNotEnabled()
        assertTrue("exit opacity", green(point) in 1..254)
        assertTrue(duration().width < settled.width)
        frames(240)
        compose.onNodeWithContentDescription("Stop").assertDoesNotExist()
        assertEquals(255, green(point))
        assertTrue(touchBounds.isEmpty())
        verify(exactly = 0) { runtime.revoke(any(), any()) }
    }

    @Test fun appReducedMotionRetainsFadesWithoutAnyStripScale() {
        start(reduced = true)
        val width = duration().width
        frames(96)
        assertTrue(green(stripPoint()) in 1..254)
        assertEquals(width, duration().width, 0.01f)
        frames(240)
        assertEquals(width, duration().width, 0.01f)
        val point = stripPoint()
        release()
        frames(80)
        assertEquals(width, duration().width, 0.01f)
        assertTrue(green(point) in 1..254)
        frames(240)
        assertEquals(255, green(point))
    }

    @Test fun disabledSystemAnimationsSnapBothTransitions() {
        start(disabled = true)
        frames(16)
        val point = stripPoint()
        assertEquals(0, green(point))
        release()
        frames(16)
        compose.onNodeWithContentDescription("Stop").assertDoesNotExist()
        assertEquals(255, green(point))
    }

    @Test fun stopClickReleasesTheRealLeaseAndRetainsItsExitPresentation() {
        start()
        frames(336)
        val point = stripPoint()
        stop().performClick()
        assertNull(lease.held.value)
        frames(80)
        stop().assertIsNotEnabled()
        assertTrue(green(point) in 1..254)
        frames(240)
        compose.onNodeWithContentDescription("Stop").assertDoesNotExist()
        assertEquals(255, green(point))
        verify(exactly = 1) { runtime.revoke(any(), epoch) }
    }

    @Test fun releaseDuringEntryRetargetsCurrentOpacityWithoutAnOpaqueFlash() {
        start(reduced = true)
        frames(64)
        val point = stripPoint()
        val entering = green(point)
        assertTrue(entering in 1..254)
        release()
        frames(16)
        val firstExitFrame = green(point)
        assertTrue("first exit frame stays translucent: entering=$entering exit=$firstExitFrame", firstExitFrame in 1..254)
        frames(32)
        val nextExitFrame = green(point)
        assertTrue("exit returns toward transparent: first=$firstExitFrame next=$nextExitFrame", nextExitFrame >= firstExitFrame)
        frames(80)
        assertTrue("exit passes interrupted opacity: entering=$entering exit=${green(point)}", green(point) > entering)
        frames(240)
        assertEquals(255, green(point))
    }

    @Test fun outgoingQuestionIsFrozenAndDisabledWhileReplacementEpochGetsFreshContent() {
        start(pendingQuestion = true)
        frames(336)
        compose.onNodeWithText("Waiting for you").assertExists()
        compose.onNodeWithText("Keep this question?").assertExists()
        release()
        compose.runOnIdle {
            questions.submitAll(questions.requests.value.map { it.id to AskUserController.Answer.Unanswered })
            lease.claim(RunEffectIdentity("b", 2, "next", 0, "batch"), "B")
            action.value = "phone_tap"
            Snapshot.sendApplyNotifications()
        }
        frames(64)
        compose.onNodeWithText("Waiting for you").assertExists()
        compose.onNodeWithText("Keep this question?").assertExists()
        compose.onNodeWithText("Yes").assertIsNotEnabled()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        stop().assertIsNotEnabled()
        compose.onNodeWithText("Tap").assertDoesNotExist()
        compose.runOnIdle { presentedEpoch = lease.held.value!!.epoch; visible = true }
        frames(336)
        compose.onNodeWithText("Keep this question?").assertDoesNotExist()
        compose.onNodeWithText("Tap").assertExists()
        stop().assertIsEnabled()
        assertEquals(0, green(stripPoint()))
        verify(exactly = 0) { runtime.revoke(any(), any()) }
    }
}
