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
            MarkdownLinkTarget.Remote("https://example.com/a?b=1"),
            classifyMarkdownLink("https://example.com/a?b=1"),
        )
        assertEquals(MarkdownLinkTarget.Remote("mailto:a@b.c"), classifyMarkdownLink("mailto:a@b.c"))
    }

    @Test
    fun sandboxLinkResolvesUnderHome() {
        assertEquals(
            MarkdownLinkTarget.Local(LocalFileTarget(LocalFileRoot.SANDBOX_HOME, "attachments/a.mp4")),
            classifyMarkdownLink("shell://Local%20Sandbox/home/agora/attachments/a.mp4"),
        )
        assertEquals(
            MarkdownLinkTarget.Local(LocalFileTarget(LocalFileRoot.SANDBOX_HOME, "notes.md")),
            classifyMarkdownLink("/home/agora/notes.md"),
        )
    }

    @Test
    fun sharedStorageSpellingsShareOneRoot() {
        val expected = MarkdownLinkTarget.Local(LocalFileTarget(LocalFileRoot.SHARED_STORAGE, "Download/a.pdf"))
        assertEquals(expected, classifyMarkdownLink("/storage/emulated/0/Download/a.pdf"))
        assertEquals(expected, classifyMarkdownLink("/mnt/shared/Download/a.pdf"))
        assertEquals(expected, classifyMarkdownLink("/storage/self/primary/Download/a.pdf"))
    }

    @Test
    fun unsupportedDestinationsStayInert() {
        assertNull(classifyMarkdownLink("file:///home/agora/a.png"))
        assertNull(classifyMarkdownLink("javascript:alert(1)"))
        assertNull(classifyMarkdownLink("/etc/passwd"))
        assertNull(classifyMarkdownLink("/home/agora"))
        assertNull(classifyMarkdownLink("shell://Workstation/F:/out/a.png"))
        assertNull(classifyMarkdownLink("/home/agora/../../etc/passwd"))
        assertNull(classifyMarkdownLink("/storage/emulated/0/../etc/passwd"))
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
    fun videoSourcesStayInsideSandboxHomeOrRemainRemote() {
        assertEquals(
            MarkdownVideoSource.Local(LocalFileTarget(LocalFileRoot.SANDBOX_HOME, "clips/a.mp4")),
            markdownVideoSource("shell://Local Sandbox/home/agora/clips/a.mp4"),
        )
        assertNull(markdownVideoSource("shell://Local Sandbox/etc/a.mp4"))
        assertNull(markdownVideoSource("shell://Workstation/F:/out/a.mp4"))
        assertEquals(
            MarkdownVideoSource.Remote("https://example.com/a.mp4"),
            markdownVideoSource("https://example.com/a.mp4"),
        )
    }
}
