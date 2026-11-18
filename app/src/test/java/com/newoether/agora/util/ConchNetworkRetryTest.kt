package com.newoether.agora.util

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the Conch stale-connection policy (issue #1): no replay when the server never
 * returned a response, never for timeouts or HTTP errors, so a command can never run twice.
 */
class ConchNetworkRetryTest {
    private enum class Behaviour {
        CLOSE_BEFORE_RESPONSE_THEN_ANSWER,
        CLOSE_BEFORE_RESPONSE_ALWAYS,
        ANSWER_ERROR,
        STALL,
    }

    private fun startFakeConch(behaviour: Behaviour): Pair<ServerSocket, AtomicInteger> {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val connections = AtomicInteger()
        thread(name = "fake-conch-${behaviour.name}", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    return@thread
                }
                val connectionIndex = connections.incrementAndGet()
                thread(isDaemon = true) {
                    socket.use {
                        runCatching { readCompleteRequest(it) }
                        val answer = behaviour == Behaviour.CLOSE_BEFORE_RESPONSE_THEN_ANSWER &&
                            connectionIndex >= 2 ||
                            behaviour == Behaviour.ANSWER_ERROR
                        when {
                            behaviour == Behaviour.STALL ->
                                CountDownLatch(1).await(10, TimeUnit.SECONDS)
                            !answer -> Unit
                            behaviour == Behaviour.ANSWER_ERROR -> respond(it, 500, "err")
                            else -> respond(it, 200, "ok")
                        }
                    }
                }
            }
        }
        return server to connections
    }

    private fun readCompleteRequest(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        require(requestLine.startsWith("POST ") || requestLine.startsWith("GET ")) { requestLine }
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0 &&
                line.substring(0, separator).equals("content-length", ignoreCase = true)
            ) {
                contentLength = line.substring(separator + 1).trim().toInt()
            }
        }
        var remaining = contentLength
        val buffer = CharArray(256)
        while (remaining > 0) {
            val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            remaining -= count
        }
    }

    private fun respond(socket: Socket, status: Int, body: String) {
        socket.getOutputStream().writer().use { writer ->
            writer.write(
                "HTTP/1.1 $status X\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body",
            )
            writer.flush()
        }
    }

    private fun respondKeepAlive(socket: Socket, body: String) {
        val bytes = body.toByteArray()
        socket.getOutputStream().run {
            write(
                (
                    "HTTP/1.1 200 X\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n\r\n"
                    ).toByteArray(),
            )
            write(bytes)
            flush()
        }
    }

    @Test
    fun readOnlyOwnerStopsAfterThreeRetriesButNeverReplaysMutations() = kotlinx.coroutines.test.runTest {
        listOf("/file/read", "/jobs/list", "/file/write", "/execute").forEach { path ->
            var envelopes = 0
            try {
                ConchNetwork.retryRead(path) {
                    envelopes++
                    throw java.io.EOFException()
                }
            } catch (_: java.io.EOFException) { }
            assertEquals(path, if (ConchNetwork.isReadOnly(path)) 4 else 1, envelopes)
        }
    }

    @Test
    fun brokenErrorBodyPreservesHttpStatusForTheOuterRetryOwner() {
        listOf(401, 503).forEach { status ->
            ServerSocket(0).use { server ->
                val worker = thread(isDaemon = true) {
                    server.accept().use { socket ->
                        readCompleteRequest(socket)
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $status X\r\nContent-Length: 100\r\nRetry-After: 120\r\nConnection: close\r\n\r\nx".toByteArray())
                            flush()
                        }
                    }
                }
                val failure = runCatching {
                    ConchNetwork.postTextResponse(
                        "http://127.0.0.1:${server.localPort}/file/read", "{}", emptyMap(), maxRetries = 0,
                    )
                }.exceptionOrNull()
                assertTrue(failure is NetworkHttpException)
                assertEquals(status, (failure as NetworkHttpException).statusCode)
                assertEquals("120", failure.retryAfter)
                assertEquals(status == 503, NetworkRetryPolicy.isTransient(failure))
                worker.join(1_000)
            }
        }
    }

    @Test
    fun connectionClosedAfterRequestIsNeverReplayed() {
        val (server, connections) = startFakeConch(Behaviour.CLOSE_BEFORE_RESPONSE_THEN_ANSWER)
        try {
            val failure = runCatching { ConchNetwork.postTextResponse(
                "http://127.0.0.1:${server.localPort}/jobs/list",
                "{}",
                emptyMap(),
                callTimeoutMillis = 10_000,
            ) }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals(1, connections.get())
        } finally {
            server.close()
        }
    }

    /** A missing response must end the call without replay. */
    @Test
    fun secondStaleFailureEndsCallWithoutFurtherAttempts() {
        val (server, connections) = startFakeConch(Behaviour.CLOSE_BEFORE_RESPONSE_ALWAYS)
        try {
            val failure = runCatching {
                ConchNetwork.postTextResponse(
                    "http://127.0.0.1:${server.localPort}/execute",
                    "{}",
                    emptyMap(),
                    callTimeoutMillis = 10_000,
                )
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals(1, connections.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun httpErrorResponseIsNeverReplayed() {
        val (server, connections) = startFakeConch(Behaviour.ANSWER_ERROR)
        try {
            val response = ConchNetwork.postTextResponse(
                "http://127.0.0.1:${server.localPort}/file/read",
                "{}",
                emptyMap(),
                callTimeoutMillis = 10_000,
            )
            assertEquals(500, response.code)
            assertEquals(1, connections.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun timeoutWithoutResponseIsNeverReplayed() {
        val (server, connections) = startFakeConch(Behaviour.STALL)
        try {
            val failure = runCatching {
                ConchNetwork.postTextResponse(
                    "http://127.0.0.1:${server.localPort}/execute",
                    "{}",
                    emptyMap(),
                    callTimeoutMillis = 500,
                )
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals(1, connections.get())
        } finally {
            server.close()
        }
    }

    /**
     * Answers the first request on every socket and keeps it alive, then closes the socket without
     * an answer as soon as it is reused. [holdUntilEmpty] parks every answer until the latch is
     * empty, which is how a test makes two requests hold two separate sockets at the same time.
     */
    private inner class ReuseTrap(private val holdUntilEmpty: CountDownLatch) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val accepted = AtomicInteger()
        val closedOnReuse = AtomicInteger()
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        return@thread
                    }
                    accepted.incrementAndGet()
                    thread(isDaemon = true) {
                        socket.use { served ->
                            var requests = 0
                            while (true) {
                                if (!runCatching { readCompleteRequest(served) }.isSuccess) return@use
                                requests++
                                if (requests >= 2) {
                                    closedOnReuse.incrementAndGet()
                                    return@use
                                }
                                holdUntilEmpty.countDown()
                                holdUntilEmpty.await(20, TimeUnit.SECONDS)
                                respondKeepAlive(served, "ok")
                            }
                        }
                    }
                }
            }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    /**
     * The Conch server closes idle sockets at its idle timeout, so a later request can land on a
     * socket that is already dead while other idle sockets are dead too. The failure must be
     * surfaced without opening another connection to replay the signed request.
     */
    @Test
    fun requestIsNotReplayedWhenIdleSocketsDieOnReuse() {
        val trap = ReuseTrap(CountDownLatch(2))
        try {
            fun post() = ConchNetwork.postTextResponse(
                "http://127.0.0.1:${trap.port}/jobs/list",
                "{}",
                emptyMap(),
                callTimeoutMillis = 20_000,
            )

            // Two parked requests force two pooled sockets to the same host.
            val start = CyclicBarrier(2)
            val seeded = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
            val seeders = List(2) {
                thread(isDaemon = true) {
                    runCatching { start.await(20, TimeUnit.SECONDS) }
                    seeded += runCatching { post().isSuccessful }.getOrDefault(false)
                }
            }
            seeders.forEach { it.join(25_000) }
            assertEquals(2, seeded.size)
            assertTrue(seeded.all { it })
            assertEquals(2, trap.accepted.get())

            // Every idle socket is now one request away from dying silently.
            val failure = runCatching { post() }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertTrue(trap.closedOnReuse.get() >= 1)
            assertEquals(2, trap.accepted.get())
        } finally {
            trap.close()
        }
    }

    @Test
    fun streamOpenNeverReplaysUnknownOutcome() {
        val (server, connections) = startFakeConch(Behaviour.CLOSE_BEFORE_RESPONSE_THEN_ANSWER)
        try {
            val request = Request.Builder()
                .url("http://127.0.0.1:${server.localPort}/execute")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            val failure = runCatching { ConchNetwork.streamCalls.newCall(request).execute() }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals(1, connections.get())
        } finally {
            server.close()
        }
    }
    @Test
    fun cancelledStreamCallNeverReplaysOrOpensAnotherConnection() {
        val (server, connections) = startFakeConch(Behaviour.STALL)
        try {
            val request = Request.Builder()
                .url("http://127.0.0.1:${server.localPort}/execute")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            val call = ConchNetwork.streamCalls.newCall(request)
            val failure = AtomicReference<Throwable?>()
            val worker = thread(isDaemon = true) {
                failure.set(runCatching { call.execute().close() }.exceptionOrNull())
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (connections.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1, connections.get())
            call.cancel()
            worker.join(5_000)
            assertTrue(!worker.isAlive)
            assertTrue(failure.get() is java.io.IOException)
            assertEquals(1, connections.get())
        } finally {
            server.close()
        }
    }
}
