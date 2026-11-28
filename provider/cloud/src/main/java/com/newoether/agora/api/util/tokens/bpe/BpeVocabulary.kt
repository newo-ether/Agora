package com.newoether.agora.api.util.tokens.bpe

/**
 * A byte-level BPE vocabulary, stored so that it can be searched without allocating.
 *
 * A vocabulary of two hundred thousand entries is kept as flat arrays rather than a map of byte
 * arrays: the token bytes are concatenated once and looked up through an open-addressed index, which
 * costs a few megabytes instead of a few tens of megabytes and lets a merge loop probe the table
 * without creating garbage on every step. That matters because counting runs on the UI path every
 * time the composer's context indicator updates.
 *
 * Only [rankOf] is needed to count: merge order is decided by rank, and the number of parts left
 * when no pair can merge any further is the token count.
 */
class BpeVocabulary private constructor(
    private val tokenBytes: ByteArray,
    private val offsets: IntArray,
    private val ranks: IntArray,
    private val buckets: IntArray,
) {

    /** How many entries the vocabulary holds. */
    val size: Int get() = ranks.size

    /**
     * The rank of `input[from until to]`, or [ABSENT] when those bytes are not one token.
     *
     * Rank is merge priority: the lower it is, the earlier the pair is merged.
     */
    fun rankOf(input: ByteArray, from: Int, to: Int): Int {
        val mask = buckets.size - 1
        var slot = hashOf(input, from, to) and mask
        while (true) {
            val entry = buckets[slot]
            if (entry == EMPTY_SLOT) return ABSENT
            val index = entry - 1
            if (matches(index, input, from, to)) return ranks[index]
            slot = (slot + 1) and mask
        }
    }

    private fun matches(index: Int, input: ByteArray, from: Int, to: Int): Boolean {
        val start = offsets[index]
        val end = offsets[index + 1]
        if (end - start != to - from) return false
        var here = start
        var there = from
        while (here < end) {
            if (tokenBytes[here] != input[there]) return false
            here++
            there++
        }
        return true
    }

    companion object {
        /** Returned by [rankOf] when the bytes are not a token of this vocabulary. */
        const val ABSENT = -1

        private const val EMPTY_SLOT = 0

        /**
         * Reads the `.tiktoken` format: one line per token, holding its base64 bytes and its rank.
         *
         * Malformed lines are skipped rather than failing the load, because a partly readable
         * vocabulary still counts better than the fallback heuristic, and a vocabulary that is
         * missing entries only ever overcounts.
         */
        fun parseTiktoken(lines: Sequence<String>): BpeVocabulary {
            val allBytes = ArrayList<ByteArray>(200_000)
            val allRanks = ArrayList<Int>(200_000)
            var totalLength = 0
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                val separator = line.indexOf(' ')
                if (separator <= 0) return@forEach
                val rank = line.substring(separator + 1).trim().toIntOrNull() ?: return@forEach
                val bytes = decodeBase64(line, 0, separator) ?: return@forEach
                if (bytes.isEmpty()) return@forEach
                allBytes += bytes
                allRanks += rank
                totalLength += bytes.size
            }
            return of(allBytes, allRanks, totalLength)
        }

        private fun of(
            allBytes: List<ByteArray>,
            allRanks: List<Int>,
            totalLength: Int,
        ): BpeVocabulary {
            val tokenBytes = ByteArray(totalLength)
            val offsets = IntArray(allBytes.size + 1)
            val ranks = IntArray(allBytes.size)
            var cursor = 0
            allBytes.forEachIndexed { index, bytes ->
                offsets[index] = cursor
                bytes.copyInto(tokenBytes, cursor)
                cursor += bytes.size
                ranks[index] = allRanks[index]
            }
            offsets[allBytes.size] = cursor
            return BpeVocabulary(
                tokenBytes = tokenBytes,
                offsets = offsets,
                ranks = ranks,
                buckets = indexOf(tokenBytes, offsets, allBytes.size),
            )
        }

        /** Open-addressed index from token bytes to token position, sized to stay sparse. */
        private fun indexOf(tokenBytes: ByteArray, offsets: IntArray, count: Int): IntArray {
            var capacity = 1
            while (capacity < count * 2) capacity = capacity shl 1
            val buckets = IntArray(capacity)
            val mask = capacity - 1
            for (index in 0 until count) {
                var slot = hashOf(tokenBytes, offsets[index], offsets[index + 1]) and mask
                while (buckets[slot] != EMPTY_SLOT) slot = (slot + 1) and mask
                buckets[slot] = index + 1
            }
            return buckets
        }

        /** FNV-1a over the byte range. Cheap, and spread well enough for a sparse table. */
        private fun hashOf(input: ByteArray, from: Int, to: Int): Int {
            var hash = -2_128_831_035 // 0x811C9DC5
            var at = from
            while (at < to) {
                hash = (hash xor (input[at].toInt() and 0xFF)) * 16_777_619
                at++
            }
            // The sign bit would make the bucket index negative, so it is folded away here.
            return hash and Int.MAX_VALUE
        }

        /**
         * Standard base64 with padding, decoded in place from a line slice.
         *
         * Written out rather than taken from the platform because this runs before any Android
         * dependency is available in tests, and because it avoids allocating a substring per line
         * across two hundred thousand lines.
         */
        private fun decodeBase64(line: String, from: Int, to: Int): ByteArray? {
            var end = to
            while (end > from && line[end - 1] == '=') end--
            val length = end - from
            val decodedSize = length * 3 / 4
            if (decodedSize == 0) return null
            val out = ByteArray(decodedSize)
            var accumulator = 0
            var bitsHeld = 0
            var written = 0
            var at = from
            while (at < end) {
                val value = base64Value(line[at])
                if (value < 0) return null
                accumulator = (accumulator shl 6) or value
                bitsHeld += 6
                if (bitsHeld >= 8) {
                    bitsHeld -= 8
                    out[written++] = ((accumulator shr bitsHeld) and 0xFF).toByte()
                }
                at++
            }
            return if (written == decodedSize) out else out.copyOf(written)
        }

        private fun base64Value(symbol: Char): Int = when (symbol) {
            in 'A'..'Z' -> symbol - 'A'
            in 'a'..'z' -> symbol - 'a' + 26
            in '0'..'9' -> symbol - '0' + 52
            '+' -> 62
            '/' -> 63
            else -> -1
        }
    }
}
