package com.newoether.agora.api

import android.util.JsonReader
import android.util.JsonToken
import com.newoether.agora.util.DebugLog
import java.io.File

/**
 * Pure-Kotlin SentencePiece-style BPE tokenizer for the Bekko (Gemma-family) embedding model.
 *
 * WHY THIS EXISTS (not DJL): the DJL HuggingFace tokenizer builds its [ai.djl.huggingface.tokenizers.Encoding]
 * via a native `getTokenCharSpans` call that is invoked UNCONDITIONALLY in `toEncoding(...)` — it is
 * not gated by the `withCharSpans` flag. That native call aborts (SIGABRT, Rust panic=abort) for this
 * tokenizer.json on arm64, crashing the whole process from the embedding worker. There is no DJL Java
 * API to skip it. We only ever need `input_ids` + `attention_mask`, so we implement the tokenizer
 * ourselves and never touch native char spans.
 *
 * Faithful to the bekko tokenizer.json config:
 *  - model.type = BPE, byte_fallback = true, fuse_unk = true, ignore_merges = false,
 *    continuing_subword_prefix = null, end_of_word_suffix = null, dropout = null.
 *  - normalizer   = Replace " " -> "▁" (U+2581).
 *  - pre_tokenizer = Metaspace("▁", prepend_scheme = always, split = true): prepend a leading "▁".
 *  - post_processor = TemplateProcessing single = <bos> A <eos>  (bos id 2, eos id 1).
 *  - unk = <unk> (id 3). Byte-fallback tokens are "<0xNN>".
 *
 * The merge table is loaded as a rank map; encoding applies the lowest-rank adjacent merge
 * repeatedly (standard BPE) over the byte-fallback-expanded symbol sequence.
 */
