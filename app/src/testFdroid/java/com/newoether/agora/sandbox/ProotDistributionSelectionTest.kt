package com.newoether.agora.sandbox

import android.content.Context
import android.system.Os
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotDistributionSelectionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun manager(
        directory: File,
        rootfsInstaller: ProotRootfsInstaller = ProotRootfsInstaller(),
    ): ProotSandboxManager {
        val context = mockk<Context>()
        every { context.filesDir } returns directory
        every { context.getString(R.string.sandbox_distribution_busy) } returns "Sandbox is busy"
        every { context.getString(R.string.sandbox_snackbar_reset) } returns "Reset"
        every { context.getString(R.string.sandbox_snackbar_error, "HTTP 503 fetching rootfs") } returns
            "Error: HTTP 503 fetching rootfs"
        val settings = mockk<SettingsRepository>()
        every { settings.sandboxSharedStorageEnabled } returns MutableStateFlow(false)
        every { settings.sandboxMirror } returns MutableStateFlow(SandboxMirror.OFFICIAL)
        return ProotSandboxManager(context, settings, rootfsInstaller)
    }

    private fun marker(directory: File, path: String): File = File(directory, path).apply {
        parentFile!!.mkdirs()
        writeText(path)
    }

    @Test fun legacyAlpineAndSharedHomeSurviveSelectionsAndSelectedReset() = runBlocking {
        val directory = temporary.newFolder()
        val alpine = marker(directory, "alpine-rootfs/etc/keep")
        val debian = marker(directory, "debian-rootfs/etc/keep")
        val ubuntu = marker(directory, "ubuntu-rootfs/etc/keep")
        val home = marker(directory, "sandbox-home/attachments/keep")
        val manager = manager(directory)
        assertEquals(SandboxDistribution.ALPINE, manager.distribution.value)
        assertTrue(manager.selectDistribution(SandboxDistribution.DEBIAN))
        assertEquals(SandboxDistribution.DEBIAN, manager(directory).distribution.value)
        assertTrue(manager.selectDistribution(SandboxDistribution.UBUNTU))
        assertTrue(manager.reset())
        assertTrue(alpine.isFile)
        assertTrue(debian.isFile)
        assertTrue(home.isFile)
        assertFalse(ubuntu.exists())
        assertEquals(SandboxDistribution.UBUNTU, manager(directory).distribution.value)
        assertTrue(manager.selectDistribution(SandboxDistribution.ALPINE))
        assertEquals("alpine-rootfs/etc/keep", manager.fileRead("/etc/keep").content)
    }

    @Test fun failedSelectionPublicationLeavesPreviousDistributionAndFilesUntouched() = runBlocking {
        val directory = temporary.newFolder()
        val alpine = marker(directory, "alpine-rootfs/etc/keep")
        val manager = manager(directory)
        marker(directory, "sandbox-distribution.tmp/blocked")
        assertFalse(manager.selectDistribution(SandboxDistribution.DEBIAN))
        assertEquals(SandboxDistribution.ALPINE, manager.distribution.value)
        assertEquals(SandboxDistribution.ALPINE, manager(directory).distribution.value)
        assertTrue(alpine.isFile)
        assertFalse(File(directory, "debian-rootfs").exists())
    }

    @Test fun selectingUninstalledDistributionDoesNotDownloadOrEraseOtherSystems() = runBlocking {
        val directory = temporary.newFolder()
        val alpine = marker(directory, "alpine-rootfs/etc/keep")
        val manager = manager(directory)
        assertTrue(manager.selectDistribution(SandboxDistribution.DEBIAN))
        assertFalse(manager.isAvailableSync())
        assertFalse(File(directory, "debian-rootfs").exists())
        assertTrue(alpine.isFile)
    }

    @Test fun installDoesNotOverwriteAnIncompleteExistingDistribution() = runBlocking {
        val directory = temporary.newFolder()
        val data = marker(directory, "ubuntu-rootfs/etc/keep")
        val manager = manager(directory)
        assertTrue(manager.selectDistribution(SandboxDistribution.UBUNTU))
        assertFalse(manager.install())
        assertTrue(manager.lastError!!.contains("reset"))
        assertEquals("ubuntu-rootfs/etc/keep", data.readText())
        assertFalse(File(directory, "ubuntu-rootfs.installing").exists())
    }

    @Test fun failedRootfsDownloadBuffersActualCauseOnceAndRefreshDoesNotOverwriteIt() = runBlocking {
        val directory = temporary.newFolder()
        val alpine = marker(directory, "alpine-rootfs/etc/keep")
        val home = marker(directory, "sandbox-home/attachments/keep")
        val installer = ProotRootfsInstaller { url ->
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 503
            }
        }
        val manager = manager(directory, installer)
        assertTrue(manager.selectDistribution(SandboxDistribution.DEBIAN))

        manager.installRootfs()
        withTimeout(5_000) { manager.isInstallingRootfs.first { !it } }
        assertFalse(manager.isAvailable())
        manager.refreshPackageList()

        assertEquals("HTTP 503 fetching rootfs", manager.lastError)
        assertEquals("Error: HTTP 503 fetching rootfs", withTimeout(1_000) { manager.snackbarMessage.first() })
        assertNull(withTimeoutOrNull(100) { manager.snackbarMessage.first() })
        assertTrue(manager.terminalOutput.value.contains("Installation failed: HTTP 503 fetching rootfs"))
        assertFalse(File(directory, "debian-rootfs.installing").exists())
        assertFalse(File(directory, "debian-rootfs").exists())
        assertTrue(alpine.isFile)
        assertTrue(home.isFile)
        assertTrue(manager.selectDistribution(SandboxDistribution.UBUNTU))
        assertNull(manager.lastError)
    }

    @Test fun glibcReadinessDoesNotRequireMuslOrMutateLegacyAlpine() = runBlocking {
        val directory = temporary.newFolder()
        marker(directory, "debian-rootfs/bin/sh")
        marker(directory, "debian-rootfs/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
        val manager = manager(directory)
        assertTrue(manager.selectDistribution(SandboxDistribution.DEBIAN))
        assertTrue(manager.isAvailableSync())
        assertTrue(manager.isAvailable())
        assertFalse(File(directory, "debian-rootfs/etc/apk").exists())
        assertFalse(File(directory, "alpine-rootfs").exists())
    }

    @Test fun activeFileWriteRefusesSwitchAndFinishesInOriginalDistribution() = runBlocking {
        val directory = temporary.newFolder()
        val manager = manager(directory)
        val replacing = CountDownLatch(1)
        val release = CountDownLatch(1)
        mockkStatic(Os::class)
        try {
            every { Os.chmod(any(), any()) } just Runs
            every { Os.rename(any(), any()) } answers {
                replacing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                Files.move(File(firstArg<String>()).toPath(), File(secondArg<String>()).toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
                Unit
            }
            val write = async(Dispatchers.IO) { manager.fileWrite("/etc/new-file", "complete") }
            try {
                assertTrue(replacing.await(5, TimeUnit.SECONDS))
                assertFalse(manager.selectDistribution(SandboxDistribution.DEBIAN))
                assertEquals(SandboxDistribution.ALPINE, manager.distribution.value)
                release.countDown()
                assertNull(write.await())
                assertEquals("complete", File(directory, "alpine-rootfs/etc/new-file").readText())
                assertTrue(manager.selectDistribution(SandboxDistribution.DEBIAN))
                assertFalse(File(directory, "debian-rootfs/etc/new-file").exists())
            } finally {
                release.countDown()
                write.join()
            }
        } finally {
            release.countDown()
            unmockkStatic(Os::class)
        }
    }
}
