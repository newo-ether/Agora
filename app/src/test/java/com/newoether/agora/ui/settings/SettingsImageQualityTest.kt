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
            .assert(range(4f, 0f..6f, 5))
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(5f)) }
        compose.onNodeWithTag("image-quality-jpeg").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(92f)) }
        compose.onNodeWithTag("image-quality-mode").performScrollTo().performClick()
        compose.onNodeWithText("High").performClick()
        compose.onNodeWithTag("image-quality-long-edge").assertDoesNotExist()
        compose.onNodeWithTag("image-quality-mode").performClick()
        compose.onNodeWithText("Custom").performClick()
        compose.onNodeWithTag("image-quality-long-edge").performScrollTo().assert(range(5f, 0f..6f, 5))
        compose.onNodeWithTag("image-quality-jpeg").assert(range(92f, 1f..100f))
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("custom", "high", "custom"), modes)
            assertEquals(listOf(4096), edges)
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
        slider.assert(range(6f, 0f..6f, 5))
        slider.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(8192), edges) }
        compose.runOnIdle { quality = quality.copy(customMaxLongEdge = 4000) }
        slider.assert(range(6f, 0f..6f, 5))
        compose.runOnIdle { quality = quality.copy(customMaxLongEdge = 8192) }
        slider.assert(range(6f, 0f..6f, 5))
        compose.runOnIdle { quality = quality.copy(customMaxLongEdge = 128) }
        slider.assert(range(0f, 0f..6f, 5))
        compose.runOnIdle { assertEquals(listOf(8192), edges) }
    }

    @Test
    fun unchangedAccessibleValuesDoNotWriteAndJpegEndpointsAreReachable() {
        quality = ImageQualitySettings("custom", 1024, 1)
        showGroup()
        compose.onNodeWithTag("image-quality-long-edge").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(3f)) }
        compose.onNodeWithTag("image-quality-jpeg").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(1f)) }
        compose.runOnIdle { assertTrue(edges.isEmpty()); assertTrue(jpegs.isEmpty()) }
        compose.onNodeWithTag("image-quality-jpeg")
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(100f)) }
        compose.runOnIdle { assertEquals(listOf(100), jpegs) }
    }

    @Test
    fun everyIntegerStopIsAccessibleAndLegacyValuesAreNotRewrittenOnEntry() {
        quality = ImageQualitySettings("custom", 1500, 80)
        showGroup()
        val slider = compose.onNodeWithTag("image-quality-long-edge").performScrollTo()
        slider.assert(range(3f, 0f..6f, 5))
        compose.onNodeWithText("1500 px").assertExists()
        compose.runOnIdle { assertTrue(edges.isEmpty()); assertEquals(1500, quality.customMaxLongEdge) }
        val expected = listOf(128, 256, 512, 1024, 2048, 4096, 8192)
        val selectedIndices = listOf(0, 1, 2, 3, 4, 5, 6, 0)
        selectedIndices.forEach { index ->
            val edge = expected[index]
            slider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(index.toFloat())) }
            slider.assert(SemanticsMatcher("integer stop $index") { node ->
                val info = node.config[SemanticsProperties.ProgressBarRangeInfo]
                kotlin.math.abs(info.current - index) < 0.001f && info.range == 0f..6f && info.steps == 5
            }).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$edge px"))
            compose.onNodeWithText("$edge px").assertExists()
        }
        compose.runOnIdle { assertEquals(selectedIndices.map(expected::get), edges) }
    }

    private fun range(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0) = SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(value, range, steps),
    )
}
