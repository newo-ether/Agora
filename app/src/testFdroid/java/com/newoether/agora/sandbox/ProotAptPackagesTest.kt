package com.newoether.agora.sandbox

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotAptPackagesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun listOnlyContainsFullyInstalledRecordsAndReadsContinuationSizeAndEof() {
        val root = rootfs()
        status(root, """
            Package: base-files
            Status: install ok installed
            Architecture: arm64
            Version: 12.4
            Installed-Size: 123
            Description: Base files
             detailed description
             .
             last paragraph

            Package: removed
            Status: deinstall ok config-files
            Version: 1

            Package: half-configured
            Status: install ok half-configured
            Version: 2

            Package: held
            Status: hold ok installed
            Architecture: all
            Version: 3
            Installed-Size: invalid
            Description: Held package
        """.trimIndent())
        val packages = helper(root) { error("Listing must not execute commands") }.list()
        assertEquals(listOf("base-files", "held"), packages.map { it.name })
        assertEquals(123L * 1024, packages.first().sizeBytes)
        assertEquals("Base files\ndetailed description\n\nlast paragraph", packages.first().description)
        assertEquals(0L, packages.last().sizeBytes)
    }

    @Test
    fun invalidNamesNeverExecuteOrPrepareMetadata() = runBlocking {
        val root = rootfs()
        val helper = helper(root) { error("Invalid names must not execute") }
        for (name in listOf("--allow-unauthenticated", "a;id", "curl git", "x\napt", "pkg=1", "pkg/*", "pkg:arm64;id")) {
            assertFalse(helper.install(name))
            assertFalse(helper.remove(name))
            assertNotNull(helper.lastError)
        }
        assertFalse(File(root, "etc/agora").exists())
    }

    @Test
    fun failedIndexUpdateStopsBeforeInstallOrUpgrade() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"))
        val commands = mutableListOf<String>()
        val helper = helper(root) { command ->
            commands.add(command)
            result(code = 100, stderr = "Repository signature verification failed")
        }
        assertFalse(helper.install("python3"))
        assertEquals(0, helper.upgrade())
        assertEquals(2, commands.size)
        assertTrue(commands.all { it.contains("APT::Update::Error-Mode=any update") })
        assertTrue(helper.lastError.orEmpty().contains("signature"))
        assertEquals(listOf("base-files"), helper.list().map { it.name })
    }

    @Test
    fun installReportsNonzeroExitEvenWhenRequestedPackageAppears() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"))
        val commands = mutableListOf<String>()
        val helper = helper(root) { command ->
            commands.add(command)
            when {
                command.contains("apt-cache show") -> result(stdout = "Package: python3\nVersion: 3.12\n")
                command.contains(" install -- ") -> {
                    status(root, record("base-files", "1"), record("python3", "3.12"))
                    result(code = 100, stderr = "Post-install failed")
                }
                else -> result()
            }
        }
        assertFalse(helper.install("python3"))
        assertEquals("Post-install failed", helper.lastError)
        assertTrue(helper.list().any { it.name == "python3" })
        assertTrue(commands.last().contains("--no-remove install -- 'python3:arm64'"))
        assertTrue(commands.last().contains("DEBIAN_FRONTEND=noninteractive"))
        assertTrue(commands.last().contains("Dpkg::Use-Pty=0"))
        assertTrue(commands.last().contains("--force-confold"))
    }

    @Test
    fun exactNameCheckRejectsAptRegexpFallbackWithoutInstallingMatches() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"))
        val commands = mutableListOf<String>()
        val helper = helper(root) { command ->
            commands.add(command)
            if (command.contains("apt-cache show")) result(stdout = "Package: libfake1x2\n") else result()
        }
        assertFalse(helper.install("libfake1.2"))
        assertFalse(commands.any { it.contains(" install -- ") })
        assertNotNull(helper.lastError)
    }

    @Test
    fun successRequiresInstalledStatusAndClearsOldError() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"))
        var installed = false
        val helper = helper(root) { command ->
            when {
                command.contains("apt-cache show") -> result(stdout = "Package: curl\n")
                command.contains(" install -- ") && installed -> {
                    status(root, record("base-files", "1"), record("curl", "8.1"))
                    result()
                }
                else -> result()
            }
        }
        assertFalse(helper.install("curl"))
        assertNotNull(helper.lastError)
        installed = true
        assertTrue(helper.install("curl"))
        assertNull(helper.lastError)
    }

    @Test
    fun removalProtectsInitialBaseAndLaterEssentialPackagesBeforeExecution() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"))
        val helper = helper(root) { error("Protected removal must not execute") }
        helper.prepare()
        status(root, record("base-files", "1"), record("later-essential", "1", "Essential: yes\n"))
        assertFalse(helper.remove("base-files:arm64"))
        assertFalse(helper.remove("later-essential"))
        assertTrue(File(root, "etc/agora/apt-base-packages").readText().contains("base-files"))
        assertFalse(File(root, "etc/agora/apt-base-packages").readText().contains("later-essential"))
    }

    @Test
    fun reverseDependencyRemovalCannotRemoveBaseUserland() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"))
        val commands = mutableListOf<String>()
        val helper = helper(root) { command ->
            commands.add(command)
            result(stdout = "Remv base-files [1]\nRemv curl [8.1]\n")
        }
        helper.prepare()
        status(root, record("base-files", "1"), record("curl", "8.1"))
        assertFalse(helper.remove("curl"))
        assertEquals(1, commands.size)
        assertTrue(commands.single().contains("--simulate remove"))
        assertTrue(helper.lastError.orEmpty().contains("base-files"))
    }

    @Test
    fun failedRemovalDoesNotPurgeOrAutoremoveAndKeepsPackageData() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"))
        val commands = mutableListOf<String>()
        val helper = helper(root) { command ->
            commands.add(command)
            if (command.contains("--simulate")) result(stdout = "Remv curl [8.1]\n")
            else result(code = 100, stderr = "dpkg failed")
        }
        helper.prepare()
        status(root, record("base-files", "1"), record("curl", "8.1"))
        val data = File(root, "etc/curl-user-config").apply { writeText("keep") }
        assertFalse(helper.remove("curl"))
        assertEquals("dpkg failed", helper.lastError)
        assertEquals("keep", data.readText())
        assertTrue(helper.list().any { it.name == "curl" })
        assertFalse(commands.any { it.contains(" autoremove") || it.contains(" purge ") })
    }

    @Test
    fun removalWithoutReadablePlanFailsBeforeMutation() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"))
        val commands = mutableListOf<String>()
        val helper = helper(root) { command ->
            commands.add(command)
            result(stdout = "Plan output was incomplete")
        }
        helper.prepare()
        status(root, record("base-files", "1"), record("curl", "8.1"))
        assertFalse(helper.remove("curl"))
        assertEquals(1, commands.size)
        assertTrue(helper.lastError.orEmpty().contains("Cannot verify removal plan"))
    }

    @Test
    fun partialUpgradeCountsOnlyChangedInstalledVersionsAndRetainsFailure() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"), record("curl", "8.1"), record("held", "2"))
        val helper = helper(root) { command ->
            if (command.endsWith(" upgrade")) {
                status(root, record("base-files", "1"), record("curl", "8.2"), record("held", "2"), record("new-dependency", "1"))
                result(code = 100, stderr = "One package failed to configure")
            } else result()
        }
        assertEquals(1, helper.upgrade())
        assertEquals("One package failed to configure", helper.lastError)
        assertEquals("8.2", helper.list().single { it.name == "curl" }.version)
    }

    @Test
    fun failedUpgradeCountsChangedUnconfiguredVersionButListExcludesIt() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"), record("curl", "8.1"))
        val helper = helper(root) { command ->
            if (command.endsWith(" upgrade")) {
                status(root, record("base-files", "1"), record("curl", "8.2").replace(
                    "Status: install ok installed", "Status: install ok half-configured",
                ))
                result(code = 100, stderr = "curl configuration failed")
            } else result()
        }
        assertEquals(1, helper.upgrade())
        assertEquals("curl configuration failed", helper.lastError)
        assertEquals(listOf("base-files"), helper.list().map { it.name })
    }

    @Test
    fun absentRemovalDoesNotActOnAptTrailingActionSyntax() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"))
        val helper = helper(root) { error("Absent package must not execute") }
        helper.prepare()
        status(root, record("base-files", "1"), record("curl", "8.1"))
        assertTrue(helper.remove("curl+"))
        assertTrue(helper.remove("curl-"))
        assertTrue(helper.list().any { it.name == "curl" })
    }

    @Test
    fun nonzeroRemovalIsFailureEvenWhenPackageWasRemoved() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"))
        val helper = helper(root) { command ->
            if (command.contains("--simulate")) result(stdout = "Remv curl [8.1]\n") else {
                status(root, record("base-files", "1"))
                result(code = 100, stderr = "post-remove script failed")
            }
        }
        helper.prepare()
        status(root, record("base-files", "1"), record("curl", "8.1"))
        assertFalse(helper.remove("curl"))
        assertEquals("post-remove script failed", helper.lastError)
        assertEquals(listOf("base-files"), helper.list().map { it.name })
    }

    @Test
    fun successfulRemovalLeavesBaseAndUserDataWithoutPurging() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"))
        val commands = mutableListOf<String>()
        val helper = helper(root) { command ->
            commands.add(command)
            if (command.contains("--simulate")) result(stdout = "Remv curl [8.1]\n") else {
                status(root, record("base-files", "1"))
                result()
            }
        }
        helper.prepare()
        status(root, record("base-files", "1"), record("curl", "8.1"))
        val data = File(root, "etc/curl-user-config").apply { writeText("keep") }
        assertTrue(helper.remove("curl:arm64"))
        assertNull(helper.lastError)
        assertEquals(listOf("base-files"), helper.list().map { it.name })
        assertEquals("keep", data.readText())
        assertEquals(2, commands.size)
        assertFalse(commands.any { it.contains(" purge ") || it.contains(" autoremove") })
    }

    @Test
    fun unconfiguredPackageIsNotTreatedAsAlreadyRemoved() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"))
        val commands = mutableListOf<String>()
        val helper = helper(root) { command ->
            commands.add(command)
            if (command.contains("--simulate")) result(stdout = "Remv curl [8.2]\n") else result()
        }
        helper.prepare()
        status(root, record("base-files", "1"), record("curl", "8.2").replace(
            "Status: install ok installed", "Status: install reinstreq half-installed",
        ))
        assertFalse(helper.remove("curl"))
        assertEquals(2, commands.size)
        assertEquals("Package was not removed: curl", helper.lastError)
    }

    @Test
    fun cancellationPropagatesForEveryMutatingOperation() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"))
        val helper = helper(root) { throw CancellationException("stop") }
        helper.prepare()
        status(root, record("base-files", "1"), record("curl", "8.1"))
        var cancellations = 0
        for (operation in listOf<suspend () -> Unit>(
            { helper.install("curl") }, { helper.remove("curl") }, { helper.upgrade() },
        )) {
            try {
                operation()
            } catch (_: CancellationException) {
                cancellations++
            }
        }
        assertEquals(3, cancellations)
    }

    @Test
    fun stagingConfigurationCapturesBaseBeforeUserPackagesCanBeAdded() {
        val root = rootfs()
        val initialStatus = arrayOf(record("base-files", "1"), record("base-dependency", "1").replace(
            "Status: install ok installed", "Status: install ok unpacked",
        ))
        status(root, *initialStatus)
        File(root, "etc/passwd").apply {
            parentFile.mkdirs()
            writeText("root:x:0:0:root:/root:/bin/sh\n")
        }

        ProotRootfsInstaller().configureRootfs(SandboxDistribution.DEBIAN, root)

        val baseline = File(root, "etc/agora/apt-base-packages")
        assertEquals("base-files\nbase-dependency\n", baseline.readText())
        status(root, *initialStatus, record("curl", "8.1"))
        helper(root) { error("Prepare must not execute commands") }.prepare()
        assertEquals("base-files\nbase-dependency\n", baseline.readText())
    }

    @Test
    fun existingServicePolicyIsPreservedAndBaseCaptureIsIdempotent() {
        val root = rootfs()
        status(root, record("base-files", "1"))
        val policy = File(root, "usr/sbin/policy-rc.d").apply { parentFile.mkdirs(); writeText("custom policy") }
        val helper = helper(root) { error("Prepare must not execute commands") }
        helper.prepare()
        status(root, record("base-files", "1"), record("curl", "8.1"))
        helper.prepare()
        assertEquals("custom policy", policy.readText())
        assertEquals("base-files\n", File(root, "etc/agora/apt-base-packages").readText())
    }

    @Test
    fun installStreamsAptOutputWhileTheCommandRuns() = runBlocking {
        val root = rootfs()
        status(root, record("base-files", "1"))
        val progress = mutableListOf<String>()
        var linesDuringInstall = emptyList<String>()
        val helper = ProotAptPackages(root) { command, _, onOutput ->
            when {
                command.contains("apt-cache show") -> result(stdout = "Package: curl\n")
                command.contains(" install -- ") -> {
                    onOutput?.invoke("Get:1 http://example.invalid/curl [1838 kB]\nGet:2 http://")
                    linesDuringInstall = progress.toList()
                    onOutput?.invoke("example.invalid/libssl [1 kB]\n")
                    status(root, record("base-files", "1"), record("curl", "8.1"))
                    result()
                }
                else -> result()
            }
        }
        assertTrue(helper.install("curl", progress::add))
        assertEquals("Get:1 http://example.invalid/curl [1838 kB]", linesDuringInstall.last())
        assertTrue(progress.contains("Get:2 http://example.invalid/libssl [1 kB]"))
        assertFalse(progress.contains("Get:2 http://"))
        assertEquals(1, progress.count { it.startsWith("Get:1 ") })
    }

    private fun rootfs(): File = temporaryFolder.newFolder()

    private fun helper(root: File, execute: suspend (String) -> SandboxManager.SandboxResult) =
        ProotAptPackages(root) { command, _, _ -> execute(command) }

    private fun status(root: File, vararg entries: String) {
        File(root, "var/lib/dpkg/status").apply {
            parentFile.mkdirs()
            writeText(entries.joinToString("\n\n"))
        }
    }

    private fun record(name: String, version: String, extra: String = ""): String =
        "Package: $name\nStatus: install ok installed\nArchitecture: arm64\nVersion: $version\n${extra}Description: $name"

    private fun result(stdout: String = "", stderr: String = "", code: Int = 0) =
        SandboxManager.SandboxResult(stdout, stderr, code)
}
