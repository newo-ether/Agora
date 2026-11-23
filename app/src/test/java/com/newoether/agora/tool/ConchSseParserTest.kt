package com.newoether.agora.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConchSseParserTest {
    @Test
    fun validStreamReturnsExactlyOneTerminalResult() = runTest {
        val streamed = StringBuilder()
        val result = parse(
            "event: line",
            """data: {"line":"hello","stream":"stdout"}""",
            "",
            "event: result",
            """data: {"exit_code":7}""",
        ) { streamed.append(it) }

        assertEquals("hello", result.output)
        assertEquals("hello\n", streamed.toString())
        assertEquals(7, result.exitCode)
        assertEquals(null, result.errorMessage)
    }

    @Test
    fun backgroundedEventIsTerminalAndKeepsStreamedOutput() = runTest {
        val streamed = StringBuilder()
        val result = parse(
            "event: line",
            """data: {"line":"half","stream":"stdout"}""",
            "event: backgrounded",
            """data: {"job_id":"job-42"}""",
        ) { streamed.append(it) }

        assertEquals("half", result.output)
        assertEquals("half\n", streamed.toString())
        assertEquals("job-42", result.backgroundedJobId)
        assertEquals(null, result.exitCode)
        assertEquals(null, result.errorMessage)
    }

    @Test
    fun backgroundedWithoutJobIdAndPostTerminalFramesFailClosed() = runTest {
        assertParseFails(
            "event: backgrounded",
            """data: {}""",
        )
        assertParseFails(
            "event: backgrounded",
            """data: {"job_id":"job-42"}""",
            "event: line",
            """data: {"line":"late","stream":"stdout"}""",
        )
        assertParseFails(
            "event: backgrounded",
            """data: {"job_id":"job-42"}""",
            "event: result",
            """data: {"exit_code":0}""",
        )
    }

    @Test
    fun eofWithoutTerminalFailsClosed() = runTest {
        assertParseFails(
            "event: line",
            """data: {"line":"partial","stream":"stdout"}""",
        )
    }

    @Test
    fun malformedUnknownAndUnpairedEventsFailClosed() = runTest {
        assertParseFails(
            "event: line",
            "data: not-json",
            "event: result",
            """data: {"exit_code":0}""",
        )
        assertParseFails(
            "event: future",
            """data: {"value":1}""",
            "event: result",
            """data: {"exit_code":0}""",
        )
        assertParseFails(
            "event: line",
            "event: result",
            """data: {"exit_code":0}""",
        )
        assertParseFails(
            """data: {"exit_code":0}""",
        )
        assertParseFails("not-sse")
    }

    @Test
    fun invalidOutputStreamAndMissingRequiredFieldsFailClosed() = runTest {
        assertParseFails(
            "event: line",
            """data: {"line":"hello","stream":"future"}""",
            "event: result",
            """data: {"exit_code":0}""",
        )
        assertParseFails(
            "event: result",
            """data: {}""",
        )
        assertParseFails(
            "event: error",
            """data: {"timed_out":true}""",
        )
        assertParseFails(
            "event: warning",
            """data: {}""",
            "event: result",
            """data: {"exit_code":0}""",
        )
    }

    @Test
    fun duplicateTerminalAndPostTerminalDataFailClosed() = runTest {
        assertParseFails(
            "event: result",
            """data: {"exit_code":0}""",
            "event: result",
            """data: {"exit_code":1}""",
        )
        assertParseFails(
            "event: result",
            """data: {"exit_code":0}""",
            "event: line",
            """data: {"line":"late","stream":"stderr"}""",
        )
    }

    @Test
    fun retainedAndStreamedOutputAreBoundedAndReported() = runTest {
        val line = "x".repeat((1 shl 20) + 16)
        val streamed = StringBuilder()
        val result = parse(
            "event: line",
            """data: {"line":"$line","stream":"stdout"}""",
            "event: result",
            """data: {"exit_code":0}""",
        ) { streamed.append(it) }

        assertTrue(result.output.toByteArray().size <= (1 shl 20))
        assertTrue(streamed.toString().toByteArray().size <= (1 shl 20))
        assertNotNull(result.warningMessage)
        assertTrue(result.warningMessage!!.contains("truncated", ignoreCase = true))
    }

    @Test
    fun cancellationIsNeverConvertedIntoAResult() = runTest {
        try {
            parseConchSseLines(
                encrypted = false,
                readLine = { throw CancellationException("stop") },
                decrypt = { it },
                onOutput = {},
            )
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    private suspend fun parse(
        vararg lines: String,
        onOutput: suspend (String) -> Unit = {},
    ): ConchSseResult {
        val iterator = lines.iterator()
        return parseConchSseLines(
            encrypted = false,
            readLine = { if (iterator.hasNext()) iterator.next() else null },
            decrypt = { it },
            onOutput = onOutput,
        )
    }

    private suspend fun assertParseFails(vararg lines: String) {
        try {
            parse(*lines)
            fail("Expected strict SSE parse failure")
        } catch (_: IllegalStateException) {
        }
    }
}
