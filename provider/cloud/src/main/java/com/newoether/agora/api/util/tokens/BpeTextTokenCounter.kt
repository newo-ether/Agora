package com.newoether.agora.api.util.tokens

import com.newoether.agora.api.util.tokens.bpe.BpeTokenCount
import com.newoether.agora.api.util.tokens.bpe.BpeVocabulary
import kotlin.math.ceil

/**
 * Text counting through a real BPE vocabulary, with the offline heuristic as a fallback.
 *
 * The vocabulary is supplied rather than held, because it is loaded in the background and counting
 * must stay synchronous: before it arrives, and if it fails to load at all, the heuristic answers.
 *
 * [scale] exists for families whose own tokenizer is not published. Counting their text with a known
 * vocabulary and correcting by a measured factor is closer to the truth than counting characters, but
 * the factor has to be measured offline first; until it is, a family stays at 1.0 rather than
 * carrying an invented number.
 */
class BpeTextTokenCounter(
    private val vocabulary: () -> BpeVocabulary?,
    private val scale: Double = 1.0,
    private val fallback: TextTokenCounter = HeuristicTextTokenCounter,
) : TextTokenCounter {

    init {
        // A factor below one would claim a family is cheaper than the vocabulary says, which is the
        // unsafe direction for a budget.
        require(scale >= 1.0) { "scale must be at least 1.0, was $scale" }
    }

    override fun count(text: String): Long {
        if (text.isEmpty()) return 0L
        val loaded = vocabulary() ?: return fallback.count(text)
        val counted = CountMemo.countOf(text, loaded)
        if (scale == 1.0) return counted
        return ceil(counted * scale).toLong()
    }

    /**
     * Remembers raw BPE counts of recently counted texts.
     *
     * The context indicator re-prices the whole selected history on every projection, and dispatch
     * prices it again; almost all of that text is unchanged since the last time. A count depends only
     * on the text and the vocabulary, so a hit is exact. Short texts are cheaper to count than to
     * hash and store, so they bypass the memo. Size is bounded by stored characters, evicting the
     * least recently used text first.
     */
    internal object CountMemo {
        private const val MIN_MEMO_CHARS = 64
        private const val MAX_MEMO_CHARS = 4_000_000L

        private var vocabularyOwner: BpeVocabulary? = null
        private var storedChars = 0L
        private val counts = LinkedHashMap<String, Long>(256, 0.75f, true)

        fun countOf(text: String, vocabulary: BpeVocabulary): Long {
            if (text.length < MIN_MEMO_CHARS) return BpeTokenCount.of(text, vocabulary)
            synchronized(this) {
                if (vocabularyOwner !== vocabulary) {
                    counts.clear()
                    storedChars = 0L
                    vocabularyOwner = vocabulary
                }
                counts[text]?.let { return it }
            }
            // Counted outside the lock so concurrent callers never wait on each other's merges.
            val counted = BpeTokenCount.of(text, vocabulary)
            synchronized(this) {
                if (vocabularyOwner === vocabulary && counts.put(text, counted) == null) {
                    storedChars += text.length
                    val entries = counts.entries.iterator()
                    while (storedChars > MAX_MEMO_CHARS && entries.hasNext()) {
                        storedChars -= entries.next().key.length
                        entries.remove()
                    }
                }
            }
            return counted
        }
    }
}
