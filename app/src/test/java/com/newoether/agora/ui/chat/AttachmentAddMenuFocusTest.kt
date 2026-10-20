package com.newoether.agora.ui.chat

import android.app.Application
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.newoether.agora.ui.chat.bottombar.AttachmentAddMenu
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class AttachmentAddMenuFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ordinaryMenuDoesNotTakeTheKeyboardWindowFocus() = verifyMenu(false)
    @Test fun einkMenuDoesNotTakeTheKeyboardWindowFocus() = verifyMenu(true)

    private fun verifyMenu(eink: Boolean) {
        val text = TextFieldState("keep my draft")
        var selected = 0
        lateinit var manager: WindowManager
        compose.setContent {
            manager = LocalView.current.context.getSystemService(WindowManager::class.java)
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(false, eink)) {
                MaterialTheme { Column {
                    BasicTextField(text, Modifier.testTag("draft"))
                    AttachmentAddMenu(true, {}, { selected++ }, {}, {})
                } }
            }
        }
        compose.onNodeWithTag("draft").performClick().assertIsFocused()
        compose.onNodeWithContentDescription("Add Attachment").performClick()
        compose.onNodeWithText("Photos").assertExists()
        compose.onNodeWithTag("draft").assertIsFocused()
        compose.runOnIdle {
            val shadow = Shadow.extract<ShadowWindowManagerImpl>(manager)
            val popup = shadow.views.mapNotNull { it.layoutParams as? WindowManager.LayoutParams }
                .single { it.type == WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL }
            assertTrue(popup.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertEquals("keep my draft", text.text.toString())
        }
        compose.onNodeWithText("Photos").performClick()
        compose.onNodeWithText("Photos").assertDoesNotExist()
        compose.onNodeWithTag("draft").assertIsFocused()
        compose.runOnIdle { assertEquals(1, selected); assertEquals("keep my draft", text.text.toString()) }
    }
}
