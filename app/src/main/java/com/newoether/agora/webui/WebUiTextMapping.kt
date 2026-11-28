package com.newoether.agora.webui

import com.newoether.agora.ui.components.parseLatexSpans

/** A math span in [WebText.markdown] is `MATH_OPEN + index + MATH_CLOSE`. */
internal const val MATH_OPEN = '\uE000'
internal const val MATH_CLOSE = '\uE001'

/**
 * Markdown with math already split out by the app's [parseLatexSpans], so the browser detects
 * formulas exactly as the app does. Private-use placeholder characters already in the text are
 * replaced so they cannot be mistaken for a placeholder.
 */
internal fun String.toWebText(parseInlineDollarMath: Boolean): WebText {
    val spans = parseLatexSpans(this, parseInlineDollarMath)
    val math = mutableListOf<WebMath>()
    val sourceMap = mutableListOf<List<Int>>()
    var sourceCursor = 0
    var lastDelta = 0
    val markdown = buildString {
        fun mapped(character: Char, original: Int) {
            val delta = original - length
            if (delta != lastDelta) {
                sourceMap += listOf(length, original)
                lastDelta = delta
            }
            append(character)
        }
        spans.forEach { span ->
            if (span.isLatex) {
                val start = this@toWebText.indexOf(span.source, sourceCursor)
                check(start >= sourceCursor)
                val slot = "$MATH_OPEN${math.size}$MATH_CLOSE"
                slot.forEach { mapped(it, start) }
                sourceCursor = start + span.source.length
                math += WebMath(span.content, span.display)
            } else {
                span.content.forEachIndexed { index, character ->
                    // Plain-span dollar escaping can insert a protective slash, but no source glyph.
                    val inserted = character == '\\' && span.content.getOrNull(index + 1) == '$' &&
                        this@toWebText.getOrNull(sourceCursor) == '$'
                    mapped(if (character == MATH_OPEN || character == MATH_CLOSE) '\uFFFD' else character, sourceCursor)
                    if (!inserted) sourceCursor += 1
                }
            }
        }
        if (sourceCursor - length != lastDelta) sourceMap += listOf(length, sourceCursor)
    }
    return WebText(markdown, math, sourceMap)
}
