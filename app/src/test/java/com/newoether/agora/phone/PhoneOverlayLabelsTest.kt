package com.newoether.agora.phone

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.AgoraTheme
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
class PhoneOverlayLabelsTest {
    @get:Rule val compose = createComposeRule()
    private var seconds by mutableLongStateOf(0)
    private var status by mutableStateOf("Thinking")
    private var zeroWidth = 0
    private var oneWidth = 0
    private var widestTemplate = ""
    private var digitWidths = emptyList<Int>()
    private var longStatusWidth = 0

    private fun start(reduced: Boolean = false) {
        compose.setContent {
            AgoraTheme {
                CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = reduced)) {
                    val measurer = rememberTextMeasurer()
                    val style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
                    zeroWidth = measurer.measure(AnnotatedString("0:00"), style, maxLines = 1, softWrap = false).size.width
                    oneWidth = measurer.measure(AnnotatedString("1:11"), style, maxLines = 1, softWrap = false).size.width
                    digitWidths = ('0'..'9').map {
                        measurer.measure(AnnotatedString(it.toString()), style, maxLines = 1, softWrap = false).size.width
                    }
                    widestTemplate = phoneDurationTemplate("0:00", measurer, style)
                    longStatusWidth = measurer.measure(AnnotatedString("Waiting for your answer"),
                        MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false).size.width + 24
                    Column {
                        PhoneDurationCapsule(seconds, Modifier.testTag("duration"))
                        PhoneStatusCapsule(status, Modifier.testTag("status"))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }
    private fun frames(millis: Long) {
        val end = compose.mainClock.currentTime + millis
        while (compose.mainClock.currentTime < end) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }
    private fun width(tag: String): Float {
        val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        return (bounds.right - bounds.left).value
    }

    @Test fun actualDefaultFontMeasurementsAreReportedAndTemplateUsesItsWidestDigit() {
        start()
        println("PHONE_TNUM font=app_default labelLarge zero=$zeroWidth one=$oneWidth equal=${zeroWidth == oneWidth} digits=$digitWidths template=$widestTemplate")
        assertTrue(zeroWidth > 0 && oneWidth > 0)
        val widest = digitWidths.max()
        widestTemplate.filter(Char::isDigit).forEach { assertEquals(widest, digitWidths[it - '0']) }
        assertEquals(':', widestTemplate[1])
    }
    @Test fun digitsDoNotChangeWidthWithinAFormatAndCarriesUseTheSharedClipDeadline() {
        start()
        val initial = width("duration")
        for (value in listOf(1L, 11L, 71L, 119L, 599L)) {
            compose.runOnIdle { seconds = value }
            frames(240)
            assertEquals(initial, width("duration"), 0.01f)
        }
        compose.runOnIdle { seconds = 600 }
        frames(160)
        val during = width("duration")
        frames(320)
        val minutes = width("duration")
        assertTrue(during > initial && during < minutes)
        compose.runOnIdle { seconds = 3599 }
        frames(240)
        assertEquals(minutes, width("duration"), 0.01f)
        compose.runOnIdle { seconds = 3600 }
        frames(160)
        val hourDuring = width("duration")
        frames(320)
        assertTrue(hourDuring > minutes && hourDuring < width("duration"))
    }
    @Test fun statusWidthAnimationCanBeInterruptedWithoutJumpingToEitherEndpoint() {
        start()
        val initial = width("status")
        compose.runOnIdle { status = "Waiting for your answer" }
        frames(160)
        val expanding = width("status")
        assertTrue(expanding > initial)
        compose.runOnIdle { status = "Tap" }
        assertEquals(expanding, width("status"), 0.01f)
        frames(48)
        val rebased = width("status")
        assertTrue("expanding=$expanding rebased=$rebased target=$longStatusWidth",
            rebased > initial && rebased < longStatusWidth)
        frames(48)
        val returning = width("status")
        frames(400)
        val final = width("status")
        assertTrue(final < initial)
        assertTrue("rebased=$rebased returning=$returning final=$final",
            returning < rebased && returning > final)
    }
    @Test fun reducedMotionSnapsStatusWidth() {
        start(reduced = true)
        val initial = width("status")
        compose.runOnIdle { status = "Waiting for you" }
        frames(48)
        val final = width("status")
        assertTrue(final > initial)
        frames(400)
        assertEquals(final, width("status"), 0.01f)
    }
    @Test fun durationFormatsCoverBothRequestedCarries() {
        assertEquals("0:00", phoneDuration(0))
        assertEquals("1:11", phoneDuration(71))
        assertEquals("9:59", phoneDuration(599))
        assertEquals("10:00", phoneDuration(600))
        assertEquals("59:59", phoneDuration(3599))
        assertEquals("1:00:00", phoneDuration(3600))
        assertEquals("10:00:00", phoneDuration(36000))
    }
}
