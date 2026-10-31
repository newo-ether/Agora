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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
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
    private var direction by mutableStateOf(LayoutDirection.Ltr)
    private var stops = 0
    private var background by mutableStateOf(Color.Magenta)
    private var dark by mutableStateOf(false)
    private var baseColor = Color.Unspecified
    private var contentColor = Color.Unspecified
    private var status by mutableStateOf("Thinking")
    private var elapsed by mutableLongStateOf(0)

    private fun start(reduced: Boolean = false) {
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(
                    LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduced),
                    LocalLayoutDirection provides direction,
                ) {
                    baseColor = MaterialTheme.colorScheme.primaryContainer
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    Box(Modifier.size(360.dp, 240.dp).background(background)) {
                        PhoneControlStrip(elapsed, status, { stops++ }, Modifier.testTag("strip"),
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

    @Test fun endpointContentTracksThePaintedCentroidInBothLayoutDirections() {
        start(reduced = true)
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle { direction = layoutDirection }
            frames(48)
            val duration = compose.onNodeWithText("0:00").fetchSemanticsNode().boundsInRoot
            val stop = stopButton().fetchSemanticsNode().boundsInRoot
            val icon = compose.onNodeWithContentDescription("Stop", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                compose.runOnUiThread { view.draw(Canvas(bitmap)) }
                val y = stop.center.y.toInt()
                val end = if (layoutDirection == LayoutDirection.Ltr) stop.right.toInt() else view.width
                val begin = if (layoutDirection == LayoutDirection.Ltr) 0 else stop.left.toInt()
                val durationPixels = (begin until end).filter { x ->
                    val onDurationSide = if (layoutDirection == LayoutDirection.Ltr) x < duration.center.x
                        else x > duration.center.x
                    onDurationSide && bitmap.getPixel(x, y) == baseColor.toArgb()
                }
                val outer = if (layoutDirection == LayoutDirection.Ltr) durationPixels.first().toFloat()
                    else durationPixels.last() + 1f
                // The settled duration is 48 dp tall and its width is its reserved text canvas + padding.
                val right = if (layoutDirection == LayoutDirection.Ltr) {
                    val x = duration.center.x.toInt()
                    (x until stop.left.toInt()).first { bitmap.getPixel(it, y) == background.toArgb() }.toFloat()
                } else outer
                val left = if (layoutDirection == LayoutDirection.Ltr) outer else {
                    val x = duration.center.x.toInt()
                    (x downTo stop.right.toInt()).first { bitmap.getPixel(it, y) == background.toArgb() } + 1f
                }
                fun centroid(start: Float, finish: Float, roundedLeft: Boolean): Float {
                    var total = 0.0
                    var moment = 0.0
                    val width = finish - start
                    // Numerically integrate the actual 24/5 dp rounded shape, independently of production math.
                    for (step in 0 until (width * 100).toInt()) {
                        val x = (step + 0.5) / 100.0
                        val l = if (roundedLeft) 24.0 else 5.0
                        val r = if (roundedLeft) 5.0 else 24.0
                        val h = when {
                            x < l -> 48.0 - 2 * (l - kotlin.math.sqrt(l * l - (x - l) * (x - l)))
                            x > width - r -> 48.0 - 2 * (r - kotlin.math.sqrt(r * r - (x - width + r) * (x - width + r)))
                            else -> 48.0
                        }
                        total += h
                        moment += h * x
                    }
                    return start + (moment / total).toFloat()
                }
                assertEquals(centroid(left, right, layoutDirection == LayoutDirection.Ltr), duration.center.x, 1f)
                assertEquals(centroid(stop.left, stop.right, layoutDirection == LayoutDirection.Rtl), icon.center.x, 1f)
                assertEquals(stop.center.y, icon.center.y, 0.5f)
                assertTrue(stop.width >= 48f && stop.height >= 48f)
            } finally { bitmap.recycle() }
        }
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

    /** Read the actual painted spans above the text, including both visible inter-capsule gaps. */
    private fun paintedCapsules(): List<IntRange> {
        val stop = stopButton().fetchSemanticsNode().boundsInRoot
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            val expected = baseColor.toArgb()
            val y = (stop.top + 8f).toInt()
            val runs = mutableListOf<IntRange>()
            var start: Int? = null
            for (x in 0..stop.center.x.toInt()) {
                val color = bitmap.getPixel(x, y)
                val painted = listOf(16, 8, 0).all { shift ->
                    kotlin.math.abs(((color shr shift) and 255) - ((expected shr shift) and 255)) <= 2
                }
                if (painted && start == null) start = x
                if (!painted && start != null) {
                    runs += start until x
                    start = null
                }
            }
            if (start != null) runs += start..stop.center.x.toInt()
            assertEquals("duration, status and Stop spans: $runs", 3, runs.size)
            assertEquals("duration/status gap: $runs", 2, runs[1].first - runs[0].last - 1)
            assertEquals("status/Stop gap: $runs", 2, runs[2].first - runs[1].last - 1)
            assertEquals("Stop hit target follows its painted edge", stop.left, runs[2].first.toFloat(), 1f)
            runs
        } finally { bitmap.recycle() }
    }

    @Test fun statusPaintAndStopStayAdjacentOnEveryExpansionShrinkAndInterruptedFrame() {
        start()
        val initial = paintedCapsules()[1].count()
        compose.runOnIdle { status = "Waiting for your answer" }
        val expanding = mutableListOf<Int>()
        repeat(32) { frames(16); expanding += paintedCapsules()[1].count() }
        assertTrue(expanding.any { it > initial && it < expanding.last() })
        val longWidth = expanding.last()
        compose.runOnIdle { status = "Tap" }
        val shrinking = mutableListOf<Int>()
        repeat(10) { frames(16); shrinking += paintedCapsules()[1].count() }
        assertTrue(shrinking.last() < longWidth)
        assertTrue(shrinking.last() > 48)
        compose.runOnIdle { status = "Swipe" }
        val interrupted = paintedCapsules()[1].count()
        assertEquals(shrinking.last(), interrupted)
        val returning = mutableListOf<Int>()
        repeat(32) { frames(16); returning += paintedCapsules()[1].count() }
        assertTrue(returning.any { it < interrupted && it > returning.last() })
        assertTrue(returning.last() < initial)
        assertEquals(0, stops)
        assertEquals(Offset.Zero, movement)
    }

    @Test fun durationFormatCarriesMoveBothFollowingCapsulesWithTheSameClip() {
        elapsed = 599
        start()
        val shortWidth = paintedCapsules()[0].count()
        compose.runOnIdle { elapsed = 600 }
        val minuteWidths = mutableListOf<Int>()
        repeat(32) { frames(16); minuteWidths += paintedCapsules()[0].count() }
        assertTrue(minuteWidths.last() > shortWidth)
        assertTrue(minuteWidths.any { it > shortWidth && it < minuteWidths.last() })
        compose.runOnIdle { elapsed = 3599 }
        frames(32)
        assertEquals(minuteWidths.last(), paintedCapsules()[0].count())
        compose.runOnIdle { elapsed = 3600 }
        val hourWidths = mutableListOf<Int>()
        repeat(32) { frames(16); hourWidths += paintedCapsules()[0].count() }
        assertTrue(hourWidths.any { it > minuteWidths.last() && it < hourWidths.last() })
        compose.runOnIdle { elapsed = 599 }
        val shrinking = mutableListOf<Int>()
        repeat(32) { frames(16); shrinking += paintedCapsules()[0].count() }
        assertTrue(shrinking.any { it > shortWidth && it < hourWidths.last() })
        assertEquals(shortWidth, shrinking.last())
    }

    @Test fun reducedMotionSnapsPaintAndStopTogetherWithoutAGap() {
        start(reduced = true)
        compose.runOnIdle { status = "Waiting for your answer" }
        frames(48)
        val expanded = paintedCapsules()[1].count()
        compose.runOnIdle { status = "Tap" }
        frames(48)
        val snapped = paintedCapsules()[1].count()
        assertTrue(snapped < expanded)
        repeat(26) { frames(16); assertEquals(snapped, paintedCapsules()[1].count()) }
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
