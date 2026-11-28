package com.newoether.agora.api

import android.util.Log
import com.newoether.agora.diagnostics.DiagnosticRequestContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpClientRequestTraceTest {
    @Before
    fun stubAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
    }

    @After
    fun restoreAndroidLog() {
        unmockkStatic(Log::class)
    }

    @Test
    fun childTracePropagatesAllCallbacksAndCorrelation() = runTest {
        val recorded = RecordedCallbacks()
        val base = DiagnosticRequestContext(
            requestId = "request-id",
            conversationIdHash = "conversation-hash",
            runId = "run-id",
            pass = 3,
            provider = "provider",
            model = "model",
            requestKind = "chat",
        )
        val child = recorded.trace(base).child("tool_continuation", "provider-1")
        val headers = mapOf("X-Fixture" to "fixture")
        val event = StreamEvent.TextChunk("parsed fixture")
        HttpClient.withStreamScope(null, child) {
            val handle = HttpClient.streamPost(
                url = FIXTURE_URL,
                jsonBody = "{\"fixture\":true}",
                headers = headers,
                scope = null,
                callClient = fixtureCalls("data: fixture\nresponse body", 400),
            )
            try {
                assertEquals("data: fixture", handle.readLine())
                assertEquals("response body", handle.errorBody)
                child.recordParsedEvent(event)
            } finally {
                handle.close()
            }
        }

        val expected = base.copy(
            requestId = "request-id:provider-1:http-1",
            requestKind = "tool_continuation",
        )
        assertEquals(listOf(expected), recorded.requests.map { it.context })
        val request = recorded.requests.single()
        assertEquals("POST", request.method)
        assertEquals(FIXTURE_URL, request.url)
        assertEquals(headers, request.headers)
        assertEquals("{\"fixture\":true}", request.body)
        assertEquals(listOf(Triple(expected, 400, "response body")), recorded.responses)
        assertEquals(listOf(Triple(expected, 1L, "data: fixture")), recorded.lines)
        assertEquals(listOf(expected to event), recorded.events)
        assertSame(event, recorded.events.single().second)
        assertEquals(
            listOf("http_execute", "response_headers", "first_wire_line"),
            recorded.stages.map { it.stage },
        )
        assertTrue(recorded.stages.all { it.context == expected && it.elapsedMillis >= 0L })
        assertEquals("code=400", recorded.stages[1].detail)
        assertNull(HttpClient.boundRequestTrace())
    }

    @Test
    fun overlappingStreamsKeepCapturedExchangeContexts() = runTest {
        val recorded = RecordedCallbacks()
        val base = DiagnosticRequestContext(requestId = "request-id", requestKind = "chat")
        val trace = recorded.trace(base)
        HttpClient.withStreamScope(null, trace) {
            val calls = fixtureCalls("data: fixture\nresponse body", 400)
            val first = HttpClient.streamPost(FIXTURE_URL, "{}", scope = null, callClient = calls)
            try {
                val second = HttpClient.streamPost(FIXTURE_URL, "{}", scope = null, callClient = calls)
                try {
                    assertEquals("data: fixture", first.readLine())
                    assertEquals("data: fixture", second.readLine())
                    assertEquals("response body", first.errorBody)
                    assertEquals("response body", second.errorBody)
                    trace.recordParsedEvent(StreamEvent.TextChunk("current exchange"))
                } finally {
                    second.close()
                }
            } finally {
                first.close()
            }
        }

        val expected = listOf(
            base.copy(requestId = "request-id:http-1"),
            base.copy(requestId = "request-id:http-2"),
        )
        assertEquals(expected, recorded.requests.map { it.context })
        assertEquals(expected, recorded.lines.map { it.first })
        assertEquals(listOf(1L, 1L), recorded.lines.map { it.second })
        assertEquals(expected, recorded.responses.map { it.first })
        assertEquals(expected.last(), recorded.events.single().first)
        assertEquals(expected, recorded.stages.filter { it.stage == "http_execute" }.map { it.context })
        assertEquals(expected, recorded.stages.filter { it.stage == "response_headers" }.map { it.context })
        // Stage events retain the trace's current context; stream payloads retain their own exchange.
        assertEquals(expected.last(), recorded.stages.single { it.stage == "first_wire_line" }.context)
    }

    @Test
    fun stagesDeduplicateWithinEachExchange() {
        val recorded = RecordedCallbacks()
        val trace = recorded.trace(DiagnosticRequestContext(requestId = "request-id"))
        val first = trace.beginHttpExchange()
        trace.mark("fixture", "bytes=1")
        trace.mark("fixture", "bytes=2")
        val second = trace.beginHttpExchange()
        trace.mark("fixture", "bytes=3")
        trace.mark("fixture", "bytes=4")

        assertEquals(listOf(first, second), recorded.stages.map { it.context })
        assertEquals(listOf("bytes=1", "bytes=3"), recorded.stages.map { it.detail })
    }

    @Test
    fun defaultCallbacksAllowTracesWithoutDiagnosticCapture() = runTest {
        val trace = HttpClient.RequestTrace("request-id", "chat")
        assertNull(trace.beginHttpExchange())
        HttpClient.withStreamScope(null, trace) {
            val handle = HttpClient.streamPost(
                FIXTURE_URL, "{}", scope = null,
                callClient = fixtureCalls("data: fixture\nresponse body", 400),
            )
            try {
                assertEquals("data: fixture", handle.readLine())
                assertEquals("response body", handle.errorBody)
                trace.recordParsedEvent(StreamEvent.TextChunk("fixture"))
            } finally {
                handle.close()
            }
        }
    }

    private class RecordedCallbacks {
        val stages = mutableListOf<Stage>()
        val requests = mutableListOf<RequestRecord>()
        val responses = mutableListOf<Triple<DiagnosticRequestContext?, Int, String>>()
        val lines = mutableListOf<Triple<DiagnosticRequestContext?, Long, String>>()
        val events = mutableListOf<Pair<DiagnosticRequestContext?, StreamEvent>>()

        fun trace(context: DiagnosticRequestContext) = HttpClient.RequestTrace(
            requestId = "request-id",
            origin = "chat",
            diagnosticContext = context,
            onStage = { value, stage, elapsed, detail -> stages += Stage(value, stage, elapsed, detail) },
            onRequest = { value, method, url, headers, body ->
                requests += RequestRecord(value, method, url, headers, body)
            },
            onResponseBody = { value, code, body -> responses += Triple(value, code, body) },
            onWireLine = { value, number, line -> lines += Triple(value, number, line) },
            onParsedEvent = { value, event -> events += value to event },
        )
    }

    private data class Stage(
        val context: DiagnosticRequestContext?,
        val stage: String,
        val elapsedMillis: Long,
        val detail: String,
    )

    private data class RequestRecord(
        val context: DiagnosticRequestContext?,
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun fixtureCalls(body: String, code: Int): Call.Factory {
        val factory = mockk<Call.Factory>()
        every { factory.newCall(any()) } answers {
            val request = firstArg<Request>()
            val call = mockk<Call>(relaxed = true)
            every { call.execute() } returns Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Fixture response")
                .body(body.toResponseBody())
                .build()
            call
        }
        return factory
    }

    companion object {
        private const val FIXTURE_URL = "https://example.invalid/fixture"
    }
}
