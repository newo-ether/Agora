package com.newoether.agora.api.util.tokens

import kotlin.math.ceil

/**
 * Prices one string in provider-visible tokens, before any safety margin.
 *
 * Implementations must be pure and offline: no I/O, no suspending work, no provider clients, and
 * never any influence from endpoint-reported usage. The app targets arbitrary OpenAI-compatible
 * endpoints whose reported counts cannot be trusted as ground truth, so counting stays a local
 * computation that is identical on every surface.
 */
fun interface TextTokenCounter {
    fun count(text: String): Long
}

/**
 * Character-class heuristic used when no real vocabulary is available for a model.
 *
 * ASCII word-like runs cost roughly one token per four characters; every other code point costs one
 * token on its own, which keeps CJK and punctuation-heavy text from being under-counted. Whitespace
 * only terminates a run. This is deliberately coarse and deliberately conservative.
 */
object HeuristicTextTokenCounter : TextTokenCounter {
    private const val ASCII_CHARS_PER_TOKEN = 4.0

    override fun count(text: String): Long {
        if (text.isEmpty()) return 0L
        var tokens = 0L
        var asciiRun = 0

        fun flushAsciiRun() {
            if (asciiRun > 0) {
                tokens += ceil(asciiRun / ASCII_CHARS_PER_TOKEN).toLong()
                asciiRun = 0
            }
        }

        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            when {
                codePoint <= 0x7f && Character.isLetterOrDigit(codePoint) -> asciiRun++
                Character.isWhitespace(codePoint) -> flushAsciiRun()
                else -> {
                    flushAsciiRun()
                    tokens++
                }
            }
            index += Character.charCount(codePoint)
        }
        flushAsciiRun()
        return tokens
    }
}
