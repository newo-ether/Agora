package com.newoether.agora.sandbox

import android.app.Application
import android.database.Cursor
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SandboxDocumentsProviderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val selected = MutableStateFlow(SandboxDistribution.ALPINE)
    private lateinit var roots: Map<SandboxDistribution, File>
    private lateinit var home: File
    private lateinit var shared: File
    private lateinit var provider: SandboxDocumentsProvider
    private var sharedAllowed = false

    @Before fun setUp() {
        roots = SandboxDistribution.entries.associateWith { temporary.newFolder(it.id) }
        home = temporary.newFolder("home")
        shared = temporary.newFolder("shared")
        val manager = mockk<SandboxManager>()
        every { manager.distribution } returns selected
        every { manager.resolveHostFile(any(), any()) } answers {
            SandboxPathResolver(
                roots.getValue(secondArg()), home, "/home/agora", "/mnt/shared",
            ) { shared.takeIf { sharedAllowed } }.resolvePath(firstArg())
        }
        provider = SandboxDocumentsProvider().apply { managerProvider = { manager } }
    }

    @Test fun rootsShowOnlySelectedDistributionAndHideUnavailableSystems() {
        selected.value = SandboxDistribution.DEBIAN
        provider.queryRoots(null).use {
            assertTrue(it.moveToFirst())
            assertEquals("debian-root", it.string(Root.COLUMN_DOCUMENT_ID))
            assertEquals("Debian /", it.string(Root.COLUMN_SUMMARY))
            assertEquals(1, it.count)
        }
        selected.value = SandboxDistribution.UBUNTU
        provider.queryRoots(null).use {
            assertTrue(it.moveToFirst())
            assertEquals("ubuntu-root", it.string(Root.COLUMN_DOCUMENT_ID))
        }
        roots.getValue(SandboxDistribution.UBUNTU).delete()
        provider.queryRoots(null).use { assertEquals(0, it.count) }
        provider.managerProvider = { null }
        provider.queryRoots(null).use { assertEquals(0, it.count) }
    }

    @Test fun retainedIdsKeepOriginalDistributionAndLegacyHomeAcrossSelectionChanges() {
        roots.forEach { (distribution, root) -> File(root, "note.txt").writeText(distribution.id) }
        File(home, "note.txt").writeText("shared home")
        selected.value = SandboxDistribution.UBUNTU
        assertEquals("alpine", read("root:note.txt"))
        assertEquals("debian", read("debian-root:note.txt"))
        assertEquals("ubuntu", read("ubuntu-root:note.txt"))
        assertEquals("shared home", read("home:note.txt"))
        selected.value = SandboxDistribution.DEBIAN
        assertEquals("ubuntu", read("ubuntu-root:note.txt"))
        assertEquals("shared home", read("root:home/agora/note.txt"))
        provider.queryDocument("home:note.txt", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("home:note.txt", it.string(Document.COLUMN_DOCUMENT_ID))
        }
        assertEquals(setOf("home:note.txt"), children("home"))
    }

    @Test fun mergedUsrAndGuestAbsoluteLinksAreVisibleAndOpenable() {
        val root = roots.getValue(SandboxDistribution.DEBIAN)
        File(root, "usr/bin/tool.txt").apply { parentFile!!.mkdirs(); writeText("guest tool") }
        symlink(File(root, "bin"), "usr/bin")
        symlink(File(root, "usr/bin/sh.txt"), "/usr/bin/tool.txt")
        assertTrue("debian-root:bin" in children("debian-root"))
        assertEquals(setOf("debian-root:bin/sh.txt", "debian-root:bin/tool.txt"), children("debian-root:bin"))
        assertEquals("guest tool", read("debian-root:bin/sh.txt"))
    }

    @Test fun sharedStorageAndAliasesUseCurrentMountPermission() {
        val root = roots.getValue(SandboxDistribution.DEBIAN)
        symlink(File(root, "shared-alias"), "/mnt/shared")
        File(shared, "note.txt").writeText("shared file")
        assertFalse("debian-root:shared-alias" in children("debian-root"))
        assertTrue(runCatching { read("debian-root:shared-alias/note.txt") }.exceptionOrNull() is SecurityException)
        sharedAllowed = true
        assertTrue("debian-root:shared-alias" in children("debian-root"))
        assertEquals("shared file", read("debian-root:shared-alias/note.txt"))
        sharedAllowed = false
        assertTrue(runCatching { read("debian-root:mnt/shared/note.txt") }.exceptionOrNull() is SecurityException)
    }

    @Test fun deletingAndRenamingLinkEntriesPreserveHomeTargets() {
        val root = roots.getValue(SandboxDistribution.DEBIAN)
        val note = File(home, "keep.txt").apply { writeText("keep") }
        symlink(File(root, "home-alias"), "/home/agora")
        assertEquals("debian-root:renamed", provider.renameDocument("debian-root:home-alias", "renamed"))
        assertFalse(Files.exists(File(root, "home-alias").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isSymbolicLink(File(root, "renamed").toPath()))
        assertEquals("keep", read("debian-root:renamed/keep.txt"))
        provider.deleteDocument("debian-root:renamed")
        assertFalse(Files.isSymbolicLink(File(root, "renamed").toPath()))
        assertEquals("keep", note.readText())
        assertTrue(home.isDirectory)
    }

    @Test fun deletingDirectoryNeverFollowsContainedLinks() {
        val root = roots.getValue(SandboxDistribution.UBUNTU)
        val outside = temporary.newFolder("outside")
        val keep = File(outside, "keep.txt").apply { writeText("outside") }
        val directory = File(root, "remove").apply { mkdir() }
        File(directory, "local.txt").writeText("local")
        Files.createSymbolicLink(File(directory, "outside").toPath(), outside.toPath())
        symlink(File(directory, "home"), "/home/agora")
        File(home, "keep.txt").writeText("home")
        provider.deleteDocument("ubuntu-root:remove")
        assertFalse(directory.exists())
        assertEquals("outside", keep.readText())
        assertEquals("home", File(home, "keep.txt").readText())
    }

    @Test fun createRenameAndWriteKeepGuestAliasAndDistributionIdentity() {
        val root = roots.getValue(SandboxDistribution.DEBIAN)
        File(root, "usr/bin").mkdirs()
        symlink(File(root, "bin"), "/usr/bin")
        val id = provider.createDocument("debian-root:bin", "text/plain", "new.txt")
        assertEquals("debian-root:bin/new.txt", id)
        ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument(id, "w", null)).use {
            it.write("written".toByteArray())
        }
        assertEquals("written", File(root, "usr/bin/new.txt").readText())
        val renamed = provider.renameDocument(id, "renamed.txt")
        assertEquals("debian-root:bin/renamed.txt", renamed)
        assertEquals("written", read(renamed))
        assertEquals("home:new.txt", provider.createDocument("home", "text/plain", "new.txt"))
        assertTrue(File(home, "new.txt").isFile)
        provider.deleteDocument(renamed)
        assertFalse(File(root, "usr/bin/renamed.txt").exists())
    }

    @Test fun treeChecksRejectCrossDistributionIdsAndProtectedOrInvalidPaths() {
        assertTrue(provider.isChildDocument("debian-root", "debian-root:etc/config"))
        assertFalse(provider.isChildDocument("root", "debian-root:etc/config"))
        assertFalse(provider.isChildDocument("debian-root", "debian-root"))
        assertTrue(provider.isChildDocument("home", "home:note.txt"))
        listOf("root", "home", "root:home", "root:home/agora", "root:mnt/shared").forEach {
            assertTrue(runCatching { provider.deleteDocument(it) }.exceptionOrNull() is FileNotFoundException)
        }
        listOf("unknown-root", "root:../outside", "debian-root:/etc", "root:etc//config").forEach {
            assertTrue(runCatching { provider.queryDocument(it, null) }.exceptionOrNull() is FileNotFoundException)
        }
    }

    private fun read(id: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(provider.openDocument(id, "r", null)).bufferedReader().use {
            it.readText()
        }

    private fun children(id: String): Set<String> = provider.queryChildDocuments(id, null, sortOrder = null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.string(Document.COLUMN_DOCUMENT_ID)) }
    }

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

    private fun symlink(file: File, target: String) {
        file.parentFile!!.mkdirs()
        Files.createSymbolicLink(file.toPath(), Paths.get(target))
    }
}
