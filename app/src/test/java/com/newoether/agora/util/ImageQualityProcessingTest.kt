package com.newoether.agora.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.media.MediaMetadataRetriever
import com.newoether.agora.data.withImageQualityTestSettings
import com.newoether.agora.tool.ToolImageStore
import com.newoether.agora.viewmodel.ImageProcessor
import com.newoether.agora.viewmodel.VideoSliceConfig
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35], application = Application::class,
    shadows = [ImageQualityProcessingTest.FixturePdfRenderer::class, ImageQualityProcessingTest.FixturePdfPage::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageQualityProcessingTest {
    @get:Rule val files = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun longLivedIngressOwnersUseCurrentSettingsAndRemoteKeepsTheOriginalBytes() = runBlocking {
        withImageQualityTestSettings(app) { manager, _ ->
            val directory = files.newFolder("tools")
            val store = ToolImageStore(app, directory)
            val remote = ToolImageStore(app, files.newFolder("remote"), prepareForModel = false)
            val image = bitmap(2100, 1050)
            try {
                val bytes = encoded(image)
                manager.imageQuality.update("light")
                val light = store.persistGeneratedBytes(bytes)
                assertEquals(1024, light.width)
                assertEquals(512, light.height)
                val remoteLight = remote.persistBytes(bytes, "image/jpeg")
                assertArrayEquals(bytes, File(remoteLight.path).readBytes())
                manager.imageQuality.update("custom", 1536, 33)
                val phone = store.persistBitmap(image, "phone")
                assertEquals(1536, phone.width)
                assertEquals(768, phone.height)
                val reference = ImageArtifactProcessor(app, 1536, 33).fromBitmap(image, files.newFolder("reference"))
                assertArrayEquals(File(reference.path).readBytes(), File(phone.path).readBytes())
                val custom = store.persistGeneratedBytes(bytes)
                assertEquals(1536, custom.width)
                val remoteCustom = remote.persistBytes(bytes, "image/jpeg")
                assertArrayEquals(bytes, File(remoteCustom.path).readBytes())
                assertEquals(2100, remoteCustom.width)
                assertEquals(0, remoteCustom.policyVersion)
                assertFalse(image.isRecycled)
            } finally {
                image.recycle()
            }
        }
    }

    @Test
    fun customJpegQualityDoesNotDiscardAlphaOrUpscaleSmallRasters() = runBlocking {
        withImageQualityTestSettings(app) { manager, _ ->
            val store = ToolImageStore(app, files.newFolder("alpha"))
            val image = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.TRANSPARENT)
                setPixel(10, 10, Color.argb(96, 20, 120, 200))
            }
            try {
                for (quality in listOf(1, 100)) {
                    manager.imageQuality.update("custom", 128, quality)
                    val artifact = store.persistBitmap(image)
                    assertEquals(64, artifact.width)
                    assertEquals(32, artifact.height)
                    assertEquals("image/png", artifact.mimeType)
                    val decoded = android.graphics.BitmapFactory.decodeFile(artifact.path)
                    try {
                        assertEquals(0, Color.alpha(decoded.getPixel(0, 0)))
                        assertEquals(96, Color.alpha(decoded.getPixel(10, 10)))
                    } finally {
                        decoded.recycle()
                    }
                }
            } finally {
                image.recycle()
            }
        }
    }

    @Test
    fun composerUsesCurrentSettingsAndCompletedHighQualityArtifactsSurviveLoweringThePreset() = runBlocking {
        withImageQualityTestSettings(app) { manager, _ ->
            val source = files.newFile("source.jpg")
            val image = bitmap(2100, 1050)
            source.writeBytes(encoded(image))
            image.recycle()
            val owner = ImageProcessor(app)
            val created = mutableListOf<File>()
            try {
                manager.imageQuality.update("high")
                val highPath = requireNotNull(owner.normalizeImage(source.absolutePath))
                created += File(highPath)
                val high = owner.measureArtifact(highPath)
                assertEquals(2100, high.width)
                val bytes = File(highPath).readBytes()
                manager.imageQuality.update("light")
                val processor = ImageArtifactProcessor.configured(app)
                assertTrue(processor.isValid(high))
                assertEquals(high, processor.fromSource(highPath, files.newFolder("reuse"), existing = high))
                assertArrayEquals(bytes, File(highPath).readBytes())
                val lightPath = requireNotNull(owner.normalizeImage(source.absolutePath))
                created += File(lightPath)
                assertEquals(1024, owner.measureArtifact(lightPath).width)
                assertEquals(2100, owner.measureArtifact(highPath).width)
            } finally {
                created.forEach { it.delete() }
            }
        }
    }

    @Test
    fun pdfMixedPagesFreezeTheBatchPresetAndNextBatchUsesTheNewValue() = runBlocking {
        withImageQualityTestSettings(app) { manager, _ ->
            val pdf = createPdf()
            val created = mutableListOf<File>()
            try {
                manager.imageQuality.update("custom", 256, 80)
                val paths = PdfPageRenderer.renderAllPages(app, pdf.absolutePath, onProgress = { current, _ ->
                    if (current == 1) manager.imageQuality.update(customMaxLongEdge = 512)
                })
                created += paths.map(::File)
                val processor = ImageArtifactProcessor(app)
                val artifacts = paths.map { processor.inspect(it) }
                assertEquals(listOf(256 to 128, 128 to 256), artifacts.map { it.width to it.height })
                assertTrue(artifacts.all { it.mimeType == "image/jpeg" })
                val next = PdfPageRenderer.renderAsImages(app, pdf.absolutePath, setOf(0))
                created += next.map(::File)
                assertEquals(512, processor.inspect(next.single()).width)
            } finally {
                created.forEach { it.delete() }
            }
        }
    }

    @Test
    fun pdfCancellationDeletesEveryPagePublishedByThatBatch() = runBlocking {
        withImageQualityTestSettings(app) { manager, _ ->
            manager.imageQuality.update("custom", 128, 80)
            val before = app.filesDir.listFiles().orEmpty().map { it.name }.toSet()
            try {
                PdfPageRenderer.renderAllPages(app, createPdf().absolutePath, onProgress = { _, _ ->
                    throw CancellationException("cancel after first page")
                })
                fail("Cancellation was swallowed")
            } catch (_: CancellationException) {
                assertEquals(before, app.filesDir.listFiles().orEmpty().map { it.name }.toSet())
            }
        }
    }

    @Test
    fun videoMixedFramesFreezeOneSnapshotAndCancellationCleansPublishedFrames() = runBlocking {
        withImageQualityTestSettings(app) { manager, _ ->
            val owner = ImageProcessor(app)
            val created = mutableListOf<File>()
            mockkConstructor(MediaMetadataRetriever::class)
            try {
                every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<String>()) } just Runs
                every { anyConstructed<MediaMetadataRetriever>().release() } just Runs
                manager.imageQuality.update("custom", 256, 80)
                var count = 0
                every { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) } answers {
                    count++
                    if (count == 1) runBlocking { manager.imageQuality.update(customMaxLongEdge = 512) }
                    if (count % 2 == 1) bitmap(600, 300) else bitmap(300, 600)
                }
                val paths = owner.extractVideoFrames("/test-video.mp4", VideoSliceConfig(1000, 2))
                created += paths.map(::File)
                val processor = ImageArtifactProcessor(app)
                assertEquals(listOf(256 to 128, 128 to 256), paths.map {
                    processor.inspect(it).let { artifact -> artifact.width to artifact.height }
                })
                val next = owner.extractVideoFrames("/test-video.mp4", VideoSliceConfig(1000, 1))
                created += next.map(::File)
                assertEquals(512, processor.inspect(next.single()).width)
                val before = app.filesDir.listFiles().orEmpty().map { it.name }.toSet()
                count = 0
                every { anyConstructed<MediaMetadataRetriever>().getFrameAtTime(any(), any()) } answers {
                    if (++count == 2) throw CancellationException("cancel after first frame")
                    bitmap(600, 300)
                }
                try {
                    owner.extractVideoFrames("/test-video.mp4", VideoSliceConfig(1000, 2))
                    fail("Cancellation was swallowed")
                } catch (_: CancellationException) {
                    assertEquals(before, app.filesDir.listFiles().orEmpty().map { it.name }.toSet())
                }
                verify(atLeast = 3) { anyConstructed<MediaMetadataRetriever>().release() }
            } finally {
                unmockkConstructor(MediaMetadataRetriever::class)
                created.forEach { it.delete() }
            }
        }
    }

    /** Robolectric has no PDF engine; only that framework boundary is replaced. */
    @Implements(PdfRenderer::class)
    class FixturePdfRenderer {
        private lateinit var input: ParcelFileDescriptor.AutoCloseInputStream
        private lateinit var sizes: List<Pair<Int, Int>>

        @Implementation
        fun __constructor__(descriptor: ParcelFileDescriptor) {
            input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            sizes = Regex("/MediaBox \\[0 0 (\\d+) (\\d+)]").findAll(input.bufferedReader().readText())
                .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
            check(sizes.size == 2)
        }

        @Implementation
        fun getPageCount(): Int = sizes.size

        @Implementation
        fun openPage(index: Int): PdfRenderer.Page = Shadow.newInstanceOf(PdfRenderer.Page::class.java).also {
            val page = Shadow.extract<FixturePdfPage>(it)
            page.size = sizes[index]
        }

        @Implementation
        fun close() { input.close() }
    }

    @Implements(PdfRenderer.Page::class)
    class FixturePdfPage {
        lateinit var size: Pair<Int, Int>

        @Implementation
        fun getWidth(): Int = size.first

        @Implementation
        fun getHeight(): Int = size.second

        @Implementation
        fun render(bitmap: Bitmap, clip: Rect?, transform: Matrix?, mode: Int) {
            assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
            assertFalse(bitmap.hasAlpha())
            assertEquals(PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY, mode)
        }

        @Implementation
        fun close() = Unit
    }

    private fun bitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(false)
            eraseColor(Color.rgb(48, 113, 177))
        }

    private fun encoded(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it))
        it.toByteArray()
    }

    private fun createPdf(): File {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Resources << >> /Contents 5 0 R >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 200] /Resources << >> /Contents 5 0 R >>",
            "<< /Length 0 >>\nstream\nendstream",
        )
        val document = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, value ->
            document.length.also { document.append("${index + 1} 0 obj\n$value\nendobj\n") }
        }
        val xref = document.length
        document.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { document.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        document.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return files.newFile().apply { writeText(document.toString(), Charsets.US_ASCII) }
    }
}
