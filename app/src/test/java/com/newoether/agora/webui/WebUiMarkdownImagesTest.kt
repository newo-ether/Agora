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

    @Test fun repeatedDestinationsAndFragmentsResolveTheSamePersistentOccurrence() {
        val first = "![first]($link)\n\n"
        val source = message(first + "![second]($link)")
        assertEquals("answer:0", source.shellMarkdownImageOccurrence(link, 0, 0, "answer"))
        assertEquals("answer:1", source.shellMarkdownImageOccurrence(link, 0, 1, "answer"))
        assertEquals("answer:1", source.shellMarkdownImageOccurrence(link, first.length, 0, "answer"))
        assertNull(source.shellMarkdownImageOccurrence(link, 0, 2, "answer"))
        assertNull(source.shellMarkdownImageOccurrence(link, -1, 0, "answer"))
        val thought = source.copy(segments = listOf(MessageSegment(type = "thought", content = first)))
        assertEquals("segment:0:0", thought.shellMarkdownImageOccurrence(link, 0, 0, "segment:0"))
        assertNull(thought.shellMarkdownImageOccurrence(link, 0, 0, "segment:1"))
        assertNull(thought.shellMarkdownImageOccurrence(link, 0, 0, "invalid"))
    }

    @Test fun fragmentOffsetsRetainReferencesDefinedLaterInTheFullDocument() {
        val first = "![first][r]\n\n"
        val source = first + "![second]($link)\n\n[r]: $link"
        assertEquals(1, com.newoether.agora.ui.chat.message.shellMarkdownImageCount(source, first.length))
        assertEquals("answer:1", message(source).shellMarkdownImageOccurrence(link, first.length, 0, "answer"))
    }

    @Test fun mixedHttpAndShellImagesSkipInvalidDestinationsAndKeepExactOwnership() {
        val url = "https://example.test/same.png"
        val prefix = "![missing](shell://Quantum)\n\n![escape](shell://Quantum/F:/%ZZ.png)\n\n" +
            "![utf8](shell://Quantum/F:/%FF.png)\n\n![empty](shell://Quantum/%20)\n\n"
        val first = "![shell]($link)\n\n"
        val source = message(prefix + first + "![web]($url)\n\n![again]($url)")
        assertEquals("answer:0", source.shellMarkdownImageOccurrence(link, 0, 0, "answer"))
        assertEquals("answer:1", source.shellMarkdownImageOccurrence(url, 0, 1, "answer"))
        assertEquals("answer:2", source.shellMarkdownImageOccurrence(url, 0, 2, "answer"))
        assertEquals("answer:1", source.shellMarkdownImageOccurrence(url, prefix.length + first.length, 0, "answer"))
        assertEquals(1, com.newoether.agora.ui.chat.message.shellMarkdownImageCount(source.text, prefix.length + first.length))
        assertNull(source.shellMarkdownImageOccurrence(url, 0, 0, "answer"))
        assertNull(source.shellMarkdownImageOccurrence("https://example.test/other.png", 0, 1, "answer"))
        assertFalse(source.ownsShellMarkdownImage(url))
        for (text in listOf(url, "[link]($url)", "`![code]($url)`", "<img src=\"$url\">")) {
            assertNull(message(text).shellMarkdownImageOccurrence(url, 0, 0, "answer"))
        }
    }

    @Test fun httpReferenceNestedAndTableImagesUseTheSameNativeOrdinalSet() {
        val source = "`![code](https://example.test/ignored.png)`\n\n" +
            "[link](https://example.test/ignored.png)\n\n![unsupported](ftp://example.test/a.png)\n\n" +
            "- ![nested](http://example.test/a.png)\n\n![ref][r]\n\n" +
            "| image |\n| --- |\n| ![table](shell://Quantum/F:/b.png) |\n\n[r]: https://example.test/ref.png"
        val parsedMessage = message(source)
        for ((ordinal, destination) in listOf("http://example.test/a.png", "https://example.test/ref.png",
            "shell://Quantum/F:/b.png").withIndex()) {
            assertEquals("answer:$ordinal", parsedMessage.shellMarkdownImageOccurrence(destination, 0, ordinal, "answer"))
        }
        assertEquals(3, com.newoether.agora.ui.chat.message.shellMarkdownImageCount(source))
        val fragment = "![web][r]\n\n"
        val references = message(fragment + "![again][r]\n\n[r]: https://example.test/ref.png")
        assertEquals("answer:1", references.shellMarkdownImageOccurrence("https://example.test/ref.png", fragment.length, 0, "answer"))
    }

    @Test fun detailIdentityUsesMergedNonAnswerIndicesLikeBothPresenters() {
        val source = message("").copy(segments = listOf(
            MessageSegment(type = "answer", content = "![answer]($link)"),
            MessageSegment(type = "thought", content = "![first]($link)\n\n"),
            MessageSegment(type = "thought", content = "![second]($link)"),
            MessageSegment(type = "tool", content = "![not-an-image]($link)"),
            MessageSegment(type = "thought", content = "![third]($link)"),
        ))
        assertEquals("segment:0:0", source.shellMarkdownImageOccurrence(link, 0, 0, "segment:0"))
        assertEquals("segment:0:1", source.shellMarkdownImageOccurrence(link, 0, 1, "segment:0"))
        assertNull(source.shellMarkdownImageOccurrence(link, 0, 0, "segment:1"))
        assertEquals("segment:2:0", source.shellMarkdownImageOccurrence(link, 0, 0, "segment:2"))
    }

    @Test fun exactOwnerAndSourceAreCheckedBeforeTheDeviceLoaderAndRasterBytesAreInspected() = runTest {
        val bytes = webUiSameLengthPngs().first
        val file = temporary.newFile("image.png").apply { writeBytes(bytes) }
        var loaded = 0
        var source = message("![image]($link)")
        val images = WebUiToolImages(temporary.root, markdownImage = { _, _ ->
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
        assertNull(WebUiToolImages(temporary.root, markdownImage = { _, _ -> null }) { _, _ -> message("![x]($link)") }
            .openMarkdown("c", "m", link))
        val images = WebUiToolImages(temporary.root, markdownImage = { _, _ -> throw CancellationException("cancel") }) {
            _, _ -> message("![x]($link)")
        }
        assertTrue(runCatching { images.openMarkdown("c", "m", link) }.exceptionOrNull() is CancellationException)
    }
}
