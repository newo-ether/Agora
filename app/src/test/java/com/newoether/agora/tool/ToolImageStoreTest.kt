package com.newoether.agora.tool

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.newoether.agora.util.ImageArtifactProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolImageStoreTest {
    @Test
    fun remoteBase64ImagePreservesReceivedBytesEncodingAndDimensions() {
        val directory = Files.createTempDirectory("agora-tool-images-").toFile()
        try {
            val image = encodedImage(2049, 1025, alpha = true)
            val encoded = Base64.encodeToString(image, Base64.DEFAULT)
            val store = ToolImageStore(
                RuntimeEnvironment.getApplication(), directory, prepareForModel = false,
            )

            val result = store.persistBase64(encoded, "image/png; charset=utf-8", "conch")

            assertTrue(File(result.path).name.startsWith("conch_"))
            assertArrayEquals(image, File(result.path).readBytes())
            assertEquals(image.size.toLong(), result.sizeBytes)
            assertEquals(2049, result.width)
            assertEquals(1025, result.height)
            assertEquals("image/png", result.mimeType)
            assertEquals(0, result.policyVersion)
            assertEquals(1, directory.listFiles()?.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun modelStreamUsesExactBoundsAndMeasuredEncodingDespiteTheDeclaredMime() {
        val directory = Files.createTempDirectory("agora-tool-images-").toFile()
        try {
            val image = encodedImage(2049, 1025, alpha = false)
            val store = ToolImageStore(RuntimeEnvironment.getApplication(), directory)

            val result = store.persistBytes(image, "image/png", "mcp")
            val published = File(result.path).readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(published, 0, published.size, bounds)

            assertEquals(2048, result.width)
            assertEquals(1024, result.height)
            assertEquals(bounds.outWidth, result.width)
            assertEquals(bounds.outHeight, result.height)
            assertEquals("image/jpeg", bounds.outMimeType)
            assertEquals(bounds.outMimeType, result.mimeType)
            assertTrue(result.path.endsWith(".jpg"))
            assertEquals(published.size.toLong(), result.sizeBytes)
            assertEquals(ImageArtifactProcessor.POLICY_VERSION, result.policyVersion)
            assertFalse(image.contentEquals(published))
            assertEquals(listOf(File(result.path)), directory.listFiles()?.toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun phoneBitmapUsesTheSharedProcessorAndRetainsCallerOwnership() {
        val directory = Files.createTempDirectory("agora-tool-images-").toFile()
        val bitmap = Bitmap.createBitmap(1025, 2049, Bitmap.Config.ARGB_8888)
        try {
            val store = ToolImageStore(RuntimeEnvironment.getApplication(), directory)

            val result = store.persistBitmap(bitmap, "phone")

            assertEquals(1024, result.width)
            assertEquals(2048, result.height)
            assertEquals("image/png", result.mimeType)
            assertEquals(ImageArtifactProcessor.POLICY_VERSION, result.policyVersion)
            assertTrue(File(result.path).name.startsWith("phone_"))
            assertFalse(bitmap.isRecycled)
            assertEquals(1, directory.listFiles()?.size)
        } finally {
            bitmap.recycle()
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidBase64AndOversizedPayloadLeaveNoPublishedFile() {
        val directory = Files.createTempDirectory("agora-tool-images-").toFile()
        try {
            val store = ToolImageStore(RuntimeEnvironment.getApplication(), directory)
            assertThrows(IllegalArgumentException::class.java) {
                Base64.decode("a", Base64.DEFAULT)
            }
            assertThrows(IOException::class.java) {
                store.persistBase64("a", "image/png")
            }
            assertThrows(IOException::class.java) {
                store.persistBase64("A".repeat(((ToolImageStore.MAX_IMAGE_BYTES + 4) * 4 / 3).toInt()), "image/png")
            }
            assertFalse(directory.listFiles()?.isNotEmpty() == true)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun generatedBytesUseSharedValidationAndMeasuredArtifact() {
        val directory = Files.createTempDirectory("agora-generated-images-").toFile()
        try {
            val store = ToolImageStore(RuntimeEnvironment.getApplication(), directory)
            val result = store.persistGeneratedBytes(encodedImage(32, 16, alpha = false))

            assertEquals(32, result.width)
            assertEquals(16, result.height)
            assertEquals("image/jpeg", result.mimeType)
            assertEquals(ImageArtifactProcessor.POLICY_VERSION, result.policyVersion)
            assertEquals(result, ImageArtifactProcessor(RuntimeEnvironment.getApplication())
                .inspect(result.path, ImageArtifactProcessor.POLICY_VERSION))
            assertThrows(IOException::class.java) {
                store.persistGeneratedBytes("not an image".toByteArray())
            }
            assertEquals(listOf(File(result.path)), directory.listFiles()?.toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun encodedImage(width: Int, height: Int, alpha: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setHasAlpha(alpha)
            return ByteArrayOutputStream().use { output ->
                val format = if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                check(bitmap.compress(format, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

}
