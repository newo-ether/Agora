package com.newoether.agora.ui.chat.message

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownLinkTargetsTest {
    @Test
    fun networkSchemesStayRemote() {
        assertEquals(
            MarkdownDestination.Web("https://example.com/a?b=1"),
            resolveMarkdownDestination("https://example.com/a?b=1"),
        )
        assertEquals(MarkdownDestination.Web("mailto:a@b.c"), resolveMarkdownDestination("mailto:a@b.c"))
    }

    @Test
    fun sandboxLinkResolvesUnderHome() {
        assertEquals(
            MarkdownDestination.File(LocalFileTarget(LocalFileRoot.SANDBOX_HOME, "attachments/a.mp4")),
            resolveMarkdownDestination("shell://Local%20Sandbox/home/agora/attachments/a.mp4"),
        )
        assertEquals(
            MarkdownDestination.File(LocalFileTarget(LocalFileRoot.SANDBOX_HOME, "notes.md")),
            resolveMarkdownDestination("/home/agora/notes.md"),
        )
    }

    @Test
    fun sandboxSpellingOfSharedStorageJoinsTheSameRoot() {
        val expected = MarkdownDestination.File(LocalFileTarget(LocalFileRoot.SHARED_STORAGE, "Download/a.pdf"))
        assertEquals(expected, resolveMarkdownDestination("shell://Local Sandbox/storage/emulated/0/Download/a.pdf"))
        assertEquals(expected, resolveMarkdownDestination("shell://Local%20Sandbox/mnt/shared/Download/a.pdf"))
    }

    @Test
    fun sharedStorageSpellingsShareOneRoot() {
        val expected = MarkdownDestination.File(LocalFileTarget(LocalFileRoot.SHARED_STORAGE, "Download/a.pdf"))
        assertEquals(expected, resolveMarkdownDestination("/storage/emulated/0/Download/a.pdf"))
        assertEquals(expected, resolveMarkdownDestination("/mnt/shared/Download/a.pdf"))
        assertEquals(expected, resolveMarkdownDestination("/storage/self/primary/Download/a.pdf"))
    }

    @Test
    fun handWrittenPathsDecodePercentAndAcceptSdcard() {
        assertEquals(
            MarkdownDestination.File(LocalFileTarget(LocalFileRoot.SHARED_STORAGE, "Download/a b.png")),
            resolveMarkdownDestination("/storage/emulated/0/Download/a%20b.png"),
        )
        assertEquals(
            MarkdownDestination.File(LocalFileTarget(LocalFileRoot.SHARED_STORAGE, "Download/a.pdf")),
            resolveMarkdownDestination("/sdcard/Download/a.pdf"),
        )
        // A malformed escape keeps the literal percent sign instead of dropping the destination.
        assertEquals(
            MarkdownDestination.File(LocalFileTarget(LocalFileRoot.SHARED_STORAGE, "100%.png")),
            resolveMarkdownDestination("/mnt/shared/100%.png"),
        )
    }

    @Test
    fun otherDevicesKeepTheirServerAndPath() {
        assertEquals(
            MarkdownDestination.Device(ShellMediaLink("Local Sandbox", "/etc/passwd")),
            resolveMarkdownDestination("shell://Local%20Sandbox/etc/passwd"),
        )
        assertEquals(
            MarkdownDestination.Device(ShellMediaLink("Workstation", "F:/out/a.png")),
            resolveMarkdownDestination("shell://Workstation/F:/out/a.png"),
        )
        assertEquals(
            MarkdownDestination.Device(ShellMediaLink("OnePlus 13T", "/storage/emulated/0/Download/a.pdf")),
            resolveMarkdownDestination("shell://OnePlus%2013T/storage/emulated/0/Download/a.pdf"),
        )
    }

    @Test
    fun fileUrlsFoldToTheSameLocalRoots() {
        val shared = MarkdownDestination.File(LocalFileTarget(LocalFileRoot.SHARED_STORAGE, "Download/a b.png"))
        assertEquals(shared, resolveMarkdownDestination("file:///sdcard/Download/a%20b.png"))
        assertEquals(shared, resolveMarkdownDestination("file://localhost/storage/emulated/0/Download/a%20b.png"))
        assertEquals(
            MarkdownDestination.File(LocalFileTarget(LocalFileRoot.SANDBOX_HOME, "out/a.png")),
            resolveMarkdownDestination("file:///home/agora/out/a.png"),
        )
        assertNull(resolveMarkdownDestination("file:///etc/passwd"))
    }

    @Test
    fun unsupportedDestinationsStayInert() {
        assertNull(resolveMarkdownDestination("javascript:alert(1)"))
        assertNull(resolveMarkdownDestination("/etc/passwd"))
        assertNull(resolveMarkdownDestination("/home/agora"))
        assertNull(resolveMarkdownDestination("/home/agora/../../etc/passwd"))
        assertNull(resolveMarkdownDestination("/storage/emulated/0/../etc/passwd"))
    }

    @Test
    fun annotatedLinkWinsAtItsOffset() {
        val text = AnnotatedString(
            text = "see here",
            annotations = listOf(
                AnnotatedString.Range(LinkAnnotation.Url("https://example.com"), 4, 8),
            ),
        )
        assertEquals("https://example.com", markdownLinkAt(text, 5))
        assertNull(markdownLinkAt(text, 0))
    }

    @Test
    fun bareProsePathIsTappableAcrossItsSpelling() {
        val text = AnnotatedString("见 /home/agora/out/a.png，然后")
        assertEquals("/home/agora/out/a.png", markdownLinkAt(text, text.indexOf("out")))
        assertEquals("/home/agora/out/a.png", markdownLinkAt(text, text.indexOf("/home")))
        assertNull(markdownLinkAt(AnnotatedString("见 home/agora/out/a.png"), 6))
    }

    @Test
    fun trailingSentencePunctuationIsNotPartOfThePath() {
        val text = AnnotatedString("open /storage/emulated/0/Download/a.mp4.")
        assertEquals("/storage/emulated/0/Download/a.mp4", markdownBarePathAt(text, 10))
    }

    @Test
    fun videoDestinationsNeverJoinTheImageSnapshotStore() {
        val sandbox = "shell://Local%20Sandbox/home/agora/clip.mp4"
        assertEquals(sandbox, markdownVideoLink(sandbox))
        assertNull(markdownImageSnapshotKey(sandbox))
        assertEquals("https://example.com/a.webm?t=1", markdownVideoLink("https://example.com/a.webm?t=1"))
        assertNull(markdownImageSnapshotKey("https://example.com/a.webm?t=1"))
        assertNull(markdownVideoLink("https://example.com/a.png"))
        assertEquals("https://example.com/a.png", markdownImageSnapshotKey("https://example.com/a.png"))
    }

    @Test
    fun bareVideoPathIsPlayableFromAReadableRoot() {
        assertEquals("/home/agora/clips/a.mp4", markdownVideoLink("/home/agora/clips/a.mp4"))
        assertEquals(
            MarkdownVideoSource.Local(LocalFileTarget(LocalFileRoot.SANDBOX_HOME, "clips/a.mp4")),
            markdownVideoSource("/home/agora/clips/a.mp4"),
        )
        assertEquals(
            MarkdownVideoSource.Local(LocalFileTarget(LocalFileRoot.SHARED_STORAGE, "Download/a.mp4")),
            markdownVideoSource("/mnt/shared/Download/a.mp4"),
        )
        assertNull(markdownVideoLink("/etc/a.mp4"))
        assertNull(markdownVideoSource("/etc/a.mp4"))
    }

    @Test
    fun videoSourcesStayInsideSandboxHomeOrResolveToTheirConchDevice() {
        assertEquals(
            MarkdownVideoSource.Local(LocalFileTarget(LocalFileRoot.SANDBOX_HOME, "clips/a.mp4")),
            markdownVideoSource("shell://Local Sandbox/home/agora/clips/a.mp4"),
        )
        assertNull(markdownVideoSource("shell://Local Sandbox/etc/a.mp4"))
        assertEquals(
            MarkdownVideoSource.Conch("Workstation", "F:/out/a.mp4"),
            markdownVideoSource("shell://Workstation/F:/out/a.mp4"),
        )
        assertEquals(
            MarkdownVideoSource.Conch("tinybox", "/home/newoether/clip.webm"),
            markdownVideoSource("shell://tinybox/home/newoether/clip.webm"),
        )
        assertNull(markdownVideoSource("shell://tinybox/home/newoether/clip.png"))
    }

    @Test
    fun remoteVideoCacheNameFollowsTheSourceContentHash() {
        val hash = "ab".repeat(32)
        assertEquals("$hash.mp4", markdownVideoCacheFileName(hash, "/tips/clip.mp4"))
        assertEquals("$hash.webm", markdownVideoCacheFileName(hash, "/tips/clip.WEBM"))
        assertEquals("$hash.mp4", markdownVideoCacheFileName(hash, "/tips/clip"))
    }

    @Test
    fun linkTailStopsBeforeSentencePunctuationAndUnbalancedBrackets() {
        fun kept(text: String): String = text.substring(0, trimLinkTail(text, 0, text.length))

        assertEquals("https://github.com/x/y/releases/tag/v1.2.0", kept("https://github.com/x/y/releases/tag/v1.2.0)"))
        assertEquals("https://github.com/x/y/releases/tag/v1.2.0", kept("https://github.com/x/y/releases/tag/v1.2.0）。"))
        assertEquals("https://example.com/a", kept("https://example.com/a！"))
        assertEquals("https://example.com/a", kept("https://example.com/a."))
        assertEquals("https://example.com/a", kept("https://example.com/a]"))
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", kept("https://en.wikipedia.org/wiki/Foo_(bar)"))
        assertEquals("https://example.com/a", kept("https://example.com/a"))
    }

    @Test
    fun linkAnnotationsDropTheTrailingBracketTheyDoNotOwn() {
        val url = "https://github.com/x/y/releases/tag/v1.2.0"
        val text = "见（$url）。"
        val start = text.indexOf(url)
        val annotated = AnnotatedString(
            text,
            annotations = listOf(
                AnnotatedString.Range(LinkAnnotation.Url("$url）"), start, start + url.length + 1, "link"),
            ),
        )

        val range = annotated.trimMarkdownLinkTail().getLinkAnnotations(start, start + url.length).single()

        assertEquals(start, range.start)
        assertEquals(start + url.length, range.end)
        assertEquals(url, (range.item as LinkAnnotation.Url).url)
    }
}
