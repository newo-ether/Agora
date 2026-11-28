package com.newoether.agora.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.sandbox.SandboxMirror
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.ChatViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h900dp-mdpi")
class SettingsShellGuideNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val settings = mockk<SettingsRepository>()
    private val viewModel = mockk<ChatViewModel>()
    private val devices = MutableStateFlow<List<ShellDeviceConfig>>(emptyList())
    private val additions = mutableListOf<ShellDeviceConfig>()
    private val results = mutableListOf<(Boolean) -> Unit>()
    private val retention = SettingsScrollRetention()
    private var active by mutableStateOf(true)
    private var backs = 0
    private lateinit var strings: Map<Int, String>

    private fun show(existing: List<ShellDeviceConfig> = emptyList()) {
        devices.value = existing
        every { viewModel.settings } returns settings
        every { viewModel.isSandboxFlavor } returns false
        every { settings.shellEnabled } returns MutableStateFlow(true)
        every { settings.shellConfirmEnabled } returns MutableStateFlow(true)
        every { settings.shellDevices } returns devices
        every { settings.sandboxEnabled } returns MutableStateFlow(false)
        every { settings.sandboxSharedStorageEnabled } returns MutableStateFlow(false)
        every { settings.sandboxMirror } returns MutableStateFlow(SandboxMirror.DEFAULT)
        every { settings.showDocumentationFab } returns MutableStateFlow(true)
        every { settings.addShellDevice(any(), any(), any()) } answers {
            additions += firstArg<ShellDeviceConfig>()
            results += secondArg<(Boolean) -> Unit>()
            Job().apply { complete() }
        }
        compose.setContent {
            val context = LocalContext.current
            strings = listOf(
                R.string.back, R.string.save, R.string.unsaved_changes_discard,
                R.string.documentation, R.string.shell_add_device,
            ).associateWith { context.getString(it) }
            CompositionLocalProvider(
                LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true),
                LocalSettingsScrollRetention provides retention,
                LocalSettingsScrollRetentionActive provides active,
            ) {
                MaterialTheme {
                    Box(Modifier.size(400.dp, 600.dp)) {
                        SettingsShellPage(viewModel) { backs++ }
                    }
                }
            }
        }
    }

    private fun guideEntry() = compose.onNodeWithTag("shell-conch-guide-entry")
    private fun addDevice() {
        compose.onNodeWithText(strings.getValue(R.string.shell_add_device)).performScrollTo().performClick()
        compose.waitForIdle()
    }
    private fun openGuide() {
        guideEntry().performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("conch-install-guide").assertExists()
    }
    private fun back() = compose.onNodeWithContentDescription(strings.getValue(R.string.back))
    private fun name() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("shell-field-name")))
    private fun save() = compose.onNodeWithContentDescription(strings.getValue(R.string.save))
    private fun returnFromGuide() {
        back().performClick()
        compose.waitForIdle()
        name().assertExists()
        compose.onNodeWithTag("conch-install-guide").assertDoesNotExist()
    }
    private fun assertNoPersistence() {
        verify(exactly = 0) { settings.addShellDevice(any(), any(), any()) }
        verify(exactly = 0) { settings.updateShellDevice(any(), any(), any()) }
    }

    @Test
    fun emptyListGuideOpensTheExistingDraftAndBackKeepsItsUnsavedGuard() {
        show()
        guideEntry().assertDoesNotExist()
        addDevice()
        name().performScrollTo().performTextReplacement("Unsaved host")
        openGuide()
        compose.onNodeWithText(strings.getValue(R.string.documentation)).assertDoesNotExist()
        assertNoPersistence()
        returnFromGuide()
        name().assertTextContains("Unsaved host")
        save().assertIsEnabled()
        compose.onNodeWithText(strings.getValue(R.string.documentation)).assertExists()
        name().performScrollTo().performTextReplacement("Unsaved host")
        assertNoPersistence()
        back().performClick()
        compose.onNode(hasText(strings.getValue(R.string.unsaved_changes_discard)) and hasClickAction()).performClick()
        compose.waitForIdle()
        guideEntry().assertDoesNotExist()
        name().assertDoesNotExist()
        assertNoPersistence()
        compose.runOnIdle { assertEquals(0, backs) }
    }

    @Test
    fun populatedListGuideOnlyPersistsAfterExplicitSaveAndReturnsToTheList() {
        val initial = ShellDeviceConfig(id = "existing", name = "Existing host")
        show(listOf(initial))
        addDevice()
        openGuide()
        returnFromGuide()
        name().performScrollTo().performTextReplacement("Added from guide")
        assertNoPersistence()
        save().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Added from guide", additions.single().name)
            assertEquals("conch", additions.single().type)
            devices.value = listOf(initial, additions.single())
            results.single()(true)
        }
        compose.waitForIdle()
        name().assertDoesNotExist()
        guideEntry().assertDoesNotExist()
        compose.onNodeWithTag("shell-device-row:existing").assertExists()
        compose.onNodeWithTag("shell-device-row:${additions.single().id}").assertExists()
        verify(exactly = 1) { settings.addShellDevice(any(), any(), any()) }
        verify(exactly = 0) { settings.updateShellDevice(any(), any(), any()) }
        compose.runOnIdle { assertEquals(0, backs) }
    }

    @Test
    fun helpBackRestoresTheEditorScrollAcrossReentry() {
        show((1..20).map { ShellDeviceConfig(id = "$it", name = "Host $it") })
        addDevice()
        guideEntry().performScrollTo()
        val editorScroll = compose.onNode(hasScrollAction()).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        openGuide()
        compose.onNodeWithTag("conch-guide-source").performScrollTo()
        back().performClick()
        compose.waitForIdle()
        val returnedScroll = compose.onNode(hasScrollAction()).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(editorScroll, returnedScroll)
        openGuide()
        compose.onNodeWithTag("conch-guide-source").performScrollTo().assertIsDisplayed()
        assertNoPersistence()
        compose.runOnIdle { assertEquals(0, backs) }
    }

    @Test
    fun returnedDraftRejectsTheOldPaneSaveCallbackAndKeepsOneExplicitSave() {
        show()
        addDevice()
        name().performScrollTo().performTextReplacement("Draft before help")
        val oldSave = requireNotNull(save().fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        openGuide()
        returnFromGuide()
        compose.runOnIdle { oldSave() }
        assertNoPersistence()
        name().assertTextContains("Draft before help")
        save().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Draft before help", additions.single().name) }
    }

    @Test
    fun categoryLossClosesTheGuideAndReturningCannotRestoreItsOldActions() {
        show()
        addDevice()
        openGuide()
        compose.runOnIdle { active = false }
        compose.waitForIdle()
        compose.runOnIdle { active = true }
        compose.waitForIdle()
        compose.onNodeWithTag("conch-install-guide").assertDoesNotExist()
        guideEntry().assertDoesNotExist()
        name().assertDoesNotExist()
        addDevice()
        openGuide()
        returnFromGuide()
        save().assertIsNotEnabled()
        assertNoPersistence()
    }
}
