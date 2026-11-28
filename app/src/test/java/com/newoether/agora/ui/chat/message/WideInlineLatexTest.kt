package com.newoether.agora.ui.chat.message

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/** Only a formula that cannot fit its paragraph leaves the line; wide ones wrap like words. */
class WideInlineLatexTest {
    private fun formula(link: String, start: Int) = InlineImageSlot(link, start, start + 1, null, "\\($link\\)")
    private fun image(link: String, start: Int) = InlineImageSlot(link, start, start + 1, null, null)

    private val sizes = mapOf(
        "narrow" to Size(100f, 30f),
        "wide" to Size(1100f, 30f),
        "tall" to Size(100f, 90f),
    )

    private fun blocks(vararg slots: InlineImageSlot) =
        blockImageSlots(slots.toList(), maxWidthPx = 1000f, lineHeightPx = 40f, formulaSize = sizes::get)
            .map { it.link }

    @Test
    fun narrowFormulaStaysInsideTheParagraph() {
        assertEquals(emptyList<String>(), blocks(formula("narrow", 0), image("png", 2)))
    }

    @Test
    fun formulaWiderThanTheParagraphLeavesTheLineAndNarrowOneStays() {
        assertEquals(listOf("wide"), blocks(formula("narrow", 0), formula("wide", 2)))
    }

    @Test
    fun onceSplitTallFormulasAndImagesKeepTheirBlockPromotion() {
        assertEquals(
            listOf("wide", "tall", "png"),
            blocks(formula("wide", 0), formula("narrow", 2), formula("tall", 4), image("png", 6)),
        )
    }

    @Test
    fun onlyAFullWidthOverflowSplitsTheParagraph() {
        val slot = formula("edge", 0)
        fun split(width: Float) = blockImageSlots(listOf(slot), 1000f, 40f) { Size(width, 30f) }.size
        assertEquals(0, split(1000f))
        assertEquals(1, split(1001f))
    }
}
