package com.newoether.agora.webui

import android.app.Application
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.tool.ToolImageStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
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
class WebUiToolImagesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = webUiSameLengthPngs().first
    private fun image(file: File) = ToolImageAttachment(file.absolutePath, "image/png", bytes.size.toLong())
    private fun message(image: ToolImageAttachment) = ChatMessage(
        id = "m", text = "", participant = Participant.MODEL, status = MessageStatus.SUCCESS,
        segments = listOf(
            MessageSegment(type = "thought", content = "first"),
            MessageSegment(type = "thought", content = "second"),
            MessageSegment(type = "answer", content = "answer"),
            MessageSegment(type = "tool", toolName = "view_image", toolResult = "{}", toolImages = listOf(image)),
        ),
    )

    @Test fun resolvesOnlyExactOwnerMessageAndDisplayedToolImageIndex() = runTest {
        val root = temporary.newFolder("tool-media")
        val file = File(root, "image.png").apply { writeBytes(bytes) }
        var loads = 0
        val resolver = WebUiToolImages(root) { conversation, id ->
            loads++
            if (conversation == "c" && id == "m") message(image(file)) else null
        }
        val opened = resolver.open("c", "m", 1, 0)!!
        opened.file.inputStream().use { assertArrayEquals(bytes, it.readBytes()) }
        assertEquals("image/png", opened.mimeType)
        assertNull(resolver.open("other", "m", 1, 0))
        assertNull(resolver.open("c", "other", 1, 0))
        assertNull(resolver.open("c", "m", 0, 0))
        assertNull(resolver.open("c", "m", 1, 1))
        assertNull(resolver.open("c", "m", -1, 0))
        assertEquals(5, loads)
        file.delete()
        assertNull(resolver.open("c", "m", 1, 0))
    }

    @Test fun rejectsPrivatePathEscapesCorruptBytesAndActualOversizedFiles() = runTest {
        val root = temporary.newFolder("tool-media")
        val inside = File(root, "image.png").apply { writeBytes(bytes) }
        val outside = temporary.newFile("outside.png").apply { writeBytes(bytes) }
        val sibling = temporary.newFolder("tool-media-other")
        val siblingImage = File(sibling, "image.png").apply { writeBytes(bytes) }
        for (attachment in listOf(image(outside), image(siblingImage), image(File(root, "../outside.png")))) {
            assertNull(WebUiToolImages(root) { _, _ -> message(attachment) }.open("c", "m", 1, 0))
        }
        assertNull(WebUiToolImages(root) { _, _ -> message(image(inside)).copy(id = "wrong") }.open("c", "m", 1, 0))
        inside.writeText("not an image")
        assertNull(WebUiToolImages(root) { _, _ -> message(image(inside)) }.open("c", "m", 1, 0))
        RandomAccessFile(inside, "rw").use { it.setLength(ToolImageStore.MAX_IMAGE_BYTES + 1) }
        assertNull(WebUiToolImages(root) { _, _ -> message(image(inside)) }.open("c", "m", 1, 0))
    }

    @Test fun readableImagesIgnoreHistoricalMimeDimensionsAndSize() = runTest {
        val root = temporary.newFolder("tool-media")
        val inside = File(root, "image.png").apply { writeBytes(bytes) }
        for (size in listOf(0L, 5L, ToolImageStore.MAX_IMAGE_BYTES + 1)) {
            val attachment = image(inside).copy(mimeType = "image/svg+xml", sizeBytes = size, width = 2, height = 3)
            val opened = requireNotNull(WebUiToolImages(root) { _, _ -> message(attachment) }.open("c", "m", 1, 0))
            assertEquals("image/png", opened.mimeType)
            assertEquals(bytes.size.toLong(), opened.size)
            assertArrayEquals(bytes, opened.file.readBytes())
        }
    }

    @Test fun symbolicLinkCannotEscapeThePrivateRoot() = runTest {
        val root = temporary.newFolder("tool-media")
        val outside = temporary.newFile("outside.png").apply { writeBytes(bytes) }
        val link = File(root, "link.png")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertNull(WebUiToolImages(root) { _, _ -> message(image(link)) }.open("c", "m", 1, 0))
    }
}
