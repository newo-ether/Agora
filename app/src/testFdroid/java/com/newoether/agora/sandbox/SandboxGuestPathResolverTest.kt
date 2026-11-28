package com.newoether.agora.sandbox

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxGuestPathResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun relativeAndAbsoluteGuestLinksResolveWithinGuestRoot() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val home = temporaryFolder.newFolder("home")
        val resolver = resolver(rootfs, home)
        val tool = File(rootfs, "usr/bin/tool").apply { parentFile!!.mkdirs(); writeText("tool") }
        symlink(File(rootfs, "bin"), "usr/bin")
        symlink(File(rootfs, "usr/bin/sh"), "/usr/bin/tool")

        assertEquals(tool, resolver.resolvePath("/bin/sh"))
        assertEquals(tool, resolver.resolvePath("/bin/../bin/tool"))
        assertEquals(File(rootfs, "usr/bin/new-file"), resolver.resolvePath("/bin/new-file"))
        assertTrue(Files.isSymbolicLink(File(rootfs, "bin").toPath()))
    }

    @Test
    fun absoluteAndRelativeLinksIntoMountsUseTheirPermissionsAndPhysicalRoots() {
        val rootfs = temporaryFolder.newFolder("mount-rootfs")
        val home = temporaryFolder.newFolder("mount-home")
        val shared = temporaryFolder.newFolder("mount-shared")
        val note = File(home, "note.txt").apply { writeText("home") }
        val photo = File(shared, "photo.txt").apply { writeText("shared") }
        symlink(File(rootfs, "home-alias"), "/home/agora")
        symlink(File(home, "shared-alias"), "/mnt/shared")
        val enabled = resolver(rootfs, home) { shared }

        val homePath = enabled.resolveSandboxPath("/home-alias/note.txt")
        assertEquals(note, homePath.file)
        assertEquals(home.canonicalFile, homePath.physicalRoot)
        assertEquals("/home/agora", homePath.virtualRoot)
        assertEquals(photo, enabled.resolvePath("/home/agora/shared-alias/photo.txt"))
        assertTrue(
            runCatching { resolver(rootfs, home).resolvePath("/home/agora/shared-alias/photo.txt") }
                .exceptionOrNull() is SecurityException,
        )
    }

    @Test
    fun traversalAndLinkLoopsFailWithoutReturningHostPaths() {
        val rootfs = temporaryFolder.newFolder("secure-rootfs")
        val home = temporaryFolder.newFolder("secure-home")
        val resolver = resolver(rootfs, home)
        val outside = temporaryFolder.newFolder("outside")
        symlink(File(home, "relative-escape"), "../outside")
        symlink(File(rootfs, "first"), "second")
        symlink(File(rootfs, "second"), "/first")

        assertTrue(runCatching { resolver.resolvePath("/home/agora/../../escape") }.isFailure)
        assertTrue(runCatching { resolver.resolvePath("/home/agora/relative-escape/secret") }.isFailure)
        assertTrue(runCatching { resolver.resolvePath("/first") }.isFailure)
        val outsideGuestPath = "/" + outside.toPath().root.relativize(outside.toPath())
            .toString().replace(File.separatorChar, '/')
        assertFalse(resolver.resolvePath(outsideGuestPath).toPath() == outside.toPath())
    }

    @Test
    fun searchesFollowGuestLinksKeepAliasesAndStopDirectoryCycles() {
        val rootfs = temporaryFolder.newFolder("search-rootfs")
        val home = temporaryFolder.newFolder("search-home")
        val resolver = resolver(rootfs, home)
        File(rootfs, "usr/bin/tool.txt").apply { parentFile!!.mkdirs(); writeText("needle") }
        symlink(File(rootfs, "bin"), "/usr/bin")
        symlink(File(rootfs, "usr/bin/loop"), "/usr")
        symlink(File(rootfs, "usr/bin/shared"), "/mnt/shared")
        val files = mutableListOf<String>()
        val base = resolver.resolveSandboxPath("/usr")
        resolver.walkVirtualFiles(base.file, files, base.physicalRoot.canonicalPath, base.virtualRoot)

        assertEquals(listOf("/usr/bin/tool.txt"), files)
        val aliasFiles = mutableListOf<String>()
        val alias = resolver.resolveSandboxPath("/bin")
        resolver.walkVirtualFiles(
            alias.file, aliasFiles, alias.physicalRoot.canonicalPath, alias.virtualRoot,
            virtualBasePath = "/bin",
        )
        assertEquals(listOf("/bin/tool.txt"), aliasFiles)
        assertTrue(aliasFiles.size < 5)
    }

    @Test
    fun readOnlyReadinessResolutionDoesNotCreateRootfsOrMounts() {
        val parent = temporaryFolder.newFolder("read-only")
        val rootfs = File(parent, "missing-rootfs")
        val home = File(parent, "missing-home")
        val resolver = resolver(rootfs, home)

        assertFalse(resolver.resolvePath("/bin/sh").exists())
        assertFalse(resolver.resolvePath("/lib/ld-linux-aarch64.so.1").exists())
        assertFalse(rootfs.exists())
        assertFalse(home.exists())
    }

    @Test
    fun mountTargetCreationDoesNotFollowHostAbsoluteLinks() {
        val rootfs = temporaryFolder.newFolder("targets-rootfs")
        val home = temporaryFolder.newFolder("targets-home")
        symlink(File(rootfs, "home"), "/guest-home")
        symlink(File(rootfs, "mnt"), "/guest-mnt")
        val resolver = resolver(rootfs, home)
        resolver.ensureSandboxMountTargets()

        assertTrue(File(rootfs, "guest-home/agora").isDirectory)
        assertTrue(File(rootfs, "guest-mnt/shared").isDirectory)
        assertEquals(File(home, "note.txt"), resolver.resolvePath("/home/agora/note.txt"))
        assertTrue(runCatching { resolver.resolvePath("/mnt/shared/private.txt") }.exceptionOrNull() is SecurityException)
        assertTrue(Files.isSymbolicLink(File(rootfs, "home").toPath()))
    }

    private fun resolver(rootfs: File, home: File, shared: () -> File? = { null }) =
        SandboxPathResolver(rootfs, home, "/home/agora", "/mnt/shared", shared)

    private fun symlink(file: File, target: String) {
        file.parentFile!!.mkdirs()
        Files.createSymbolicLink(file.toPath(), Paths.get(target))
    }
}
