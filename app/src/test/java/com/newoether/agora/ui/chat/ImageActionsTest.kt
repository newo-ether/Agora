@file:Suppress("DEPRECATION")

package com.newoether.agora.ui.chat

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.newoether.agora.R
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageActionsTest {
    @get:Rule
    val files = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var resolver: ContentResolver
    private lateinit var cache: File
    private var galleryBytes = ByteArrayOutputStream()
    private var galleryValues: ContentValues? = null
    private var finalized = false
    private var deleted = false
    private var sharedFile: File? = null
    private var chooser: Intent? = null
    private val galleryUri = Uri.parse("content://image-actions/gallery/1")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        cache = files.newFolder("cache")
        resolver = mockk()
        context = mockk()
        every { context.cacheDir } returns cache
        every { context.contentResolver } returns resolver
        every { context.packageName } returns "com.newoether.agora"
        every { context.getString(R.string.img_action_share) } returns "Share image"
        every { context.startActivity(any()) } answers { chooser = firstArg() }
        every { resolver.insert(any(), any<ContentValues>()) } answers {
            galleryValues = ContentValues(secondArg<ContentValues>())
            galleryUri
        }
        every { resolver.openOutputStream(galleryUri) } answers { galleryBytes }
        every { resolver.update(galleryUri, any<ContentValues>(), null, null) } answers {
            finalized = secondArg<ContentValues>().getAsInteger(MediaStore.Images.Media.IS_PENDING) == 0
            1
        }
        every { resolver.delete(galleryUri, null, null) } answers { deleted = true; 1 }
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } answers {
            sharedFile = thirdArg()
            Uri.parse("content://image-actions/shared/${sharedFile!!.name}")
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(FileProvider::class)
        Dispatchers.resetMain()
    }

    @Test
    fun galleryUsesActualPngTypeAndPreservesAlphaImageBytesDespiteJpegName() = runTest {
        val bytes = encodedImage(Bitmap.CompressFormat.PNG)
        val source = files.newFile("misleading.jpg").apply { writeBytes(bytes) }

        assertTrue(saveImageToGallery(context, source.absolutePath))

        val values = requireNotNull(galleryValues)
        assertEquals("image/png", values.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertTrue(values.getAsString(MediaStore.Images.Media.DISPLAY_NAME).endsWith(".png"))
        assertArrayEquals(bytes, galleryBytes.toByteArray())
        assertArrayEquals(bytes, source.readBytes())
        assertTrue(finalized)
        assertFalse(deleted)
    }

    @Test
    fun galleryUsesActualJpegTypeDespitePngName() = runTest {
        val bytes = encodedImage(Bitmap.CompressFormat.JPEG)
        val source = files.newFile("misleading.png").apply { writeBytes(bytes) }

        assertTrue(saveImageToGallery(context, Uri.fromFile(source).toString()))

        val values = requireNotNull(galleryValues)
        assertEquals("image/jpeg", values.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertTrue(values.getAsString(MediaStore.Images.Media.DISPLAY_NAME).endsWith(".jpg"))
        assertArrayEquals(bytes, galleryBytes.toByteArray())
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test
    fun shareUsesActualJpegTypeAndCopiesBytesWithoutReencoding() = runTest {
        val bytes = encodedImage(Bitmap.CompressFormat.JPEG)
        val source = files.newFile("misleading.png").apply { writeBytes(bytes) }

        assertTrue(shareImage(context, source.absolutePath))

        assertShared("image/jpeg", ".jpg", bytes)
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test
    fun httpShareDetectsPngDespiteHeaderAndNameAndDownloadsOnlyOnce() = runTest {
        val bytes = encodedImage(Bitmap.CompressFormat.PNG)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            assertTrue(shareImage(context, "http://127.0.0.1:${server.address.port}/misleading.jpg"))

            assertShared("image/png", ".png", bytes)
            assertEquals(1, requests.get())
            assertFalse(cache.listFiles().orEmpty().any { it.name.startsWith("image-download-") })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun invalidBytesCreateNeitherGalleryEntryNorSharedFile() = runTest {
        val source = files.newFile("not-an-image.png").apply { writeText("not image bytes") }

        assertFalse(saveImageToGallery(context, source.absolutePath))
        assertFalse(shareImage(context, source.absolutePath))

        assertEquals(null, galleryValues)
        assertEquals(null, chooser)
        assertTrue(cache.listFiles().isNullOrEmpty())
    }

    @Test
    fun galleryWriteFailureDeletesPartialDestination() = runTest {
        val source = files.newFile("source.png").apply {
            writeBytes(encodedImage(Bitmap.CompressFormat.PNG))
        }
        galleryBytes = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, minOf(length, 1))
                throw IOException("Destination write failed")
            }
        }

        assertFalse(saveImageToGallery(context, source.absolutePath))

        assertTrue(deleted)
        assertFalse(finalized)
        assertTrue(galleryBytes.size() > 0)
    }

    @Test
    fun galleryCancellationDeletesPendingEntryAndPropagates() = runTest {
        val source = files.newFile("source.png").apply {
            writeBytes(encodedImage(Bitmap.CompressFormat.PNG))
        }
        galleryBytes = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                throw CancellationException("Gallery copy cancelled")
            }
        }

        try {
            saveImageToGallery(context, source.absolutePath)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(deleted)
            assertFalse(finalized)
        }
    }

    @Test
    fun contentShareCancellationClosesBothStreamsAndDeletesPartialFile() = runTest {
        val bytes = encodedImage(Bitmap.CompressFormat.PNG)
        val uri = Uri.parse("content://image-actions/picked/misleading.jpg")
        var opens = 0
        var closes = 0
        every { resolver.openAssetFileDescriptor(uri, "r") } returns null
        every { resolver.openInputStream(uri) } answers {
            val copyStream = ++opens == 2
            object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (copyStream) throw CancellationException("Source copy cancelled")
                    return super.read(buffer, offset, length)
                }
                override fun close() {
                    closes++
                    super.close()
                }
            }
        }

        try {
            shareImage(context, uri.toString())
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(2, opens)
            assertEquals(2, closes)
            assertTrue(File(cache, "shared").listFiles().isNullOrEmpty())
            assertEquals(null, chooser)
        }
    }

    private fun assertShared(mime: String, extension: String, bytes: ByteArray) {
        val file = requireNotNull(sharedFile)
        assertTrue(file.name.endsWith(extension))
        assertArrayEquals(bytes, file.readBytes())
        val intent = requireNotNull(chooser).getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_SEND, intent!!.action)
        assertEquals(mime, intent.type)
        assertNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    private fun encodedImage(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(2111, 17, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.argb(128, 255, 0, 0))
            bitmap.setHasAlpha(format == Bitmap.CompressFormat.PNG)
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(format, 93, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
