package com.newoether.agora.sharing

import android.app.Application
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.viewmodel.AttachmentImportProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SharedFileInboxTest {
    class FilesProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = when(uri.lastPathSegment!!.substringAfterLast('.')) {
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any?>(uri.lastPathSegment, File(context!!.cacheDir, uri.lastPathSegment!!).length()))
            }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(File(context!!.cacheDir, uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    private fun setup(): Application = RuntimeEnvironment.getApplication().also {
        Robolectric.buildContentProvider(FilesProvider::class.java).create("shared.files")
    }
    private fun uri(name: String) = Uri.parse("content://shared.files/$name")
    private fun inbox(app: Application) = SharedFileInbox(app, AttachmentImportProcessor(app,
        readPdfPageCount = { 3 }, readVideoDurationMs = { 1000 }))
    private fun id() = UUID.randomUUID().toString()

    @Test fun manifestRegistersSingleAndMultipleSharesForArbitraryTypes() {
        val app = setup()
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (mime in listOf("image/png","video/mp4","application/pdf","application/epub+zip","application/octet-stream")) {
                assertTrue(app.packageManager.queryIntentActivities(Intent(action).setType(mime),0)
                    .any { it.activityInfo.name == ShareTextActivity::class.java.name })
            }
        }
    }

    @Test fun mixedGroupCopiesAllBytesAndUsesTheExistingPickerRoutes() = runBlocking<Unit> {
        val app = setup()
        val names = listOf("picture.png","paper.pdf","movie.mp4","book.epub")
        names.forEach { File(app.cacheDir,it).writeText("owned bytes $it") }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(names.map(::uri)))
        val request = id()
        val received = inbox(app).receive(request,intent)
        assertEquals(listOf("image","pdf","video","file"),received.map { it.type })
        assertTrue(received.all { it.importState == AttachmentImportState.PROCESSING })
        assertEquals(3, received[1].pageCount)
        assertEquals(AttachmentStorage.LOCAL_SANDBOX_PENDING, received[3].storage)
        received.zip(names).forEach { (attachment,name) ->
            assertEquals("owned bytes $name",File(requireNotNull(attachment.localPath)).readText())
            File(app.cacheDir,name).delete()
        }
        assertEquals(received,inbox(app).receive(request,intent))
        assertEquals(received,SharedTextInbox(app).loadMany(request))
    }

    @Test fun clipOnlySingleFileUsesActualProviderMimeAndName() = runBlocking<Unit> {
        val app = setup()
        File(app.cacheDir,"paper.pdf").writeText("pdf bytes")
        val intent = Intent(Intent.ACTION_SEND).setType("*/*").apply { clipData=ClipData.newRawUri("File",uri("paper.pdf")) }
        val attachment = inbox(app).receive(id(),intent).single()
        assertEquals("paper.pdf",attachment.fileName)
        assertEquals("application/pdf",attachment.mimeType)
        assertEquals("pdf",attachment.type)
    }

    @Test fun failureOfOneSourceDoesNotLeaveAPartialGroupReceipt() = runBlocking<Unit> {
        val app = setup()
        File(app.cacheDir,"picture.png").writeText("first file")
        val request = id()
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM,arrayListOf(uri("picture.png"),uri("missing.pdf")))
        assertTrue(runCatching { inbox(app).receive(request,intent) }.isFailure)
        assertNull(inbox(app).load(request))
        assertTrue(File(app.filesDir,"attachments/shared/$request").walkTopDown().none { it.isFile })
    }

    @Test fun rawPathsRemoteUrlsAndTooManyFilesAreRejected() = runBlocking<Unit> {
        val app = setup()
        for (value in listOf("file:///sdcard/book.epub","https://example.invalid/book.epub")) {
            assertTrue(runCatching { inbox(app).receive(id(),Intent(Intent.ACTION_SEND).setType("*/*")
                .putExtra(Intent.EXTRA_STREAM,Uri.parse(value))) }.isFailure)
        }
        assertTrue(runCatching { inbox(app).receive(id(),Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList((0..32).map { uri("$it.pdf") }))) }.isFailure)
    }
}
