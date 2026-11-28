package com.newoether.agora.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.sandbox.RootfsInstallPhase
import com.newoether.agora.sandbox.SandboxDistribution
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val phase = MutableStateFlow<RootfsInstallPhase?>(null)
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
        every { rootfsPhase } returns phase
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

    @Test fun uninstalledRowShowsAnInstallButtonAndChoosingADistributionDoesNotInstall() {
        showPage()
        compose.onNodeWithText(text(R.string.sandbox_not_installed)).assertIsDisplayed()
        compose.onNodeWithText(NOT_INSTALLED_DETAIL).assertDoesNotExist()
        installButton().assertIsDisplayed()
        val rowBounds = compose.onNodeWithText(debianVersion).getUnclippedBoundsInRoot()
        assertTrue(rowBounds.bottom - rowBounds.top >= 48.dp)

        row(text(R.string.sandbox_distribution)).performClick()
        val ubuntu = SandboxDistribution.UBUNTU
        compose.onNode(hasText("${ubuntu.displayName} ${ubuntu.versionLabel}") and hasAnyAncestor(isPopup()))
            .performClick()
        compose.onNodeWithText("${ubuntu.displayName} ${ubuntu.versionLabel}").assertIsDisplayed()
        verify(exactly = 0) { manager.installRootfs() }
        compose.runOnIdle { assertTrue(pageMessages.isEmpty()) }
    }

    @Test fun failedInstallRestoresRetryWithoutRenderingOrPostingTheProbeError() {
        showPage()
        installButton().performClick()
        row(text(R.string.sandbox_distribution)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.sandbox_install)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sandbox_preparing_rootfs)).assertIsDisplayed()
        compose.runOnIdle { phase.value = RootfsInstallPhase.EXTRACTING }
        compose.onNodeWithText(text(R.string.sandbox_extracting_rootfs)).assertIsDisplayed()
        compose.runOnIdle {
            phase.value = RootfsInstallPhase.DOWNLOADING
            progress.value = 0.42f
        }
        compose.onNodeWithText(text(R.string.sandbox_downloading_rootfs, 42)).assertIsDisplayed()

        compose.runOnIdle {
            diagnostic = "Download or verification failed"
            busy.value = false
            installing.value = false
            progress.value = null
            phase.value = null
            sharedStorage.value = true
        }
        compose.onNodeWithText(text(R.string.sandbox_not_installed)).assertIsDisplayed()
        compose.onNodeWithText(NOT_INSTALLED_DETAIL).assertDoesNotExist()
        compose.onNodeWithText("Download or verification failed").assertDoesNotExist()
        compose.runOnIdle { assertTrue(pageMessages.isEmpty()) }
        installButton().assertIsEnabled().performClick()
        verify(exactly = 2) { manager.installRootfs() }
    }

    @Test fun successfulInstallHidesSetupAndResetRestoresIt() {
        showPage()
        installButton().performClick()
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
        installButton().assertIsEnabled()
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
        installButton().performClick()
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

    private fun installButton() =
        compose.onNode(hasText(text(R.string.sandbox_install)) and hasClickAction())

    private fun text(id: Int, vararg arguments: Any) =
        ApplicationProvider.getApplicationContext<Application>().getString(id, *arguments)

    private companion object {
        const val NOT_INSTALLED_DETAIL = "Sandbox rootfs not installed"
    }
}
