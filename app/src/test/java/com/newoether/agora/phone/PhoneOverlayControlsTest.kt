package com.newoether.agora.phone

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.ui.theme.AgoraTheme
import com.newoether.agora.viewmodel.AskUserController
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
class PhoneOverlayControlsTest {
    @get:Rule val compose = createComposeRule()
    private val lease = PhoneControlLease { 0L }
    private val action = MutableStateFlow<String?>(null)
    private val runtime = mockk<PhoneUseRuntime> {
        every { lease } returns this@PhoneOverlayControlsTest.lease
        every { askUser } returns AskUserController()
        every { actionName } returns action
    }
    private val bounds = mutableMapOf<String, Rect>()
    private var frameWidth by mutableStateOf(360.dp)
    private var frameHeight by mutableStateOf(500.dp)
    private var direction by mutableStateOf(LayoutDirection.Ltr)

    private fun start() {
        lease.claim(RunEffectIdentity("a", 1, "run", 0, "batch"), "A")
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                AgoraTheme {
                    Box(Modifier.size(frameWidth, frameHeight)) {
                        PhoneControls(runtime, lease.held.value!!.epoch, {}, { key, rect ->
                            if (rect == null) bounds.remove(key) else bounds[key] = rect
                        })
                    }
                }
            }
        }
    }

    @Test fun actualControlBoundsMoveAndClampOnDragAndFrameResize() {
        start()
        val initial = bounds.getValue("strip")
        compose.onNodeWithText("0:00").performTouchInput {
            down(center); moveBy(Offset(80f, 90f), delayMillis = 100); up()
        }
        compose.waitForIdle()
        val moved = bounds.getValue("strip")
        assertTrue(moved.left > initial.left && moved.top > initial.top)
        compose.onNodeWithText("0:00").performTouchInput {
            down(center); moveBy(Offset(1000f, 1000f), delayMillis = 100); up()
        }
        compose.waitForIdle()
        val edge = bounds.getValue("strip")
        assertTrue(edge.right <= 348f && edge.bottom <= 494f)
        val restDuration = compose.onNodeWithText("0:00").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("0:00").performTouchInput { down(center) }
        compose.waitForIdle()
        val pressedStop = compose.onNodeWithContentDescription("Stop").fetchSemanticsNode().boundsInRoot
        val pressedDuration = compose.onNodeWithText("0:00").fetchSemanticsNode().boundsInRoot
        assertEquals(restDuration.width * 1.05f, pressedDuration.width, 0.3f)
        assertTrue(pressedStop.right <= 348f && pressedStop.bottom <= 494f)
        compose.onNodeWithText("0:00").performTouchInput { cancel() }
        compose.waitForIdle()
        compose.runOnIdle { frameWidth = 300.dp; frameHeight = 260.dp; action.value = "phone_swipe" }
        compose.waitForIdle()
        val resized = bounds.getValue("strip")
        assertTrue(resized.left >= 12f && resized.top >= 6f)
        assertTrue(resized.right <= 288f && resized.bottom <= 254f)
        assertEquals(setOf("strip"), bounds.keys)
    }

    @Test fun physicalDragDirectionAndInitialTopLeftAreStableInRtl() {
        direction = LayoutDirection.Rtl
        start()
        val initial = bounds.getValue("strip")
        assertEquals(12f, initial.left, 0.1f)
        compose.onNodeWithText("0:00").performTouchInput {
            down(center); moveBy(Offset(60f, 40f), delayMillis = 100); up()
        }
        compose.waitForIdle()
        val moved = bounds.getValue("strip")
        assertTrue(moved.left > initial.left && moved.top > initial.top)
    }
}