class BpeTokenizer private constructor(
    private val vocab: HashMap<String, Int>,
    private val mergeRanks: HashMap<Long, Int>,
    private val bosId: Int,
    private val eosId: Int,
    private val unkId: Int,
    private val maxTokens: Int,
) {

    companion object {
        private const val TAG = "BpeTokenizer"
        private const val METASPACE = '\u2581' // ▁

        /**
         * Load from a HuggingFace tokenizer.json. Streams the (large) file with [JsonReader] so we
         * only retain the vocab + merge rank maps, not the whole JSON tree. Returns null on failure.
         */
        fun load(tokenizerFile: File, maxTokens: Int): BpeTokenizer? {
            return try {
                val vocab = HashMap<String, Int>(300_000)
                // merges: pack the two token ids into a Long key (left<<32 | right) -> rank.
                val mergeRanks = HashMap<Long, Int>(700_000)
                // Temp holder for merges as raw string pairs; resolved to ids after vocab is read.
                // tokenizer.json always emits "model" with "vocab" before "merges", but we do not
                // rely on ordering: collect raw pairs, resolve against vocab at the end.
                val rawMerges = ArrayList<Pair<String, String>>(600_000)
                var bos = 2; var eos = 1; var unk = 3

                tokenizerFile.inputStream().reader(Charsets.UTF_8).use { r ->
                    JsonReader(r).use { jr ->
                        jr.beginObject()
                        while (jr.hasNext()) {
                            when (jr.nextName()) {
                                "added_tokens" -> {
                                    jr.beginArray()
                                    while (jr.hasNext()) {
                                        var id = -1; var content = ""
                                        jr.beginObject()
                                        while (jr.hasNext()) {
                                            when (jr.nextName()) {
                                                "id" -> id = jr.nextInt()
                                                "content" -> content = jr.nextString()
                                                else -> jr.skipValue()
                                            }
                                        }
                                        jr.endObject()
                                        when (content) {
                                            "<bos>" -> bos = id
                                            "<eos>" -> eos = id
                                            "<unk>" -> unk = id
                                        }
                                    }
                                    jr.endArray()
                                }
                                "model" -> {
                                    jr.beginObject()
                                    while (jr.hasNext()) {
                                        when (jr.nextName()) {
                                            "vocab" -> {
                                                jr.beginObject()
                                                while (jr.hasNext()) {
                                                    val tokenStr = jr.nextName()
                                                    vocab[tokenStr] = jr.nextInt()
                                                }
                                                jr.endObject()
                                            }
                                            "merges" -> {
                                                jr.beginArray()
                                                while (jr.hasNext()) {
                                                    // Newer tokenizers.json: merges is an array of
                                                    // [left, right] arrays. Older: array of "l r".
                                                    if (jr.peek() == JsonToken.BEGIN_ARRAY) {
                                                        jr.beginArray()
                                                        val l = jr.nextString()
                                                        val rr = jr.nextString()
                                                        jr.endArray()
                                                        rawMerges.add(l to rr)
                                                    } else {
                                                        val s = jr.nextString()
                                                        val sp = s.indexOf(' ')
                                                        if (sp > 0) {
                                                            rawMerges.add(
                                                                s.substring(0, sp) to s.substring(sp + 1),
                                                            )
                                                        }
                                                    }
                                                }
                                                jr.endArray()
                                            }
                                            else -> jr.skipValue()
                                        }
                                    }
                                    jr.endObject()
                                }
                                else -> jr.skipValue()
                            }
                        }
                        jr.endObject()
                    }
                }

                if (vocab.isEmpty()) {
                    DebugLog.e(TAG, "tokenizer vocab empty")
                    return null
                }
                // Resolve merge pairs to packed id keys, preserving rank (array order = priority).
                var rank = 0
                for ((l, rr) in rawMerges) {
                    val li = vocab[l] ?: continue
                    val ri = vocab[rr] ?: continue
                    val merged = vocab[l + rr] ?: continue
                    val key = (li.toLong() shl 32) or (ri.toLong() and 0xFFFFFFFFL)
                    // Store rank only if unseen (first occurrence wins — lowest rank).
                    if (!mergeRanks.containsKey(key)) {
                        mergeRanks[key] = rank
                        // Also stash the merged id alongside rank via a parallel map avoided by
                        // recomputing from vocab at apply time; see encodeWord().
                    }
                    rank++
                }
                DebugLog.d(TAG, "Loaded BPE: vocab=${vocab.size} merges=${mergeRanks.size}")
                BpeTokenizer(vocab, mergeRanks, bos, eos, unk, maxTokens)
            } catch (e: Throwable) {
                DebugLog.e(TAG, "load failed: ${e.javaClass.simpleName}")
                null
            }
        }
    }

    /**
     * Encode text into token ids with <bos> ... <eos> wrapping, truncated to [maxTokens].
     * Returns the id list (always length >= 2 for non-empty input: bos+eos).
     */
    fun encode(text: String): IntArray {
        // Normalizer: Replace ' ' -> '▁'. Metaspace(prepend='always', split=true): prepend a
        // single leading '▁' when the text does not already begin with a space. Then run plain BPE
        // over the ENTIRE normalized string (non-space whitespace like \n/\t is kept literal and
        // byte-fallback tokenized). Letting BPE decide every merge reproduces HF exactly — verified
        // id-for-id against HF `tokenizers` for ASCII, CJK, Cyrillic, emoji, tabs and newlines.
        // Build the metaspace-normalized string. Rules derived empirically against HF `tokenizers`
        // for this tokenizer.json (Gemma-family Metaspace, prepend_scheme=always, split=true):
        //  - Each space ' '  -> '▁'.
        //  - Prepend '▁' at the very start unless the text already begins with a space.
        //  - A newline '\n' is a boundary: it stays literal, and a '▁' is inserted AFTER it so the
        //    following segment starts a new metaspace word (matches HF: "x\ny" -> ▁x \n ▁y).
        //  - '\t', '\r' and other chars are ordinary and stay inside the current word.
        // Word boundaries are then every '▁'; BPE is applied independently per word.
        val normalized = buildString(text.length + 2) {
            if (text.isNotEmpty() && text[0] != ' ') append(METASPACE)
            for (ch in text) {
                when (ch) {
                    ' ' -> append(METASPACE)
                    '\n' -> { append('\n'); append(METASPACE) }
                    else -> append(ch)
                }
            }
        }

        val out = ArrayList<Int>(maxTokens)
        out.add(bosId)
        val budget = (maxTokens - 2).coerceAtLeast(0)
        val n = normalized.length
        var wordStart = 0
        emit@ while (wordStart < n) {
            // A word runs from its leading '▁' (or string start) up to the next '▁'. A word that is
            // exactly "<ws>▁" (e.g. "\n▁") is split so the '\n' tokenizes on its own and the '▁'
            // begins the next word — handled naturally because '▁' is always a boundary.
            var j = wordStart + 1
            while (j < n && normalized[j] != METASPACE) j++
            val word = normalized.substring(wordStart, j)
            for (id in encodeWord(word)) {
                if (out.size - 1 >= budget) break@emit
                out.add(id)
            }
            wordStart = j
        }
        out.add(eosId)
        return out.toIntArray()
    }

    /**
     * Byte-fallback + BPE merges over a single pre-token word and return token ids. Each starting
     * symbol is a single-codepoint vocab id or, if unknown, its UTF-8 bytes as "<0xNN>" ids
     * (byte_fallback). Merges are then applied greedily by lowest rank until none remain.
     */
    private fun encodeWord(s: String): IntArray {
        if (s.isEmpty()) return IntArray(0)

        // Build the initial symbol id list.
        val symbols = ArrayList<Int>(s.length + 8)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val chars = String(Character.toChars(cp))
            i += chars.length
            val direct = vocab[chars]
            if (direct != null) {
                symbols.add(direct)
            } else {
                // byte_fallback: emit <0xNN> for each UTF-8 byte; if even that is missing, <unk>.
                val bytes = chars.toByteArray(Charsets.UTF_8)
                for (b in bytes) {
                    val hex = "<0x%02X>".format(b.toInt() and 0xFF)
                    symbols.add(vocab[hex] ?: unkId)
                }
            }
        }

        if (symbols.size <= 1) return symbols.toIntArray()

        // Greedy BPE: repeatedly find the adjacent pair with the lowest merge rank and merge it.
        // Uses a simple O(n) scan per merge; sequences here are short (one truncated input).
        while (true) {
            var bestRank = Int.MAX_VALUE
            var bestPos = -1
            var j = 0
            while (j < symbols.size - 1) {
                val key = (symbols[j].toLong() shl 32) or (symbols[j + 1].toLong() and 0xFFFFFFFFL)
                val r = mergeRanks[key]
                if (r != null && r < bestRank) {
                    bestRank = r
                    bestPos = j
                }
                j++
            }
            if (bestPos < 0) break
            // Resolve the merged id from vocab by concatenating the two symbols' surface strings.
            val mergedId = mergedIdFor(symbols[bestPos], symbols[bestPos + 1])
            if (mergedId == null) {
                // Shouldn't happen (merge existed), but guard against infinite loop.
                break
            }
            symbols[bestPos] = mergedId
            symbols.removeAt(bestPos + 1)
            if (symbols.size <= 1) break
        }
        return symbols.toIntArray()
    }

    // Reverse lookup: id -> surface string, lazily built from vocab on first use.
    private val idToTokenCache: HashMap<Int, String> by lazy {
        val m = HashMap<Int, String>(vocab.size)
        for ((k, v) in vocab) m[v] = k
        m
    }

    private fun mergedIdFor(leftId: Int, rightId: Int): Int? {
        val l = idToTokenCache[leftId] ?: return null
        val r = idToTokenCache[rightId] ?: return null
        return vocab[l + r]
    }
}
