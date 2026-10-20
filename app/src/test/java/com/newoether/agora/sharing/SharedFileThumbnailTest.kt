package com.newoether.agora.sharing

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.viewmodel.MessagePayloadBuilder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SharedFileThumbnailTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun cover(): ByteArray {
        val image = Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray() } }
        finally { image.recycle() }
    }
    private fun epub(opf: String, extras: Map<String,ByteArray> = mapOf("EPUB/images/cover art.png" to cover())): File {
        val directory=File(app.cacheDir,"preview-${UUID.randomUUID()}").apply { mkdirs() }
        return File(directory,"book.epub").apply {
            ZipOutputStream(outputStream()).use { zip ->
                val entries = mapOf("META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"EPUB/content.opf\"/></rootfiles></container>".toByteArray(),
                    "EPUB/content.opf" to opf.toByteArray()) + extras
                entries.forEach { (name,bytes) -> zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() }
            }
        }
    }
    private fun thumbnail(file: File): String? = SharedFileThumbnail.create(app, Uri.parse("content://no-preview/book"),
        SelectedAttachment(uri=file.toURI().toString(),type="file",fileName=file.name,mimeType="application/epub+zip",localPath=file.path))

    @Test fun epub3CoverIsOwnedAndBoundedAndRemainsDisplayOnlyForSandboxFiles() {
        val file=epub("""<package><manifest><item id="cover" href="images/cover%20art.png" properties="cover-image"/></manifest></package>""")
        val path=requireNotNull(thumbnail(file))
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(path,bounds)
        assertTrue(bounds.outWidth in 1..160 && bounds.outHeight in 1..220)
        assertTrue(File(path).length()<=90000)
        val attachment=SelectedAttachment(uri=file.toURI().toString(),type="file",fileName="book.epub",localPath=file.path,
            storage=AttachmentStorage.LOCAL_SANDBOX_PENDING,sandboxPath="/home/agora/attachments/book.epub",sourceThumbnailPath=path)
        val payload=MessagePayloadBuilder().buildComposerPayload(listOf(attachment))
        assertTrue(payload.allImages.isEmpty())
        assertEquals(path,payload.attachmentMeta!!.items.single().sourceThumbnailPath)
        file.delete()
        assertTrue(File(path).isFile)
    }

    @Test fun epub2CoverMetadataFindsTheManifestImage() {
        val file=epub("""<package><metadata><meta name="cover" content="old-cover"/></metadata><manifest><item id="old-cover" href="images/cover art.png"/></manifest></package>""")
        assertNotNull(thumbnail(file))
    }

    @Test fun epub2GuideCoverPageResolvesItsRelativeImage() {
        val page="""<html><body><img src="../images/cover%20art.png"/></body></html>""".toByteArray()
        val file=epub("""<package><guide><reference type="cover" href="text/cover.xhtml"/></guide></package>""",
            mapOf("EPUB/text/cover.xhtml" to page,"EPUB/images/cover art.png" to cover()))
        assertNotNull(thumbnail(file))
    }

    @Test fun absentOrInvalidOrRemoteCoversNeverFailTheFileImport() {
        for (opf in listOf("<package/>","not xml",
            """<package><manifest><item properties="cover-image" href="https://example.invalid/cover.jpg"/></manifest></package>""",
            """<!DOCTYPE package [<!ENTITY example SYSTEM "file:///private">]><package>&example;</package>""")) {
            assertNull(thumbnail(epub(opf)))
        }
    }

    @Test fun oversizedInflatedCoverIsIgnored() {
        val file=epub("""<package><manifest><item properties="cover-image" href="large.png"/></manifest></package>""",
            mapOf("EPUB/large.png" to ByteArray(8*1024*1024+1)))
        assertNull(thumbnail(file))
    }
}
