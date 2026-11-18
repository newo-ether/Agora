package com.newoether.agora.sandbox

import android.content.Context
import android.content.pm.ApplicationInfo
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotProcessRunnerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun commandBuilderRetainsKillOnExitMountsEnvironmentAndLiteralCommand() {
        val files = temporary.newFolder("files")
        val native = temporary.newFolder("native")
        File(native, "libtalloc.so").writeText("library bytes")
        val context = mockk<Context>()
        every { context.filesDir } returns files
        val applicationInfo = mockk<ApplicationInfo>().apply { nativeLibraryDir = native.absolutePath }
        every { context.applicationInfo } returns applicationInfo
        val rootfs = File(files, "alpine-rootfs").apply { mkdirs() }
        val home = File(files, "sandbox-home").apply { mkdirs() }
        val shared = temporary.newFolder("shared")
        val runner = ProotProcessRunner(context, rootfs, home) { shared }
        val command = "printf '%s' 'literal command; not interpolated by the host'"
        val builder = runner.builder(command, "/home/agora/project")
        assertTrue(builder.command().contains("--kill-on-exit"))
        assertTrue(builder.command().contains("--bind=${home.absolutePath}:/home/agora"))
        assertTrue(builder.command().contains("--bind=${shared.absolutePath}:/mnt/shared"))
        assertEquals(command, builder.command().last())
        assertEquals("/home/agora/project", builder.command()[builder.command().indexOf("-w") + 1])
        assertEquals("/home/agora", builder.environment()["HOME"])
        assertEquals(File(rootfs, "tmp").absolutePath, builder.environment()["PROOT_TMP_DIR"])
        // Maintainer scripts must not see Android's app-cache TMPDIR, which is outside the guest.
        assertEquals("/tmp", builder.environment()["TMPDIR"])
        assertEquals("/tmp", builder.environment()["TMP"])
        assertEquals("library bytes", File(files, "lib/libtalloc.so.2").readText())
        assertTrue(builder.redirectErrorStream())
    }

    @Test fun matchingIdentityWithAnUnverifiableExecutableBlocksRecovery() = kotlinx.coroutines.runBlocking {
        val context = mockk<Context>()
        val applicationInfo = mockk<ApplicationInfo>().apply { nativeLibraryDir = temporary.root.absolutePath }
        every { context.applicationInfo } returns applicationInfo
        val identity = mockk<SandboxProcessIdentity>()
        every { identity.pid } returns Int.MAX_VALUE
        every { identity.stillMatches() } returns true
        val runner = ProotProcessRunner(context, temporary.root, temporary.root) { null }
        val error = runCatching { runner.interruptOrphan(identity) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("rootfs access remains blocked"))
    }

    @Test fun aStalePidIsNeverSignalledDuringRecovery() = kotlinx.coroutines.runBlocking {
        val context = mockk<Context>()
        val runner = ProotProcessRunner(context, temporary.root, temporary.root) { null }
        runner.interruptOrphan(SandboxProcessIdentity(Int.MAX_VALUE, "stale", "different-boot"))
        assertFalse(SandboxProcessIdentity(Int.MAX_VALUE, "stale", "different-boot").stillMatches())
    }
}
