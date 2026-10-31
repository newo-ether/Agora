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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import io.mockk.every
import io.mockk.mockk
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
class PhoneBorderMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val runtime = mockk<PhoneUseRuntime> {
        every { cursor } returns MutableStateFlow<PhoneCursor?>(null)
    }
    private lateinit var overlay: PhoneOverlay

    private fun start(reduced: Boolean = false, disabled: Boolean = false) {
        overlay = PhoneOverlay(compose.activity, runtime, 1)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color.Red)) {
                CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduced || disabled, disabled)) {
                    Box(Modifier.size(300.dp, 500.dp).background(Color.White).testTag("border")) {
                        PhoneEffect(runtime, overlay)
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

    private fun strength(y: Int): Int {
        val bounds = compose.onNodeWithTag("border").fetchSemanticsNode().boundsInRoot
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            val pixel = bitmap.getPixel(bounds.left.toInt() + 1, bounds.top.toInt() + y)
            255 - android.graphics.Color.green(pixel)
        } finally { bitmap.recycle() }
    }

    @Test fun borderSweepsUpOverFourHundredMillisecondsAndReversesOnRelease() {
        start()
        assertEquals(0, strength(50))
        assertEquals(0, strength(400))
        frames(224)
        val upperDuringEntry = strength(50)
        assertTrue("lower edge is revealed", strength(400) > 0)
        frames(240)
        val upperSettled = strength(50)
        assertTrue("entry remains incomplete after the old 240ms deadline", upperSettled > upperDuringEntry)
        compose.runOnIdle { overlay.visible.value = false }
        frames(96)
        assertTrue("exit sweeps from the top", strength(50) < upperSettled)
        assertTrue("lower edge retained during exit", strength(400) > 0)
        frames(240)
        assertEquals(0, strength(50))
        assertEquals(0, strength(400))
    }

    @Test fun reducedMotionFadesTheWholeBorderInsteadOfSweeping() {
        start(reduced = true)
        frames(80)
        val upper = strength(50)
        val lower = strength(400)
        assertTrue(upper in 1..254)
        assertEquals(upper.toFloat(), lower.toFloat(), 2f)
        frames(192)
        val settled = strength(50)
        assertTrue(settled > upper)
        compose.runOnIdle { overlay.visible.value = false }
        frames(80)
        assertTrue(strength(50) in 1 until settled)
        assertEquals(strength(50).toFloat(), strength(400).toFloat(), 2f)
        frames(192)
        assertEquals(0, strength(50))
    }

    @Test fun disabledSystemAnimationsSnapTheBorder() {
        start(disabled = true)
        frames(16)
        assertTrue(strength(50) > 0)
        assertTrue(strength(400) > 0)
        compose.runOnIdle { overlay.visible.value = false }
        frames(16)
        assertEquals(0, strength(50))
        assertEquals(0, strength(400))
    }
}
