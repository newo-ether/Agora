package com.newoether.agora.templates

import android.app.Application
import android.content.ContentResolver
import android.content.ContextWrapper
import android.content.Intent
import android.content.UriPermission
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TemplateAndroidSourcesTest {
    private val root = Uri.parse("content://references.test/tree/root")
    private val resolver = mockk<ContentResolver>()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val context = object : ContextWrapper(application) { override fun getContentResolver() = resolver }
    private val access = TemplateAndroidSources(context)
    private val tree = mutableMapOf(
        "root" to mutableListOf(TemplateAndroidSources.Entry("medicine", "Medicine", true)),
        "medicine" to mutableListOf(TemplateAndroidSources.Entry("first", "01_heart-failure.MD", false)),
    )
    private val contents = mutableMapOf("first" to "# Heart failure\n\n**Read this article.**".toByteArray())
    private fun connected(): String {
        val permission = mockk<UriPermission>()
        every { permission.uri } returns root
        every { permission.isReadPermission } returns true
        every { resolver.persistedUriPermissions } returns listOf(permission)
        every { resolver.takePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION) } just Runs
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val uri = firstArg<Uri>()
            val id = DocumentsContract.getDocumentId(uri)
            val columns = secondArg<Array<String>>()
            MatrixCursor(columns).also { cursor ->
                if (uri.lastPathSegment == "children") tree[id].orEmpty().forEach { entry ->
                    cursor.addRow(arrayOf<Any?>(entry.id, entry.name, if (entry.directory) DocumentsContract.Document.MIME_TYPE_DIR else entry.mimeType ?: "text/markdown", entry.size))
                } else cursor.addRow(arrayOf("Clinical references"))
            }
        }
        every { resolver.openInputStream(any()) } answers {
            contents[DocumentsContract.getDocumentId(firstArg())]?.let(::ByteArrayInputStream)
        }
        return access.connect(root)
    }
    private fun template(folder: String) = InteractiveTemplate(name = "OSCE", sourceFolderId = folder,
        answer = InteractiveAnswer("OSCE", "Choose a reference", "Choose a condition", html = "<p>Choose</p>"))

    @Test fun opensWithShellDisabledAndListsOnlyMetadataThenReadsSelectedFile() = runTest {
        val folder = connected()
        val sandbox = mockk<SandboxManager>()
        val sources = TemplateSources(sandbox, GenerationContext(shellEnabled = false, sandboxEnabled = false), access)
        val opened = sources.prepare(template(folder))
        assertEquals(listOf("Medicine/01_heart-failure.MD"), opened.resourceFiles)
        assertEquals("01 heart failure", templateReferenceSnapshot(opened.answer).single().label)
        verify(exactly = 0) { resolver.openInputStream(any()) }
        assertEquals(contents["first"]!!.toString(Charsets.UTF_8), sources.read(opened, opened.resourceFiles.single()))
        verify(exactly = 1) { resolver.openInputStream(any()) }
        coVerify { sandbox wasNot Called }
    }
    @Test fun nextOpenRefreshesCatalogueWhileLiveNativeReadsStayWithinTheGrantedFolder() = runTest {
        val sources = TemplateSources(null, GenerationContext(), access)
        val initial = template(connected())
        val first = sources.prepare(initial)
        tree["medicine"]!!.add(TemplateAndroidSources.Entry("second", "New_condition.md", false))
        contents["second"] = "New article".toByteArray()
        val next = sources.prepare(initial)
        assertEquals(2, next.resourceFiles.size)
        assertEquals(1, first.resourceFiles.size)
        assertEquals("New article", sources.read(first, "Medicine/New_condition.md"))
        assertEquals("New article", sources.read(next, "Medicine/New_condition.md"))
    }
    @Test fun missingGrantMovedFileTraversalAndOversizedFileFailBeforeOpeningAnythingElse() = runTest {
        val folder = connected()
        val sources = TemplateSources(null, GenerationContext(), access)
        val opened = sources.prepare(template(folder))
        try { sources.read(opened, "../secret.md"); fail() } catch (_: IllegalArgumentException) { }
        contents["first"] = ByteArray(128001) { 65 }
        try { sources.read(opened, opened.resourceFiles.single()); fail() } catch (_: IllegalArgumentException) { }
        tree["medicine"]!!.clear()
        try { sources.read(opened, opened.resourceFiles.single()); fail() } catch (_: IllegalStateException) { }
        every { resolver.persistedUriPermissions } returns emptyList()
        assertFalse(access.connected(folder))
        try { sources.prepare(template(folder)); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun ambiguousNamesAndCyclesAreRejectedRatherThanSelectingAnotherDocument() {
        val folder = connected()
        tree["medicine"]!!.add(TemplateAndroidSources.Entry("other", "01_heart-failure.MD", false))
        assertThrows(IllegalArgumentException::class.java) { access.list(folder) }
        tree["medicine"]!!.clear()
        tree["medicine"]!!.add(TemplateAndroidSources.Entry("root", "Again", true))
        assertThrows(IllegalArgumentException::class.java) { access.list(folder) }
    }
    @Test fun manualCatalogueRemainsFixedAndCaseSensitivePathsRemainExact() = runTest {
        val folder = connected()
        val sources = TemplateSources(null, GenerationContext(), access)
        val configured = template(folder).copy(resourceFiles = listOf("Medicine/01_heart-failure.MD"), sourceFilesAuto = false)
        tree["medicine"]!!.add(TemplateAndroidSources.Entry("extra", "Extra.md", false))
        assertEquals(configured.resourceFiles, sources.prepare(configured).resourceFiles)
        try { sources.read(configured, "medicine/01_heart-failure.MD"); fail() } catch (_: IllegalStateException) { }
        assertTrue(isTemplateSourcePath("Medicine/01_heart-failure.MD", true))
    }
    @Test fun genericDirectoryListsEveryTypeAndEmptyFoldersWithoutReadingFileContents() {
        tree["root"]!!.addAll(listOf(
            TemplateAndroidSources.Entry("json", "menu.json", false, "application/json", 7),
            TemplateAndroidSources.Entry("blob", "unknown.custom", false, "application/octet-stream", 3),
            TemplateAndroidSources.Entry("empty", "Empty", true),
            TemplateAndroidSources.Entry("plain", "README", false, "text/plain", 0)))
        val folder = connected()
        val entries = access.listDirectory(folder)
        assertEquals(setOf("Medicine", "menu.json", "unknown.custom", "Empty", "README"), entries.map { it.name }.toSet())
        assertTrue(entries.first().directory)
        assertEquals(7L, entries.single { it.name == "menu.json" }.size)
        assertEquals(emptyList<TemplateDirectoryEntry>(), access.listDirectory(folder, "Empty"))
        verify(exactly = 0) { resolver.openInputStream(any()) }
        assertTrue(access.list(folder).containsAll(listOf("menu.json", "unknown.custom", "README")))
    }
    @Test fun templateCodeCanReadJsonOrArbitraryBytesInsideItsFolderWithNoModelOrShell() {
        tree["root"]!!.addAll(listOf(TemplateAndroidSources.Entry("json", "menu.json", false),
            TemplateAndroidSources.Entry("blob", "unknown.custom", false), TemplateAndroidSources.Entry("plain", "README", false)))
        contents["json"] = "{\"title\":\"Cases\"}".toByteArray()
        contents["blob"] = byteArrayOf(0, 1, 0xFF.toByte())
        contents["plain"] = byteArrayOf()
        val template = template(connected()).copy(sourceFilesAuto = false)
        assertEquals("{\"title\":\"Cases\"}", (access.execute(template, TemplateFileRequest("readTextFile", "menu.json")) as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("AAH/", (access.execute(template, TemplateFileRequest("readFile", "unknown.custom", "base64")) as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("", (access.execute(template, TemplateFileRequest("readTextFile", "README")) as kotlinx.serialization.json.JsonPrimitive).content)
        assertThrows(Exception::class.java) { access.execute(template, TemplateFileRequest("readTextFile", "unknown.custom")) }
        for (path in listOf("../private", "/sdcard/private", "Medicine/../../private", "content://elsewhere/file"))
            assertThrows(IllegalArgumentException::class.java) { access.readBytes(template.sourceFolderId!!, path) }
    }

    @Test fun selectedJsonUsesOwnedAttachmentSnapshotAndInvalidBinaryIsNotSentAsText() = runTest {
        tree["root"]!!.addAll(listOf(TemplateAndroidSources.Entry("json", "menu.json", false, "application/json"),
            TemplateAndroidSources.Entry("blob", "unknown.custom", false, "application/octet-stream")))
        contents["json"] = "{\"title\":\"Cases\"}".toByteArray(); contents["blob"] = byteArrayOf(0, 1, 0xFF.toByte())
        val folder = connected()
        val directory = java.io.File(application.cacheDir, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val app = mockk<Application> { every { filesDir } returns directory; every { contentResolver } returns resolver }
        val attachments = com.newoether.agora.viewmodel.TemplateReferenceAttachments(app) { directory }
        try {
            attachments.addNative(access, folder, "menu.json")
            val attachment = attachments.attachments.single()
            assertEquals("application/json", attachment.mimeType)
            assertEquals("{\"title\":\"Cases\"}", attachment.preparedText)
            assertEquals(com.newoether.agora.model.AttachmentImportState.READY, attachment.importState)
            assertTrue(java.io.File(attachment.localPath!!).isFile)
            try { attachments.addNative(access, folder, "unknown.custom"); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(1, attachments.attachments.size)
        } finally { attachments.close() }
        assertFalse(directory.walkTopDown().any { it.isFile })
        assertEquals("{\"title\":\"Cases\"}", contents["json"]!!.toString(Charsets.UTF_8))
        directory.deleteRecursively()
    }

    @Test fun onDemandDirectoryPagesDoNotRecursivelyWalkOrOpenDocuments() {
        val template = template(connected()).copy(sourceFilesAuto = false)
        tree["root"]!!.addAll((1..4).map { TemplateAndroidSources.Entry("file$it", "file$it.custom", false) })
        val page = kotlinx.serialization.json.Json.decodeFromString<TemplateDirectoryPage>(
            access.execute(template, TemplateFileRequest("listDirectory", "", offset = 0, limit = 2)).toString())
        assertEquals(2, page.entries.size); assertEquals(2, page.nextOffset)
        val final = kotlinx.serialization.json.Json.decodeFromString<TemplateDirectoryPage>(
            access.execute(template, TemplateFileRequest("listDirectory", "", offset = 4, limit = 2)).toString())
        assertEquals(1, final.entries.size); assertNull(final.nextOffset)
        verify(exactly = 0) { resolver.openInputStream(any()) }
        every { resolver.persistedUriPermissions } returns emptyList()
        assertThrows(IllegalArgumentException::class.java) { access.execute(template, TemplateFileRequest("listDirectory", "")) }
    }

}
