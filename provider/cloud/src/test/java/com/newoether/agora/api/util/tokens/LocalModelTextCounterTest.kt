package com.newoether.agora.api.util.tokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact-when-possible text counting for the embedded runtime.
 *
 * The real tokenizer lives behind JNI and cannot run in a JVM test, so the runtime call is injected
 * here. What matters is the decision logic around it: use the exact count when the runtime answers,
 * and fall back to the offline heuristic whenever it cannot.
 */
class LocalModelTextCounterTest {

    private val heuristic = HeuristicTextTokenCounter

    @Test
    fun anExactCountFromTheRuntimeIsUsedAsIs() {
        val counter = LocalModelTextCounter(exactCount = { 42 })
        assertEquals(42L, counter.count("some prompt text"))
    }

    @Test
    fun noResidentModelFallsBackToTheHeuristic() {
        val text = "some prompt text"
        val counter = LocalModelTextCounter(exactCount = { null })
        assertEquals(heuristic.count(text), counter.count(text))
    }

    @Test
    fun aFailingRuntimeCallFallsBackInsteadOfPropagating() {
        val text = "some prompt text"
        val counter = LocalModelTextCounter(
            exactCount = { throw UnsatisfiedLinkError("native library missing") },
        )
        assertEquals(heuristic.count(text), counter.count(text))
    }

    @Test
    fun emptyTextCostsNothingAndNeverReachesTheRuntime() {
        var called = false
        val counter = LocalModelTextCounter(
            exactCount = {
                called = true
                7
            },
        )
        assertEquals(0L, counter.count(""))
        assertTrue(!called)
    }

    @Test
    fun aNegativeCountIsTreatedAsAFailureNotAsACost() {
        // The native side reports failure as a negative value, so it must not become a cost of zero.
        val text = "some prompt text"
        val counter = LocalModelTextCounter(exactCount = { -1 })
        assertEquals(heuristic.count(text), counter.count(text))
    }
}
