package com.newoether.agora.api

import android.os.Build
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.URL

/**
 * Validates the pure-Kotlin [BpeTokenizer] against reference ids produced by the HuggingFace
 * `tokenizers` Rust implementation for the bekko (Gemma-family) tokenizer.json.
 *
 * The 34MB tokenizer.json is NOT committed; the test downloads it once to the build tmp dir and
 * skips (assumeTrue) if the download is unavailable, so offline CI does not fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class BpeTokenizerTest {

    private fun tokenizerFile(): File? {
        val cache = File(System.getProperty("java.io.tmpdir"), "bekko-tokenizer.json")
        if (cache.isFile && cache.length() > 1_000_000) return cache
        return try {
            val url = URL(
                "https://github.com/XNet-NGO/deps/releases/download/bekko-embedding/bekko-tokenizer.json",
            )
            url.openStream().use { input -> cache.outputStream().use { input.copyTo(it) } }
            if (cache.length() > 1_000_000) cache else null
        } catch (_: Throwable) {
            null
        }
    }

    @Test
    fun matchesReferenceIds() {
        val f = tokenizerFile()
        assumeTrue("tokenizer.json unavailable (offline)", f != null)
        val tok = BpeTokenizer.load(f!!, maxTokens = 8192)
        assertNotNull("tokenizer failed to load", tok)

        // Reference ids from HF `tokenizers` 0.23.3 (Tokenizer.from_file(...).encode(t).ids).
        val cases = linkedMapOf(
            "Hello world" to intArrayOf(2, 25957, 2134, 1),
            "The quick brown fox." to intArrayOf(2, 714, 4320, 8426, 25341, 235265, 1),
            "日本語のテスト" to intArrayOf(2, 26867, 236333, 235372, 73714, 1),
            "Привет мир" to intArrayOf(2, 17952, 7540, 53459, 1),
            "café ☕ 🚀" to intArrayOf(2, 18688, 235248, 241275, 167340, 1),
            "a" to intArrayOf(2, 476, 1),
            "   multiple   spaces" to intArrayOf(2, 235248, 235248, 6733, 235248, 235248, 14130, 1),
            "" to intArrayOf(2, 1),
            " leading" to intArrayOf(2, 8133, 1),
            "trailing " to intArrayOf(2, 60732, 235248, 1),
            "newline\nhere" to intArrayOf(2, 130942, 108, 1517, 1),
            "emoji only 🎉🎉" to intArrayOf(2, 52810, 1297, 160588, 239548, 1),
            "Tab\tsep" to intArrayOf(2, 14968, 226, 17062, 1),
            "x\ny" to intArrayOf(2, 1141, 108, 597, 1),
            "a\tb" to intArrayOf(2, 476, 226, 235268, 1),
            "word1\nword2\nword3" to intArrayOf(
                2, 2204, 235274, 108, 2204, 235284, 108, 2204, 235304, 1,
            ),
            "Multi line\ntext here\nthird" to intArrayOf(
                2, 9530, 2017, 108, 2793, 1517, 108, 4906, 1,
            ),
        )
        for ((text, expected) in cases) {
            val got = tok!!.encode(text)
            assertEquals(
                "ids mismatch for ${'$'}{text.take(40)}",
                expected.toList(), got.toList(),
            )
        }
    }
}
