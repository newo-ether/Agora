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
import kotlinx.coroutines.CancellationException
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
class WebUiMarkdownImagesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val link = "shell://Local%20Sandbox/home/a%20b.png"
    private fun message(source: String) = ChatMessage(id = "m", text = source,
        participant = Participant.MODEL, status = MessageStatus.SUCCESS)

    @Test fun onlyParsedImageDestinationsGrantReadsIncludingReferenceAndSegmentForms() {
        for (source in listOf("![image]($link)", "![image][ref]\n\n[ref]: $link",
            "![ref]\n\n[ref]: <$link>", "![image](<$link>)")) {
            assertTrue(source, message(source).ownsShellMarkdownImage(link))
        }
        for (source in listOf(link, "[ordinary]($link)", "`![image]($link)`",
            "```md\n![image]($link)\n```", "    ![image]($link)", "<img src=\"$link\">",
            "<!-- ![image]($link) -->", "![image](shell://Local%20Sandbox/home/other.png)")) {
            assertFalse(source, message(source).ownsShellMarkdownImage(link))
        }
        assertTrue(message("").copy(segments = listOf(MessageSegment(type = "answer", content = "![x]($link)")))
            .ownsShellMarkdownImage(link))
        assertTrue(message("").copy(segments = listOf(MessageSegment(type = "thought", content = "![x]($link)")))
            .ownsShellMarkdownImage(link))
        assertFalse(message("").copy(segments = listOf(MessageSegment(type = "tool", content = "![x]($link)")))
            .ownsShellMarkdownImage(link))
        val windows = "shell://Quantum/F:/work/a%20b.png"
        assertTrue(message("![x]($windows)").ownsShellMarkdownImage(windows))
        assertFalse(message("![x]($link)").ownsShellMarkdownImage("/home/a b.png"))
        assertFalse(message("![x]($link)").ownsShellMarkdownImage("shell://Local%20Sandbox/home/%ZZ.png"))
    }

    @Test fun exactOwnerAndSourceAreCheckedBeforeTheDeviceLoaderAndRasterBytesAreInspected() = runTest {
        val bytes = webUiSameLengthPngs().first
        val file = temporary.newFile("image.png").apply { writeBytes(bytes) }
        var loaded = 0
        var source = message("![image]($link)")
        val images = WebUiToolImages(temporary.root, markdownImage = {
            loaded++
            ToolImageAttachment(file.path, "image/svg+xml", 0)
        }) { conversation, id -> if (conversation == "c" && id == "m") source else null }
        assertNull(images.openMarkdown("other", "m", link))
        assertNull(images.openMarkdown("c", "wrong", link))
        assertNull(images.openMarkdown("c", "m", "shell://Quantum/etc/private.png"))
        assertNull(images.openMarkdown("c", "m", "x".repeat(8_193)))
        source = source.copy(id = "wrong")
        assertNull(images.openMarkdown("c", "m", link))
        assertEquals(0, loaded)
        source = message("![image]($link)")
        val image = requireNotNull(images.openMarkdown("c", "m", link))
        assertEquals("image/png", image.mimeType)
        assertEquals(bytes.size.toLong(), image.size)
        assertArrayEquals(bytes, image.file.readBytes())
        file.writeText("corrupt raster")
        assertNull(images.openMarkdown("c", "m", link))
        RandomAccessFile(file, "rw").use { it.setLength(ToolImageStore.MAX_IMAGE_BYTES + 1) }
        assertNull(images.openMarkdown("c", "m", link))
        file.delete()
        assertNull(images.openMarkdown("c", "m", link))
    }

    @Test fun unavailableLoadsFailClosedAndCancellationStillPropagates() = runTest {
        assertNull(WebUiToolImages(temporary.root, markdownImage = { null }) { _, _ -> message("![x]($link)") }
            .openMarkdown("c", "m", link))
        val images = WebUiToolImages(temporary.root, markdownImage = { throw CancellationException("cancel") }) {
            _, _ -> message("![x]($link)")
        }
        assertTrue(runCatching { images.openMarkdown("c", "m", link) }.exceptionOrNull() is CancellationException)
    }
}
