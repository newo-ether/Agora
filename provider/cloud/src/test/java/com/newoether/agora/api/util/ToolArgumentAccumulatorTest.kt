package com.newoether.agora.api.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentAccumulatorTest {

    @Test
    fun compliantIncrementalDeltas_areAppendedInOrder() {
        val accumulator = ToolArgumentAccumulator()
        accumulator.append("{\"path\"")
        accumulator.append(":\"a.txt\"")
        accumulator.append("}")

        assertEquals("{\"path\":\"a.txt\"}", accumulator.toString())
    }

    @Test
    fun emptyAndNullDeltas_neverEraseAccumulatedContent() {
        val accumulator = ToolArgumentAccumulator()
        accumulator.append("{\"path\":\"a.txt\"}")
        accumulator.append("")
        accumulator.append(null)

        assertEquals("{\"path\":\"a.txt\"}", accumulator.toString())
    }

    @Test
    fun growingSnapshotDeltas_replaceInsteadOfConcatenating() {
        // Non-compliant relay: every delta carries the whole value accumulated so far.
        val accumulator = ToolArgumentAccumulator()
        accumulator.append("{\"path\"")
        accumulator.append("{\"path\":\"a.txt\"")
        accumulator.append("{\"path\":\"a.txt\"}")

        assertEquals("{\"path\":\"a.txt\"}", accumulator.toString())
    }

    @Test
    fun repeatedIdenticalSnapshot_isIgnored() {
        val accumulator = ToolArgumentAccumulator()
        accumulator.append("{\"a\":1}")
        accumulator.append("{\"a\":1}")

        assertEquals("{\"a\":1}", accumulator.toString())
    }

    @Test
    fun staleLongerSnapshotResend_isIgnored() {
        val accumulator = ToolArgumentAccumulator()
        accumulator.append("{\"path\":\"a.txt\",\"query\":\"search me\"}")
        accumulator.append("{\"path\":\"a.txt\",\"que")

        assertEquals("{\"path\":\"a.txt\",\"query\":\"search me\"}", accumulator.toString())
    }

    @Test
    fun nestedArrayOpeningBracePair_isAppendedNotDropped() {
        // Real Anthropic chunking: `[` and `{"` arrive as separate deltas. `{"` is the head of
        // every argument object, so reading it as a stale snapshot used to truncate the call and
        // leave Agora holding arguments that never parse.
        val accumulator = ToolArgumentAccumulator()
        accumulator.append("{\"questions\":[")
        accumulator.append("{\"")
        accumulator.append("question\":\"Pick\",\"options\":[\"a\"]}]}")

        assertEquals(
            "{\"questions\":[{\"question\":\"Pick\",\"options\":[\"a\"]}]}",
            accumulator.toString(),
        )
    }

    @Test
    fun shortIncrementMatchingTheAccumulatedHead_isAppended() {
        val accumulator = ToolArgumentAccumulator("{\"outer\":")
        accumulator.append("{\"")

        assertEquals("{\"outer\":{\"", accumulator.toString())
    }

    @Test
    fun nestedObjectOpeningBraceIsNotMistakenForAStaleSnapshot() {
        val accumulator = ToolArgumentAccumulator()
        accumulator.append("{\"outer\":")
        accumulator.append("{")
        accumulator.append("\"value\":1}}")

        assertEquals("{\"outer\":{\"value\":1}}", accumulator.toString())
    }

    @Test
    fun finalValueCanReplaceTheIncrementalSnapshot() {
        val accumulator = ToolArgumentAccumulator("{\"q\":\"x\"}")

        accumulator.replace("{ \"q\": \"x\" }")

        assertEquals("{ \"q\": \"x\" }", accumulator.toString())
    }

    @Test
    fun initialValueFromBlockStart_isPreserved() {
        val accumulator = ToolArgumentAccumulator("{\"seed\":true}")
        assertTrue(!accumulator.isEmpty)
        assertEquals("{\"seed\":true}", accumulator.toString())
    }

    @Test
    fun singleCharacterFragments_areTreatedAsIncrements() {
        // A lone brace is ambiguous, so snapshot detection must not swallow it.
        val accumulator = ToolArgumentAccumulator()
        accumulator.append("{")
        accumulator.append("}")

        assertEquals("{}", accumulator.toString())
    }
}
