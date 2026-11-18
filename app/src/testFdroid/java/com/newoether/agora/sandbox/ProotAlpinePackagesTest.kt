package com.newoether.agora.sandbox

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotAlpinePackagesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun listPreservesDescriptionsAndLastRecordWithoutBlankLine() {
        val root = rootfs()
        installed(root, "P:busybox\nV:1.37-r1\nT:BusyBox tools\n\nP:curl\nV:8.1-r0\nT:Transfer utility")
        val packages = helper(root) { error("Listing must not execute commands") }.list()
        assertEquals(listOf("busybox", "curl"), packages.map { it.name })
        assertEquals("8.1-r0", packages.last().version)
        assertEquals("Transfer utility", packages.last().description)
    }

    @Test
    fun invalidNamesFailWithoutMetadataOrExecution() = runBlocking {
        val root = rootfs()
        val helper = helper(root) { error("Invalid package name must not execute") }
        assertFalse(helper.install("curl; id"))
        assertFalse(helper.remove("--force-broken-world"))
        assertNotNull(helper.lastError)
        assertFalse(File(root, "etc/agora").exists())
    }

    @Test
    fun initialWorldIsProtectedAndLaterExplicitPackagesStayRemovable() = runBlocking {
        val root = rootfs()
        installed(root, "P:busybox\nV:1\n\nP:curl\nV:1\n")
        world(root, "busybox\n")
        val helper = helper(root) { error("Base package removal must not execute") }
        helper.prepare(forceBaseWorld = true)
        assertFalse(helper.remove("busybox"))
        assertTrue(helper.lastError.orEmpty().contains("base package"))
        assertEquals("busybox\n", File(root, "etc/apk/world").readText())
        assertEquals("", File(root, "etc/agora/explicit-packages").readText())
    }

    @Test
    fun failedRemovalRestoresExplicitPackagesAndWorld() = runBlocking {
        val root = rootfs()
        installed(root, "P:busybox\nV:1\n\nP:curl\nV:1\n")
        world(root, "busybox\n")
        val helper = helper(root) { SandboxManager.SandboxResult("", "Dependency conflict", 1) }
        helper.prepare(forceBaseWorld = true)
        AlpinePackageMetadataStore(root).addExplicitPackage("curl")
        val before = File(root, "etc/apk/world").readText()
        assertFalse(helper.remove("curl"))
        assertEquals(before, File(root, "etc/apk/world").readText())
        assertEquals("curl\n", File(root, "etc/agora/explicit-packages").readText())
        assertEquals("Dependency conflict", helper.lastError)
    }

    @Test
    fun cancellationRestoresWorldAndPropagatesWithoutSuccess() = runBlocking {
        val root = rootfs()
        installed(root, "P:busybox\nV:1\n\nP:curl\nV:1\n")
        world(root, "busybox\n")
        val helper = helper(root) { throw CancellationException("stop") }
        helper.prepare(forceBaseWorld = true)
        AlpinePackageMetadataStore(root).addExplicitPackage("curl")
        var cancelled = false
        try {
            helper.remove("curl")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals("busybox\ncurl\n", File(root, "etc/apk/world").readText())
        assertEquals("curl\n", File(root, "etc/agora/explicit-packages").readText())
    }

    @Test
    fun successfulRemovalUpdatesOnlyPackageMetadataAndPreservesUnrelatedTmpFiles() = runBlocking {
        val root = rootfs()
        installed(root, "P:busybox\nV:1\n\nP:curl\nV:1\n")
        world(root, "busybox\n")
        val userFile = File(root, "tmp/user-data").apply { parentFile.mkdirs(); writeText("keep") }
        val commands = mutableListOf<String>()
        val helper = helper(root) { command ->
            commands.add(command)
            installed(root, "P:busybox\nV:1\n")
            SandboxManager.SandboxResult("Removed curl", "", 0)
        }
        helper.prepare(forceBaseWorld = true)
        AlpinePackageMetadataStore(root).addExplicitPackage("curl")
        assertTrue(helper.remove("curl"))
        assertEquals(listOf("apk del 'curl'"), commands)
        assertEquals("busybox\n", File(root, "etc/apk/world").readText())
        assertEquals("", File(root, "etc/agora/explicit-packages").readText())
        assertEquals("keep", userFile.readText())
    }

    @Test
    fun cancelledDownloadAdmissionDoesNotConnectOrExecute() = runBlocking {
        val root = rootfs()
        installed(root, "P:busybox\nV:1\n")
        world(root, "busybox\n")
        val helper = helper(root) { error("Cancelled download must not execute apk") }
        var entered = false
        var cancelled = false
        var returned = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            entered = true
            coroutineContext.cancel()
            try {
                helper.install("curl")
                returned = true
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        job.join()
        assertTrue(entered)
        assertTrue(cancelled)
        assertFalse(returned)
    }

    @Test
    fun removingAbsentPackageRepairsStaleExplicitMetadataWithoutExecuting() = runBlocking {
        val root = rootfs()
        installed(root, "P:busybox\nV:1\n")
        world(root, "busybox\n")
        val helper = helper(root) { error("Absent package removal must not execute") }
        helper.prepare(forceBaseWorld = true)
        AlpinePackageMetadataStore(root).addExplicitPackage("curl")
        assertTrue(helper.remove("curl"))
        assertEquals("busybox\n", File(root, "etc/apk/world").readText())
        assertEquals("", File(root, "etc/agora/explicit-packages").readText())
    }

    private fun rootfs(): File = temporaryFolder.newFolder()

    private fun helper(root: File, execute: suspend (String) -> SandboxManager.SandboxResult) =
        ProotAlpinePackages(root, temporaryFolder.newFolder()) { command, _, _ -> execute(command) }

    private fun installed(root: File, value: String) {
        File(root, "lib/apk/db/installed").apply { parentFile.mkdirs(); writeText(value) }
    }

    private fun world(root: File, value: String) {
        File(root, "etc/apk/world").apply { parentFile.mkdirs(); writeText(value) }
    }
}
