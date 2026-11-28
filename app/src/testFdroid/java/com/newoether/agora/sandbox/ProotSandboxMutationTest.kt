package com.newoether.agora.sandbox

import android.content.Context
import android.system.Os
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotSandboxMutationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resetCancelsAnInFlightRootfsInstall() = runBlocking {
        val directory = temporaryFolder.newFolder("sandbox-reset-install")
        val context = mockk<Context>()
        every { context.filesDir } returns directory
        every { context.getString(R.string.sandbox_snackbar_reset) } returns "Reset"
        val settings = mockk<SettingsRepository>()
        every { settings.sandboxMirror } returns MutableStateFlow(SandboxMirror.OFFICIAL)
        val downloading = CountDownLatch(1)
        val installer = mockk<ProotRootfsInstaller>()
        coEvery { installer.install(any(), any(), any(), any(), any(), any()) } coAnswers {
            downloading.countDown()
            awaitCancellation()
        }
        val manager = ProotSandboxManager(context, settings, installer)
        manager.installRootfs()
        assertTrue("Install must reach the archive download", downloading.await(5, TimeUnit.SECONDS))
        assertTrue("Reset must interrupt the running install",
            withTimeoutOrNull(5_000) { manager.reset() } == true)
        assertFalse("Reset must clear the install state", manager.isInstallingRootfs.value)
        assertFalse(manager.isBusy.value)
        assertFalse(File(directory, "alpine-rootfs.installing").exists())
    }

    @Test
    fun ordinaryMutationsCannotBypassFailedJobRecovery() = runBlocking {
        val directory = temporaryFolder.newFolder("sandbox-recovery")
        val marker = File(directory, "alpine-rootfs/etc/marker")
        marker.parentFile!!.mkdirs()
        marker.writeText("keep rootfs")
        val id = java.util.UUID.randomUUID().toString()
        val jobs = File(directory, "sandbox-jobs").apply { mkdirs() }
        File(jobs, "$id.json").writeText("""{"jobId":"$id","command":"old job","description":"recovery","workdir":"/home/agora","timeoutMs":1000,"createdAt":1,"state":"running"}""")
        File(jobs, "$id.output").writeText("")
        // A blocked metadata publication makes recovery fail without invoking an OS process.
        File(jobs, "$id.json.tmp/blocked").apply { parentFile!!.mkdirs(); writeText("blocked") }
        val context = mockk<Context>()
        every { context.filesDir } returns directory
        val manager = ProotSandboxManager(context, mockk<SettingsRepository>(relaxed = true))
        val actions: List<suspend () -> Any?> = listOf(
            { manager.isAvailable() },
            { manager.install() },
            { manager.executeCommand("true", "/", 1000) },
            { manager.fileWrite("/etc/new-file", "new") },
            { manager.fileEdit("/etc/marker", emptyList(), "") },
            { manager.apkInstall("fixture") {} },
            { manager.apkDelete("fixture") },
            { manager.apkUpgrade {} },
            { manager.reset() },
        )
        for (action in actions) {
            assertTrue("Rootfs mutation must fail when recovery cannot settle", runCatching { action() }.isFailure)
            org.junit.Assert.assertEquals("keep rootfs", marker.readText())
            org.junit.Assert.assertFalse(File(directory, "alpine-rootfs/etc/new-file").exists())
        }
    }

    @Test
    fun resetWaitsForAnInFlightAtomicFileWriteBeforeRemovingRootfs() = runBlocking {
        val directory = temporaryFolder.newFolder("sandbox-mutation")
        val marker = File(directory, "alpine-rootfs/etc/marker")
        marker.parentFile!!.mkdirs()
        marker.writeText("rootfs must remain intact while a write owns it")
        val context = mockk<Context>()
        every { context.filesDir } returns directory
        every { context.getString(R.string.sandbox_snackbar_reset) } returns "Reset"
        val manager = ProotSandboxManager(context, mockk<SettingsRepository>(relaxed = true))
        val replacing = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        mockkStatic(Os::class)
        try {
            every { Os.chmod(any(), any()) } just Runs
            every { Os.rename(any(), any()) } answers {
                replacing.countDown()
                check(releaseWrite.await(5, TimeUnit.SECONDS)) { "Write barrier timed out" }
                Files.move(
                    File(firstArg<String>()).toPath(),
                    File(secondArg<String>()).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                Unit
            }
            val write = async(Dispatchers.IO) { manager.fileWrite("/etc/new-file", "complete bytes") }
            try {
                assertTrue("The real file operation reached atomic replacement", replacing.await(5, TimeUnit.SECONDS))
                val reset = async(Dispatchers.IO) { manager.reset() }
                try {
                    assertNull("Reset must wait for the existing writer", withTimeoutOrNull(250) { reset.await() })
                    assertTrue("Reset must not delete the writer's rootfs", marker.isFile)
                    releaseWrite.countDown()
                    assertNull(write.await())
                    assertTrue(reset.await())
                } finally {
                    releaseWrite.countDown()
                    reset.join()
                }
            } finally {
                releaseWrite.countDown()
                write.join()
            }
        } finally {
            releaseWrite.countDown()
            unmockkStatic(Os::class)
        }
    }
}
