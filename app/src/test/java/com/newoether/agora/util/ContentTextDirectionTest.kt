package com.newoether.agora.util

import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app language must never decide the direction of a message's own content. */
class ContentTextDirectionTest {

    @Test
    fun `arabic text is rtl`() {
        assertTrue(isRtlContent("السلام عليكم ورحمة الله"))
    }

    @Test
    fun `english text is ltr`() {
        assertFalse(isRtlContent("Hello, how are you?"))
    }

    @Test
    fun `hebrew text is rtl`() {
        assertTrue(isRtlContent("שלום עולם"))
    }

    @Test
    fun `leading digits and punctuation defer to the first strong character`() {
        assertTrue(isRtlContent("2024 - تقرير"))
        assertFalse(isRtlContent("2024 - report"))
    }

    @Test
    fun `neutral only content falls back to ltr`() {
        assertFalse(isRtlContent(""))
        assertFalse(isRtlContent("123 456 !!!"))
        assertFalse(isRtlContent("😀"))
        assertFalse(isRtlContent(null))
    }

    @Test
    fun `emoji before arabic still resolves rtl`() {
        assertTrue(isRtlContent("😀 مرحبا"))
    }

    @Test
    fun `mixed content follows the first strong run`() {
        assertTrue(isRtlContent("مرحبا hello"))
        assertFalse(isRtlContent("hello مرحبا"))
    }

    @Test
    fun `text direction maps from the detected content`() {
        assertEquals(TextDirection.Rtl, contentTextDirection("مرحبا"))
        assertEquals(TextDirection.Rtl, contentTextDirection("שלום"))
        assertEquals(TextDirection.Ltr, contentTextDirection("hello"))
    }

    @Test
    fun strongDirectionIsNullForNeutralText() {
        assertNull(strongTextDirection("1234 — 56%"))
        assertNull(strongTextDirection("😀 🎉"))
        assertEquals(TextDirection.Rtl, strongTextDirection("1234 مرحبًا"))
        assertEquals(TextDirection.Ltr, strongTextDirection("1234 Hello"))
    }
}
