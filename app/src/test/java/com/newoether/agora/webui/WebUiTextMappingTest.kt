package com.newoether.agora.webui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Direct [toWebText] edge cases: dollar escaping, protected Markdown ranges, inline-whitespace
 * absorption by formula sources, and private-use placeholder hygiene. Each assertion pins the
 * exact [WebText.sourceMap] contract the browser uses to invert rendered positions.
 */
class WebUiTextMappingTest {
    @Test
    fun escapedDollarKeepsTheIdentityMap() {
        val text = "pay \\\$5 now".toWebText(true)
        assertEquals("pay \\\$5 now", text.markdown)
        assertEquals(emptyList<List<Int>>(), text.sourceMap)
        assertEquals(0, text.math.size)
    }

    @Test
    fun bareDollarEscapeMapsBothInsertedGlyphsOntoTheOriginalDollar() {
        val text = "cost \$5 total".toWebText(true)
        assertEquals("cost \\\$5 total", text.markdown)
        assertEquals(listOf(listOf(6, 5)), text.sourceMap)
        assertEquals(0, text.math.size)
    }

    @Test
    fun protectedCodeKeepsDollarTextAndSplitsOnlyRealMath() {
        val text = "use `\$x\$` and \$y^2\$ ok".toWebText(true)
        assertEquals("use `\$x\$` and" + MATH_OPEN + "0" + MATH_CLOSE + "ok", text.markdown)
        assertEquals(1, text.math.size)
        assertEquals("y^2", text.math.single().tex)
        assertEquals(false, text.math.single().display)
        assertEquals(listOf(listOf(14, 13), listOf(15, 13), listOf(16, 20)), text.sourceMap)
    }

    @Test
    fun inlineWhitespaceAbsorptionIsPartOfTheFormulaSource() {
        val text = "x \\(a^2\\) y".toWebText(true)
        assertEquals("x" + MATH_OPEN + "0" + MATH_CLOSE + "y", text.markdown)
        assertEquals("a^2", text.math.single().tex)
        assertEquals(listOf(listOf(2, 1), listOf(3, 1), listOf(4, 10)), text.sourceMap)
    }

    @Test
    fun foreignPrivateUseCharactersBecomeReplacementGlyphs() {
        val text = "a${MATH_OPEN}b${MATH_CLOSE}c".toWebText(false)
        assertEquals("a\uFFFDb\uFFFDc", text.markdown)
        assertEquals(emptyList<List<Int>>(), text.sourceMap)
        assertEquals(0, text.math.size)
    }
}
