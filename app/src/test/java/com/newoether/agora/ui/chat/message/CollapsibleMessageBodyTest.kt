package com.newoether.agora.ui.chat.message

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
class CollapsibleMessageBodyTest {
    @get:Rule val compose = createComposeRule()
    private val searching = mutableStateOf(false)
    private val direction = mutableStateOf(LayoutDirection.Ltr)
    private val longBody = (1..20).joinToString("\n") { "Original line $it" }
    private var drawingView: View? = null
    private var mounts = 0
    private var disposals = 0

    private fun host(body: @Composable () -> Unit) {
        compose.setContent {
            drawingView = LocalView.current
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                MaterialTheme {
                    Box(Modifier.background(Color.Blue)) {
                        CollapsibleMessageBody(
                            messageKey = "message",
                            searchActive = searching.value,
                            modifier = Modifier.width(300.dp).testTag("viewport"),
                        ) {
                            DisposableEffect(Unit) {
                                mounts++
                                onDispose { disposals++ }
                            }
                            body()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun host(text: String = longBody) = host { Column { Text(text) } }

    private fun bounds() = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
    private fun height() = bounds().height

    @Test
    fun longBodyExpandsAndCollapsesWithoutReplacingOrTruncatingItsContent() {
        host()
        val collapsedHeight = height()
        compose.onNodeWithText(longBody, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Expand").performClick()
        assertTrue(height() > collapsedHeight)
        compose.onNodeWithText(longBody, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Collapse").performClick()
        assertEquals(collapsedHeight, height(), 0.1f)
        compose.runOnIdle {
            assertEquals(1, mounts)
            assertEquals(0, disposals)
        }
    }

    @Test
    fun disclosureStaysOnThePhysicalLeftInsideThe320DpViewportAndAfterTheFullBody() {
        host { Box(Modifier.width(300.dp).height(600.dp).testTag("full-body")) }
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle { direction.value = layoutDirection }
            val viewport = bounds()
            val expand = compose.onNode(hasClickAction() and hasText("Expand"))
                .fetchSemanticsNode().boundsInRoot
            assertEquals(320f, viewport.height, 0.1f)
            assertEquals(viewport.left, expand.left, 0.1f)
            // Material exposes its 40dp inner button in semantics, centered in a 48dp touch target.
            compose.onNode(hasClickAction() and hasText("Expand")).assertTouchHeightIsEqualTo(48.dp)
            assertEquals(viewport.bottom - 24f, expand.center.y, 0.1f)
            assertTrue(expand.top >= viewport.bottom - 48f && expand.bottom <= viewport.bottom)

            compose.onNodeWithText("Expand").performClick()
            val fullBody = compose.onNodeWithTag("full-body").fetchSemanticsNode().boundsInRoot
            val collapse = compose.onNode(hasClickAction() and hasText("Collapse"))
                .fetchSemanticsNode().boundsInRoot
            assertEquals(600f, fullBody.height, 0.1f)
            assertTrue(collapse.top >= fullBody.bottom)
            assertEquals(bounds().left, collapse.left, 0.1f)
            compose.onNode(hasClickAction() and hasText("Collapse")).assertTouchHeightIsEqualTo(48.dp)
            assertEquals(bounds().bottom - 24f, collapse.center.y, 0.1f)
            assertEquals(fullBody.bottom + 48f, bounds().bottom, 0.1f)
            compose.onNodeWithText("Collapse").performClick()
        }
    }

    private fun pixels(): Bitmap {
        compose.waitForIdle()
        val viewport = bounds()
        val bitmap = Bitmap.createBitmap(viewport.width.toInt(), viewport.height.toInt(), Bitmap.Config.ARGB_8888)
        compose.runOnUiThread {
            val canvas = Canvas(bitmap)
            canvas.translate(-viewport.left, -viewport.top)
            requireNotNull(drawingView).draw(canvas)
        }
        return bitmap
    }

    @Test
    fun collapsedPixelsFadeToTheActualBackdropAndLeaveTheFooterClear() {
        host { Box(Modifier.width(300.dp).height(600.dp).background(Color.Red)) }
        val collapsed = pixels()
        try {
            fun assertColor(y: Int, red: Float, blue: Float) {
                val actual = Color(collapsed.getPixel(292, y))
                assertEquals("red at y=$y", red, actual.red, 0.05f)
                assertEquals("blue at y=$y", blue, actual.blue, 0.05f)
                assertEquals("opaque backdrop at y=$y", 1f, actual.alpha, 0.01f)
            }
            assertColor(190, red = 1f, blue = 0f)
            assertColor(240, red = 0.5f, blue = 0.5f)
            assertColor(280, red = 0f, blue = 1f)
            assertColor(310, red = 0f, blue = 1f)
        } finally { collapsed.recycle() }

        compose.onNodeWithText("Expand").performClick()
        val expanded = pixels()
        try {
            assertEquals(Color.Red, Color(expanded.getPixel(8, 310)))
            assertEquals(Color.Red, Color(expanded.getPixel(8, 590)))
        } finally { expanded.recycle() }
        compose.runOnIdle {
            assertEquals(1, mounts)
            assertEquals(0, disposals)
        }
    }

    @Test
    fun searchRevealsTheFullBodyAndRestoresThePreviousManualChoice() {
        host()
        val collapsedHeight = height()
        compose.runOnIdle { searching.value = true }
        assertTrue(height() > collapsedHeight)
        compose.onNodeWithText("Expand").assertDoesNotExist()
        compose.runOnIdle { searching.value = false }
        compose.onNodeWithText("Expand").assertExists()
        assertEquals(collapsedHeight, height(), 0.1f)
        compose.onNodeWithText("Expand").performClick()
        compose.runOnIdle { searching.value = true }
        compose.runOnIdle { searching.value = false }
        compose.onNodeWithText("Collapse").assertExists()
    }

    @Test
    fun shortBodyKeepsItsIntrinsicHeightWithoutDisclosure() {
        host("Short body")
        assertTrue(height() < 320f)
        compose.onNodeWithText("Expand").assertDoesNotExist()
        compose.onNodeWithText("Collapse").assertDoesNotExist()
    }
}
