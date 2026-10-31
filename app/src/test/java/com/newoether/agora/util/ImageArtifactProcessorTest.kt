package com.newoether.agora.util

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import com.newoether.agora.model.ImageArtifact
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageArtifactProcessorTest {
    @get:Rule
    val files = TemporaryFolder()

    private fun processor() = ImageArtifactProcessor(RuntimeEnvironment.getApplication())

    @Test
    fun rasterDimensionsKeepSmallInputsAndResizePastTheBoundaryExactly() {
        val processor = processor()

        assertEquals(ImageArtifactProcessor.PixelSize(40, 16), processor.targetSize(40, 16))
        assertEquals(ImageArtifactProcessor.PixelSize(2048, 1024), processor.targetSize(2049, 1025))
        assertEquals(ImageArtifactProcessor.PixelSize(1024, 2048), processor.targetSize(1025, 2049))
        assertEquals(1, processor.sampleSizeForBounds(2049, 1025))
        assertEquals(16, processor.sampleSizeForBounds(40_000, 1_000))
    }

    @Test
    fun sourceJustOverTheBoundaryPublishes2048RatherThanASubsampled1024() {
        val source = files.newFile("source.jpg")
        writeImage(source, 2049, 1025, alpha = false)
        val output = files.newFolder("output")

        val artifact = processor().fromSource(source.absolutePath, output)

        assertEquals(2048, artifact.width)
        assertEquals(1024, artifact.height)
        assertEquals("image/jpeg", artifact.mimeType)
        assertMeasuredArtifact(artifact)
        assertEquals(listOf(File(artifact.path)), output.listFiles()?.toList())
    }

    @Test
    fun bitmapEncodingPreservesAlphaAndDoesNotRecycleTheCallerBitmap() {
        val output = files.newFolder("output")
        val opaque = Bitmap.createBitmap(91, 37, Bitmap.Config.ARGB_8888)
        val transparent = Bitmap.createBitmap(37, 91, Bitmap.Config.ARGB_8888)
        try {
            opaque.eraseColor(Color.WHITE)
            opaque.setHasAlpha(false)
            transparent.eraseColor(Color.TRANSPARENT)
            transparent.setPixel(3, 4, Color.argb(128, 255, 0, 0))

            val jpeg = processor().fromBitmap(opaque, output)
            val png = processor().fromBitmap(transparent, output)

            assertEquals("image/jpeg", jpeg.mimeType)
            assertTrue(jpeg.path.endsWith(".jpg"))
            assertEquals(91, jpeg.width)
            assertEquals(37, jpeg.height)
            assertEquals("image/png", png.mimeType)
            assertTrue(png.path.endsWith(".png"))
            assertMeasuredArtifact(jpeg)
            assertMeasuredArtifact(png)
            val decoded = requireNotNull(BitmapFactory.decodeFile(png.path))
            try {
                assertEquals(0, Color.alpha(decoded.getPixel(0, 0)))
                assertEquals(128, Color.alpha(decoded.getPixel(3, 4)))
            } finally {
                decoded.recycle()
            }
            assertFalse(opaque.isRecycled)
            assertFalse(transparent.isRecycled)
        } finally {
            opaque.recycle()
            transparent.recycle()
        }
    }

    @Test
    fun exifRotationIsAppliedToPixelsBeforeTheirDimensionsArePublished() {
        val source = files.newFile("rotated.jpg")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            for (x in 40 until 80) for (y in 0 until 40) bitmap.setPixel(x, y, Color.BLUE)
            bitmap.setHasAlpha(false)
            source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val artifact = processor().fromSource(source.absolutePath, files.newFolder("output"))

        assertEquals(40, artifact.width)
        assertEquals(80, artifact.height)
        val decoded = requireNotNull(BitmapFactory.decodeFile(artifact.path))
        try {
            assertTrue(Color.red(decoded.getPixel(20, 10)) > 200)
            assertTrue(Color.blue(decoded.getPixel(20, 70)) > 200)
        } finally {
            decoded.recycle()
        }
        val publishedOrientation = ExifInterface(artifact.path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED,
        )
        assertTrue(
            "Published pixels must not retain an EXIF rotation or reflection",
            publishedOrientation == ExifInterface.ORIENTATION_UNDEFINED ||
                publishedOrientation == ExifInterface.ORIENTATION_NORMAL,
        )
        assertMeasuredArtifact(artifact)
    }

    @Test
    fun fileUriInspectionMeasuresExistingBytesWithoutReencodingOrClaimingProcessing() {
        val source = files.newFile("legacy.png")
        writeImage(source, 73, 41, alpha = true)
        val original = source.readBytes()

        val artifact = processor().inspect(source.toURI().toString())

        assertEquals(source.absolutePath, artifact.path)
        assertEquals("image/png", artifact.mimeType)
        assertEquals(73, artifact.width)
        assertEquals(41, artifact.height)
        assertEquals(original.size.toLong(), artifact.sizeBytes)
        assertEquals(0, artifact.policyVersion)
        assertArrayEquals(original, source.readBytes())
        assertEquals(1, source.parentFile.listFiles()?.size)
    }

    @Test
    fun contentUriInspectionRetainsTheUriAndMeasuresWithoutCopyingItsBytes() {
        val source = files.newFile("content.png")
        writeImage(source, 61, 43, alpha = true)
        val original = source.readBytes()
        val uri = android.net.Uri.parse("content://image-test/picked/1")
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(uri) } answers { original.inputStream() }
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver

        val artifact = ImageArtifactProcessor(context).inspect(uri.toString())

        assertEquals(uri.toString(), artifact.path)
        assertEquals("image/png", artifact.mimeType)
        assertEquals(61, artifact.width)
        assertEquals(43, artifact.height)
        assertEquals(original.size.toLong(), artifact.sizeBytes)
        assertEquals(0, artifact.policyVersion)
        assertEquals(1, source.parentFile.listFiles()?.size)
        assertArrayEquals(original, source.readBytes())
    }

    @Test
    fun processedArtifactIsReusedWithoutAContentIdentityGate() {
        val output = files.newFolder("output")
        val source = files.newFile("source.jpg")
        writeImage(source, 80, 40, alpha = false)
        val processor = processor()
        val artifact = processor.fromSource(source.absolutePath, output)
        val published = File(artifact.path).readBytes()

        val reused = processor.fromSource(artifact.path, output, existing = artifact)

        assertEquals(artifact, reused)
        assertArrayEquals(published, File(artifact.path).readBytes())
        assertEquals(1, output.listFiles()?.size)
        assertTrue(processor.isValid(artifact))
        assertFalse(processor.isValid(artifact.copy(width = 1)))
        assertFalse(processor.isValid(artifact.copy(unavailable = true)))
        val publication = File(artifact.path)
        publication.appendBytes(byteArrayOf(0))
        val measured = processor.inspect(artifact.path, ImageArtifactProcessor.POLICY_VERSION)
        assertTrue(processor.isValid(measured))
        assertArrayEquals(published + byteArrayOf(0), publication.readBytes())
    }

    @Test
    fun encodedByteCapFailureLeavesNoPublishedOrTemporaryFile() {
        val output = files.newFolder("output")
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        try {
            assertThrows(IOException::class.java) {
                processor().fromBitmap(bitmap, output, maxBytes = 1)
            }
            assertEquals(0, output.listFiles()?.size)
            assertFalse(bitmap.isRecycled)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun corruptPayloadWithPngBoundsCannotPublishAnArtifact() {
        val source = files.newFile("truncated.png")
        writeImage(source, 80, 40, alpha = true)
        source.writeBytes(source.readBytes().copyOf(33))
        assertThrows(IOException::class.java) { processor().inspect(source.absolutePath) }
        val output = files.newFolder("output")
        assertThrows(IOException::class.java) { processor().fromSource(source.absolutePath, output) }
        assertEquals(0, output.listFiles()?.size)
    }

    @Test
    fun oldSerializedImageDigestIsIgnoredAndNeverWritten() {
        val legacy = """{"path":"/image.png","mimeType":"image/png","sizeBytes":12,"width":2,"height":3,"sha256":"legacy"}"""
        val codecs = listOf(
            kotlinx.serialization.json.Json,
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        )
        for (json in codecs) {
            val artifact = json.decodeFromString<ImageArtifact>(legacy)
            assertEquals(2, artifact.width)
            assertEquals(3, artifact.height)
            assertFalse(json.encodeToString(ImageArtifact.serializer(), artifact).contains("sha256"))
            val metadata = json.decodeFromString<com.newoether.agora.model.AttachmentMeta>(
                """{"items":[{"type":"image","imageArtifacts":[$legacy]}]}""",
            )
            assertEquals(artifact, metadata.items.single().imageArtifacts.single())
            assertFalse(json.encodeToString(com.newoether.agora.model.AttachmentMeta.serializer(), metadata).contains("sha256"))
            val draft = json.decodeFromString<com.newoether.agora.model.SelectedAttachment>(
                """{"uri":"/image.png","type":"image","imageArtifacts":[$legacy]}""",
            )
            assertEquals(artifact, draft.imageArtifacts.single())
            assertFalse(json.encodeToString(com.newoether.agora.model.SelectedAttachment.serializer(), draft).contains("sha256"))
            val segment = json.decodeFromString<com.newoether.agora.model.MessageSegment>(
                """{"type":"tool","toolImages":[$legacy]}""",
            )
            assertEquals(artifact, segment.toolImages.single())
            assertFalse(json.encodeToString(com.newoether.agora.model.MessageSegment.serializer(), segment).contains("sha256"))
        }
    }

    private fun assertMeasuredArtifact(artifact: ImageArtifact) {
        val bytes = File(artifact.path).readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals(bounds.outWidth, artifact.width)
        assertEquals(bounds.outHeight, artifact.height)
        assertEquals(bounds.outMimeType, artifact.mimeType)
        assertEquals(bytes.size.toLong(), artifact.sizeBytes)
        assertEquals(ImageArtifactProcessor.POLICY_VERSION, artifact.policyVersion)
    }

    private fun writeImage(file: File, width: Int, height: Int, alpha: Boolean) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setHasAlpha(alpha)
            file.outputStream().use { output ->
                val format = if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                check(bitmap.compress(format, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

}
