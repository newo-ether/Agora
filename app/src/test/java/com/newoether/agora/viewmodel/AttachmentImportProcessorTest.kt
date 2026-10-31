package com.newoether.agora.viewmodel

import android.app.Application
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.Constants
import com.newoether.agora.util.AttachmentFiles
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentImportProcessorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    @Test
    fun stagedAndPartialPathsAreRetainedBeforeSourceReadAndReleasedByExactProducer() = runTest {
        val owner = Any()
        val target = File(temporaryFolder.root, "attachments/staged/id/source.txt")
        val partial = File(target.parentFile, "source.txt.part")
        val processor = processor(openSource = {
            assertFalse(AttachmentFiles.deleteIfUnowned(target))
            assertFalse(AttachmentFiles.deleteIfUnowned(partial))
            ByteArrayInputStream("source".toByteArray())
        })
        try {
            val result = processor.stage(attachment(type = "file", fileName = "notes.txt"), owner)
            assertTrue(result is AttachmentImportProcessor.StageResult.Success)
            assertTrue(target.exists())
            assertFalse(partial.exists())
            assertFalse(AttachmentFiles.deleteIfUnowned(target))
        } finally {
            AttachmentFiles.deleteUnownedPaths(AttachmentFiles.releaseLivePaths(owner))
        }
        assertFalse(target.exists())
    }

    @Test
    fun stageCreatesProcessingSnapshotAndReportsOwnedPaths() = runTest {
        val source = temporaryFolder.newFile("camera.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val processor = processor(
            openSource = { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) },
        )

        val result = processor.stage(
            attachment(
                type = "image",
                localPath = source.absolutePath,
                processedFrames = listOf("old-frame"),
                preRenderedPaths = listOf("old-page"),
                unavailable = true,
            ),
        ) as AttachmentImportProcessor.StageResult.Success

        val staged = File(requireNotNull(result.attachment.localPath))
        assertTrue(staged.isFile)
        assertEquals(listOf(1, 2, 3, 4), staged.readBytes().map(Byte::toInt))
        assertEquals(AttachmentImportState.PROCESSING, result.attachment.importState)
        assertEquals(4L, result.attachment.fileSize)
        assertNull(result.attachment.processedFrames)
        assertNull(result.attachment.preRenderedPaths)
        assertFalse(result.attachment.unavailable)
        assertEquals(listOf(staged.absolutePath), result.createdPaths)
        assertEquals(listOf(source.absolutePath), result.obsoletePaths)
        assertNull(result.attachment.pageCount)
        assertNull(result.attachment.videoDurationMs)
    }

    @Test
    fun stageRestagesExistingPrivatePathWithoutOverwritingIt() = runTest {
        val staged = File(temporaryFolder.root, "attachments/staged/id/source.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val processor = processor()

        val result = processor.stage(
            attachment(
                type = "image",
                fileName = "photo.jpg",
                localPath = staged.absolutePath,
            ),
        ) as AttachmentImportProcessor.StageResult.Success

        val restaged = File(requireNotNull(result.attachment.localPath))
        assertTrue(staged.isFile)
        assertTrue(restaged.isFile)
        assertFalse(staged.absolutePath == restaged.absolutePath)
        assertEquals(listOf(1, 2, 3, 4), restaged.readBytes().map(Byte::toInt))
        assertEquals(listOf(restaged.absolutePath), result.createdPaths)
        assertEquals(listOf(staged.absolutePath), result.obsoletePaths)
    }

    @Test
    fun stagePersistsPdfPageCountAndVideoDurationFromPrivateCopies() = runTest {
        val processor = processor(
            openSource = { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) },
            readPdfPageCount = { source ->
                assertTrue(File(source).isFile)
                12
            },
            readVideoDurationMs = { source ->
                assertTrue(File(source).isFile)
                34_500L
            },
        )

        val pdf = processor.stage(
            attachment(type = "pdf", fileName = "document.pdf"),
        ) as AttachmentImportProcessor.StageResult.Success
        val video = processor.stage(
            attachment(type = "video", fileName = "clip.mp4"),
        ) as AttachmentImportProcessor.StageResult.Success

        assertEquals(12, pdf.attachment.pageCount)
        assertNull(pdf.attachment.videoDurationMs)
        assertEquals(34_500L, video.attachment.videoDurationMs)
        assertNull(video.attachment.pageCount)
    }

    @Test
    fun stageFailsWithoutPdfPageCountAndKeepsUnknownVideoDuration() = runTest {
        val processor = processor(
            openSource = { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) },
        )

        val pdfResult = processor.stage(
            attachment(type = "pdf", fileName = "document.pdf"),
        )
        val videoResult = processor.stage(
            attachment(type = "video", fileName = "clip.mp4"),
        ) as AttachmentImportProcessor.StageResult.Success

        val pdfFailure = pdfResult as AttachmentImportProcessor.StageResult.Failure
        val failedPdf = requireNotNull(pdfFailure.attachment)
        val failedPdfPath = requireNotNull(failedPdf.localPath)
        assertEquals(AttachmentImportState.FAILED, failedPdf.importState)
        assertEquals(listOf(failedPdfPath), pdfFailure.createdPaths)
        assertTrue(File(failedPdfPath).isFile)
        assertEquals(0L, videoResult.attachment.videoDurationMs)
        assertTrue(File(temporaryFolder.root, "attachments/staged/id/source.mp4").isFile)
    }

    @Test
    fun stageRejectsActualOverflowAndDeletesPartialOutput() = runTest {
        val processor = processor(
            openSource = { ByteArrayInputStream(ByteArray(5)) },
            maxAttachmentBytes = 4L,
        )

        val result = processor.stage(attachment(type = "file", fileName = "notes.txt"))

        assertEquals(AttachmentImportProcessor.StageResult.TooLarge, result)
        assertFalse(File(temporaryFolder.root, "attachments/staged/id/source.txt.part").exists())
        assertFalse(File(temporaryFolder.root, "attachments/staged/id/source.txt").exists())
    }

    @Test
    fun cancelledStageDeletesPartialOutputAndPropagatesCancellation() = runTest {
        val cancellation = CancellationException("cancelled")
        val processor = processor(
            openSource = {
                object : InputStream() {
                    override fun read(): Int = throw cancellation
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        throw cancellation
                }
            },
        )

        val thrown = runCatching {
            processor.stage(attachment(type = "file", fileName = "notes.txt"))
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(cancellation.message, thrown?.message)
        assertFalse(File(temporaryFolder.root, "attachments/staged/id/source.txt.part").exists())
        assertFalse(File(temporaryFolder.root, "attachments/staged/id").exists())
    }

    @Test
    fun missingStagedSourceFailsWithoutCallingTypeProcessor() = runTest {
        var imageCalled = false
        val processor = processor(
            normalizeImage = {
                imageCalled = true
                null
            },
        )

        val result = processor.process(
            attachment(type = "image", localPath = File(temporaryFolder.root, "missing").path),
        )

        assertTrue(result is AttachmentImportProcessor.ProcessResult.Failure)
        assertFalse(imageCalled)
    }

    @Test
    fun imageProcessingPromotesNormalizedArtifactAndRetiresStagedSource() = runTest {
        val staged = temporaryFolder.newFile("staged-image")
        val normalized = temporaryFolder.newFile("normalized.jpg").apply {
            writeBytes(byteArrayOf(8, 9))
        }
        val measured = ImageArtifact(normalized.absolutePath, "image/png", 2, 901, 377, 1)
        var normalizedSource: String? = null
        val processor = processor(
            normalizeImage = { source ->
                normalizedSource = source
                normalized.absolutePath
            },
            measureArtifact = { path ->
                assertEquals(normalized.absolutePath, path)
                measured
            },
        )

        val result = processor.process(
            attachment(type = "image", localPath = staged.absolutePath).copy(
                mimeType = "image/gif", pixelWidth = 9_999, pixelHeight = 9_999, fileSize = 99_999,
            ),
        ) as AttachmentImportProcessor.ProcessResult.Ready

        assertEquals(staged.absolutePath, normalizedSource)
        assertEquals(normalized.absolutePath, result.attachment.localPath)
        assertEquals(2L, result.attachment.fileSize)
        assertEquals("image/png", result.attachment.mimeType)
        assertEquals(901, result.attachment.pixelWidth)
        assertEquals(377, result.attachment.pixelHeight)
        assertEquals(listOf(measured), result.attachment.imageArtifacts)
        assertEquals(AttachmentImportState.READY, result.attachment.importState)
        assertEquals(listOf(normalized.absolutePath), result.createdPaths)
        assertEquals(listOf(staged.absolutePath), result.obsoletePaths)
    }

    @Test
    fun incompleteImageMeasurementsFailBeforeReadyAndReclaimOnlyTheNewArtifact() = runTest {
        val staged = temporaryFolder.newFile("incomplete-source.jpg")
        val complete = ImageArtifact("output.jpg", "image/jpeg", 1, 100, 200, 1)
        val incomplete = listOf(
            complete.copy(path = "wrong-output.jpg"),
            complete.copy(mimeType = "application/octet-stream"),
            complete.copy(sizeBytes = 0),
            complete.copy(width = null),
            complete.copy(height = 0),
            complete.copy(unavailable = true),
        )
        incomplete.forEachIndexed { index, facts ->
            val normalized = temporaryFolder.newFile("incomplete-output-$index.jpg")
            val descriptor = if (index == 0) facts else facts.copy(path = normalized.absolutePath)
            val result = processor(
                normalizeImage = { normalized.absolutePath },
                measureArtifact = { descriptor },
            ).process(attachment(type = "image", localPath = staged.absolutePath))

            assertTrue("Incomplete measurement $index must fail", result is AttachmentImportProcessor.ProcessResult.Failure)
            assertFalse(normalized.exists())
            assertTrue(staged.exists())
        }
    }

    @Test
    fun textProcessingCachesBoundedTextOnReadyAttachment() = runTest {
        val staged = temporaryFolder.newFile("notes.txt")
        var requestedMaxChars: Int? = null
        val processor = processor(
            readText = { source, maxChars ->
                assertEquals(staged.absolutePath, source)
                requestedMaxChars = maxChars
                "prepared text"
            },
        )

        val result = processor.process(
            attachment(type = "file", localPath = staged.absolutePath),
        ) as AttachmentImportProcessor.ProcessResult.Ready

        assertEquals(Constants.MAX_FILE_CONTENT_READ_LENGTH, requestedMaxChars)
        assertEquals("prepared text", result.attachment.preparedText)
        assertEquals(AttachmentImportState.READY, result.attachment.importState)
        assertTrue(result.createdPaths.isEmpty())
        assertTrue(result.obsoletePaths.isEmpty())
    }

    @Test
    fun videoAndPdfProcessingReportEveryCreatedArtifact() = runTest {
        val stagedVideo = temporaryFolder.newFile("video.mp4")
        val stagedPdf = temporaryFolder.newFile("document.pdf")
        val frames = listOf("frame-0", "frame-1")
        val pages = listOf("page-0", "page-1")
        var videoConfig: VideoSliceConfig? = null
        var selectedPages: Set<Int>? = null
        val processor = processor(
            extractVideoFrames = { source, config ->
                assertEquals(stagedVideo.absolutePath, source)
                videoConfig = config
                frames
            },
            renderPdf = { source, selection ->
                assertEquals(stagedPdf.absolutePath, source)
                selectedPages = selection
                pages
            },
        )

        val video = processor.process(
            attachment(
                type = "video",
                localPath = stagedVideo.absolutePath,
                frameCount = 2,
                sliceIntervalMs = 1500L,
            ),
        ) as AttachmentImportProcessor.ProcessResult.Ready
        val pdf = processor.process(
            attachment(
                type = "pdf",
                localPath = stagedPdf.absolutePath,
                selectedPages = setOf(1, 3),
            ),
        ) as AttachmentImportProcessor.ProcessResult.Ready

        assertEquals(VideoSliceConfig(intervalMicros = 1_500_000L, frameCount = 2), videoConfig)
        assertEquals(frames, video.attachment.processedFrames)
        assertEquals(frames, video.createdPaths)
        assertEquals(setOf(1, 3), selectedPages)
        assertEquals(pages, pdf.attachment.preRenderedPaths)
        assertEquals(setOf(0, 1), pdf.attachment.selectedPages)
        assertEquals(pages, pdf.createdPaths)
    }

    @Test
    fun videoAndPdfKeepEachMeasuredArtifactWithoutRepeatingTheFirstImageSize() = runTest {
        val stagedVideo = temporaryFolder.newFile("mixed-video.mp4")
        val stagedPdf = temporaryFolder.newFile("mixed-document.pdf")
        val frameA = ImageArtifact("frame-a.jpg", "image/jpeg", 20, 2048, 1152, 1)
        val frameB = ImageArtifact("frame-b.png", "image/png", 30, 911, 1611, 1)
        val pageA = ImageArtifact("page-a.jpg", "image/jpeg", 40, 1448, 2048, 1)
        val pageB = ImageArtifact("page-b.jpg", "image/jpeg", 50, 2048, 1448, 1)
        val measured = listOf(frameA, frameB, pageA, pageB).associateBy { it.path }
        val processor = processor(
            extractVideoFrames = { _, _ -> listOf(frameA.path, frameB.path) },
            renderPdf = { _, _ -> listOf(pageA.path, pageB.path) },
            measureArtifact = { measured.getValue(it) },
        )

        val video = processor.process(
            attachment(type = "video", localPath = stagedVideo.absolutePath, frameCount = 2, sliceIntervalMs = 1000),
        ) as AttachmentImportProcessor.ProcessResult.Ready
        val pdf = processor.process(
            attachment(type = "pdf", localPath = stagedPdf.absolutePath, selectedPages = setOf(0, 1)),
        ) as AttachmentImportProcessor.ProcessResult.Ready

        assertEquals(listOf(frameA, frameB), video.attachment.imageArtifacts)
        assertEquals(listOf(pageA, pageB), pdf.attachment.imageArtifacts)
        assertEquals(listOf(frameA.path, frameB.path), video.createdPaths)
        assertEquals(listOf(pageA.path, pageB.path), pdf.createdPaths)
        assertTrue(stagedVideo.exists())
        assertTrue(stagedPdf.exists())
        assertTrue(video.obsoletePaths.isEmpty())
        assertTrue(pdf.obsoletePaths.isEmpty())
    }

    @Test
    fun failedArtifactMeasurementReclaimsEveryNewFrameAndKeepsTheVideoSource() = runTest {
        val staged = temporaryFolder.newFile("failed-metadata.mp4")
        val frames = (0..1).map { temporaryFolder.newFile("failed-frame-$it.jpg").absolutePath }
        val processor = processor(
            extractVideoFrames = { _, _ -> frames },
            measureArtifact = { path ->
                if (path == frames[1]) throw java.io.IOException("Unreadable frame")
                ImageArtifact(path, "image/jpeg", 1, 100, 100, 1)
            },
        )

        val result = processor.process(
            attachment(type = "video", localPath = staged.absolutePath, frameCount = 2, sliceIntervalMs = 1000),
        )

        assertTrue(result is AttachmentImportProcessor.ProcessResult.Failure)
        assertTrue(frames.none { File(it).exists() })
        assertTrue(staged.exists())
    }

    @Test
    fun incompleteFrameMeasurementReclaimsEveryNewFrameAndKeepsTheVideoSource() = runTest {
        val staged = temporaryFolder.newFile("incomplete-metadata.mp4")
        val frames = (0..1).map { temporaryFolder.newFile("incomplete-frame-$it.jpg").absolutePath }
        val processor = processor(
            extractVideoFrames = { _, _ -> frames },
            measureArtifact = { path ->
                ImageArtifact(path, "image/jpeg", 1, 100, if (path == frames[1]) null else 200, 1)
            },
        )

        val result = processor.process(
            attachment(type = "video", localPath = staged.absolutePath, frameCount = 2, sliceIntervalMs = 1000),
        )

        assertTrue(result is AttachmentImportProcessor.ProcessResult.Failure)
        assertTrue(frames.none { File(it).exists() })
        assertTrue(staged.exists())
    }

    @Test
    fun cancelledArtifactMeasurementReclaimsEveryNewFrameAndPropagatesCancellation() = runTest {
        val staged = temporaryFolder.newFile("cancelled-metadata.mp4")
        val frames = (0..1).map { temporaryFolder.newFile("cancelled-frame-$it.jpg").absolutePath }
        val processor = processor(
            extractVideoFrames = { _, _ -> frames },
            measureArtifact = { throw CancellationException("cancelled measurement") },
        )

        val failure = runCatching {
            processor.process(
                attachment(type = "video", localPath = staged.absolutePath, frameCount = 2, sliceIntervalMs = 1000),
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(frames.none { File(it).exists() })
        assertTrue(staged.exists())
    }

    @Test
    fun pdfPreviewUsesPrivateStageReportsProgressAndKeepsProcessing() = runTest {
        val stagedPdf = temporaryFolder.newFile("preview.pdf")
        val pages = listOf("preview-0", "preview-1", "preview-2")
        val progress = mutableListOf<Pair<Int, Int>>()
        var renderedSource: String? = null
        var renderedLimit = 0
        val processor = processor(
            renderAllPdfPages = { source, maxPages, onProgress ->
                renderedSource = source
                renderedLimit = maxPages
                pages.indices.forEach { index -> onProgress?.invoke(index + 1, pages.size) }
                pages
            },
        )

        val result = processor.preparePdfPreview(
            attachment(
                type = "pdf",
                localPath = stagedPdf.absolutePath,
            ).copy(
                pageCount = pages.size,
                importState = AttachmentImportState.PROCESSING,
            ),
        ) { current, total -> progress += current to total }
            as AttachmentImportProcessor.ProcessResult.Ready

        assertEquals(stagedPdf.absolutePath, renderedSource)
        assertEquals(pages.size, renderedLimit)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
        assertEquals(AttachmentImportState.PROCESSING, result.attachment.importState)
        assertNull(result.attachment.selectedPages)
        assertEquals(pages, result.attachment.preRenderedPaths)
        assertEquals(pages, result.createdPaths)
    }

    @Test
    fun pdfPreviewRejectsNonProcessingAttachmentWithoutRendering() = runTest {
        val stagedPdf = temporaryFolder.newFile("preview-ready.pdf")
        var renderCalls = 0
        val processor = processor(
            renderAllPdfPages = { _, _, _ ->
                renderCalls += 1
                emptyList()
            },
        )

        val result = processor.preparePdfPreview(
            attachment(
                type = "pdf",
                localPath = stagedPdf.absolutePath,
            ).copy(
                pageCount = 1,
                importState = AttachmentImportState.READY,
            ),
        )

        assertTrue(result is AttachmentImportProcessor.ProcessResult.Failure)
        assertEquals(0, renderCalls)
    }

    @Test
    fun pdfPreviewRejectsPartialOutput() = runTest {
        val stagedPdf = temporaryFolder.newFile("preview-partial.pdf")
        val partialPage = temporaryFolder.newFile("preview-0.jpg")
        val processor = processor(
            renderAllPdfPages = { _, _, _ -> listOf(partialPage.absolutePath) },
        )

        val result = processor.preparePdfPreview(
            attachment(
                type = "pdf",
                localPath = stagedPdf.absolutePath,
            ).copy(
                pageCount = 2,
                importState = AttachmentImportState.PROCESSING,
            ),
        )

        assertTrue(result is AttachmentImportProcessor.ProcessResult.Failure)
        assertFalse(partialPage.exists())
    }

    @Test
    fun pdfSelectionReusesTheCompletedPreviewArtifactsWithoutRenderingAgain() = runTest {
        val source = temporaryFolder.newFile("selected-source.pdf")
        val paths = (0..2).map { temporaryFolder.newFile("preview-$it.jpg").absolutePath }
        val artifacts = paths.mapIndexed { index, path ->
            ImageArtifact(path, "image/jpeg", 1, 2048 - index * 100, 1000 + index * 100, 1)
        }
        val result = processor(
            renderPdf = { _, _ -> error("Completed previews must be reused") },
            measureArtifact = { path -> artifacts.first { it.path == path } },
        ).process(
            attachment(type = "pdf", localPath = source.absolutePath, selectedPages = setOf(0, 2),
                preRenderedPaths = paths).copy(imageArtifacts = artifacts),
        ) as AttachmentImportProcessor.ProcessResult.Ready

        assertEquals(listOf(paths[0], paths[2]), result.attachment.preRenderedPaths)
        assertEquals(listOf(artifacts[0], artifacts[2]), result.attachment.imageArtifacts)
        assertTrue(result.createdPaths.isEmpty())
    }

    @Test
    fun legacyPdfPreviewMeasuresSelectedBytesWithoutRenderingOrClaimingCurrentPolicy() = runTest {
        val source = temporaryFolder.newFile("legacy-preview-source.pdf")
        val pages = (0..2).map { index ->
            temporaryFolder.newFile("legacy-preview-$index.jpg").apply { writeText("page $index") }
        }
        val paths = pages.map { it.absolutePath }
        val measured = mutableListOf<String>()
        val processor = processor(
            renderPdf = { _, _ -> error("Legacy preview bytes must be reused") },
            measureArtifact = { path ->
                measured += path
                ImageArtifact(path, "image/jpeg", File(path).length(), 100, 200, 1)
            },
        )

        val result = processor.process(
            attachment(type = "pdf", localPath = source.absolutePath, selectedPages = setOf(2, 0),
                preRenderedPaths = paths),
        ) as AttachmentImportProcessor.ProcessResult.Ready

        assertEquals(listOf(paths[0], paths[2]), measured)
        assertEquals(measured, result.attachment.preRenderedPaths)
        assertEquals(measured, result.attachment.imageArtifacts.map { it.path })
        assertTrue(result.attachment.imageArtifacts.all { it.policyVersion == 0 })
        assertEquals(listOf("page 0", "page 1", "page 2"), pages.map { it.readText() })
        assertTrue(result.createdPaths.isEmpty())
        assertTrue(result.obsoletePaths.isEmpty())
        assertTrue(source.exists())
    }

    @Test
    fun incompleteRecordedPdfPreviewMeasuresCurrentBytesWithoutRendering() = runTest {
        val source = temporaryFolder.newFile("incomplete-preview-source.pdf")
        val page = temporaryFolder.newFile("incomplete-preview.jpg").apply { writeText("preview") }
        val recorded = ImageArtifact(page.absolutePath, "image/jpeg", page.length(), null, 200, 1)
        val result = processor(
            renderPdf = { _, _ -> error("Completed preview must not be rendered again") },
            measureArtifact = { recorded.copy(width = 100) },
        ).process(
            attachment(type = "pdf", localPath = source.absolutePath, selectedPages = setOf(0),
                preRenderedPaths = listOf(page.absolutePath)).copy(imageArtifacts = listOf(recorded)),
        ) as AttachmentImportProcessor.ProcessResult.Ready

        assertEquals(listOf(recorded.copy(width = 100)), result.attachment.imageArtifacts)
        assertEquals("preview", page.readText())
        assertTrue(source.exists())
    }

    @Test
    fun missingSelectedPdfPreviewRetainsABrokenSlotWithoutRenderingReplacementBytes() = runTest {
        val source = temporaryFolder.newFile("missing-preview-source.pdf")
        val first = temporaryFolder.newFile("available-preview.jpg")
        val missing = File(temporaryFolder.root, "missing-preview.jpg")
        val paths = listOf(first.absolutePath, missing.absolutePath)
        val artifacts = paths.map { ImageArtifact(it, "image/jpeg", 1, 100, 200, 1) }
        val result = processor(
            renderPdf = { _, _ -> error("Missing preview must stay unavailable") },
            measureArtifact = { throw java.io.IOException("Missing preview") },
        ).process(
            attachment(type = "pdf", localPath = source.absolutePath, selectedPages = setOf(1),
                preRenderedPaths = paths).copy(imageArtifacts = artifacts),
        ) as AttachmentImportProcessor.ProcessResult.Ready

        assertEquals(listOf(artifacts[1].copy(unavailable = true)), result.attachment.imageArtifacts)
        assertTrue(first.exists())
        assertTrue(source.exists())
        assertFalse(missing.exists())
    }

    @Test
    fun sandboxProcessingCopiesFromStageAndReportsPromotionPaths() = runTest {
        val staged = temporaryFolder.newFile("archive.bin").apply { writeBytes(byteArrayOf(4, 5)) }
        val sandboxHome = temporaryFolder.newFolder("sandbox-home")
        val processor = processor()

        val result = processor.process(
            attachment(
                type = "file",
                fileName = "../archive?.bin",
                localPath = staged.absolutePath,
                storage = AttachmentStorage.LOCAL_SANDBOX_PENDING,
            ),
            sandboxHomeDir = sandboxHome,
        ) as AttachmentImportProcessor.ProcessResult.Ready

        val target = File(requireNotNull(result.attachment.localPath))
        assertTrue(target.isFile)
        assertEquals(listOf(4, 5), target.readBytes().map(Byte::toInt))
        assertEquals(".._archive_.bin", result.attachment.fileName)
        assertEquals("/home/agora/attachments/id/.._archive_.bin", result.attachment.sandboxPath)
        assertEquals(listOf(target.absolutePath), result.createdPaths)
        assertEquals(listOf(staged.absolutePath), result.obsoletePaths)
    }

    private fun processor(
        normalizeImage: suspend (String) -> String? = { null },
        extractVideoFrames: suspend (String, VideoSliceConfig) -> List<String> = { _, _ -> emptyList() },
        renderPdf: suspend (String, Set<Int>?) -> List<String> = { _, _ -> emptyList() },
        renderAllPdfPages: suspend (
            String,
            Int,
            (suspend (current: Int, total: Int) -> Unit)?,
        ) -> List<String> = { _, _, _ -> emptyList() },
        readText: (String, Int) -> String? = { _, _ -> null },
        openSource: (String) -> InputStream? = { source -> File(source).inputStream() },
        readPdfPageCount: (String) -> Int = { 0 },
        readVideoDurationMs: (String) -> Long = { 0L },
        maxAttachmentBytes: Long = Long.MAX_VALUE,
        measureArtifact: suspend (String) -> ImageArtifact = { path ->
            ImageArtifact(path, "image/jpeg", File(path).length().coerceAtLeast(1L), 640, 480, 1)
        },
    ): AttachmentImportProcessor {
        val app = mockk<Application>()
        every { app.filesDir } returns temporaryFolder.root
        return AttachmentImportProcessor(
            app = app,
            normalizeImage = { source, _ -> normalizeImage(source) },
            extractVideoFrames = { source, config, _ -> extractVideoFrames(source, config) },
            renderPdf = { source, pages, _ -> renderPdf(source, pages) },
            renderAllPdfPages = { source, maxPages, progress, _ -> renderAllPdfPages(source, maxPages, progress) },
            readText = readText,
            openSource = openSource,
            readPdfPageCount = readPdfPageCount,
            readVideoDurationMs = readVideoDurationMs,
            maxAttachmentBytes = maxAttachmentBytes,
            measureArtifact = measureArtifact,
        )
    }

    private fun attachment(
        type: String,
        fileName: String? = null,
        localPath: String? = null,
        storage: AttachmentStorage = AttachmentStorage.APP_PRIVATE,
        frameCount: Int? = null,
        sliceIntervalMs: Long? = null,
        processedFrames: List<String>? = null,
        selectedPages: Set<Int>? = null,
        preRenderedPaths: List<String>? = null,
        unavailable: Boolean = false,
    ) = SelectedAttachment(
        localId = "id",
        uri = localPath ?: "content://source",
        type = type,
        fileName = fileName,
        localPath = localPath,
        storage = storage,
        frameCount = frameCount,
        sliceIntervalMs = sliceIntervalMs,
        processedFrames = processedFrames,
        selectedPages = selectedPages,
        preRenderedPaths = preRenderedPaths,
        unavailable = unavailable,
    )
}
