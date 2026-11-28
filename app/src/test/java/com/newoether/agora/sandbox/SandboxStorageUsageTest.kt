package com.newoether.agora.sandbox

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxStorageUsageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun includesRootfsAndHomeButExcludesSharedMountContent() = runTest {
        val rootfs = temporary.newFolder("rootfs")
        val home = temporary.newFolder("home")
        File(rootfs, "system").writeBytes(ByteArray(31))
        File(home, "attachments/input").apply { parentFile.mkdirs(); writeBytes(ByteArray(79)) }
        File(rootfs, "mnt/shared/external").apply { parentFile.mkdirs(); writeBytes(ByteArray(1000)) }
        assertEquals(110L, sandboxPrivateUsageBytes(rootfs, home))
    }

    @Test
    fun symbolicLinksCannotDoubleCountHomeOrEscapePrivateStorage() = runTest {
        val rootfs = temporary.newFolder("rootfs")
        val home = temporary.newFolder("home")
        val external = temporary.newFolder("external")
        File(home, "input").writeBytes(ByteArray(53))
        File(external, "private").writeBytes(ByteArray(1000))
        try {
            Files.createSymbolicLink(File(rootfs, "home").toPath(), home.toPath())
            Files.createSymbolicLink(File(home, "outside").toPath(), external.toPath())
            Files.createSymbolicLink(File(home, "loop").toPath(), home.toPath())
        } catch (error: Exception) {
            assumeNoException("This host cannot create symbolic links", error)
        }
        assertEquals(53L, sandboxPrivateUsageBytes(rootfs, home))
    }

    @Test
    fun missingDirectoriesHaveNoUsage() = runTest {
        assertEquals(0L, sandboxPrivateUsageBytes(File(temporary.root, "rootfs"), File(temporary.root, "home")))
    }
}
