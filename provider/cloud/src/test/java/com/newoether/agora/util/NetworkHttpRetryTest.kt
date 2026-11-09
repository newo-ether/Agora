package com.newoether.agora.util

import java.io.EOFException
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class NetworkHttpRetryTest {
    private val request = Request.Builder().url("https://example.invalid").build()
    private fun response(chain: Interceptor.Chain, code: Int) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .header("Retry-After", "120").body("complete".toResponseBody()).build()
    private val noWait: (Int, String?, () -> Boolean) -> Unit = { _, _, _ -> }

    @Test
    fun bodyReadFailuresDiscardPartialAttemptsAndRecover() {
        val attempts = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { response(it, 200) }.build()
        val retries = mutableListOf<Int>()
        val result = client.executeWithNetworkRetry(request, wait = { n, _, _ -> retries += n }) {
            if (attempts.incrementAndGet() < 6) throw EOFException()
            it.body.string()
        }
        assertEquals("complete", result)
        assertEquals(6, attempts.get())
        assertEquals(listOf(1, 2, 3, 4, 5), retries)
    }

    @Test
    fun transientHttpConsumesFiveSilentRetriesAndPreservesRetryAfter() {
        listOf(408, 425, 429, 500, 502, 503, 504, 529).forEach { code ->
            var attempts = 0
            val client = OkHttpClient.Builder().addInterceptor { attempts++; response(it, code) }.build()
            val retries = mutableListOf<Int>()
            val result = client.executeWithNetworkRetry(request, wait = { n, header, _ ->
                assertEquals("120", header)
                retries += n
            }) { it.code }
            assertEquals(code, result)
            assertEquals(6, attempts)
            assertEquals(listOf(1, 2, 3, 4, 5), retries)
        }
    }

    @Test
    fun permanentStatusCannotBecomeRetryableThroughBrokenBody() {
        listOf(400, 401, 403, 404, 422).forEach { code ->
            var attempts = 0
            val client = OkHttpClient.Builder().addInterceptor { attempts++; response(it, code) }.build()
            assertThrows(EOFException::class.java) {
                client.executeWithNetworkRetry(request, wait = noWait) { throw EOFException() }
            }
            assertEquals(1, attempts)
        }
    }

    @Test
    fun sideEffectsNeverReplayUncertainOutcomesOrHttpErrors() {
        var attempts = 0
        val disconnected = OkHttpClient.Builder().addInterceptor { attempts++; throw EOFException() }.build()
        assertThrows(EOFException::class.java) {
            disconnected.executeWithNetworkRetry(request, replaySafe = false, wait = noWait) { it.code }
        }
        assertEquals(1, attempts)
        val http = OkHttpClient.Builder().addInterceptor { attempts++; response(it, 503) }.build()
        assertEquals(503, http.executeWithNetworkRetry(request, replaySafe = false, wait = noWait) { it.code })
        assertEquals(2, attempts)
    }

    @Test
    fun preConnectFailureCanRetryButHeaderAdmissionFencesIt() {
        listOf(false, true).forEach { headersStarted ->
            var attempts = 0
            val client = OkHttpClient.Builder().addInterceptor {
                attempts++
                it.request().tag(NetworkRequestProgress::class.java)!!.started = headersStarted
                throw ConnectException()
            }.build()
            assertThrows(ConnectException::class.java) {
                client.executeWithNetworkRetry(request, replaySafe = false, wait = noWait) { it.code }
            }
            assertEquals(if (headersStarted) 1 else 6, attempts)
        }
    }

    @Test
    fun callTimeoutIsANetworkFailureRatherThanUserCancellation() {
        java.net.ServerSocket(0).use { server ->
            val sockets = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
            val worker = kotlin.concurrent.thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    sockets += socket
                }
            }
            try {
                var retries = 0
                assertThrows(java.io.InterruptedIOException::class.java) {
                    OkHttpClient().executeWithNetworkRetry(
                        Request.Builder().url("http://127.0.0.1:${server.localPort}").build(),
                        callTimeoutMillis = 100L,
                        wait = { _, _, _ -> retries++ },
                    ) { it.code }
                }
                assertEquals(5, retries)
                assertEquals(6, sockets.size)
            } finally {
                server.close()
                sockets.forEach { it.close() }
                worker.join(1_000)
            }
        }
    }

    @Test
    fun cancellationDuringBackoffNeverOpensAnotherCall() {
        var attempts = 0
        var stopped = false
        val client = OkHttpClient.Builder().addInterceptor { attempts++; throw EOFException() }.build()
        assertThrows(CancellationException::class.java) {
            client.executeWithNetworkRetry(request, cancelled = { stopped }, wait = { _, _, _ -> stopped = true }) { it.code }
        }
        assertEquals(1, attempts)
    }
}
