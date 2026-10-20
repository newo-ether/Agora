package com.newoether.agora.sharing

import kotlinx.coroutines.runBlocking
import android.app.Application
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import org.robolectric.Robolectric
import android.graphics.Bitmap
import android.util.Base64
import com.newoether.agora.viewmodel.MessagePayloadBuilder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SharedTextInboxTest {
    private fun incoming() = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "The complete chapter. café 中文！")
        .putExtra(Intent.EXTRA_TITLE, "Chapter Two")
    private fun id() = UUID.randomUUID().toString()

    @Test fun androidManifestExposesATextShareTarget() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val activities = context.packageManager.queryIntentActivities(incoming(), 0)
        assertTrue(activities.any { it.activityInfo.name == ShareTextActivity::class.java.name })
    }

    @Test fun ordinaryAndroidShareBecomesUnsentTextAttachmentWithItsTitle() = runBlocking<Unit> {
        val context=RuntimeEnvironment.getApplication()
        val attachment=SharedTextInbox(context).receive(id(), incoming())
        assertEquals("Chapter Two",attachment.sourceTitle)
        assertEquals("file",attachment.type)
        assertEquals("text/plain",attachment.mimeType)
        assertEquals("The complete chapter. café 中文！",File(requireNotNull(attachment.localPath)).readText())
        assertTrue(requireNotNull(attachment.preparedText).contains("Chapter Two"))
        assertNull(attachment.notesDocumentId)
        val payload=MessagePayloadBuilder().buildComposerPayload(listOf(attachment))
        assertTrue(payload.allImages.isEmpty())
        assertEquals("Chapter Two",payload.attachmentMeta!!.items.single().sourceTitle)
    }

    @Test fun chapterBookAuthorAndCoverSurviveDraftSerializationWithoutBecomingAiImage() = runBlocking<Unit> {
        val context=RuntimeEnvironment.getApplication()
        val image=Bitmap.createBitmap(20,30,Bitmap.Config.ARGB_8888)
        val bytes=ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray() }
        val metadata=JSONObject().put("schema","org.koreader.excerpt/v1").put("title","Chapter 2")
            .put("bookTitle","Example Book").put("authors","Example Author")
            .put("thumbnailBase64",Base64.encodeToString(bytes,Base64.NO_WRAP))
        val attachment=SharedTextInbox(context).receive(id(),incoming().putExtra(EXTRA_EXCERPT_METADATA,metadata.toString()))
        assertEquals("Example Book",attachment.sourceBookTitle)
        assertEquals("Example Author",attachment.sourceAuthors)
        assertTrue(File(requireNotNull(attachment.sourceThumbnailPath)).isFile)
        val restored=Json.decodeFromString<com.newoether.agora.model.SelectedAttachment>(Json.encodeToString(attachment))
        assertEquals(attachment,restored)
        val payload=MessagePayloadBuilder().buildComposerPayload(listOf(restored))
        assertTrue(payload.allImages.isEmpty())
        val item=payload.attachmentMeta!!.items.single()
        assertEquals(attachment.sourceThumbnailPath,item.sourceThumbnailPath)
        assertTrue(requireNotNull(item.textContent).contains("Book: Example Book"))
        assertTrue(item.textContent!!.endsWith("The complete chapter. café 中文！"))
    }

    @Test fun malformedOptionalCoverDoesNotLoseTheText() = runBlocking<Unit> {
        val store=SharedTextInbox(RuntimeEnvironment.getApplication())
        val raw=JSONObject().put("schema","org.koreader.excerpt/v1").put("title","Section")
            .put("thumbnailBase64","not an image").toString()
        val attachment=store.receive(id(),incoming().putExtra(EXTRA_EXCERPT_METADATA,raw))
        assertNull(attachment.sourceThumbnailPath)
        assertTrue(attachment.preparedText!!.contains("The complete chapter"))
    }

    @Test fun retryKeepsStableIdentityOwnerAndBytesInsteadOfDuplicating() = runBlocking<Unit> {
        val context=RuntimeEnvironment.getApplication();val request=id();val store=SharedTextInbox(context)
        val first=store.receive(request,incoming());store.choose(request,"original-draft")
        val again=SharedTextInbox(context).receive(request,incoming().putExtra(Intent.EXTRA_TEXT,"different"))
        assertEquals(first,again)
        assertEquals("original-draft",store.owner(request))
        assertFalse(store.consumed(request));store.consume(request)
        assertTrue(SharedTextInbox(context).consumed(request))
    }


    class CoverProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(File(context!!.cacheDir, "shared-cover.png"), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @Test fun standardAndroidContentUriCoverIsCopiedAndRetainedOnlyAsDisplayMetadata() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "shared-cover.png")
        val image = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        Robolectric.buildContentProvider(CoverProvider::class.java).create("excerpt.cover")
        val uri = Uri.parse("content://excerpt.cover/cover.png")
        val intent = incoming().apply {
            clipData = ClipData.newRawUri("Cover", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_TITLE, "Chapter\nTwo\u0000")
        }
        val attachment = SharedTextInbox(context).receive(id(), intent)
        file.delete()
        assertEquals("Chapter Two", attachment.sourceTitle)
        assertTrue(File(requireNotNull(attachment.sourceThumbnailPath)).isFile)
        assertTrue(MessagePayloadBuilder().buildComposerPayload(listOf(attachment)).allImages.isEmpty())
    }

    @Test fun unreadableCoverUriAndForeignSchemePreserveCompleteSharedText() = runBlocking<Unit> {
        for (uri in listOf("content://unavailable/cover", "https://example.invalid/cover")) {
            val attachment = SharedTextInbox(RuntimeEnvironment.getApplication()).receive(id(), incoming().apply {
                clipData = ClipData.newRawUri("Cover", Uri.parse(uri))
            })
            assertNull(attachment.sourceThumbnailPath)
            assertTrue(attachment.preparedText!!.endsWith("The complete chapter. café 中文！"))
        }
    }

    class TextProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(File(context!!.cacheDir, "shared-book.txt"), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun fileShare(): Intent {
        Robolectric.buildContentProvider(TextProvider::class.java).create("excerpt.text")
        val uri = Uri.parse("content://excerpt.text/book.txt")
        return Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TITLE, "Whole Book")
            .apply { clipData = ClipData.newRawUri("Book", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    @Test fun fourMiBFileIsCopiedInFullWithVisibleContextLimitAndStableRetry() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "shared-book.txt")
        val text = "x".repeat(4 * 1024 * 1024 - 4) + "END!"
        source.writeText(text)
        val store = SharedTextInbox(context)
        val request = id()
        val intent = fileShare().putExtra(Intent.EXTRA_TEXT, "This optional caption must not replace the book")
        val attachment = store.receive(request, intent)
        assertEquals(text, File(requireNotNull(attachment.localPath)).readText())
        assertEquals(4L * 1024 * 1024, attachment.fileSize)
        assertEquals("Whole Book", attachment.sourceTitle)
        assertTrue(requireNotNull(attachment.warning).contains("500000"))
        assertTrue(requireNotNull(attachment.preparedText).endsWith("x".repeat(500_000)))
        source.delete()
        assertEquals(attachment, store.receive(request, intent))
        val item = MessagePayloadBuilder().buildComposerPayload(listOf(attachment)).attachmentMeta!!.items.single()
        assertEquals(attachment.warning, item.warning)
        assertTrue(item.textContent!!.contains("Full file attached"))
    }

    @Test fun utf8TextFileRetainsBookMetadataAndOwnedCoverWithoutAnImageInput() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        File(context.cacheDir, "shared-book.txt").writeText("Complete book café 中文！")
        val image = Bitmap.createBitmap(20,30,Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray() }
        image.recycle()
        val metadata = JSONObject().put("schema","org.koreader.excerpt/v1").put("title","Whole Book")
            .put("bookTitle","Whole Book").put("authors","Author")
            .put("thumbnailBase64",Base64.encodeToString(bytes,Base64.NO_WRAP))
        val attachment = SharedTextInbox(context).receive(id(),fileShare().putExtra(EXTRA_EXCERPT_METADATA,metadata.toString()))
        assertEquals("Whole Book.txt", attachment.fileName)
        assertEquals("Author", attachment.sourceAuthors)
        assertNull(attachment.warning)
        assertTrue(File(requireNotNull(attachment.sourceThumbnailPath)).isFile)
        assertTrue(attachment.preparedText!!.endsWith("Complete book café 中文！"))
        assertTrue(MessagePayloadBuilder().buildComposerPayload(listOf(attachment)).allImages.isEmpty())
    }

    @Test fun emptyFileIsRejectedAndItsStagedCopyIsRemoved() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        File(context.cacheDir, "shared-book.txt").writeText("   ")
        val request = id()
        assertTrue(runCatching { SharedTextInbox(context).receive(request, fileShare()) }.isFailure)
        assertFalse(File(context.filesDir, "attachments/staged/$request/source.txt").exists())
        assertFalse(File(context.filesDir, "attachments/shared/$request/attachment.json").exists())
    }

    @Test fun exactContextBoundaryDoesNotClaimThatTextWasOmitted() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        File(context.cacheDir, "shared-book.txt").writeText("x".repeat(500_000))
        val attachment = SharedTextInbox(context).receive(id(), fileShare())
        assertNull(attachment.warning)
        assertTrue(attachment.preparedText!!.endsWith("x".repeat(500_000)))
    }

    @Test fun fileUrisAndUnreadableContentStreamsAreRejectedInsteadOfImportingTheirCaption() = runBlocking<Unit> {
        for (value in listOf("file:///sdcard/book.txt", "https://example.invalid/book.txt", "content://missing/book.txt")) {
            val intent = incoming().putExtra(Intent.EXTRA_STREAM, Uri.parse(value))
            assertTrue(runCatching { SharedTextInbox(RuntimeEnvironment.getApplication()).receive(id(), intent) }.isFailure)
        }
    }

    @Test(expected=IllegalArgumentException::class) fun emptyTextIsRejected() = runBlocking<Unit> {
        SharedTextInbox(RuntimeEnvironment.getApplication()).receive(id(),incoming().putExtra(Intent.EXTRA_TEXT,""))
    }
    @Test(expected=IllegalArgumentException::class) fun oversizedTextIsRejectedWithoutTruncation() = runBlocking<Unit> {
        SharedTextInbox(RuntimeEnvironment.getApplication()).receive(id(),incoming().putExtra(Intent.EXTRA_TEXT,"x".repeat(512*1024+1)))
    }
    @Test(expected=IllegalArgumentException::class) fun pathsCannotBeRequestIds() = runBlocking<Unit> {
        SharedTextInbox(RuntimeEnvironment.getApplication()).receive("../anything",incoming())
    }
}
