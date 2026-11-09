package com.newoether.agora.api.util.tokens

import com.newoether.agora.api.util.tokens.bpe.BpeVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * How the shipped vocabulary is used, and what happens before it is there.
 *
 * The vocabulary is loaded in the background, so every counter has to work without it. A tiny
 * hand-built vocabulary stands in for the real one here, which keeps the merge behaviour visible:
 * whether a piece is one token or several is decided by what the vocabulary contains.
 */
class BpeTextTokenCounterTest {

    /** Single letters plus the pair "he", so "hello" must merge into h-e then stop. */
    private val tinyVocabulary = BpeVocabulary.parseTiktoken(
        sequenceOf(
            "aA== 0", // h
            "ZQ== 1", // e
            "bA== 2", // l
            "bw== 3", // o
            "aGU= 4", // he
        ),
    )

    private val wholeWordVocabulary = BpeVocabulary.parseTiktoken(
        sequenceOf(
            "aA== 0",
            "ZQ== 1",
            "bA== 2",
            "bw== 3",
            "aGVsbG8= 4", // hello
        ),
    )

    @Test
    fun `text is merged by rank until no pair is left`() {
        val counter = BpeTextTokenCounter(vocabulary = { tinyVocabulary })
        // h e l l o merges to he l l o: four parts, because no other pair is in the vocabulary.
        assertEquals(4L, counter.count("hello"))
    }

    @Test
    fun `a piece that is itself a token costs one`() {
        val counter = BpeTextTokenCounter(vocabulary = { wholeWordVocabulary })
        assertEquals(1L, counter.count("hello"))
    }

    @Test
    fun `a vocabulary that has not loaded yet falls back to the heuristic`() {
        val counter = BpeTextTokenCounter(vocabulary = { null })
        val text = "The quick brown fox jumps over the lazy dog."
        assertEquals(HeuristicTextTokenCounter.count(text), counter.count(text))
    }

    @Test
    fun `empty text costs nothing either way`() {
        assertEquals(0L, BpeTextTokenCounter(vocabulary = { tinyVocabulary }).count(""))
        assertEquals(0L, BpeTextTokenCounter(vocabulary = { null }).count(""))
    }

    @Test
    fun `a family factor scales the count upwards and never fractionally`() {
        val plain = BpeTextTokenCounter(vocabulary = { tinyVocabulary }).count("hello")
        val scaled = BpeTextTokenCounter(vocabulary = { tinyVocabulary }, scale = 1.15).count("hello")
        // 4 * 1.15 is 4.6, which has to become 5 rather than 4.
        assertEquals(5L, scaled)
        assertEquals(4L, plain)
    }

    @Test
    fun `a factor below one is refused because it would understate the budget`() {
        assertThrows(IllegalArgumentException::class.java) {
            BpeTextTokenCounter(vocabulary = { tinyVocabulary }, scale = 0.9)
        }
    }
}
