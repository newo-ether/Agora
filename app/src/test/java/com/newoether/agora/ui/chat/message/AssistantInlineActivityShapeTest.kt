package com.newoether.agora.ui.chat.message

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantInlineActivityShapeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View
    private var stopped by mutableStateOf(false)

    private fun host(retry: Boolean, reduceMotion: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(
                LocalDensity provides Density(3f),
                LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = reduceMotion),
            ) {
                MaterialTheme(colorScheme = darkColorScheme(
                    onBackground = Color.White,
                    onSurfaceVariant = Color.Red,
                )) {
                    Box(Modifier.size(120.dp, 80.dp).background(Color.Black).padding(20.dp)) {
                        AssistantInlineActivity(
                            mode = when {
                                stopped -> AssistantInlineActivityMode.NONE
                                retry -> AssistantInlineActivityMode.RETRY
                                else -> AssistantInlineActivityMode.EMPTY
                            },
                            retryText = if (retry) "Retry" else null,
                            visibilityTransition = updateTransition(!stopped, label = "TestActivity"),
                            activityOpacity = if (stopped) 0f else 1f,
                            retainExitLayout = false,
                            terminalText = if (stopped) "Stopped" else null,
                            terminalIsError = false,
                            terminalShowLocalContextHelp = false,
                            precededByCard = false,
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun assertCircularDot(minimumDiameter: Int) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            var peak = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                peak = maxOf(peak, minOf(AndroidColor.green(bitmap.getPixel(x, y)),
                    AndroidColor.blue(bitmap.getPixel(x, y))))
            }
            assertTrue("The outgoing dot must still be visible", peak > 30)
            val threshold = peak / 2
            var left = bitmap.width
            var top = bitmap.height
            var right = -1
            var bottom = -1
            var pixels = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (minOf(AndroidColor.green(pixel), AndroidColor.blue(pixel)) > threshold) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                    pixels++
                }
            }
            val width = right - left + 1
            val height = bottom - top + 1
            assertTrue("Breathing circle must not clip to its 33px layout: ${width}x$height",
                width >= minimumDiameter && height >= minimumDiameter)
            assertEquals("Circle must retain equal diameters", width.toFloat(), height.toFloat(), 1f)
            val fillFraction = pixels.toFloat() / (width * height)
            assertTrue("Circle must retain empty corners, fill=$fillFraction",
                fillFraction in 0.72f..0.85f)
        } finally {
            bitmap.recycle()
        }
    }

    private fun verifyStop(retry: Boolean, reduceMotion: Boolean = false) {
        host(retry, reduceMotion)
        val minimumDiameter = if (reduceMotion) 32 else 39
        assertCircularDot(minimumDiameter)
        compose.runOnIdle { stopped = true }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val firstBounds = compose.onNodeWithText("Stopped").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        assertCircularDot(minimumDiameter)
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        val finalBounds = compose.onNodeWithText("Stopped").fetchSemanticsNode().boundsInRoot
        assertEquals("Terminal text must not move during dot exit", firstBounds, finalBounds)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                assertTrue("No white dot remains after terminal settlement",
                    minOf(AndroidColor.green(pixel), AndroidColor.blue(pixel)) < 10)
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun preOutputDotRemainsCircularWhileStopping() = verifyStop(retry = false)
    @Test fun retryDotRemainsCircularWhileStopping() = verifyStop(retry = true)
    @Test fun reducedMotionKeepsTheSameCircleAndTerminalPosition() =
        verifyStop(retry = false, reduceMotion = true)
}
