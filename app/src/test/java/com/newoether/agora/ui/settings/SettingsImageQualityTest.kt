package com.newoether.agora.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.newoether.agora.data.ImageQualitySettings
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h900dp-mdpi")
class SettingsImageQualityTest {
    @get:Rule val compose = createComposeRule()
    private var quality by mutableStateOf(ImageQualitySettings())
    private val modes = mutableListOf<String>()
    private val edges = mutableListOf<Int>()
    private val jpegs = mutableListOf<Int>()

    private fun showGroup(echo: Boolean = true) = compose.setContent {
        CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true)) {
            MaterialTheme {
                Column(Modifier.size(400.dp, 800.dp).verticalScroll(rememberScrollState())) {
                    SettingsImageQualityGroup(
                        quality,
                        onModeChange = { modes += it; if (echo) quality = quality.copy(mode = it) },
                        onMaxLongEdgeChange = { edges += it; if (echo) quality = quality.copy(customMaxLongEdge = it) },
                        onJpegQualityChange = { jpegs += it; if (echo) quality = quality.copy(customJpegQuality = it) },
                    )
                }
            }
        }
    }

    @Test
    fun wholeRowDropdownSavesImmediatelyAndShowsCustomControlsOnlyInCustomMode() {
        showGroup()
        compose.onNodeWithTag("image-quality-long-edge").assertDoesNotExist()
        compose.onNodeWithTag("image-quality-mode").performClick()
        listOf("Light", "High", "Custom").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Custom").performClick()
        compose.onNodeWithTag("image-quality-long-edge").performScrollTo().assertIsEnabled()
            .assert(range(2048f, 128f..8192f))
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(3072f)) }
        compose.onNodeWithTag("image-quality-jpeg").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(92f)) }
        compose.onNodeWithTag("image-quality-mode").performScrollTo().performClick()
        compose.onNodeWithText("High").performClick()
        compose.onNodeWithTag("image-quality-long-edge").assertDoesNotExist()
        compose.onNodeWithTag("image-quality-mode").performClick()
        compose.onNodeWithText("Custom").performClick()
        compose.onNodeWithTag("image-quality-long-edge").performScrollTo().assert(range(3072f, 128f..8192f))
        compose.onNodeWithTag("image-quality-jpeg").assert(range(92f, 1f..100f))
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("custom", "high", "custom"), modes)
            assertEquals(listOf(3072), edges)
            assertEquals(listOf(92), jpegs)
        }
    }

    @Test
    fun dragWritesOnceOnReleaseAndRetainsFinalValueUntilPersistenceCatchesUp() {
        quality = ImageQualitySettings("custom", 2048, 80)
        showGroup(echo = false)
        val slider = compose.onNodeWithTag("image-quality-long-edge").performScrollTo()
        slider.performTouchInput {
            down(center)
            moveTo(Offset(width + 32f, center.y), delayMillis = 200)
        }
        compose.runOnIdle {
            assertTrue(edges.isEmpty())
            quality = quality.copy(customMaxLongEdge = 3000)
        }
        slider.assert(range(8192f, 128f..8192f))
        slider.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(8192), edges) }
        compose.runOnIdle { quality = quality.copy(customMaxLongEdge = 4000) }
        slider.assert(range(8192f, 128f..8192f))
        compose.runOnIdle { quality = quality.copy(customMaxLongEdge = 8192) }
        slider.assert(range(8192f, 128f..8192f))
        compose.runOnIdle { quality = quality.copy(customMaxLongEdge = 128) }
        slider.assert(range(128f, 128f..8192f))
        compose.runOnIdle { assertEquals(listOf(8192), edges) }
    }

    @Test
    fun unchangedAccessibleValuesDoNotWriteAndJpegEndpointsAreReachable() {
        quality = ImageQualitySettings("custom", 128, 1)
        showGroup()
        compose.onNodeWithTag("image-quality-long-edge").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(128f)) }
        compose.onNodeWithTag("image-quality-jpeg").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(1f)) }
        compose.runOnIdle { assertTrue(edges.isEmpty()); assertTrue(jpegs.isEmpty()) }
        compose.onNodeWithTag("image-quality-jpeg")
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(100f)) }
        compose.runOnIdle { assertEquals(listOf(100), jpegs) }
    }

    private fun range(value: Float, range: ClosedFloatingPointRange<Float>) = SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(value, range, 0),
    )
}
