package com.newoether.agora.ui.chat.message

import com.newoether.agora.ui.chat.messageTextMayHostHtmlPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownHtmlPreviewContentTest {
    @Test
    fun embeddedHtmlFenceFileLinkMatchesSingleLocalOrShellHtmlPathsOnly() {
        assertEquals(
            "shell://Local%20Sandbox/home/agora/chart.html",
            embeddedHtmlFenceFileLink("\n  shell://Local%20Sandbox/home/agora/chart.html  \n"),
        )
        assertEquals(
            "/storage/emulated/0/Download/page.htm",
            embeddedHtmlFenceFileLink("/storage/emulated/0/Download/page.htm"),
        )
        assertEquals(
            "shell://Local Sandbox/home/agora/my chart.html",
            embeddedHtmlFenceFileLink("<shell://Local Sandbox/home/agora/my chart.html>"),
        )

        listOf(
            "",
            "   ",
            "<div>hello</div>",
            "<h1>chart.html</h1>",
            "shell://Local%20Sandbox/home/agora/a.html\nshell://Local%20Sandbox/home/agora/b.html",
            "https://example.com/page.html",
            "/storage/emulated/0/Download/image.png",
            "/data/data/com.termux/files/home/page.html",
        ).forEach { code ->
            assertNull("Should not match as embedded HTML file: $code", embeddedHtmlFenceFileLink(code))
        }
    }

    @Test
    fun previewTitleUsesExplicitTitleOrDecodedFileName() {
        assertEquals(
            "Sales Dashboard",
            markdownHtmlPreviewTitle("shell://Local%20Sandbox/home/agora/chart.html", " Sales Dashboard "),
        )
        assertEquals(
            "my chart.html",
            markdownHtmlPreviewTitle("shell://Local%20Sandbox/home/agora/my%20chart.html"),
        )
        assertEquals(
            "index.htm",
            markdownHtmlPreviewTitle("shell://Quantum/F:/viz/index.htm?v=2#main"),
        )
        assertEquals(
            "demo.html",
            markdownHtmlPreviewTitle("file:///storage/emulated/0/Download/demo.html"),
        )
    }

    @Test
    fun hostsHtmlPreviewDetectsFencesAndEmbeddedHtmlFiles() {
        assertTrue(messageTextMayHostHtmlPreview("```html-preview\n<div>hi</div>\n```"))
        assertTrue(messageTextMayHostHtmlPreview("```html\n<div>hi</div>\n```"))
        assertTrue(messageTextMayHostHtmlPreview("~~~html-preview\n<div>hi</div>\n~~~"))
        assertTrue(messageTextMayHostHtmlPreview("![chart](shell://Local%20Sandbox/home/agora/chart.html)"))
        assertTrue(messageTextMayHostHtmlPreview("/storage/emulated/0/Download/page.HTM"))
        assertFalse(messageTextMayHostHtmlPreview("Here is a normal answer with ![img](/home/agora/a.png)"))
    }
}
