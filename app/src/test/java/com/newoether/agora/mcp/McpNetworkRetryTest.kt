package com.newoether.agora.mcp

import com.newoether.agora.data.McpTransportType
import com.newoether.agora.util.NetworkHttpException
import com.newoether.agora.util.NetworkRequestProgress
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.EOFException
import java.net.ConnectException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class McpNetworkRetryTest {
    private lateinit var transport: McpClientTransport
    private lateinit var client: McpProtocolClient
    private val attempts = mutableMapOf<String, Int>()
    private var respond: (String, Int, NetworkRequestProgress?) -> Unit = { _, _, _ -> }

    @Before
    fun setup() {
        transport = mockk(relaxed = true)
        mockkStatic("com.newoether.agora.mcp.McpClientTransportKt")
        every { createMcpClientTransport(any(), any(), any(), any()) } returns transport
        coEvery { transport.ensureReady() } returns 0L
        coEvery { transport.send(any(), any(), any(), any()) } answers {
            val envelope = firstArg<JsonObject>()
            val method = envelope.getValue("method").jsonPrimitive.content
            val count = attempts.getOrDefault(method, 0) + 1
            attempts[method] = count
            respond(method, count, arg(3))
            if (method == "notifications/initialized") null else buildJsonObject {
                put("id", envelope.getValue("id"))
                put("result", buildJsonObject {
                    when (method) {
                        "initialize" -> put("protocolVersion", "2025-11-25")
                        "tools/list" -> put("tools", kotlinx.serialization.json.JsonArray(emptyList()))
                        "tools/call" -> put("content", kotlinx.serialization.json.JsonArray(emptyList()))
                    }
                })
            }
        }
        client = McpProtocolClient("https://example.invalid/mcp", emptyMap(), McpTransportType.STREAMABLE_HTTP)
    }

    @After
    fun cleanup() {
        client.close()
        unmockkStatic("com.newoether.agora.mcp.McpClientTransportKt")
    }

    @Test
    fun initializationAndNotificationRecoverOnTheSixthAttempt() = runTest {
        respond = { method, count, _ ->
            if (method != "tools/list" && count < 6) throw EOFException()
        }
        assertTrue(client.listTools().isEmpty())
        assertEquals(6, attempts["initialize"])
        assertEquals(6, attempts["notifications/initialized"])
        assertEquals(1, attempts["tools/list"])
    }

    @Test
    fun listExhaustsFiveRetriesBeforeExposingFailure() = runTest {
        respond = { method, _, _ -> if (method == "tools/list") throw EOFException() }
        try {
            client.listTools()
            fail("Expected exhaustion")
        } catch (_: EOFException) { }
        assertEquals(6, attempts["tools/list"])
    }

    @Test
    fun transientHttpUsesTheSameBudgetAndPermanentStatusDoesNotRetry() = runTest {
        respond = { method, _, _ -> if (method == "tools/list") throw NetworkHttpException(503, "120", "Busy") }
        val start = testScheduler.currentTime
        try { client.listTools(); fail("Expected exhaustion") } catch (_: NetworkHttpException) { }
        assertEquals(6, attempts["tools/list"])
        assertEquals(600_000L, testScheduler.currentTime - start)
        attempts.clear()
        respond = { method, _, _ -> if (method == "tools/list") throw NetworkHttpException(401, null, "Socket is closed") }
        try { client.listTools(); fail("Expected authentication error") } catch (_: NetworkHttpException) { }
        assertEquals(1, attempts["tools/list"])
    }

    @Test
    fun toolCallNeverReplaysAnUncertainOrAdmittedRequest() = runTest {
        listOf(false, true).forEach { admitted ->
            attempts.clear()
            respond = { method, _, progress ->
                if (method == "tools/call") {
                    progress!!.started = admitted
                    if (admitted) throw ConnectException() else throw EOFException()
                }
            }
            try { client.callTool("edit", buildJsonObject {}); fail("Expected failure") }
            catch (_: java.io.IOException) { }
            assertEquals(1, attempts["tools/call"])
        }
    }

    @Test
    fun toolCallCanRecoverBeforeAnyHeadersWereSent() = runTest {
        respond = { method, count, _ -> if (method == "tools/call" && count < 6) throw ConnectException() }
        client.callTool("edit", buildJsonObject {})
        assertEquals(6, attempts["tools/call"])
    }

    @Test
    fun nextCallAfterResponseFailureDoesNotReplayOrRefreshTheCatalog() = runTest {
        respond = { method, count, progress ->
            if (method == "tools/call" && count == 1) {
                progress!!.started = true
                throw EOFException("unexpected end of stream")
            }
        }
        client.listTools()
        try { client.callTool("first", buildJsonObject {}); fail("Expected failure") }
        catch (_: EOFException) { }
        assertEquals(1, attempts["tools/call"])

        client.callTool("next", buildJsonObject {})
        assertEquals(2, attempts["tools/call"])
        assertEquals(1, attempts["initialize"])
        assertEquals(1, attempts["tools/list"])
        io.mockk.verify(exactly = 0) { transport.resetSession() }
    }

    @Test
    fun expiredToolSessionIsResetForTheNextCallWithoutReplayingTheFailedCall() = runTest {
        var generation = 0L
        coEvery { transport.ensureReady() } answers { generation }
        every { transport.resetSession() } answers { generation++; Unit }
        respond = { method, count, progress ->
            if (method == "tools/call" && count == 1) {
                progress!!.started = true
                throw McpSessionExpiredException()
            }
        }
        client.listTools()
        try { client.callTool("first", buildJsonObject {}); fail("Expected expiry") }
        catch (_: McpSessionExpiredException) { }
        assertEquals(1, attempts["tools/call"])
        assertEquals(1, attempts["initialize"])

        client.callTool("next", buildJsonObject {})
        assertEquals(2, attempts["tools/call"])
        assertEquals(2, attempts["initialize"])
        assertEquals(2, attempts["notifications/initialized"])
        assertEquals(1, attempts["tools/list"])
        io.mockk.verify(exactly = 1) { transport.resetSession() }
    }

    @Test
    fun cancellationDuringBackoffPreventsAnotherDispatch() = runTest {
        respond = { _, _, _ -> throw EOFException() }
        val job = async { client.listTools() }
        runCurrent()
        assertEquals(1, attempts["initialize"])
        job.cancelAndJoin()
        assertEquals(1, attempts["initialize"])
    }
}
