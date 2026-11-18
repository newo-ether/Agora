package com.newoether.agora.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxOutputLinesTest {
    @Test
    fun chunksBecomeWholeLinesAndTrailingTextWaitsForFlush() {
        val lines = mutableListOf<String>()
        val output = SandboxOutputLines(lines::add)
        output.accept("Get:1 http://exa")
        assertTrue(lines.isEmpty())
        output.accept("mple.invalid/a [1 kB]\nGet:2 http")
        output.accept("://example.invalid/b [2 kB]\r\n")
        assertEquals(
            listOf("Get:1 http://example.invalid/a [1 kB]", "Get:2 http://example.invalid/b [2 kB]"),
            lines,
        )
        output.accept("partial")
        output.flush()
        assertEquals(listOf("partial"), lines.drop(2))
    }

    @Test
    fun carriageReturnProgressEmitsEachUpdateAndDropsEmptyLines() {
        val lines = mutableListOf<String>()
        val output = SandboxOutputLines(lines::add)
        output.accept("Reading 1%\rReading 50%\rReading 100%\r\n")
        output.flush()
        assertEquals(listOf("Reading 1%", "Reading 50%", "Reading 100%"), lines)
    }

    @Test
    fun aLineWithoutTerminatorIsSplitAtTheBound() {
        val lines = mutableListOf<String>()
        val output = SandboxOutputLines(lines::add)
        output.accept("x".repeat(4096))
        assertEquals(1, lines.size)
        assertEquals(4096, lines.single().length)
        output.accept("y")
        output.flush()
        assertEquals(listOf("y"), lines.drop(1))
    }
}
