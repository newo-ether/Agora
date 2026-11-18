package com.newoether.agora.webui

import android.app.Application
import android.graphics.BitmapFactory
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.SelectedAttachment
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebUiImageArtifactTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun attachmentPreviewUsesEachCompletedPageAndFrameMime() {
        val first = ImageArtifact("/owned/first.jpg", "image/png", 20, 31, 47)
        val second = ImageArtifact("/owned/second.png", "image/jpeg", 30, 73, 29)
        val pdf = SelectedAttachment(uri = "source", type = "pdf", preRenderedPaths = listOf(first.path, second.path),
            imageArtifacts = listOf(first, second), importState = AttachmentImportState.PROCESSING)
        assertEquals(first.path to "image/png", attachmentPreviewArtifact(pdf, "page", 0))
        assertEquals(second.path to "image/jpeg", attachmentPreviewArtifact(pdf, "page", 1))
        assertNull(attachmentPreviewArtifact(pdf, "page", 2))
        assertEquals(first.path to "image/*", attachmentPreviewArtifact(pdf.copy(imageArtifacts = emptyList()), "page", 0))
        assertNull(attachmentPreviewArtifact(pdf.copy(imageArtifacts = listOf(first.copy(unavailable = true))), "page", 0))
        val video = pdf.copy(type = "video", processedFrames = pdf.preRenderedPaths, mimeType = "video/mp4")
        assertEquals(second.path to "image/jpeg", attachmentPreviewArtifact(video, "frame", 1))
        val image = pdf.copy(type = "image", localPath = first.path, importState = AttachmentImportState.READY)
        assertEquals(first.path to "image/png", attachmentPreviewArtifact(image, "source", 0))
        assertNull(attachmentPreviewArtifact(image.copy(importState = AttachmentImportState.PROCESSING), "source", 0))
        assertEquals(first.path to "image/*", attachmentPreviewArtifact(image.copy(imageArtifacts = emptyList()), "source", 0))
    }

    @Test
    fun mixedPdfPagesAndVideoFramesIgnoreOnlyTheAggregateUnavailableFlag() = runTest {
        val root = temporary.newFolder("mixed-private")
        val first = png(File(root, "pdf_00000000-0000-0000-0000-000000000004.png"))
        val second = png(File(root, "pdf_00000000-0000-0000-0000-000000000005.png"))
        val artifacts = listOf(first.copy(unavailable = true), second, first)
        val paths = artifacts.map(ImageArtifact::path)
        for ((type, kind) in listOf("pdf" to "page", "video" to "frame")) {
            val selected = SelectedAttachment(uri = "source", type = type, unavailable = true,
                preRenderedPaths = paths, processedFrames = paths, imageArtifacts = artifacts)
            assertNull(attachmentPreviewArtifact(selected, kind, 0))
            assertEquals(second.path to second.mimeType, attachmentPreviewArtifact(selected, kind, 1))
            assertEquals(first.path to first.mimeType, attachmentPreviewArtifact(selected, kind, 2))
            val item = AttachmentItem(type = type, imageIndex = 0, pageCount = 3,
                unavailable = true, imageArtifacts = artifacts)
            val message = ChatMessage(id = "m", text = "", participant = Participant.USER, images = paths,
                imageArtifacts = mapOf(first.path to artifacts.first()), attachmentMeta = AttachmentMeta(listOf(item)))
            val resolver = WebUiToolImages(root, root) { _, _ -> message }
            assertEquals(listOf(true, false, false), message.toWeb(null).attachments.map {
                it["unavailable"] == kotlinx.serialization.json.JsonPrimitive(true)
            })
            assertFalse(resolver.consumeAttachment("c", "m", 0) { _, _ -> fail("Rejected occurrence") })
            for (index in listOf(1, 2)) assertTrue(resolver.consumeAttachment("c", "m", index) { file, mime ->
                assertEquals("image/png", mime)
                assertArrayEquals(File(paths[index]).readBytes(), file.readBytes())
            })
        }
        val selectedImage = SelectedAttachment(uri = "source", type = "image", unavailable = true,
            localPath = second.path, imageArtifacts = listOf(second))
        assertNull(attachmentPreviewArtifact(selectedImage, "source", 0))
        val message = ChatMessage(id = "m", text = "", participant = Participant.USER, images = listOf(second.path),
            attachmentMeta = AttachmentMeta(listOf(AttachmentItem(type = "image", imageIndex = 0,
                unavailable = true, imageArtifacts = listOf(second)))))
        assertFalse(WebUiToolImages(root, root) { _, _ -> message }.consumeAttachment("c", "m", 0) { _, _ ->
            fail("Unavailable single image")
        })
    }

    @Test
    fun persistedPdfVideoAndRestoredToolImagesReturnTheirExactRasterBytes() = runTest {
        val root = temporary.newFolder("private")
        val toolRoot = File(root, "tool-media")
        val restored = File(root, "images").apply { mkdir() }
        val forked = File(root, "fork-attachments").apply { mkdir() }
        val fixtures = listOf(
            png(File(root, "pdf_00000000-0000-0000-0000-000000000001.png")),
            png(File(root, "vid_00000000-0000-0000-0000-000000000002.png")),
            png(File(restored, "restore.jpg")),
            png(File(forked, "fork.png")),
        )
        for ((index, artifact) in fixtures.withIndex()) {
            val file = File(artifact.path)
            val original = file.readBytes()
            val message = ChatMessage(id = "m", text = "", participant = Participant.USER,
                images = listOf(artifact.path), imageArtifacts = mapOf(artifact.path to artifact),
                attachmentMeta = AttachmentMeta(listOf(AttachmentItem(
                    type = if (index == 1) "video" else "pdf", imageIndex = 0, pageCount = 1,
                    mimeType = if (index == 1) "video/mp4" else "application/pdf", imageArtifacts = listOf(artifact),
                ))),
                segments = listOf(MessageSegment(type = "tool", toolImages = listOf(artifact))))
            val resolver = WebUiToolImages(toolRoot, root) { _, _ -> message }
            assertTrue(resolver.consumeAttachment("c", "m", 0) { actual, mime ->
                assertEquals("image/png", mime)
                assertArrayEquals(original, actual.readBytes())
            })
            val tool = resolver.open("c", "m", 0, 0)
            assertNotNull(tool)
            assertArrayEquals(original, tool!!.file.readBytes())
            assertEquals("image/png", tool.mimeType)
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test
    fun currentBytesAreReadableAcrossReplacementAndStaleHistoricalMetadata() = runTest {
        val root = temporary.newFolder("private")
        val file = File(File(root, "images").apply { mkdir() }, "photo.png")
        val (original, replacement) = webUiSameLengthPngs()
        assertEquals(original.size, replacement.size)
        assertFalse(original.contentEquals(replacement))
        for (policy in listOf(0, 1)) {
            file.writeBytes(original)
            val artifact = ImageArtifact(file.absolutePath, "image/svg+xml", 0, 2, 3,
                policyVersion = policy)
            val message = ChatMessage(id = "m", text = "", participant = Participant.USER,
                images = listOf(artifact.path), imageArtifacts = mapOf(artifact.path to artifact),
                segments = listOf(MessageSegment(type = "tool", toolImages = listOf(artifact))))
            val resolver = WebUiToolImages(File(root, "tool-media"), root) { _, _ -> message }
            val originalPixel = ImageIO.read(file).getRGB(0, 0)
            assertTrue(verifyWebUiImageArtifact(file, artifact))
            assertTrue(resolver.consumeAttachment("c", "m", 0) { actual, _ ->
                assertArrayEquals(original, actual.readBytes())
            })
            assertNotNull(resolver.open("c", "m", 0, 0))
            file.writeBytes(replacement)
            assertEquals(original.size.toLong(), file.length())
            assertFalse(originalPixel == ImageIO.read(file).getRGB(0, 0))
            assertTrue(verifyWebUiImageArtifact(file, artifact))
            assertTrue(resolver.consumeAttachment("c", "m", 0) { actual, mime ->
                assertEquals("image/png", mime)
                assertArrayEquals(replacement, actual.readBytes())
            })
            val opened = requireNotNull(resolver.open("c", "m", 0, 0))
            assertEquals(file.length(), opened.size)
            assertEquals("image/png", opened.mimeType)
            assertArrayEquals(replacement, opened.file.readBytes())
        }
    }

    @Test
    fun metadataFreeLegacyAttachmentIsInspectedWithoutReencoding() = runTest {
        val root = temporary.newFolder("private")
        val file = File(File(root, "images").apply { mkdir() }, "legacy.png")
        png(file)
        val message = ChatMessage(id = "m", text = "", participant = Participant.USER,
            images = listOf(file.absolutePath))
        val resolver = WebUiToolImages(File(root, "tool-media"), root) { _, _ -> message }
        assertTrue(resolver.consumeAttachment("c", "m", 0) { actual, mime ->
            assertEquals("image/png", mime)
            assertArrayEquals(file.readBytes(), actual.readBytes())
        })
        assertTrue(file.isFile)
    }

    @Test
    fun missingOrUnownedArtifactsAreNotServed() = runTest {
        val root = temporary.newFolder("private")
        val toolRoot = File(root, "tool-media").apply { mkdir() }
        val artifact = png(temporary.newFile("outside.png"))
        val message = ChatMessage(id = "m", text = "", participant = Participant.USER,
            images = listOf(artifact.path), imageArtifacts = mapOf(artifact.path to artifact),
            segments = listOf(MessageSegment(type = "tool", toolImages = listOf(artifact))))
        val resolver = WebUiToolImages(toolRoot, root) { _, _ -> message }
        assertFalse(resolver.consumeAttachment("c", "m", 0) { _, _ -> fail("Unowned path") })
        assertNull(resolver.open("c", "m", 0, 0))
        val owned = png(File(root, "img_00000000-0000-0000-0000-000000000003.png"))
        val missing = owned.copy(unavailable = true)
        val unavailable = WebUiToolImages(toolRoot, root) { _, _ -> message.copy(
            images = listOf(missing.path), imageArtifacts = mapOf(missing.path to missing),
            segments = listOf(MessageSegment(type = "tool", toolImages = listOf(missing)))) }
        assertFalse(unavailable.consumeAttachment("c", "m", 0) { _, _ -> fail("Unavailable artifact") })
        assertNull(unavailable.open("c", "m", 0, 0))
    }

    @Test
    fun corruptMissingAndCancelledReadsDoNotPublishImages() = runTest {
        val file = temporary.newFile("corrupt.png").apply { writeText("broken image") }
        val artifact = ImageArtifact(file.path, "image/png", file.length(), 31, 47)
        assertNull(inspectWebUiImageArtifact(file, artifact))
        file.writeBytes(webUiSameLengthPngs().first)
        val measured = requireNotNull(inspectWebUiImageArtifact(file, artifact))
        assertEquals(31, measured.width)
        assertEquals(47, measured.height)
        assertEquals("image/png", measured.mimeType)
        val decoded = requireNotNull(file.inputStream().use { BitmapFactory.decodeStream(it) })
        assertEquals(measured.width, decoded.width)
        decoded.recycle()
        val read = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            inspectWebUiImageArtifact(file, artifact)
        }
        read.cancel()
        try { read.await(); fail("Cancelled image read") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue("Image fixture must be deleted before checking the missing-file path", file.delete())
        assertNull(inspectWebUiImageArtifact(file, artifact))
    }

    @Test
    fun sandboxOriginalAndDerivedPreviewsRequireOwnedMetadataAndPrivateRoot() = runTest {
        val root = temporary.newFolder("sandbox-private")
        val original = png(File(File(root, "sandbox-home/attachments/id").apply { mkdirs() }, "original.png"))
        val page = png(File(root, "pdf_00000000-0000-0000-0000-000000000009.png"))
        for ((type, artifact) in listOf("image" to original, "pdf" to page, "video" to page)) {
            val selected = SelectedAttachment(uri = "source", type = type, localPath = original.path,
                preRenderedPaths = listOf(artifact.path), processedFrames = listOf(artifact.path),
                imageArtifacts = listOf(artifact), storage = com.newoether.agora.model.AttachmentStorage.LOCAL_SANDBOX_PENDING)
            val kind = when (type) { "pdf" -> "page"; "video" -> "frame"; else -> "source" }
            assertEquals(artifact.path to "image/png", attachmentPreviewArtifact(selected, kind, 0))
            val item = AttachmentItem(type = type, imageIndex = 0, pageCount = 1,
                storage = com.newoether.agora.model.AttachmentStorage.LOCAL_SANDBOX_RUNTIME,
                sandboxPath = "/home/agora/attachments/id/original.png", imageArtifacts = listOf(artifact))
            val message = ChatMessage(id = "m", text = "", participant = Participant.USER,
                images = listOf(artifact.path), attachmentMeta = AttachmentMeta(listOf(item)))
            assertTrue(WebUiToolImages(root, root) { _, _ -> message }.consumeAttachment("c", "m", 0) { actual, mime ->
                assertEquals("image/png", mime)
                assertArrayEquals(File(artifact.path).readBytes(), actual.readBytes())
            })
        }
        val untagged = ChatMessage(id = "m", text = "", participant = Participant.USER, images = listOf(original.path))
        assertFalse(WebUiToolImages(root, root) { _, _ -> untagged }.consumeAttachment("c", "m", 0) { _, _ ->
            fail("Sandbox root requires explicit attachment ownership")
        })
        val outside = png(temporary.newFile("sandbox-outside.png"))
        val escaped = untagged.copy(images = listOf(outside.path), attachmentMeta = AttachmentMeta(listOf(
            AttachmentItem(type = "image", imageIndex = 0,
                storage = com.newoether.agora.model.AttachmentStorage.LOCAL_SANDBOX_RUNTIME,
                imageArtifacts = listOf(outside)))))
        assertFalse(WebUiToolImages(root, root) { _, _ -> escaped }.consumeAttachment("c", "m", 0) { _, _ ->
            fail("Sandbox storage must not admit an outside file")
        })
    }

    @Test
    fun resolvedSandboxImagePreviewSurvivesOriginalMutationAndDeletionThroughHydrationAndWebReads() = runTest {
        val root = temporary.newFolder("snapshot-private")
        val home = File(root, "sandbox-home").apply { mkdirs() }
        val source = png(File(root, "source.webp"))
        val bytes = File(source.path).readBytes()
        val app = io.mockk.mockk<Application>()
        io.mockk.every { app.filesDir } returns root
        io.mockk.every { app.applicationContext } returns app
        val processor = com.newoether.agora.viewmodel.AttachmentImportProcessor(app = app,
            measureArtifact = { com.newoether.agora.util.ImageArtifactProcessor(app).inspect(it) })
        val ready = processor.process(SelectedAttachment(localId = "id", uri = source.path,
            type = "image", fileName = "source.webp", localPath = source.path, fileSize = bytes.size.toLong(),
            storage = com.newoether.agora.model.AttachmentStorage.LOCAL_SANDBOX_PENDING), home)
            as com.newoether.agora.viewmodel.AttachmentImportProcessor.ProcessResult.Ready
        val original = File(ready.attachment.localPath!!)
        val snapshot = ready.attachment.imageArtifacts.single()
        assertNotEquals(original.path, snapshot.path)
        assertEquals("img", File(snapshot.path).extension)
        assertEquals("image/png", snapshot.mimeType)
        for (delete in listOf(false, true)) {
            if (delete) assertTrue(original.delete()) else original.writeText("tool edit")
            val hydrated = processor.hydrateDraftAttachment(ready.attachment)
            assertFalse(hydrated.unavailable)
            assertEquals(original.path, hydrated.localPath)
            val preview = requireNotNull(attachmentPreviewArtifact(hydrated, "source", 0))
            assertEquals(snapshot.path, preview.first)
            assertArrayEquals(bytes, File(preview.first).readBytes())
            val payload = com.newoether.agora.viewmodel.MessagePayloadBuilder().buildComposerPayload(listOf(hydrated))
            val message = ChatMessage(id = "m", text = "", participant = Participant.USER,
                images = payload.allImages, attachmentMeta = payload.attachmentMeta)
            assertEquals("file://${original.path}", message.attachmentMeta!!.items.single().originalUri)
            assertTrue(WebUiToolImages(root, root) { _, _ -> message }.consumeAttachment("c", "m", 0) { file, mime ->
                assertEquals("image/png", mime)
                assertArrayEquals(bytes, file.readBytes())
            })
        }
        val video = ready.attachment.copy(type = "video", mimeType = "video/mp4")
        assertNull(attachmentPreviewArtifact(video, "source", 0))
    }

    @Test
    fun sentTextProjectionIncludesSavedSnapshotWithoutFilesystemPath() {
        val item = AttachmentItem(type = "file", fileName = "notes.txt", textContent = "saved preview",
            storage = com.newoether.agora.model.AttachmentStorage.LOCAL_SANDBOX_RUNTIME,
            sandboxPath = "/home/agora/attachments/id/notes.txt")
        val wire = ChatMessage(id = "text", text = "", participant = Participant.USER,
            attachmentMeta = AttachmentMeta(listOf(item))).toWeb(null)
        val attachment = wire.attachments.single()
        assertEquals(kotlinx.serialization.json.JsonPrimitive("saved preview"), attachment["text"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive("file"), attachment["type"])
        assertFalse(attachment.toString().contains("/home/agora"))
    }

    @Test
    fun sandboxVideoSkipsSlicingAndPublishesOnlyTheOriginalFile() = runBlocking {
        val root = temporary.newFolder("video-session-private")
        val home = File(root, "sandbox-home").apply { mkdirs() }
        val frame = png(File(root, "vid_configuration.png"))
        val bytes = "private staged video".toByteArray()
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val settings = webUiChatSessionSettings().apply {
            every { sandboxEnabled } returns MutableStateFlow(true)
        }
        val conversations = mockk<com.newoether.agora.data.repository.ConversationRepository>(relaxed = true) {
            every { observeConversation(any()) } returns flowOf(null)
            every { observeMessageTopology(any()) } returns flowOf(emptyList())
            coEvery { getConversation(any()) } returns null
            coEvery { recoverConversationRuntime(any(), any()) } returns 0
        }.apply { stubOpenRecovery() }
        val state = mockk<com.newoether.agora.viewmodel.ConversationGenerationState> {
            every { generationSnapshot } returns MutableStateFlow(com.newoether.agora.viewmodel.ConversationGenerationSnapshot())
            every { generating } returns MutableStateFlow(false)
            every { stopping } returns MutableStateFlow(false)
            every { queuedSends } returns MutableStateFlow(emptyList())
            every { streamingMessage } returns MutableStateFlow(null)
        }
        val app = mockk<Application> { every { filesDir } returns root }
        val processor = com.newoether.agora.viewmodel.AttachmentImportProcessor(app,
            readVideoDurationMs = { 15_000L }, measureArtifact = { frame.copy(path = it) },
            extractVideoFrames = { _, _, owner ->
                com.newoether.agora.util.AttachmentFiles.retainLivePath(requireNotNull(owner), frame.path)
                listOf(frame.path)
            })
        val session = WebUiChatSession(
            generation = mockk(relaxed = true), generationStop = mockk(relaxed = true),
            clients = com.newoether.agora.viewmodel.ChatClients(), conversations = conversations,
            registry = mockk { every { getOrCreate(any()) } returns state },
            executionCoordinator = com.newoether.agora.automation.ConversationExecutionCoordinator(),
            settings = settings, transfers = mockk(relaxed = true), attachmentProcessor = processor,
            scope = scope, uploadDirectory = root, compactFailureMessage = { it.reason.name },
            allowLocalSandbox = { true }, sandboxHomeDir = { home },
        )
        try {
            session.start()
            assertEquals(io.ktor.http.HttpStatusCode.Accepted, session.upload(0, "clip.mp4", "video/mp4", "video",
                bytes.size.toLong(), io.ktor.utils.io.ByteReadChannel(bytes)))
            val ready = withTimeout(5_000) {
                session.composerState.first {
                    it.attachments.singleOrNull()?.importState == AttachmentImportState.READY
                }
            }.attachments.single()
            assertEquals(com.newoether.agora.model.AttachmentStorage.LOCAL_SANDBOX_PENDING, ready.storage)
            assertEquals(15_000L, ready.videoDurationMs)
            assertNull(ready.frameCount)
            assertTrue(ready.processedFrames.isNullOrEmpty())
            val original = File(ready.localPath!!)
            assertTrue(original.canonicalPath.startsWith(home.canonicalPath))
            assertArrayEquals(bytes, original.readBytes())
            original.writeText("edited sandbox video")
            assertTrue(original.delete())
            assertFalse(session.previewAttachment(0, ready.localId, "source", 0) { _, _ -> fail("READY source") })
            assertFalse(session.previewAttachment(0, ready.localId, "frame", 0) { _, _ -> fail("No frame snapshot") })
        } finally {
            session.close()
            scope.cancel()
        }
    }

    private fun png(file: File): ImageArtifact {
        check(ImageIO.write(BufferedImage(31, 47, BufferedImage.TYPE_INT_ARGB), "png", file))
        return ImageArtifact(file.absolutePath, "image/png", file.length(), 31, 47)
    }
}

internal fun webUiSameLengthPngs(): Pair<ByteArray, ByteArray> {
    fun encode(pixel: Int): ByteArray = ByteArrayOutputStream().use { output ->
        val image = BufferedImage(31, 47, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, pixel) }
        check(ImageIO.write(image, "png", output))
        output.toByteArray()
    }
    val original = encode(0xffff0000.toInt())
    val replacement = encode(0xff0000ff.toInt())
    val size = maxOf(original.size, replacement.size)
    return original.copyOf(size) to replacement.copyOf(size)
}
