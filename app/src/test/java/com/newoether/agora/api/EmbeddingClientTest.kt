package com.newoether.agora.api

import com.newoether.agora.viewmodel.StreamScope
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import com.newoether.agora.util.DebugLog
import io.mockk.*

class EmbeddingClientTest {
    @Before
    fun logging() {
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any()) } just Runs
        every { DebugLog.d(any(), any()) } just Runs
        every { DebugLog.w(any(), any()) } just Runs
    }

    @After
    fun cleanup() = unmockkObject(DebugLog)

    @Test
    fun indexedResponseIsMappedToInputsAndSingleRequestKeepsStringShape() = runBlocking {
        withServer("""{"data":[{"index":1,"embedding":[0,1]},{"index":0,"embedding":[1,0]}]}""") { url, body, calls ->
            val result = EmbeddingClient.computeEmbeddings(listOf("first", "second"), "", baseUrl = "$url/")
            assertArrayEquals(floatArrayOf(1f, 0f), result[0], 0f)
            assertArrayEquals(floatArrayOf(0f, 1f), result[1], 0f)
            assertTrue(body.await().contains("\"input\":[\"first\",\"second\"]"))
            assertEquals(1, calls.get())
        }
        withServer("""{"data":[{"index":0,"embedding":[0.5,1]}]}""") { url, body, _ ->
            assertArrayEquals(floatArrayOf(0.5f, 1f),
                EmbeddingClient.computeEmbedding("one", "", baseUrl = url), 0f)
            assertTrue(body.await().contains("\"input\":\"one\""))
        }
    }

    @Test
    fun compatibleOrderedResponseCanOmitAllIndices() = runBlocking {
        withServer("""{"data":[{"embedding":[1,0]},{"embedding":[0,1]}]}""") { url, _, _ ->
            val result = EmbeddingClient.computeEmbeddings(listOf("a", "b"), "", baseUrl = url)
            assertArrayEquals(floatArrayOf(1f, 0f), result[0], 0f)
            assertArrayEquals(floatArrayOf(0f, 1f), result[1], 0f)
        }
    }

    @Test
    fun malformedOrAmbiguousBatchesNeverReturnPartiallyMappedVectors() = runBlocking {
        val rows = listOf(
            """{"index":0,"embedding":[1]},{"index":0,"embedding":[2]}""",
            """{"index":0,"embedding":[1]},{"index":2,"embedding":[2]}""",
            """{"index":0,"embedding":[1]},{"embedding":[2]}""",
            """{"index":0,"embedding":[]},{"index":1,"embedding":[2]}""",
            """{"index":0,"embedding":[1]},{"index":1,"embedding":[2,3]}""",
            """{"index":0,"embedding":[1e100]},{"index":1,"embedding":[2]}""",
            """{"index":0,"embedding":[1]}""",
        )
        for (row in rows) {
            withServer("{\"data\":[$row]}") { url, _, calls ->
                assertEquals(listOf(null, null),
                    EmbeddingClient.computeEmbeddings(listOf("a", "b"), "", baseUrl = url))
                assertEquals(1, calls.get())
            }
        }
        assertTrue(EmbeddingClient.computeEmbeddings(emptyList(), "", baseUrl = "invalid").isEmpty())
    }

    @Test
    fun cancellingBlockedRequestClosesSocketWithoutRetryOrCompletingAsNull() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = CountDownLatch(1)
            val disconnected = CountDownLatch(1)
            val reader = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    accepted.countDown()
                    while (socket.getInputStream().read() != -1) { /* Drain the request. */ }
                    disconnected.countDown()
                }
            }
            var completed = false
            val request = launch(Dispatchers.Default) {
                HttpClient.withStreamScope(StreamScope()) {
                    EmbeddingClient.computeEmbedding("cancel", "", baseUrl = "http://127.0.0.1:${server.localPort}")
                    completed = true
                }
            }
            assertTrue(withContext(Dispatchers.IO) { accepted.await(5, TimeUnit.SECONDS) })
            withTimeout(5_000) { request.cancelAndJoin() }
            assertTrue(withContext(Dispatchers.IO) { disconnected.await(5, TimeUnit.SECONDS) })
            assertFalse(completed)
            reader.join(1_000)
            assertFalse(reader.isAlive)
        }
    }

    @Test
    fun cancellingParentScopeStopsOnlyItsRequestAndDoesNotRetry() = runBlocking {
        withBlockedServer { firstUrl, firstAccepted, _, firstCalls ->
            withBlockedServer { secondUrl, secondAccepted, releaseSecond, secondCalls ->
                val firstScope = StreamScope()
                val secondScope = StreamScope()
                val first = async(Dispatchers.Default) {
                    HttpClient.withStreamScope(firstScope) {
                        EmbeddingClient.computeEmbedding("first", "", baseUrl = firstUrl)
                    }
                }
                val second = async(Dispatchers.Default) {
                    HttpClient.withStreamScope(secondScope) {
                        EmbeddingClient.computeEmbedding("second", "", baseUrl = secondUrl)
                    }
                }
                try {
                    withTimeout(5_000) { firstAccepted.await(); secondAccepted.await() }
                    firstScope.cancelAll()
                    try {
                        withTimeout(5_000) { first.await() }
                        fail("Scope cancellation must propagate")
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        if (cancelled is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                    }
                    assertFalse(second.isCompleted)
                    releaseSecond.countDown()
                    assertArrayEquals(floatArrayOf(1f, 0f), withTimeout(5_000) { second.await() }, 0f)
                    assertEquals(1, firstCalls.get())
                    assertEquals(1, secondCalls.get())
                } finally {
                    first.cancel()
                    second.cancel()
                }
            }
        }
    }

    private suspend fun withBlockedServer(
        block: suspend (String, CompletableDeferred<Unit>, CountDownLatch, AtomicInteger) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val accepted = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        server.createContext("/embeddings") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            accepted.complete(Unit)
            try {
                check(release.await(10, TimeUnit.SECONDS)) { "Test did not release response" }
                val bytes = """{"data":[{"index":0,"embedding":[1,0]}]}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (_: java.io.IOException) {
                // The cancelled client's socket is expected to be closed.
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", accepted, release, calls)
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    private suspend fun withServer(
        response: String,
        block: suspend (String, CompletableDeferred<String>, AtomicInteger) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val body = CompletableDeferred<String>()
        val calls = AtomicInteger()
        server.createContext("/embeddings") { exchange ->
            calls.incrementAndGet()
            body.complete(exchange.requestBody.bufferedReader().use { it.readText() })
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", body, calls)
        } finally {
            server.stop(0)
        }
    }
}
