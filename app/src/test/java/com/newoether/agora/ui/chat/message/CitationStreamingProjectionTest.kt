package com.newoether.agora.ui.chat.message

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class CitationStreamingProjectionTest : CitationContentTestFixture() {
    @Test
    fun terminalProjectionHandoffTracksMarkdownAndLateMetadataChanges() {
        val answer = "Answer."
        val source = citation(
            answer = answer,
            title = "Example",
            url = "https://example.com/source",
            ranges = arrayOf(0 until 6),
        )
        val streaming = requireNotNull(
            citationMarkdownProjection(answer, listOf(source), isStreaming = true),
        )
        val terminal = requireNotNull(
            citationMarkdownProjection(answer, listOf(source), isStreaming = false),
        )

        assertFalse(citationProjectionRequiresTerminalHandoff(streaming, streaming.copy()))
        assertTrue(citationProjectionRequiresTerminalHandoff(streaming, terminal))
        assertTrue(
            citationProjectionRequiresTerminalHandoff(
                terminal,
                terminal.copy(markers = emptyList()),
            ),
        )
        assertTrue(citationProjectionRequiresTerminalHandoff(null, streaming))
    }
}
