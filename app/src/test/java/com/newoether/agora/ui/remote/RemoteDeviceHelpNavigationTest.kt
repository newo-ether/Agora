package com.newoether.agora.ui.remote

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.newoether.agora.R
import com.newoether.agora.remote.RemoteState
import com.newoether.agora.remote.RemoteViewModel
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h900dp-mdpi")
class RemoteDeviceHelpNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val vm = mockk<RemoteViewModel>(relaxed = true)
    private var active by mutableStateOf(true)
    private var state = RemoteState(restoring = false, addingDevice = true)
    private var backs = 0
    private lateinit var strings: Map<Int, String>
    private fun show() {
        every { vm.editorConnection() } returns null
        compose.setContent {
            val context = LocalContext.current
            strings = listOf(R.string.back, R.string.save, R.string.shell_device_name,
                R.string.remote_address, R.string.remote_token).associateWith { context.getString(it) }
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true)) {
                MaterialTheme { RemoteAddDevice(state, vm, active, { backs++ }, {}) }
            }
        }
    }
    private fun fill() {
        // Inputs share the original MCP field owner and appear in name/address/token order.
        compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement("Draft host")
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextReplacement("http://computer:8080")
        compose.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextReplacement("draft-token")
    }
    @Test fun helpIsFirstAndReturnsToAllThreeDraftFieldsWithoutSaving() {
        show()
        compose.onNodeWithTag("remote-filo-guide-entry").assertIsDisplayed()
        fill()
        compose.onNodeWithTag("remote-filo-guide-entry").performScrollTo().performClick()
        compose.onNodeWithTag("filo-install-guide").assertExists()
        verify(exactly = 0) { vm.saveDevice(any(), any(), any()) }
        compose.onNodeWithContentDescription(strings.getValue(R.string.back)).performClick()
        compose.onAllNodes(hasSetTextAction())[0].assertTextContains("Draft host")
        compose.onAllNodes(hasSetTextAction())[1].assertTextContains("http://computer:8080")
        compose.onNodeWithContentDescription(strings.getValue(R.string.save)).assertIsEnabled().performClick()
        verify(exactly = 1) { vm.saveDevice("http://computer:8080", "draft-token", "Draft host") }
        assertEquals(0, backs)
    }
    @Test fun helpBackReturnsToDraftAndClosedEditorCallbacksCannotRevive() {
        show()
        fill()
        val save = requireNotNull(compose.onNodeWithContentDescription(strings.getValue(R.string.save))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.onNodeWithTag("remote-filo-guide-entry").performScrollTo().performClick()
        compose.onNodeWithContentDescription(strings.getValue(R.string.back)).performClick()
        compose.onAllNodes(hasSetTextAction())[0].assertTextContains("Draft host")
        compose.runOnIdle { save() }
        verify(exactly = 0) { vm.saveDevice(any(), any(), any()) }
        compose.runOnIdle { active = false }
        compose.waitForIdle()
        compose.runOnIdle { active = true }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(strings.getValue(R.string.save)).assertIsNotEnabled()
    }
    @Test fun editHasNoAddDeviceHelpEntry() {
        state = state.copy(editedDeviceId = "existing")
        show()
        compose.onNodeWithTag("remote-filo-guide-entry").assertDoesNotExist()
    }
}
