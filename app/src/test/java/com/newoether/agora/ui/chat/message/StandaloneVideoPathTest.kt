package com.newoether.agora.ui.chat.message

import org.junit.Assert.assertEquals
import org.junit.Test

class StandaloneVideoPathTest {
    @Test
    fun standaloneVideoPathBecomesAnEmbeddedCard() {
        assertEquals(
            "![](</home/agora/clips/a.mp4>)",
            "/home/agora/clips/a.mp4".embedStandaloneVideoPaths(),
        )
        assertEquals(
            "![](<shell://Local%20Sandbox/home/agora/clips/a.mp4>)",
            "shell://Local%20Sandbox/home/agora/clips/a.mp4".embedStandaloneVideoPaths(),
        )
        assertEquals(
            "![](</storage/emulated/0/Download/a.mp4>)",
            "/storage/emulated/0/Download/a.mp4".embedStandaloneVideoPaths(),
        )
    }

    @Test
    fun surroundingProseKeepsThePathAsText() {
        val text = "打开 /home/agora/clips/a.mp4 看看"
        assertEquals(text, text.embedStandaloneVideoPaths())
    }

    @Test
    fun fencedAndIndentedCodeStayVerbatim() {
        val fenced = "```\n/home/agora/clips/a.mp4\n```"
        assertEquals(fenced, fenced.embedStandaloneVideoPaths())
        val indented = "    /home/agora/clips/a.mp4"
        assertEquals(indented, indented.embedStandaloneVideoPaths())
    }

    @Test
    fun inlineCodeAndExistingNodesStayVerbatim() {
        val inline = "`/home/agora/clips/a.mp4`"
        assertEquals(inline, inline.embedStandaloneVideoPaths())
        val existingLink = "[clip](/home/agora/clips/a.mp4)"
        assertEquals(existingLink, existingLink.embedStandaloneVideoPaths())
        val existingImage = "![](/home/agora/clips/a.mp4)"
        assertEquals(existingImage, existingImage.embedStandaloneVideoPaths())
    }

    @Test
    fun nonVideoPathsKeepTheirText() {
        val text = "/home/agora/clips/a.png"
        assertEquals(text, text.embedStandaloneVideoPaths())
        val unsupported = "/etc/a.mp4"
        assertEquals(unsupported, unsupported.embedStandaloneVideoPaths())
    }

    @Test
    fun renderableMarkdownEmbedsTheStandalonePath() {
        assertEquals(
            "![](</home/agora/clips/a.mp4>)",
            "/home/agora/clips/a.mp4".toRenderableMarkdownText(),
        )
    }
}
