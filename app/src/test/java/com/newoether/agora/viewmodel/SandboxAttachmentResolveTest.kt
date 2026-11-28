package com.newoether.agora.viewmodel

import android.app.Application
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.Constants
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxAttachmentResolveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun pdfStagesPageCountPreviewsAndReusesSelectedPagesWhileKeepingOriginalBytes() = runTest {
        val raw = source("document.pdf")
        val original = raw.readBytes()
        val pages = listOf(source("pdf_0.png"), source("pdf_1.png"), source("pdf_2.png"))
        var renders = 0
        val owner = Any()
        val processor = AttachmentImportProcessor(
            app = app(), readPdfPageCount = { assertArrayEquals(original, File(it).readBytes()); 3 },
            renderAllPdfPages = { staged, count, progress, actualOwner ->
                assertEquals(owner, actualOwner)
                assertEquals(3, count)
                assertArrayEquals(original, File(staged).readBytes())
                renders++
                progress?.invoke(3, 3)
                pages.map { it.path }
            },
            renderPdf = { _, _, _ -> error("Selected previews must be reused") },
            measureArtifact = { measured(it) },
        )
        val staged = processor.stage(selected("pdf", raw), owner) as AttachmentImportProcessor.StageResult.Success
        assertEquals(3, staged.attachment.pageCount)
        var progress = 0
        val preview = processor.preparePdfPreview(staged.attachment, owner) { done, total ->
            assertEquals(3, total)
            progress = done
        } as AttachmentImportProcessor.ProcessResult.Ready
        assertEquals(AttachmentImportState.PROCESSING, preview.attachment.importState)
        assertEquals(3, progress)
        val home = temporary.newFolder("sandbox-home")
        val ready = processor.process(preview.attachment.copy(selectedPages = setOf(2, 0)), home, owner)
            as AttachmentImportProcessor.ProcessResult.Ready
        assertEquals(1, renders)
        assertEquals(listOf(pages[0].path, pages[2].path), ready.attachment.preRenderedPaths)
        assertEquals(setOf(0, 1), ready.attachment.selectedPages)
        assertEquals("/home/agora/attachments/id/document.pdf", ready.attachment.sandboxPath)
        assertArrayEquals(original, File(requireNotNull(ready.attachment.localPath)).readBytes())
        assertTrue(pages.all { it.isFile })
        assertEquals(listOf(ready.attachment.localPath), ready.createdPaths)
        assertEquals(listOf(staged.attachment.localPath), ready.obsoletePaths)
        val payload = MessagePayloadBuilder().buildComposerPayload(listOf(ready.attachment))
        assertEquals(ready.attachment.preRenderedPaths, payload.allImages)
        assertEquals(2, requireNotNull(payload.attachmentMeta).items.single().pageCount)
    }

    @Test
    fun videoStaysOriginalFileWithoutSlicingInSandbox() = runTest {
        val raw = source("clip.mp4")
        var slices = 0
        val processor = AttachmentImportProcessor(
            app = app(), readVideoDurationMs = { assertTrue(File(it).isFile); 9000L },
            extractVideoFrames = { _, _, _ -> slices++; error("Sandbox videos must not be sliced") },
            measureArtifact = { measured(it) },
        )
        val staged = processor.stage(selected("video", raw)) as AttachmentImportProcessor.StageResult.Success
        assertEquals(9000L, staged.attachment.videoDurationMs)
        val home = temporary.newFolder("video-home")
        val ready = processor.process(staged.attachment, home) as AttachmentImportProcessor.ProcessResult.Ready
        assertEquals(0, slices)
        assertEquals(AttachmentImportState.READY, ready.attachment.importState)
        assertTrue(ready.attachment.processedFrames.isNullOrEmpty())
        assertTrue(ready.attachment.imageArtifacts.isEmpty())
        assertEquals("/home/agora/attachments/id/clip.mp4", ready.attachment.sandboxPath)
        assertArrayEquals(raw.readBytes(), File(requireNotNull(ready.attachment.localPath)).readBytes())
        val payload = MessagePayloadBuilder().buildComposerPayload(listOf(ready.attachment))
        assertTrue(payload.allImages.isEmpty())
        val item = requireNotNull(payload.attachmentMeta).items.single()
        assertEquals("video", item.type)
        assertEquals(ready.attachment.sandboxPath, item.sandboxPath)
        assertNull(item.imageIndex)
        assertNull(item.pageCount)
    }

    @Test
    fun sandboxIgnoresStaleSliceConfiguration() = runTest {
        val raw = source("sliced.mp4")
        var slices = 0
        val processor = AttachmentImportProcessor(
            app = app(), readVideoDurationMs = { 9000L },
            extractVideoFrames = { _, _, _ -> slices++; error("Sandbox videos must not be sliced") },
            measureArtifact = { measured(it) },
        )
        val staged = processor.stage(selected("video", raw)) as AttachmentImportProcessor.StageResult.Success
        val home = temporary.newFolder("sliced-home")
        val ready = processor.process(staged.attachment.copy(frameCount = 2, sliceIntervalMs = 4500L), home)
            as AttachmentImportProcessor.ProcessResult.Ready
        assertEquals(0, slices)
        assertTrue(ready.attachment.processedFrames.isNullOrEmpty())
        assertArrayEquals(raw.readBytes(), File(requireNotNull(ready.attachment.localPath)).readBytes())
    }

    @Test
    fun nonSandboxVideoKeepsOrdinarySlicing() = runTest {
        val raw = source("private.mp4")
        val frames = listOf(source("private_0.png"), source("private_1.png"))
        var slices = 0
        val processor = AttachmentImportProcessor(
            app = app(), readVideoDurationMs = { 9000L },
            extractVideoFrames = { staged, config, _ ->
                assertArrayEquals(raw.readBytes(), File(staged).readBytes())
                assertEquals(2, config.frameCount)
                assertEquals(4_500_000L, config.intervalMicros)
                slices++
                frames.map { it.path }
            }, measureArtifact = { measured(it) },
        )
        val staged = processor.stage(selected("video", raw, sandbox = false))
            as AttachmentImportProcessor.StageResult.Success
        val ready = processor.process(staged.attachment.copy(frameCount = 2, sliceIntervalMs = 4500L))
            as AttachmentImportProcessor.ProcessResult.Ready
        assertEquals(1, slices)
        assertEquals(frames.map { it.path }, ready.attachment.processedFrames)
        assertEquals(2, ready.attachment.imageArtifacts.size)
    }

    @Test
    fun textPreviewIsBoundedWhilePublishedUtf8RemainsComplete() = runTest {
        val text = "中文🙂\n".repeat(Constants.MAX_FILE_CONTENT_READ_LENGTH / 3)
        val raw = temporary.newFile("notes.txt").apply { writeText(text, Charsets.UTF_8) }
        val processor = AttachmentImportProcessor(app = app(), readText = { path, limit ->
            assertEquals(Constants.MAX_FILE_CONTENT_READ_LENGTH, limit)
            File(path).readText(Charsets.UTF_8).take(limit)
        })
        val ready = processor.process(selected("file", raw).copy(mimeType = "text/plain"), temporary.newFolder("text-home"))
            as AttachmentImportProcessor.ProcessResult.Ready
        assertEquals(text.take(Constants.MAX_FILE_CONTENT_READ_LENGTH), ready.attachment.preparedText)
        val original = File(requireNotNull(ready.attachment.localPath))
        assertArrayEquals(raw.readBytes(), original.readBytes())
        original.writeText("tool edit")
        assertEquals(text.take(Constants.MAX_FILE_CONTENT_READ_LENGTH), ready.attachment.preparedText)
        assertTrue(original.delete())
        assertEquals(text.take(Constants.MAX_FILE_CONTENT_READ_LENGTH), ready.attachment.preparedText)
    }

    @Test
    fun failedSandboxVideoPublicationKeepsStagedSource() = runTest {
        val raw = source("oversize.mp4")
        val home = temporary.newFolder("failed-home")
        val processor = AttachmentImportProcessor(app = app(), maxAttachmentBytes = 2L,
            measureArtifact = { measured(it) })
        val result = processor.process(selected("video", raw), home)
        assertTrue(result is AttachmentImportProcessor.ProcessResult.Failure)
        assertTrue(raw.isFile)
        assertFalse(File(home, "attachments/id/oversize.mp4").exists())
    }

    @Test
    fun failedPublicationDoesNotDeleteExistingPdfPreviews() = runTest {
        val raw = source("oversize.pdf")
        val preview = source("existing-page.png")
        val home = temporary.newFolder("pdf-failed-home")
        val processor = AttachmentImportProcessor(app = app(), maxAttachmentBytes = 2L,
            measureArtifact = { measured(it) })
        val result = processor.process(selected("pdf", raw).copy(selectedPages = setOf(0),
            preRenderedPaths = listOf(preview.path), imageArtifacts = listOf(measured(preview.path))), home)
        assertTrue(result is AttachmentImportProcessor.ProcessResult.Failure)
        assertTrue(preview.isFile)
        assertTrue(raw.isFile)
        assertFalse(File(home, "attachments/id/oversize.pdf").exists())
    }

    @Test
    fun cancelledFrameMeasurementDeletesNewFramesAndNeverPublishesOriginal() = runTest {
        val raw = source("cancelled.mp4")
        val frames = listOf(source("cancelled-frame.png"))
        val processor = AttachmentImportProcessor(app = app(),
            extractVideoFrames = { _, _, _ -> frames.map { it.path } },
            measureArtifact = { throw CancellationException("cancelled resolve") })
        try {
            processor.process(
                selected("video", raw, sandbox = false).copy(frameCount = 2, sliceIntervalMs = 1000L),
            )
            fail("Cancellation must propagate")
        } catch (cancelled: CancellationException) {
            assertEquals("cancelled resolve", cancelled.message)
        }
        assertTrue(raw.isFile)
        assertFalse(frames.single().exists())
    }

    @Test
    fun unsupportedSandboxFilePublishesOnlyOriginalWithoutPreviewCopy() = runTest {
        val raw = source("archive.bin")
        val processor = AttachmentImportProcessor(app = app(), readText = { _, _ -> error("Not text") },
            measureArtifact = { error("Not an image") })
        val ready = processor.process(selected("file", raw).copy(mimeType = "application/octet-stream"),
            temporary.newFolder("generic-home")) as AttachmentImportProcessor.ProcessResult.Ready
        assertNull(ready.attachment.preparedText)
        assertTrue(ready.attachment.imageArtifacts.isEmpty())
        assertEquals(listOf(ready.attachment.localPath), ready.createdPaths)
        assertArrayEquals(raw.readBytes(), File(ready.attachment.localPath!!).readBytes())
    }

    @Test
    fun cancelledImageMeasurementDeletesSnapshotAndDoesNotPublishOriginal() = runTest {
        val raw = source("cancelled.png")
        val home = temporary.newFolder("image-cancelled-home")
        var snapshot: File? = null
        val processor = AttachmentImportProcessor(app = app(), measureArtifact = {
            snapshot = File(it)
            assertArrayEquals(raw.readBytes(), snapshot!!.readBytes())
            throw CancellationException("cancelled snapshot")
        })
        try {
            processor.process(selected("image", raw), home)
            fail("Cancellation must propagate")
        } catch (cancelled: CancellationException) {
            assertEquals("cancelled snapshot", cancelled.message)
        }
        assertNotNull(snapshot)
        assertFalse(snapshot!!.exists())
        assertTrue(raw.isFile)
        assertFalse(File(home, "attachments/id/cancelled.png").exists())
    }

    private fun selected(type: String, raw: File, sandbox: Boolean = true) = SelectedAttachment(
        localId = "id", uri = raw.path, localPath = raw.path, type = type, fileName = raw.name,
        fileSize = raw.length(),
        storage = if (sandbox) AttachmentStorage.LOCAL_SANDBOX_PENDING else AttachmentStorage.APP_PRIVATE,
        importState = AttachmentImportState.PROCESSING,
    )

    private fun source(name: String) = temporary.newFile(name).apply { writeBytes(byteArrayOf(0, 1, 2, -1)) }
    private fun measured(path: String) = ImageArtifact(path, "image/png", File(path).length(), 31, 47)
    private fun app(): Application = mockk<Application>().also { every { it.filesDir } returns temporary.root }
}
