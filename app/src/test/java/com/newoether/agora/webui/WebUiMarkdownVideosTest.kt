package com.newoether.agora.webui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebUiMarkdownVideosTest {
    private val link = "shell://Local%20Sandbox/home/agora/clip.mp4"
    private fun message(source: String) = ChatMessage(id = "m", text = source,
        participant = Participant.MODEL, status = MessageStatus.SUCCESS)

    @Test fun onlyParsedVideoDestinationsGrantReads() {
        val owned = listOf(
            "![clip]($link)" to link,
            "![clip][ref]\n\n[ref]: $link" to link,
            "![clip](<$link>)" to link,
            "![](shell://Local%20Sandbox/home/%E7%89%87.mp4)" to "shell://Local%20Sandbox/home/%E7%89%87.mp4",
            // The native renderer turns these into the same card (embedStandaloneVideoPaths/embedHtmlVideoTags).
            "intro\n$link" to link,
            "[ordinary]($link)" to link,
            "<video src=\"$link\"></video>" to link,
        )
        for ((source, destination) in owned) {
            assertTrue(source, message(source).ownsMarkdownVideo(destination, 0, "answer"))
        }
        for (source in listOf("see $link here", "[ordinary]($link) and more", "`![clip]($link)`",
            "```md\n![clip]($link)\n```", "    ![clip]($link)", "```\n<video src=\"$link\"></video>\n```",
            "![clip](shell://Local%20Sandbox/home/other.png)")) {
            assertFalse(source, message(source).ownsMarkdownVideo(link, 0, "answer"))
        }
        val thought = message("").copy(segments = listOf(MessageSegment(type = "thought", content = "![x]($link)")))
        assertTrue(thought.ownsMarkdownVideo(link, 0, "segment:0"))
        assertFalse(thought.ownsMarkdownVideo(link, 0, "segment:1"))
        assertFalse(thought.ownsMarkdownVideo(link, 0, "answer"))
        assertFalse(message("![x]($link)").ownsMarkdownVideo(link, -1, "answer"))
        assertFalse(message("![x]($link)").ownsMarkdownVideo(link, 10_000, "answer"))
    }

    @Test fun textLinksAdmitOnlyNamedLocalImagesAndVideos() {
        val image = "/home/agora/out/plot.png"
        for (source in listOf("见 $image。", "[图]($image)", "[图](<$image>)", "[图][r]\n\n[r]: $image")) {
            assertTrue(source, message(source).ownsMarkdownLinkedMedia(image, "answer"))
        }
        assertTrue(message("[v]($link)").ownsMarkdownLinkedMedia(link, "answer"))
        assertFalse(message("$image.bak").ownsMarkdownLinkedMedia(image, "answer"))
        assertFalse(message("x$image").ownsMarkdownLinkedMedia(image, "answer"))
        assertFalse(message("nothing").ownsMarkdownLinkedMedia(image, "answer"))
        assertFalse(message("/home/agora/a.pdf").ownsMarkdownLinkedMedia("/home/agora/a.pdf", "answer"))
        assertFalse(message("/home/agora/a.svg").ownsMarkdownLinkedMedia("/home/agora/a.svg", "answer"))
        assertFalse(message("/etc/a.png").ownsMarkdownLinkedMedia("/etc/a.png", "answer"))
        val remote = "shell://Quantum/F:/a.png"
        assertFalse(message("[x]($remote)").ownsMarkdownLinkedMedia(remote, "answer"))
    }

    // The 2026-11-23 GTA answer: a Conch video on a Windows path followed by pictures.
    @Test fun aConchVideoOnAWindowsPathIsOwned() {
        val link = "shell://Quantum/C:/Users/newoether/AppData/Local/Temp/gta6_pipework/gta6_pipework_mandy.mp4"
        val source = "好了，发你。\n![GTA 6 Pipework 修水管片段]($link)\n几张截图。\n" +
            "![曼迪在卧室](shell://Quantum/C:/Users/newoether/AppData/Local/Temp/gta6_pipework/shot_bedroom.jpg)\n"
        assertTrue(message(source).ownsMarkdownVideo(link, 0, "answer"))
    }

    @Test fun theFragmentStartSkipsVideoDestinationsAboveIt() {
        val first = "![first]($link)\n\n"
        val source = message(first + "![second]($link)")
        assertTrue(source.ownsMarkdownVideo(link, 0, "answer"))
        assertTrue(source.ownsMarkdownVideo(link, first.length, "answer"))
        assertFalse(source.ownsMarkdownVideo(link, first.length + 1, "answer"))
    }

    @Test fun readableSandboxVideosResolveToTheirHostFile() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val directory = File(context.filesDir, "sandbox-home").apply { mkdirs() }
        val file = File(directory, "clip.mp4").apply { writeBytes(ByteArray(8) { it.toByte() }) }
        val videos = WebUiMarkdownVideos(context, devices = { emptyList() }) { conversation, id ->
            if (conversation == "c" && id == "m") message("![clip]($link)") else null
        }
        val video = videos.open("c", "m", link, document = "answer")
        assertTrue(video is WebUiMarkdownVideoSource.Local)
        assertEquals(file.canonicalFile, (video as WebUiMarkdownVideoSource.Local).file)
        assertEquals("video/mp4", video.mimeType)
        assertNull(videos.open("c", "m", "shell://Local%20Sandbox/home/agora/other.mp4"))
        assertNull(videos.open("other", "m", link))
        assertNull(videos.open("c", "m", "shell://Quantum/F:/private.mp4"))
    }

    @Test fun byteRangesStayBoundedAndHonourIfRange() {
        assertEquals(WebUiByteRange.Whole, webUiByteRange(8, null, null))
        assertEquals(WebUiByteRange.Whole, webUiByteRange(8, "bytes=0-1", "\"v1\""))
        assertEquals(WebUiByteRange.Whole, webUiByteRange(8, "junk", null))
        assertEquals(WebUiByteRange.Partial(2, 3), webUiByteRange(8, "bytes=2-3", null))
        assertEquals(WebUiByteRange.Partial(5, 7), webUiByteRange(8, "bytes=5-", null))
        assertEquals(WebUiByteRange.Unsatisfiable, webUiByteRange(8, "bytes=9-", null))
    }

    @Test fun videoMimeTypesFollowTheContainerExtension() {
        assertEquals("video/mp4", videoMimeType(link))
        assertEquals("video/webm", videoMimeType("/home/agora/a.WEBM"))
        assertEquals("video/quicktime", videoMimeType("https://example.test/a.mov?x=1"))
        assertEquals("video/x-matroska", videoMimeType("shell://Quantum/F:/a.mkv"))
        assertEquals("video/mp4", videoMimeType("/home/agora/unknown"))
    }
}
