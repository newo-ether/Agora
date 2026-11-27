package com.newoether.agora.ui.chat.message

import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import com.newoether.agora.util.contentTextAlign
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownBlockDirectionTest {
    @Test
    fun arabicParagraphIsLaidOutRtl() {
        assertEquals(
            LayoutDirection.Rtl,
            markdownBlockLayoutDirection("PARAGRAPH", "مرحبًا بك في Agora"),
        )
    }

    @Test
    fun latinParagraphStaysLtr() {
        assertEquals(
            LayoutDirection.Ltr,
            markdownBlockLayoutDirection("PARAGRAPH", "Welcome to Agora"),
        )
    }

    @Test
    fun machineReadableBlocksStayLtrEvenInArabic() {
        for (type in listOf("CODE_FENCE", "CODE_BLOCK", "TABLE", "HTML_BLOCK")) {
            assertEquals(type, LayoutDirection.Ltr, markdownBlockLayoutDirection(type, "مرحبًا"))
        }
    }

    @Test
    fun listBlocksFollowTheirOwnText() {
        assertEquals(LayoutDirection.Rtl, markdownBlockLayoutDirection("UNORDERED_LIST", "• تحكم كامل بالشاشة"))
        assertEquals(LayoutDirection.Ltr, markdownBlockLayoutDirection("ORDERED_LIST", "1. Full screen control"))
    }

    @Test
    fun contentAlignFollowsTheParagraphDirection() {
        assertEquals(androidx.compose.ui.text.style.TextAlign.Right, contentTextAlign(TextDirection.Rtl, layoutRtl = false))
        assertEquals(androidx.compose.ui.text.style.TextAlign.Left, contentTextAlign(TextDirection.Ltr, layoutRtl = true))
    }

    @Test
    fun neutralContentAlignsToTheLayoutStart() {
        assertEquals(androidx.compose.ui.text.style.TextAlign.Right, contentTextAlign(null, layoutRtl = true))
        assertEquals(androidx.compose.ui.text.style.TextAlign.Left, contentTextAlign(null, layoutRtl = false))
    }
}
