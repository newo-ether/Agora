package com.newoether.agora.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TextFileViewerLinesTest {
    @Test
    fun splitsCrLfAndKeepsBlankLines() {
        assertEquals(listOf("Get:1", "", "apt-get exit code: 0"), codeEditorLines("Get:1\r\n\r\napt-get exit code: 0"))
    }

    @Test
    fun trailingBreakKeepsTheFinalEmptyLine() {
        assertEquals(listOf("a", ""), codeEditorLines("a\n"))
    }

    @Test
    fun emptyContentIsOneEmptyLine() {
        assertEquals(listOf(""), codeEditorLines(""))
    }
}
