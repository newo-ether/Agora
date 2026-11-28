package com.newoether.agora.api.util.tokens.bpe

import java.util.regex.Pattern

/**
 * Counts tokens the way a byte-level BPE tokenizer would, without building the token list.
 *
 * Text is first split by the tokenizer's own pattern, which is what keeps words, numbers, runs of
 * punctuation and runs of whitespace from merging across each other. Each piece is then merged by
 * rank until no pair can merge again; how many parts are left is how many tokens the piece costs.
 *
 * This is a count, not an encoding: nothing here needs token ids, so no list is ever materialised.
 */
object BpeTokenCount {

    /**
     * Unicode White_Space, which is what `\s` means in the tokenizer's own regex engine.
     *
     * Spelled out because the two engines this runs on disagree about `\s`: the JVM treats it as
     * ASCII unless `(?U)` is set, and Android's ICU engine rejects `(?U)` outright. An explicit class
     * means the same thing on both.
     */
    private const val WS =
        "\\t\\n\\x{0B}\\f\\r \\x{85}\\x{A0}\\x{1680}\\x{2000}-\\x{200A}" +
            "\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}"

    /**
     * English contraction suffixes, matched case-insensitively by the tokenizer.
     *
     * Written as explicit classes instead of `(?i:...)` because the JVM folds ASCII only by default
     * while ICU and the tokenizer fold Unicode; the one letter where that differs is `s` (`ſ`).
     */
    private const val CONTRACTIONS =
        "(?:'[sS\\x{17F}]|'[tT]|'[rR][eE]|'[vV][eE]|'[mM]|'[lL][lL]|'[dD])"

    /**
     * The o200k pre-tokenizer pattern, as published by tiktoken, restricted to syntax that both the
     * JVM and Android's ICU engine accept with identical meaning (no inline flags).
     */
    internal const val O200K_PATTERN: String =
        "[^\\r\\n\\p{L}\\p{N}]?[\\p{Lu}\\p{Lt}\\p{Lm}\\p{Lo}\\p{M}]*" +
            "[\\p{Ll}\\p{Lm}\\p{Lo}\\p{M}]+$CONTRACTIONS?" +
            "|[^\\r\\n\\p{L}\\p{N}]?[\\p{Lu}\\p{Lt}\\p{Lm}\\p{Lo}\\p{M}]+" +
            "[\\p{Ll}\\p{Lm}\\p{Lo}\\p{M}]*$CONTRACTIONS?" +
            "|\\p{N}{1,3}" +
            "| ?[^$WS\\p{L}\\p{N}]+[\\r\\n/]*" +
            "|[$WS]*[\\r\\n]+" +
            "|[$WS]+(?![^$WS])" +
            "|[$WS]+"

    private val O200K_PIECES: Pattern = Pattern.compile(O200K_PATTERN)

    /**
     * A merge loop is quadratic in the length of one piece, so an unusually long piece is merged in
     * chunks instead. Real text never reaches this; a pathological run without any boundary would
     * otherwise stall the UI thread. Chunking can only split a merge that would have happened, so it
     * overcounts slightly, which is the safe direction for a context estimate.
     */
    private const val MAX_MERGE_BYTES = 512

    /** How many tokens [text] costs under [vocabulary]. */
    fun of(text: String, vocabulary: BpeVocabulary): Long {
        if (text.isEmpty()) return 0L
        var total = 0L
        val pieces = O200K_PIECES.matcher(text)
        while (pieces.find()) {
            val piece = text.substring(pieces.start(), pieces.end()).toByteArray(Charsets.UTF_8)
            total += countPiece(piece, vocabulary)
        }
        return total
    }

    private fun countPiece(piece: ByteArray, vocabulary: BpeVocabulary): Int {
        if (piece.isEmpty()) return 0
        if (piece.size <= MAX_MERGE_BYTES) return mergeCount(piece, 0, piece.size, vocabulary)
        var total = 0
        var from = 0
        while (from < piece.size) {
            val to = minOf(from + MAX_MERGE_BYTES, piece.size)
            total += mergeCount(piece, from, to, vocabulary)
            from = to
        }
        return total
    }

    /**
     * Merges `piece[from until to]` by rank and returns how many parts survive.
     *
     * Parts are held as their start offsets plus the rank of merging each part with the next one.
     * The lowest rank merges first, which only changes the ranks of the merged part and the one
     * before it, so the loop repairs those two and rescans for the next minimum.
     */
    private fun mergeCount(
        piece: ByteArray,
        from: Int,
        to: Int,
        vocabulary: BpeVocabulary,
    ): Int {
        val length = to - from
        if (length <= 1) return length
        if (vocabulary.rankOf(piece, from, to) != BpeVocabulary.ABSENT) return 1

        // One start per byte, plus a sentinel start marking the end of the last part.
        var partCount = length + 1
        val starts = IntArray(partCount) { from + it }
        val ranks = IntArray(partCount)
        for (part in 0 until partCount) {
            ranks[part] = pairRank(piece, starts, partCount, part, vocabulary)
        }

        while (true) {
            var lowestRank = Int.MAX_VALUE
            var lowestPart = -1
            for (part in 0 until partCount - 1) {
                if (ranks[part] < lowestRank) {
                    lowestRank = ranks[part]
                    lowestPart = part
                }
            }
            if (lowestPart < 0) break

            // Merging means dropping the boundary that separates this part from the next.
            val tail = partCount - lowestPart - 2
            System.arraycopy(starts, lowestPart + 2, starts, lowestPart + 1, tail)
            System.arraycopy(ranks, lowestPart + 2, ranks, lowestPart + 1, tail)
            partCount--
            ranks[lowestPart] = pairRank(piece, starts, partCount, lowestPart, vocabulary)
            if (lowestPart > 0) {
                ranks[lowestPart - 1] =
                    pairRank(piece, starts, partCount, lowestPart - 1, vocabulary)
            }
        }
        return partCount - 1
    }

    /** The rank of merging part [part] with the one after it, or no rank when there is no pair. */
    private fun pairRank(
        piece: ByteArray,
        starts: IntArray,
        partCount: Int,
        part: Int,
        vocabulary: BpeVocabulary,
    ): Int {
        if (part + 2 > partCount - 1) return Int.MAX_VALUE
        val rank = vocabulary.rankOf(piece, starts[part], starts[part + 2])
        return if (rank == BpeVocabulary.ABSENT) Int.MAX_VALUE else rank
    }
}
