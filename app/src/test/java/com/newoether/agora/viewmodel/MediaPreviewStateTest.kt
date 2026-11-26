package com.newoether.agora.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MediaPreviewStateTest {
    @Test
    fun shortTextIsUntouchedAndNotReported() {
        var reported: Int? = null
        val state = MediaPreviewState { reported = it }
        val text = "hello\nworld"
        state.showFile("a.txt", text)
        assertSame(text, state.fileContent.value)
        assertNull(reported)
    }

    @Test
    fun longTextIsCappedAndReported() {
        var reported: Int? = null
        val state = MediaPreviewState { reported = it }
        state.showFile("big.txt", "x".repeat(MediaPreviewState.TEXT_PREVIEW_MAX_CHARS + 5))
        assertEquals(MediaPreviewState.TEXT_PREVIEW_MAX_CHARS, state.fileContent.value!!.length)
        assertEquals(MediaPreviewState.TEXT_PREVIEW_MAX_CHARS, reported)
    }

    @Test
    fun capPrefersALineBreakNearTheLimit() {
        val text = "a".repeat(95) + "\n" + "b".repeat(20)
        assertEquals("a".repeat(95), capTextPreview(text, limit = 100))
    }

    @Test
    fun capIgnoresALineBreakFarFromTheLimit() {
        val text = "a".repeat(10) + "\n" + "b".repeat(200)
        assertEquals(100, capTextPreview(text, limit = 100).length)
    }

    @Test
    fun capNeverSplitsASurrogatePair() {
        val text = "a".repeat(99) + "\uD83D\uDE00" + "tail"
        assertEquals("a".repeat(99), capTextPreview(text, limit = 100))
    }
}
