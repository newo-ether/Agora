@file:Suppress("DEPRECATION")

package com.newoether.agora.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class SettingsInstallationGuidesTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var clipboard: ClipboardManager
    private lateinit var strings: Map<Int, String>
    private var active by mutableStateOf(true)
    private var entry by mutableStateOf(0)
    private var adds = 0
    private var backs = 0
    private val openedUrls = mutableListOf<String>()
    private var missingBrowser = false

    private fun show(conch: Boolean = true, largeFont: Boolean = false, rtl: Boolean = false) {
        compose.setContent {
            val context = LocalContext.current
            val density = LocalDensity.current
            clipboard = LocalClipboardManager.current
            strings = listOf(
                R.string.back, R.string.install_guide_purpose, R.string.install_guide_install,
                R.string.install_guide_project_address, R.string.install_guide_platform, R.string.copy,
                R.string.conch_guide_description,
                R.string.filo_guide_description, R.string.install_guide_windows, R.string.install_guide_linux,
                R.string.install_guide_termux, R.string.conch_guide_windows_terminal,
                R.string.conch_guide_system_mode_desc, R.string.conch_guide_system_mode,
                R.string.conch_guide_user_mode, R.string.conch_guide_linger,
                R.string.conch_guide_mode_preserved, R.string.filo_guide_selected_user,
                R.string.filo_guide_scope, R.string.filo_guide_address_mapping,
            ).associateWith { context.getString(it) }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeFont) 1.6f else density.fontScale),
                LocalSettingsScrollRetentionActive provides active,
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true),
                LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) {
                        openedUrls += uri
                        if (missingBrowser) throw IllegalArgumentException("No browser")
                    }
                },
            ) {
                MaterialTheme {
                    Box(Modifier.size(if (largeFont) 320.dp else 400.dp, 600.dp)) {
                        key(entry) {
                            if (conch) SettingsConchGuidePage { backs++ }
                            else SettingsFiloGuidePage { backs++ }
                        }
                    }
                }
            }
        }
        compose.runOnIdle { clipboard.setText(AnnotatedString("unchanged")) }
    }

    private fun choose(platform: Int) {
        compose.onNode(hasText(strings.getValue(platform)) and hasClickAction())
            .performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun copyAndAssert(prefix: String, expected: String) {
        compose.onNodeWithTag("$prefix-command").assertTextContains(expected)
        compose.onNodeWithTag("$prefix-command-copy")
            .assert(hasAnyAncestor(hasTestTag("$prefix-command")))
            .performScrollTo().performClick()
        compose.runOnIdle { assertEquals(expected, clipboard.getText()?.text) }
    }

    private fun platformTab(index: Int): SemanticsNodeInteraction {
        val label = listOf(R.string.install_guide_windows, R.string.install_guide_linux, R.string.install_guide_termux)[index]
        return compose.onNode(hasText(strings.getValue(label)) and hasClickAction())
    }

    private fun assertPlatformLabelFits(index: Int) {
        val label = listOf(R.string.install_guide_windows, R.string.install_guide_linux, R.string.install_guide_termux)[index]
        val node = compose.onNodeWithText(strings.getValue(label), useUnmergedTree = true)
        node.performScrollTo().assertIsDisplayed()
        platformTab(index).assertHeightIsAtLeast(44.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse("Platform label $index overflow: size=${layout.size}, lines=${layout.lineCount}, " +
            "height=${layout.didOverflowHeight}, width=${layout.didOverflowWidth}, " +
            "bounds=${node.getUnclippedBoundsInRoot()}, text=${layout.layoutInput.text}", layout.hasVisualOverflow)
        for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
    }

    private fun capturedClick(tag: String): () -> Boolean = requireNotNull(
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action,
    )

    @Test
    fun conchWindowsShowsThreeStepsAndCopiesOnlyTheUserCommand() {
        show()
        compose.onNodeWithTag("conch-guide-introduction")
            .assert(hasAnyDescendant(hasText(strings.getValue(R.string.conch_guide_description))))
        compose.onNodeWithTag("conch-guide-platform")
            .assert(hasAnyAncestor(hasTestTag("conch-guide-installation")))
        val cardTop = compose.onNodeWithTag("conch-guide-platform").getUnclippedBoundsInRoot().top
        val titleTop = compose.onNodeWithText(strings.getValue(R.string.install_guide_platform))
            .getUnclippedBoundsInRoot().top
        assertEquals(16f, (titleTop - cardTop).value, 0.5f)
        platformTab(0).assertIsSelected()
        compose.onAllNodes(hasContentDescription(strings.getValue(R.string.copy)) and hasClickAction())
            .assertCountEquals(1)
        compose.onNodeWithTag("conch-guide-return").assertDoesNotExist()
        compose.onNodeWithTag("conch-guide-source")
            .assert(hasAnyAncestor(hasTestTag("conch-guide-project")))
            .assertTextContains("github.com/newo-ether/conch")
        compose.onNodeWithText(strings.getValue(R.string.install_guide_purpose)).assertExists()
        compose.onNodeWithText(strings.getValue(R.string.install_guide_install)).assertExists()
        compose.onNodeWithText(strings.getValue(R.string.install_guide_project_address)).assertExists()
        compose.onNodeWithTag("conch-guide-system-mode").assertDoesNotExist()
        compose.onNodeWithText("1").assertExists()
        compose.onNodeWithText("2").assertExists()
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithTag("conch-guide-terminal-note")
            .assertTextEquals(strings.getValue(R.string.conch_guide_windows_terminal))
        copyAndAssert("conch-guide", CONCH_USER_WINDOWS_INSTALL)
        compose.runOnIdle {
            assertEquals(0, backs)
            assertTrue(openedUrls.isEmpty())
        }
    }

    @Test
    fun linuxAndTermuxCopyTheUserCommandWithoutAdvancedOptions() {
        show()
        choose(R.string.install_guide_linux)
        copyAndAssert("conch-guide", CONCH_USER_UNIX_INSTALL)
        choose(R.string.install_guide_termux)
        compose.onNodeWithTag("conch-guide-system-mode").assertDoesNotExist()
        copyAndAssert("conch-guide", CONCH_USER_UNIX_INSTALL)
        choose(R.string.install_guide_windows)
        copyAndAssert("conch-guide", CONCH_USER_WINDOWS_INSTALL)
    }

    @Test
    fun filoCopiesTheMaintainedCommandAndReturnAndBackAreSingleEntryActions() {
        show(conch = false)
        compose.onNodeWithTag("filo-guide-introduction")
            .assert(hasAnyDescendant(hasText(strings.getValue(R.string.filo_guide_description))))
        compose.onNodeWithTag("filo-guide-source")
            .assert(hasAnyAncestor(hasTestTag("filo-guide-project")))
            .assertTextContains("github.com/newo-ether/filo")
        compose.onNodeWithTag("filo-guide-installation").assertExists()
        compose.onNodeWithText("1").assertExists()
        compose.onNodeWithText("2").assertExists()
        compose.onNodeWithText("3").assertExists()
        copyAndAssert("filo-guide", FILO_WINDOWS_INSTALL)
        compose.onNodeWithTag("filo-guide-return").assertDoesNotExist()
        compose.runOnIdle { entry++ }
        compose.waitForIdle()
        val back = requireNotNull(compose.onNodeWithContentDescription(strings.getValue(R.string.back))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnIdle {
            back()
            back()
            assertEquals(1, backs)
        }
    }

    @Test
    fun retainedConchEntryCannotReviveItsOldAddBackCopyOrSourceCallbacks() {
        show()
        val copy = capturedClick("conch-guide-command-copy")
        val source = capturedClick("conch-guide-source")
        val selectLinux = requireNotNull(platformTab(1).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val back = requireNotNull(compose.onNodeWithContentDescription(strings.getValue(R.string.back))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnIdle { active = false }
        compose.waitForIdle()
        compose.runOnIdle { active = true }
        compose.waitForIdle()
        compose.onNodeWithTag("conch-guide-command-copy").assertIsNotEnabled()
        for (index in 0..2) platformTab(index).assertIsNotEnabled()
        compose.runOnIdle {
            back()
            copy()
            source()
            selectLinux()
            assertEquals(0, adds)
            assertEquals(0, backs)
            assertEquals("unchanged", clipboard.getText()?.text)
            assertTrue(openedUrls.isEmpty())
        }
        platformTab(0).assertIsSelected()
        compose.onNodeWithTag("conch-guide-command").assertTextContains(CONCH_USER_WINDOWS_INSTALL)
    }

    @Test
    fun sourceNeedsAnExplicitTapAndMissingBrowserDoesNotCrashTheGuide() {
        show(conch = false)
        compose.runOnIdle { assertTrue(openedUrls.isEmpty()) }
        compose.onNodeWithTag("filo-guide-source").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("https://github.com/newo-ether/filo"), openedUrls)
            missingBrowser = true
        }
        compose.onNodeWithTag("filo-guide-source").performClick()
        compose.onNodeWithTag("filo-install-guide").assertExists()
        compose.runOnIdle { assertEquals(2, openedUrls.size) }
    }

    @Test
    fun narrowLargeFontRtlPlatformChipsKeepFullLabelsAndTheirOriginalDestinations() {
        show(largeFont = true, rtl = true)
        for (index in 0..2) {
            assertPlatformLabelFits(index)
            platformTab(index).performScrollTo().performClick()
            platformTab(index).assertIsSelected()
            for (other in 0..2) if (other != index) platformTab(other).assertIsNotSelected()
            val expected = if (index == 0) CONCH_USER_WINDOWS_INSTALL else CONCH_USER_UNIX_INSTALL
            copyAndAssert("conch-guide", expected)
        }
        compose.runOnIdle {
            assertEquals(0, adds)
            assertEquals(0, backs)
            assertTrue(openedUrls.isEmpty())
        }
    }

    @Test
    fun narrowLargeFontGuideKeepsCopyReachableByScrolling() {
        show(largeFont = true)
        for (index in 0..2) assertPlatformLabelFits(index)
        copyAndAssert("conch-guide", "Set-ExecutionPolicy Bypass -Scope Process -Force; irm https://raw.githubusercontent.com/newo-ether/conch/main/scripts/install.ps1 | iex")
        compose.runOnIdle {
            assertEquals(0, adds)
            assertEquals(0, backs)
            assertTrue(openedUrls.isEmpty())
        }
    }
}
