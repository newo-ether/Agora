package com.newoether.agora.ui.chat.bottombar

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.ui.theme.withAmoledBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.Test

/**
 * The reserved part of the composition bar must describe the same boundary the warning state uses,
 * otherwise the bar would show free budget the automatic compaction has already claimed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContextCompositionBarTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

    @Test fun totalOnlyShowsUsedInPrimaryEvenOverCompactThresholdAndOrdinaryKeepsCategories() {
        var breakdown by mutableStateOf(false)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color.Green, error = Color.Red)) {
                Column(Modifier.width(240.dp)) {
                    ContextCompositionBar(0, 0, 95_000, 100_000, 90, true, true,
                        showBreakdown = breakdown)
                    ComposerContextIndicator(95_000, 100_000, showBreakdown = breakdown,
                        expanded = false, onClick = {}, onDismissRequest = {})
                }
            }
        }
        compose.onNodeWithText(resources.getString(R.string.context_part_used)).assertExists()
        // Free is the budget left above the total; it needs no category or threshold data.
        compose.onNodeWithText(resources.getString(R.string.context_part_free)).assertExists()
        val categories = listOf(R.string.context_part_reserved, R.string.context_part_system,
            R.string.context_part_tools, R.string.context_part_messages)
        categories.forEach { compose.onNodeWithText(resources.getString(it)).assertDoesNotExist() }
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Used segment and ring must draw primary", pixels.count { it == Color.Green.toArgb() } > 0)
        assertEquals("Remote cannot show compact warning red", 0, pixels.count { it == Color.Red.toArgb() })
        compose.runOnIdle { breakdown = true }
        categories.forEach { compose.onNodeWithText(resources.getString(it)).assertExists() }
        compose.onNodeWithText(resources.getString(R.string.context_part_used)).assertDoesNotExist()
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Ordinary compact warning stays red", pixels.count { it == Color.Red.toArgb() } > 0)
        bitmap.recycle()
    }

    @Test fun categorySegmentsAndLegendFollowThemeChanges() {
        var scheme by mutableStateOf(lightColorScheme())
        var warning by mutableStateOf(false)
        compose.setContent {
            MaterialTheme(colorScheme = scheme) {
                Column(Modifier.width(240.dp)) {
                    ContextCompositionBar(100, 150, 200, 1_000, 80, true, warning)
                }
            }
        }
        val schemes = buildList {
            for (preset in com.newoether.agora.ui.theme.ColorSchemePreset.entries) {
                for (style in com.newoether.agora.ui.theme.SchemeStyle.entries) {
                    for (dark in listOf(false, true)) {
                        val resolved = com.newoether.agora.ui.theme.colorSchemeForPreset(preset, style, dark)
                        add(resolved)
                        if (dark) add(resolved.withAmoledBackground(dark, true))
                    }
                }
            }
            add(androidx.compose.material3.dynamicLightColorScheme(compose.activity))
            add(androidx.compose.material3.dynamicDarkColorScheme(compose.activity))
        }
        for (resolved in schemes) {
            compose.runOnIdle { scheme = resolved; warning = false }
            compose.waitForIdle()
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height,
                android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                for (color in listOf(resolved.primary, resolved.secondary, resolved.tertiary,
                    resolved.surfaceVariant, resolved.outline)) {
                    val rows = pixels.indices.filter { pixels[it] == color.toArgb() }
                        .map { it / bitmap.width }
                    assertTrue("Theme role ${color.toArgb().toUInt().toString(16)} must paint segment and legend", rows.isNotEmpty())
                    assertTrue("Segment and legend must be separate", rows.max() - rows.min() > 12)
                }
                compose.runOnIdle { warning = true }
                compose.waitForIdle()
                compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Warning must use the resolved error role", pixels.any { it == resolved.error.toArgb() })
            } finally { bitmap.recycle() }
        }
    }

    @Test fun unknownTelemetryCannotOpenOrFabricateUsage() {
        compose.setContent {
            MaterialTheme {
                ComposerContextIndicator(null, null, showBreakdown = false,
                    expanded = true, onClick = {}, onDismissRequest = {})
            }
        }
        compose.onNodeWithContentDescription(resources.getString(R.string.context_title)).assertIsNotEnabled()
        compose.onNodeWithText(resources.getString(R.string.context_part_used)).assertDoesNotExist()
    }

    @Test fun zeroAndOverBudgetTotalsAreNotClippedInTheUsedLegend() {
        var total by mutableStateOf(0)
        compose.setContent {
            MaterialTheme {
                ContextCompositionBar(0, 0, total, 100_000, 90, true, true, showBreakdown = false)
            }
        }
        compose.onNodeWithText("0").assertExists()
        // With nothing reserved, an empty window is entirely free.
        compose.onNodeWithText(com.newoether.agora.model.ContextBudget.compactLabel(100_000)).assertExists()
        compose.runOnIdle { total = 120_000 }
        compose.onNodeWithText("117.2K").assertExists()
    }

    @Test
    fun `usage percent is used over the whole budget, rounded, reserve not counted`() {
        // 462.7K of 512K, the same whether or not 51.2K is reserved for compaction.
        assertEquals(90, contextUsagePercent(estimatedTokens = 462_700, tokenBudget = 512_000))
        assertEquals(1, contextUsagePercent(estimatedTokens = 5, tokenBudget = 1_000))
        assertEquals(0, contextUsagePercent(estimatedTokens = 4, tokenBudget = 1_000))
        assertEquals(0, contextUsagePercent(estimatedTokens = 10, tokenBudget = 0))
    }
}
