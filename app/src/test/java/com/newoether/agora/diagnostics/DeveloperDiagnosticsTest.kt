package com.newoether.agora.diagnostics

import android.util.Log
import com.newoether.agora.api.StreamEvent
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeveloperDiagnosticsTest {
    @Before
    fun stubAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
    }

    @After
    fun resetDiagnostics() = runBlocking {
        try {
            DeveloperDiagnostics.disableAndClear()
            Unit
        } finally {
            unmockkStatic(Log::class)
        }
    }

    @Test
    fun `request context is not created while capture is disabled`() {
        assertNull(requestContext())
    }

    @Test
    fun `capture correlates request identity without retaining raw conversation id`() {
        runBlocking { DeveloperDiagnostics.startCapture() }

        val context = checkNotNull(requestContext())
        DeveloperDiagnostics.recordHttpStage(
            context = context,
            stage = "response_headers",
            elapsedMillis = 42L,
            detail = "code=200 authorization=secret endpoint=/private",
        )

        val snapshot = runBlocking { DeveloperDiagnostics.flush() }
        val event = snapshot.events.single()
        val stage = event.payload as DiagnosticEventPayload.HttpStage
        assertTrue(snapshot.isCaptureActive)
        assertEquals("request-id", event.context.requestId)
        assertEquals("run-id", event.context.runId)
        assertEquals(3, event.context.pass)
        assertEquals("provider", event.context.provider)
        assertEquals("provider:model", event.context.model)
        assertEquals("send", event.context.requestKind)
        assertEquals(24, event.context.conversationIdHash?.length)
        assertFalse(event.context.conversationIdHash.orEmpty().contains(CONVERSATION_ID))
        assertEquals("response_headers", stage.stage)
        assertEquals(42L, stage.elapsedMillis)
        assertEquals(mapOf("code" to "200"), stage.attributes)
    }

    @Test
    fun `raw capture correlates request wire and parsed views while removing credentials`() {
        runBlocking { DeveloperDiagnostics.startCapture() }
        val context = checkNotNull(requestContext())
        DeveloperDiagnostics.recordHttpRequest(
            context = context,
            method = "POST",
            url = "https://example.com/chat?key=query-secret",
            headers = mapOf("Authorization" to "Bearer header-secret"),
            body = """{"content":"private request","api_key":"header-secret"}""",
        )
        DeveloperDiagnostics.recordWireLine(
            context = context,
            lineNumber = 1L,
            line = """data: {"text":"private response"}""",
        )
        DeveloperDiagnostics.recordParsedStreamEvent(
            context,
            StreamEvent.TextChunk("private response"),
        )

        val events = runBlocking { DeveloperDiagnostics.flush() }.events
        assertEquals(3, events.size)
        val retained = events.joinToString()
        assertFalse(retained.contains("query-secret"))
        assertFalse(retained.contains("header-secret"))
        assertTrue(retained.contains("private request"))
        assertTrue(retained.contains("private response"))
        assertTrue(retained.contains("[REDACTED_SECRET]"))
        assertFalse(retained.contains("[REDACTED_CONTENT]"))
    }

    @Test
    fun `parsed semantic content is retained after credential token sanitization`() {
        runBlocking { DeveloperDiagnostics.startCapture() }
        val context = checkNotNull(requestContext())

        DeveloperDiagnostics.recordParsedStreamEvent(
            context,
            StreamEvent.TextChunk("visible text sk-abcdefghijklmnop"),
        )

        val parsed = runBlocking { DeveloperDiagnostics.flush() }.events.single()
            .payload as DiagnosticEventPayload.ParsedStreamEvent
        assertTrue(parsed.content?.value.orEmpty().contains("visible text"))
        assertFalse(parsed.content?.value.orEmpty().contains("sk-abcdefghijklmnop"))
        assertTrue(parsed.content?.value.orEmpty().contains("[REDACTED_SECRET]"))
    }

    @Test
    fun `paused capture ignores events from an existing request context`() {
        runBlocking { DeveloperDiagnostics.startCapture() }
        val context = checkNotNull(requestContext())
        runBlocking { DeveloperDiagnostics.pauseCapture() }

        DeveloperDiagnostics.recordParsedStreamEvent(
            context,
            StreamEvent.TextChunk("must not be retained"),
        )

        val snapshot = runBlocking { DeveloperDiagnostics.flush() }
        assertFalse(snapshot.isCaptureActive)
        assertTrue(snapshot.events.isEmpty())
    }

    @Test
    fun `child request trace keeps correlation and marks tool continuation`() {
        runBlocking { DeveloperDiagnostics.startCapture() }
        val context = checkNotNull(requestContext())
        val trace = DeveloperDiagnostics.newRequestTrace(
            requestId = "request-id",
            origin = "chat",
            diagnosticContext = context,
        ).child(
            requestKind = "tool_continuation",
            requestIdSuffix = "provider-1",
        )

        trace.recordParsedEvent(StreamEvent.TextChunk("continued"))

        val event = runBlocking { DeveloperDiagnostics.flush() }.events.single()
        assertEquals("request-id:provider-1", event.context.requestId)
        assertEquals(context.conversationIdHash, event.context.conversationIdHash)
        assertEquals("run-id", event.context.runId)
        assertEquals(3, event.context.pass)
        assertEquals("tool_continuation", event.context.requestKind)
    }

    @Test
    fun `trace factory keeps live capture checks and redaction after pause and resume`() {
        runBlocking { DeveloperDiagnostics.startCapture() }
        val context = checkNotNull(requestContext())
        val trace = DeveloperDiagnostics.newRequestTrace("request-id", "chat", context)
        runBlocking { DeveloperDiagnostics.pauseCapture() }

        trace.mark("paused", "code=200")
        trace.recordParsedEvent(StreamEvent.TextChunk("must not be retained"))
        assertTrue(runBlocking { DeveloperDiagnostics.flush() }.events.isEmpty())

        runBlocking { DeveloperDiagnostics.startCapture() }
        trace.mark("resumed", "code=200 authorization=secret")
        trace.recordParsedEvent(StreamEvent.TextChunk("visible sk-abcdefghijklmnop"))

        val events = runBlocking { DeveloperDiagnostics.flush() }.events
        assertEquals(2, events.size)
        val stage = events[0].payload as DiagnosticEventPayload.HttpStage
        assertEquals("resumed", stage.stage)
        assertEquals(mapOf("code" to "200"), stage.attributes)
        val parsed = events[1].payload as DiagnosticEventPayload.ParsedStreamEvent
        assertTrue(parsed.content?.value.orEmpty().contains("visible"))
        assertFalse(parsed.content?.value.orEmpty().contains("sk-abcdefghijklmnop"))
        assertTrue(parsed.content?.value.orEmpty().contains("[REDACTED_SECRET]"))
        assertTrue(events.all { it.context == context })
    }

    private fun requestContext() = DeveloperDiagnostics.newRequestContext(
        requestId = "request-id",
        conversationId = CONVERSATION_ID,
        runId = "run-id",
        pass = 3,
        provider = "provider",
        model = "provider:model",
        requestKind = "send",
    )

    companion object {
        private const val CONVERSATION_ID = "private-conversation-id"
        private lateinit var root: File
        private lateinit var scope: CoroutineScope

        @JvmStatic
        @BeforeClass
        fun initializeDiagnostics() {
            root = Files.createTempDirectory("agora-developer-diagnostics").toFile()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            runBlocking {
                DeveloperDiagnostics.initialize(root, scope)
                DeveloperDiagnostics.disableAndClear()
            }
        }

        @JvmStatic
        @AfterClass
        fun shutdownDiagnostics() {
            runBlocking { DeveloperDiagnostics.disableAndClear() }
            scope.cancel()
            root.deleteRecursively()
        }
    }
}
