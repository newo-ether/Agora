package com.newoether.agora.sandbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.CancellationException
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotRootfsArchiveExtractorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun mergedUsrAndGuestAbsoluteLinksStayLinksAndReceiveNestedEntries() {
        val destination = temporaryFolder.newFolder("rootfs")
        extract(destination) {
            directory("usr/bin/")
            directory("usr/lib/")
            symlink("bin", "usr/bin")
            symlink("lib", "/usr/lib")
            file("bin/tool", "guest binary", executable = true)
            file("lib/runtime.so", "guest library")
            symlink("usr/bin/sh", "tool")
            symlink("usr/bin/dangling", "/missing/target")
        }

        assertTrue(Files.isSymbolicLink(File(destination, "bin").toPath()))
        assertEquals(Paths.get("usr/bin"), guestLinkTarget(File(destination, "bin")))
        assertEquals(Paths.get("/usr/lib"), guestLinkTarget(File(destination, "lib")))
        assertEquals("guest binary", File(destination, "usr/bin/tool").readText())
        assertEquals("guest library", File(destination, "usr/lib/runtime.so").readText())
        assertEquals(Paths.get("tool"), guestLinkTarget(File(destination, "usr/bin/sh")))
        assertTrue(Files.isSymbolicLink(File(destination, "usr/bin/dangling").toPath()))
    }

    @Test
    fun hardLinkEntriesCanPrecedeTheirTargetsAndBecomeIndependentExecutableFiles() {
        val destination = temporaryFolder.newFolder("hardlinks")
        extract(destination) {
            hardLink("third", "second")
            hardLink("second", "usr/bin/tool")
            file("usr/bin/tool", "first version", executable = true)
        }

        val source = File(destination, "usr/bin/tool")
        val second = File(destination, "second")
        val third = File(destination, "third")
        assertFalse(Files.isSameFile(source.toPath(), second.toPath()))
        assertFalse(Files.isSameFile(second.toPath(), third.toPath()))
        assertTrue(second.canExecute())
        assertTrue(third.canExecute())
        assertEquals("first version", second.readText())
        source.writeText("updated")
        assertEquals("first version", third.readText())
    }

    @Test
    fun traversalMembersAndGuestLinksNeverWriteToHostTargets() {
        val destination = temporaryFolder.newFolder("secure-rootfs")
        val outside = temporaryFolder.newFolder("outside")
        val hostTarget = File(outside, "sentinel.txt").apply { writeText("unchanged") }
        extract(destination) {
            file("../outside/escape.txt", "escape")
            file("/absolute-escape.txt", "escape")
            file("C:/drive-escape.txt", "escape")
            symlink("host-looking-link", "/" + outside.toPath().root.relativize(outside.toPath())
                .toString().replace(File.separatorChar, '/'))
            symlink("relative-link", "../outside")
            file("safe.txt", "safe")
        }
        // The stored host-looking target is interpreted as a guest absolute path.
        val resolver = SandboxPathResolver(destination, destination, "", "") { null }
        assertTrue(resolver.resolvePath("/host-looking-link/sentinel.txt").toPath().startsWith(destination.toPath()))
        assertTrue(runCatching { resolver.resolvePath("/relative-link/sentinel.txt") }.isFailure)
        assertEquals("unchanged", hostTarget.readText())
        assertFalse(File(outside, "escape.txt").exists())
        assertEquals("safe", File(destination, "safe.txt").readText())
    }

    @Test
    fun archiveWritesThroughAnAbsoluteGuestLinkStayInsideRootfs() {
        val destination = temporaryFolder.newFolder("absolute-write")
        extract(destination) {
            symlink("redirect", "/guest-target")
            file("redirect/nested.txt", "inside")
        }
        assertEquals("inside", File(destination, "guest-target/nested.txt").readText())
        assertTrue(Files.isSymbolicLink(File(destination, "redirect").toPath()))
    }

    @Test
    fun replacingALinkDoesNotOverwriteItsPreviousTarget() {
        val destination = temporaryFolder.newFolder("replacement")
        extract(destination) {
            file("original", "keep")
            symlink("alias", "original")
            file("alias", "replacement")
            hardLink("hard-alias", "original")
            file("hard-alias", "last entry wins")
        }
        assertEquals("keep", File(destination, "original").readText())
        assertEquals("replacement", File(destination, "alias").readText())
        assertFalse(Files.isSymbolicLink(File(destination, "alias").toPath()))
        assertEquals("last entry wins", File(destination, "hard-alias").readText())
    }

    @Test
    fun unresolvedHardLinksFailRatherThanSilentlyProducingAnIncompleteRootfs() {
        val destination = temporaryFolder.newFolder("missing-link")
        val failure = runCatching {
            extract(destination) { hardLink("missing", "never-created") }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(File(destination, "missing").exists())
    }

    @Test
    fun deletingSandboxTreePreservesOutsideLinkTargetsAndTheirContents() {
        val destination = temporaryFolder.newFolder("delete-rootfs")
        val outside = temporaryFolder.newFolder("keep-outside")
        val sentinel = File(outside, "sentinel").apply { writeText("keep") }
        File(destination, "nested/file").apply { parentFile!!.mkdirs(); writeText("remove") }
        Files.createSymbolicLink(File(destination, "outside-link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(destination, "broken-link").toPath(), Paths.get("missing"))

        deleteSandboxTree(destination)

        assertFalse(destination.exists())
        assertEquals("keep", sentinel.readText())
        assertTrue(outside.isDirectory)
    }

    @Test
    fun deletingASymlinkRootDeletesOnlyTheLink() {
        val outside = temporaryFolder.newFolder("root-link-target")
        val sentinel = File(outside, "sentinel").apply { writeText("keep") }
        val link = File(temporaryFolder.root, "root-link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        deleteSandboxTree(link)

        assertFalse(Files.isSymbolicLink(link.toPath()))
        assertEquals("keep", sentinel.readText())
    }

    @Test
    fun cancellationDuringAFileStopsBeforeLaterEntries() {
        val destination = temporaryFolder.newFolder("cancelled")
        val bytes = archive {
            file("large", "x".repeat(256 * 1024))
            file("later", "must not be written")
        }
        var checks = 0
        val failure = runCatching {
            TarArchiveInputStream(ByteArrayInputStream(bytes)).use {
                extractTarEntries(it, destination) {
                    if (++checks == 3) throw CancellationException("test cancellation")
                }
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(File(destination, "later").exists())
    }

    private fun guestLinkTarget(file: File) = Paths.get(
        Files.readSymbolicLink(file.toPath()).toString().replace(File.separatorChar, '/'),
    )

    private fun extract(destination: File, entries: Archive.() -> Unit) {
        TarArchiveInputStream(ByteArrayInputStream(archive(entries))).use {
            extractTarEntries(it, destination)
        }
    }

    private fun archive(entries: Archive.() -> Unit): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            TarArchiveOutputStream(bytes).use { Archive(it).entries() }
            bytes.toByteArray()
        }

    private class Archive(private val tar: TarArchiveOutputStream) {
        fun directory(name: String) {
            tar.putArchiveEntry(TarArchiveEntry(name))
            tar.closeArchiveEntry()
        }

        fun file(name: String, text: String, executable: Boolean = false) {
            val bytes = text.toByteArray()
            tar.putArchiveEntry(TarArchiveEntry(name).apply {
                size = bytes.size.toLong()
                mode = if (executable) 0x1ED else 0x1A4
            })
            tar.write(bytes)
            tar.closeArchiveEntry()
        }

        fun symlink(name: String, target: String) = link(name, target, TarConstants.LF_SYMLINK)

        fun hardLink(name: String, target: String) = link(name, target, TarConstants.LF_LINK)

        private fun link(name: String, target: String, flag: Byte) {
            tar.putArchiveEntry(TarArchiveEntry(name, flag).apply { linkName = target })
            tar.closeArchiveEntry()
        }
    }
}
