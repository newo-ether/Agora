package com.newoether.agora.phone

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
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
class PhoneControlStripTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var movement = Offset.Zero
    private var stops = 0
    private var background by mutableStateOf(Color.Magenta)
    private var dark by mutableStateOf(false)
    private var baseColor = Color.Unspecified
    private var contentColor = Color.Unspecified

    private fun start(reduced: Boolean = false) {
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduced)) {
                    baseColor = MaterialTheme.colorScheme.primaryContainer
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    Box(Modifier.size(360.dp, 240.dp).background(background)) {
                        PhoneControlStrip(0, "Thinking", { stops++ }, Modifier.testTag("strip"),
                            onDrag = { movement += it })
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun frames(millis: Long = 176) {
        val end = compose.mainClock.currentTime + millis
        while (compose.mainClock.currentTime < end) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    private fun durationBounds(): Rect = compose.onNodeWithText("0:00").fetchSemanticsNode().boundsInRoot

    private fun stopButton() = compose.onNode(hasContentDescription("Stop") and hasClickAction())

    private fun backgroundPixel(): Color = backgroundPixels().last()

    private fun backgroundPixels(): List<Color> {
        val stop = stopButton().fetchSemanticsNode().boundsInRoot
        val labels = listOf("0:00", "Thinking").map {
            compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot
        }
        val points = labels.map { Offset(it.left - 6f, it.center.y) } + Offset(stop.center.x, stop.top + 8f)
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            points.map { Color(bitmap.getPixel(it.x.toInt(), it.y.toInt())) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertColor(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, 0.012f)
        assertEquals(expected.green, actual.green, 0.012f)
        assertEquals(expected.blue, actual.blue, 0.012f)
        assertEquals(1f, actual.alpha, 0.001f)
    }

    private fun assertScale(rest: Rect, scale: Float) {
        val current = durationBounds()
        assertEquals(rest.width * scale, current.width, 0.3f)
        assertEquals(rest.height * scale, current.height, 0.3f)
    }

    @Test fun stopIsOneAccessibleIconButtonWithAtLeast48DpTarget() {
        start()
        compose.onNodeWithText("Stop").assertDoesNotExist()
        stopButton().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val bounds = stopButton().getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
        stopButton().performTouchInput { click(Offset(2f, center.y)) }
        frames()
        assertEquals(1, stops)
        assertEquals(Offset.Zero, movement)
    }

    @Test
    @Config(qualifiers = "zh-rCN-mdpi")
    fun stopDescriptionUsesTheCurrentLocale() {
        start()
        compose.onNodeWithContentDescription("停止").assertHasClickAction()
        compose.onNodeWithContentDescription("Stop").assertDoesNotExist()
        compose.onNodeWithText("停止").assertDoesNotExist()
    }

    @Test fun pressAndDragShareOneScaleAndTintUntilRelease() {
        start()
        val rest = durationBounds()
        val restingColor = backgroundPixel()
        compose.onNodeWithText("0:00").performTouchInput { down(center) }
        frames()
        assertScale(rest, 1.05f)
        assertColor(lerp(baseColor, contentColor, 0.06f), backgroundPixel())
        compose.onNodeWithText("0:00").performTouchInput {
            moveBy(Offset(80f, 30f), delayMillis = 100)
        }
        frames()
        assertTrue(movement.x > 0f && movement.y > 0f)
        assertScale(rest, 1.05f)
        assertEquals(0, stops)
        compose.onNodeWithText("0:00").performTouchInput { up() }
        frames()
        assertScale(rest, 1f)
        assertColor(restingColor, backgroundPixel())
    }

    @Test fun stopPressUsesTheSameScaleOnceAndDoesNotDrag() {
        start()
        val rest = durationBounds()
        stopButton().performTouchInput { down(center) }
        frames()
        assertScale(rest, 1.05f)
        assertColor(lerp(baseColor, contentColor, 0.06f), backgroundPixel())
        assertEquals(0, stops)
        stopButton().performTouchInput { up() }
        frames()
        assertScale(rest, 1f)
        assertEquals(1, stops)
        assertEquals(Offset.Zero, movement)
    }

    @Test fun cancellationRestoresPressFeedbackAndNeverClicksStop() {
        start()
        val rest = durationBounds()
        for (node in listOf(compose.onNodeWithText("0:00"), stopButton())) {
            node.performTouchInput { down(center) }
            frames()
            assertScale(rest, 1.05f)
            node.performTouchInput { cancel() }
            frames()
            assertScale(rest, 1f)
            assertColor(baseColor, backgroundPixel())
        }
        assertEquals(0, stops)
    }

    @Test fun reducedMotionKeepsScaleAtRestAndRetainsTint() {
        start(reduced = true)
        val rest = durationBounds()
        compose.onNodeWithText("0:00").performTouchInput { down(center) }
        frames()
        assertScale(rest, 1f)
        assertColor(lerp(baseColor, contentColor, 0.06f), backgroundPixel())
        compose.onNodeWithText("0:00").performTouchInput { up() }
        frames()
        assertScale(rest, 1f)
        assertColor(baseColor, backgroundPixel())
    }

    @Test fun capsulePaintIsOpaqueAcrossThemesAndUnderlyingBackgrounds() {
        start()
        for (darkTheme in listOf(false, true)) {
            compose.runOnIdle { dark = darkTheme; background = Color.Magenta }
            frames()
            val resting = backgroundPixels()
            resting.forEach { assertColor(baseColor, it) }
            compose.runOnIdle { background = Color.Green }
            frames()
            assertEquals(resting.map(Color::toArgb), backgroundPixels().map(Color::toArgb))
            stopButton().performTouchInput { down(center) }
            frames()
            val pressed = backgroundPixels()
            pressed.forEach { assertColor(lerp(baseColor, contentColor, 0.06f), it) }
            compose.runOnIdle { background = Color.Blue }
            frames()
            assertEquals(pressed.map(Color::toArgb), backgroundPixels().map(Color::toArgb))
            stopButton().performTouchInput { cancel() }
            frames()
        }
    }
}
