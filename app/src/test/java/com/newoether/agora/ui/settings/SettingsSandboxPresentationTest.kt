package com.newoether.agora.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.sandbox.SandboxDistribution
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSandboxPresentationTest {
    @get:Rule val compose = createComposeRule()

    private val debianVersion = SandboxDistribution.DEBIAN.let { "${it.displayName} ${it.versionLabel}" }
    private val selected = MutableStateFlow(SandboxDistribution.DEBIAN)
    private val busy = MutableStateFlow(false)
    private val installing = MutableStateFlow(false)
    private val progress = MutableStateFlow<Float?>(null)
    private val sharedStorage = mutableStateOf(false)
    private val pageMessages = mutableListOf<String>()
    private var installed = false
    private var diagnostic: String? = null
    private val packages = MutableStateFlow(emptyList<SandboxManager.PackageInfo>())
    private val manager = mockk<SandboxManager> {
        every { distribution } returns selected
        every { isBusy } returns busy
        every { isInstallingRootfs } returns installing
        every { downloadProgress } returns progress
        every { terminalOutput } returns MutableStateFlow("")
        every { packageList } returns packages
        every { pendingPkgName } returns ""
        every { pendingPkgName = any() } returns Unit
        every { lastError } answers { diagnostic }
        every { isAvailableSync() } answers { installed }
        coEvery { isAvailable() } coAnswers {
            diagnostic = if (installed) null else NOT_INSTALLED_DETAIL
            installed
        }
        coEvery { getDiskUsageMB() } returns 0L
        coEvery { refreshPackageList() } returns Unit
        coEvery { selectDistribution(any()) } coAnswers {
            selected.value = firstArg()
            true
        }
        coEvery { reset() } coAnswers {
            installed = false
            true
        }
        every { installRootfs() } answers {
            busy.value = true
            installing.value = true
        }
    }

    @Test fun uninstalledRowsMatchTypographyAndChoosingADistributionDoesNotInstall() {
        showPage()
        val selectedLabel = compose.onNodeWithText("Debian", useUnmergedTree = true)
        val installLabel = compose.onNodeWithText(text(R.string.sandbox_install), useUnmergedTree = true)
        val selectedStyle = layout(selectedLabel).layoutInput.style
        val installStyle = layout(installLabel).layoutInput.style
        assertEquals(14.sp, installStyle.fontSize)
        assertEquals(selectedStyle.fontSize, installStyle.fontSize)
        assertEquals(selectedStyle.fontWeight, installStyle.fontWeight)
        assertEquals(selectedStyle.color, installStyle.color)
        assertEquals(TextAlign.End, installStyle.textAlign)
        assertEquals(selectedLabel.getUnclippedBoundsInRoot().right, installLabel.getUnclippedBoundsInRoot().right)
        val rowBounds = row(debianVersion).getUnclippedBoundsInRoot()
        assertTrue(rowBounds.bottom - rowBounds.top >= 48.dp)
        compose.onNodeWithText(text(R.string.sandbox_not_installed)).assertIsDisplayed()
        compose.onNodeWithText(NOT_INSTALLED_DETAIL).assertDoesNotExist()

        row(text(R.string.sandbox_distribution)).performClick()
        val ubuntu = SandboxDistribution.UBUNTU
        compose.onNode(hasText("${ubuntu.displayName} ${ubuntu.versionLabel}") and hasAnyAncestor(isPopup()))
            .performClick()
        row("${ubuntu.displayName} ${ubuntu.versionLabel}").assertIsEnabled()
        verify(exactly = 0) { manager.installRootfs() }
        compose.runOnIdle { assertTrue(pageMessages.isEmpty()) }
    }

    @Test fun failedInstallRestoresRetryWithoutRenderingOrPostingTheProbeError() {
        showPage()
        row(debianVersion).performClick()
        row(text(R.string.sandbox_distribution)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.sandbox_install)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sandbox_extracting_rootfs)).assertIsDisplayed()
        compose.runOnIdle { progress.value = 0.42f }
        compose.onNodeWithText(text(R.string.sandbox_downloading_rootfs, 42)).assertIsDisplayed()

        compose.runOnIdle {
            diagnostic = "Download or verification failed"
            busy.value = false
            installing.value = false
            progress.value = null
            sharedStorage.value = true
        }
        compose.onNodeWithText(text(R.string.sandbox_not_installed)).assertIsDisplayed()
        compose.onNodeWithText(NOT_INSTALLED_DETAIL).assertDoesNotExist()
        compose.onNodeWithText("Download or verification failed").assertDoesNotExist()
        compose.runOnIdle { assertTrue(pageMessages.isEmpty()) }
        row(debianVersion).assertIsEnabled().performClick()
        verify(exactly = 2) { manager.installRootfs() }
    }

    @Test fun successfulInstallHidesSetupAndResetRestoresIt() {
        showPage()
        row(debianVersion).performClick()
        row(text(R.string.sandbox_distribution)).assertIsNotEnabled()
        compose.runOnIdle {
            installed = true
            busy.value = false
            installing.value = false
        }
        compose.onNodeWithText(debianVersion).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sandbox_distribution)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sandbox_not_installed)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sandbox_upgrade)).assertIsDisplayed()

        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText(text(R.string.sandbox_reset)))
        row(text(R.string.sandbox_reset)).performClick()
        compose.onNode(hasText(text(R.string.delete)) and hasAnyAncestor(isDialog())).performClick()
        list.performScrollToIndex(0)
        row(text(R.string.sandbox_distribution)).assertIsEnabled()
        row(debianVersion).assertIsEnabled()
        compose.onNodeWithText(text(R.string.sandbox_not_installed)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(pageMessages.isEmpty()) }
    }

    @Test fun installCompletingBeforeAnotherFrameStillHidesSetup() {
        every { manager.installRootfs() } answers {
            installing.value = true
            installed = true
            installing.value = false
        }
        showPage()
        row(debianVersion).performClick()
        compose.onNodeWithText(text(R.string.sandbox_distribution)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sandbox_not_installed)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sandbox_upgrade)).assertIsDisplayed()
    }

    @Test fun enteringDuringManagerOwnedInstallObservesSuccessWithoutAnotherTap() {
        busy.value = true
        installing.value = true
        showPage()
        row(text(R.string.sandbox_distribution)).assertIsNotEnabled()
        compose.runOnIdle {
            installed = true
            busy.value = false
            installing.value = false
        }
        compose.onNodeWithText(text(R.string.sandbox_distribution)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sandbox_upgrade)).assertIsDisplayed()
        verify(exactly = 0) { manager.installRootfs() }
    }

    @org.junit.Before fun setUpStoragePermission() {
        io.mockk.mockkObject(com.newoether.agora.sandbox.SandboxSharedStorageAccess)
        every { com.newoether.agora.sandbox.SandboxSharedStorageAccess.isGranted(any()) } returns false
    }

    @org.junit.After fun releaseStoragePermission() {
        io.mockk.unmockkObject(com.newoether.agora.sandbox.SandboxSharedStorageAccess)
    }

    private fun showPage() {
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true)) {
                MaterialTheme {
                    SettingsSandboxPage(
                        sandboxManager = manager,
                        onBack = {},
                        sharedStorageEnabled = sharedStorage.value,
                        onSnackbar = { pageMessages += it },
                    )
                }
            }
        }
    }

    private fun row(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun text(id: Int, vararg arguments: Any) =
        ApplicationProvider.getApplicationContext<Application>().getString(id, *arguments)

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private companion object {
        const val NOT_INSTALLED_DETAIL = "Sandbox rootfs not installed"
    }
}
