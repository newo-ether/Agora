package com.newoether.agora.tool

import com.newoether.agora.model.ToolCallData
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellDurableJobExecutorTest {
    private val executor = ShellDurableJobExecutor()

    @Test
    fun listRequestsBoundedPagesAndPreservesContinuationMetadata() = runTest {
        mockkConstructor(ConchBackend::class)
        try {
            val page = """{"jobs":[],"total":125,"next_offset":125,"has_more":false}"""
            coEvery { anyConstructed<ConchBackend>().listJobs(100, 25) } returns page
            val result = executor.listShellJobs(
                """{"server":"server","description":"Read older jobs","offset":100,"limit":25}""",
                GenerationContext(shellDevices = listOf(ShellDeviceConfig(name = "server", serverUrl = "http://localhost"))),
            )
            assertEquals(page, result)
            coVerify(exactly = 1) { anyConstructed<ConchBackend>().listJobs(100, 25) }
        } finally {
            unmockkConstructor(ConchBackend::class)
        }
    }

    @Test
    fun onlyConchTerminalStatesFinishPolling() {
        listOf("succeeded", "failed", "stopped", "interrupted").forEach { state ->
            assertTrue(executor.isTerminalJobPayload("""{"state":"$state"}"""))
        }
        assertFalse(executor.isTerminalJobPayload("""{"state":"running"}"""))
        assertFalse(executor.isTerminalJobPayload("""{"state":"stopping"}"""))
        assertFalse(executor.isTerminalJobPayload("""{"state":"settling","error":"process exited"}"""))
        assertFalse(
            executor.isTerminalJobPayload(
                """{"state":"settling","settlement_error":"sync pending"}""",
            ),
        )
    }

    @Test
    fun explicitErrorFinishesButMalformedPayloadDoesNot() {
        assertTrue(executor.isTerminalJobPayload("""{"error":"job not found"}"""))
        assertFalse(executor.isTerminalJobPayload(""))
        assertFalse(executor.isTerminalJobPayload("not-json"))
        assertFalse(executor.isTerminalJobPayload("{}"))
    }

    @Test
    fun durableOutputCursorEmitsEachUtf8ByteExactlyOnceAcrossGrowingSnapshots() {
        val cursor = ConchJobOutputCursor()
        assertEquals(
            ConchJobOutputUpdate("one\n", 0),
            cursor.consume(jobSnapshot("one\n")),
        )
        assertEquals(
            ConchJobOutputUpdate("中文\n", 0),
            cursor.consume(jobSnapshot("one\n中文\n")),
        )
        assertEquals(
            ConchJobOutputUpdate("", 0),
            cursor.consume(jobSnapshot("one\n中文\n")),
        )
        assertEquals(
            ConchJobOutputUpdate("last\n", 0),
            cursor.consume(jobSnapshot("one\n中文\nlast\n", state = "succeeded")),
        )
    }
    @Test
    fun durableOutputCursorReportsEvictedBytesAndContinuesFromRetainedUtf8Tail() {
        val cursor = ConchJobOutputCursor()
        val retained = "中文-tail\n"
        val raw = """{"state":"running","output":${jsonString(retained)},"output_bytes":30}"""
        val update = cursor.consume(raw)
        assertEquals(retained, update.delta)
        assertEquals(30L - retained.toByteArray(Charsets.UTF_8).size, update.lostBytes)
        assertEquals(ConchJobOutputUpdate("", 0), cursor.consume(raw))
    }
    @Test
    fun durableOutputCursorIgnoresMalformedAndRegressiveSnapshotsWithoutReplaying() {
        val cursor = ConchJobOutputCursor()
        assertEquals(ConchJobOutputUpdate("", 0), cursor.consume("not-json"))
        assertEquals(ConchJobOutputUpdate("abc", 0), cursor.consume(jobSnapshot("abc")))
        assertEquals(
            ConchJobOutputUpdate("", 0),
            cursor.consume("""{"output":"ab","output_bytes":2}"""),
        )
    }
    @Test
    fun durableOutputSnapshotPreservesBoundedFullUnicodeOutputAndMetadata() {
        val raw = """{"state":"running","output":"中文 😀","output_bytes":42,"truncated":true}"""
        assertEquals(
            ConchJobOutputSnapshot(
                text = "中文 😀",
                outputBytes = 42,
                truncated = true,
            ),
            conchJobOutputSnapshot(raw),
        )
        assertEquals(null, conchJobOutputSnapshot("not-json"))
    }

    @Test
    fun waitPublishesDistinctFullSnapshotsThenTimesOutWithoutStoppingTheJob() = runTest {
        var now = 0L
        val polls = ArrayDeque(
            listOf(
                jobSnapshot("one\n"),
                jobSnapshot("one\ntwo\n"),
                jobSnapshot("one\ntwo\n"),
            ),
        )
        val published = mutableListOf<String>()
        val result = executor.waitForShellJobPolling(
            jobId = "same-job",
            serverName = "server",
            timeoutMs = 1_000,
            poller = ShellJobPoller { polls.removeFirstOrNull() ?: jobSnapshot("one\ntwo\n") },
            onOutputSnapshot = published::add,
            nowMs = { now },
            delayMs = { delay -> now += delay },
        )
        val json = Json.parseToJsonElement(result).jsonObject

        assertEquals(listOf("one\n", "one\ntwo\n"), published)
        assertEquals("same-job", json.getValue("job_id").jsonPrimitive.content)
        assertEquals("running", json.getValue("state").jsonPrimitive.content)
        assertTrue(json.getValue("timed_out").jsonPrimitive.content.toBoolean())
        assertEquals("one\ntwo\n", json.getValue("output").jsonPrimitive.content)
    }

    @Test
    fun waitPollingPropagatesCancellationImmediately() = runTest {
        var attempts = 0
        try {
            executor.waitForShellJobPolling(
                jobId = "live-job",
                serverName = "server",
                timeoutMs = 60_000,
                poller = ShellJobPoller {
                    attempts++
                    throw CancellationException("stop")
                },
                delayMs = {},
            )
            throw AssertionError("Expected CancellationException")
        } catch (_: CancellationException) {
            assertEquals(1, attempts)
        }
    }

    @Test
    fun sustainedPollFailureRetainsTheLatestSnapshotAndDoesNotClaimTheJobStopped() = runTest {
        var polls = 0
        val result = executor.waitForShellJobPolling(
            jobId = "live-job",
            serverName = "server",
            timeoutMs = 60_000,
            poller = ShellJobPoller {
                if (polls++ == 0) jobSnapshot("latest 😀\n") else error("offline")
            },
            delayMs = {},
        )
        val json = Json.parseToJsonElement(result).jsonObject

        assertEquals("poll_failed", json.getValue("error").jsonPrimitive.content)
        assertEquals("unknown", json.getValue("state").jsonPrimitive.content)
        assertEquals("latest 😀\n", json.getValue("output").jsonPrimitive.content)
        assertTrue(json.getValue("note").jsonPrimitive.content.contains("not stopped"))
    }

    @Test
    fun committedTerminalShellResultsResolveAcknowledgementsAcrossEnvelopes() {
        val calls = listOf(
            ToolCallData(
                toolName = "execute_shell_command",
                arguments = """{"server":"primary"}""",
                result = """{"server":"primary","job_id":"one","result":{"state":"succeeded"}}""",
            ),
            ToolCallData(
                toolName = "get_shell_job",
                arguments = """{"server":"secondary","job_id":"two"}""",
                result = """{"type":"shell_job","job_id":"two","state":"failed"}""",
            ),
            ToolCallData(
                toolName = "wait_for_job",
                arguments = """{"server":"third","job_id":"three"}""",
                result = """{"job_id":"three","result":{"state":"interrupted"}}""",
            ),
        )

        assertEquals(
            listOf(
                TerminalShellJobAcknowledgement("primary", "one"),
                TerminalShellJobAcknowledgement("secondary", "two"),
                TerminalShellJobAcknowledgement("third", "three"),
            ),
            terminalShellJobAcknowledgements(calls),
        )
    }

    @Test
    fun runningMalformedAndUnrelatedResultsAreNeverAcknowledged() {
        val calls = listOf(
            ToolCallData(
                toolName = "execute_shell_command",
                arguments = "{}",
                result = """{"background":true,"job_id":"running","state":"running"}""",
            ),
            ToolCallData(
                toolName = "stop_shell_job",
                arguments = "{}",
                result = """{"job_id":"stopping","state":"stopping"}""",
            ),
            ToolCallData(
                toolName = "get_shell_job",
                arguments = "{}",
                result = "not-json",
            ),
        )

        assertTrue(terminalShellJobAcknowledgements(calls).isEmpty())
    }
    private fun jobSnapshot(output: String, state: String = "running"): String =
        """{"state":"$state","output":${jsonString(output)},"output_bytes":${output.toByteArray(Charsets.UTF_8).size}}"""
    private fun jsonString(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()
}
