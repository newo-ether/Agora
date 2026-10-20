@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.newoether.agora.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CollapsibleChatHeaderUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun normalToolbarCollapsesAndRevealsWithGesturesAndButton() = gestureRoundTrip(false)
    @Test fun einkToolbarKeepsItsExistingSnapBehavior() = gestureRoundTrip(true)

    private fun gestureRoundTrip(eink: Boolean) {
        var height = 0
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(eink, eink)) {
            MaterialTheme {
                val state = rememberCollapsibleChatHeader("fixture", enabled = true)
                Column(Modifier.fillMaxSize()) {
                    CollapsibleChatHeader(state, reading = false, onHeightChanged = { height = it }) {
                        Box(Modifier.fillMaxWidth().height(180.dp)) { Text("Toolbar content") }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("messages").nestedScroll(state.connection)) {
                        items(100) { Text("Message $it", Modifier.height(64.dp)) }
                    }
                }
            }
            }
        }
        var expanded = 0
        compose.runOnIdle { expanded = height }
        compose.onNodeWithTag("messages").performTouchInput { swipeUp() }
        compose.onNodeWithContentDescription("Show toolbar").assertIsDisplayed()
        compose.onNodeWithText("Toolbar content").assertDoesNotExist()
        compose.runOnIdle { assertTrue(height < expanded) }
        compose.onNodeWithContentDescription("Show toolbar").performClick()
        compose.onNodeWithText("Toolbar content").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show toolbar").assertDoesNotExist()
        compose.onNodeWithTag("messages").performTouchInput { swipeUp() }
        compose.onNodeWithContentDescription("Show toolbar").assertIsDisplayed()
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.onNodeWithText("Toolbar content").assertIsDisplayed()
    }
    @Test fun normalControllerTracksSmallDragsAndPinsOpenForSearch() {
        lateinit var header: CollapsibleChatHeader
        var enabled by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                header = rememberCollapsibleChatHeader("fixture", enabled)
                CollapsibleChatHeader(header, false, {}) {
                    Box(Modifier.fillMaxWidth().height(180.dp)) { Text("Toolbar content") }
                }
            }
        }
        compose.runOnIdle {
            assertNotNull(header.material)
            header.connection.onPreScroll(Offset(0f, -15f), NestedScrollSource.SideEffect)
            assertEquals(0f, header.material!!.state.heightOffset, 0.01f)
            header.connection.onPreScroll(Offset(0f, -15f), NestedScrollSource.UserInput)
            assertEquals(-15f, header.material!!.state.heightOffset, 0.01f)
        }
        compose.runOnIdle {
            // The same Material controller survives layout/recomposition during the drag.
            assertEquals(-15f, header.material!!.state.heightOffset, 0.01f)
            header.connection.onPreScroll(Offset(0f, 5f), NestedScrollSource.UserInput)
            assertEquals(-10f, header.material!!.state.heightOffset, 0.01f)
            enabled = false
        }
        compose.runOnIdle {
            assertEquals(0f, header.material!!.state.heightOffset, 0.01f)
            header.connection.onPreScroll(Offset(0f, -100f), NestedScrollSource.UserInput)
            assertEquals(0f, header.material!!.state.heightOffset, 0.01f)
        }
    }

}
