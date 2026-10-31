package com.newoether.agora.sandbox

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import com.newoether.agora.api.LocalModelRuntime
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.di.AppContainer
import com.newoether.agora.util.ShellFileEdit
import com.newoether.agora.util.ShellGrepOptions
import io.mockk.Called
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaySandboxFlavorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun appContainerSelectsThePlayFactoryWithoutOpeningTheDatabaseOrSettings() {
        val application = RuntimeEnvironment.getApplication()
        val originalNativeDirectory = application.applicationInfo.nativeLibraryDir
        val nativeDirectory = temporaryFolder.newFolder("native-libraries").absolutePath
        application.applicationInfo.nativeLibraryDir = nativeDirectory
        val database = mockk<ChatDatabase>()
        // Only replace the unrelated native-model boundary; factory selection remains real.
        mockkObject(LocalModelRuntime)
        try {
            every { LocalModelRuntime.initialize(any()) } just Runs
            val container = AppContainer(application, database)

            val factory = container.sandboxManagerFactory
            assertTrue(factory is PlaySandboxManagerFactory)
            assertSame(factory, container.sandboxManagerFactory)
            assertFalse(requireNotNull(factory).isAvailable())
            assertTrue(factory.create() is PlaySandboxManager)
            verify(exactly = 1) { LocalModelRuntime.initialize(nativeDirectory) }
            verify(exactly = 0) { LocalModelRuntime.bindIdleRetention(any(), any()) }
            verify { database wasNot Called }
        } finally {
            application.applicationInfo.nativeLibraryDir = originalNativeDirectory
            unmockkObject(LocalModelRuntime)
        }
    }

    @Test
    fun unavailableSandboxNeverExecutesCommandsOrStartsPackageWork() = runTest {
        val factory = PlaySandboxManagerFactory()
        val manager = factory.create()
        val marker = File(temporaryFolder.root, "command-ran")
        val progress = mutableListOf<String>()

        assertFalse(factory.isAvailable())
        assertFalse(manager.isAvailableSync())
        assertFalse(manager.isAvailable())
        assertFalse(manager.install())
        val result = manager.executeCommand("touch '${marker.absolutePath}'", temporaryFolder.root.absolutePath, 1_000)
        assertEquals(-1, result.exitCode)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.isNotBlank())
        assertFalse(marker.exists())

        manager.installRootfs()
        manager.installPackage("bash")
        manager.removePackage("bash")
        manager.upgradePackages()
        manager.refreshPackageList()
        assertFalse(manager.apkInstall("bash", progress::add))
        assertFalse(manager.apkDelete("bash"))
        assertEquals(0, manager.apkUpgrade(progress::add))
        assertTrue(manager.apkList().isEmpty())
        assertFalse(manager.reset())
        assertFalse(manager.isBusy.value)
        assertFalse(manager.isInstallingRootfs.value)
        assertNull(manager.downloadProgress.value)
        assertTrue(manager.packageList.value.isEmpty())
        assertTrue(manager.snackbarMessage.toList().isEmpty())
        assertEquals(0L, manager.getDiskUsageMB())
        assertNull(manager.getSandboxHomeDir())
        assertNull(manager.resolveHostFile("/home/notes.txt"))
    }

    @Test
    fun unavailableFileOperationsCannotReadOrModifyHostFiles() = runTest {
        val manager = PlaySandboxManagerFactory().create()
        val existing = temporaryFolder.newFile("notes.txt").apply { writeText("original") }
        val missing = File(temporaryFolder.root, "new.txt")

        try {
            manager.fileRead(existing.absolutePath, 0, 1_024)
            org.junit.Assert.fail("Play sandbox must reject file reads")
        } catch (_: UnsupportedOperationException) {
            // Unavailable reads are the production API's explicit failure path.
        }
        assertNotNull(manager.fileWrite(existing.absolutePath, "replacement", ""))
        assertNotNull(manager.fileWrite(missing.absolutePath, "created", ""))
        val edited = manager.fileEdit(existing.absolutePath, listOf(ShellFileEdit("original", "edited")), "")
        assertEquals(0, edited.replacements)
        assertNotNull(edited.error)
        assertEquals(emptyList<String>() to false, manager.fileGlob("*", temporaryFolder.root.absolutePath, null))
        val grep = manager.fileGrep("original", temporaryFolder.root.absolutePath, "", ShellGrepOptions()).getOrThrow()
        assertTrue(grep.matches.isEmpty())
        assertFalse(grep.truncated)
        assertEquals("original", existing.readText())
        assertFalse(missing.exists())
    }

    @Test
    fun mergedPlayManifestOmitsFdroidSandboxPermissions() {
        val application = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        val info = application.packageManager.getPackageInfo(application.packageName, PackageManager.GET_PERMISSIONS)
        val permissions = info.requestedPermissions.orEmpty().toSet()

        assertTrue(Manifest.permission.INTERNET in permissions)
        assertFalse(Manifest.permission.MANAGE_EXTERNAL_STORAGE in permissions)
        assertFalse(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in permissions)
        assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in permissions)
    }
}
