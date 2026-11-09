package com.newoether.agora.util

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NetworkRetryPolicyTest {
    @Test
    fun classifiesTypesCausesAndFallbackMessages() {
        listOf(
            EOFException(), SocketException("Socket is closed"), SocketException("Software caused connection abort"),
            SocketTimeoutException(), ConnectException(), UnknownHostException(),
            IOException("Unexpected end of stream"), IOException("Broken pipe"),
            IOException("HTTP/2 stream was reset: REFUSED_STREAM"), SSLException("Connection reset"),
        ).forEach { failure ->
            assertTrue(failure.toString(), NetworkRetryPolicy.isTransient(failure))
            assertTrue(failure.toString(), NetworkRetryPolicy.isTransient(IllegalStateException("wrapper", failure)))
        }
        listOf(
            IOException("invalid JSON"), SSLHandshakeException("certificate rejected"),
            SSLException("Connection reset", CertificateException("expired")),
            IOException("Unexpected end of stream", CancellationException("stopped")),
            NetworkHttpException(401, null, "Socket is closed"),
        ).forEach { assertFalse(it.toString(), NetworkRetryPolicy.isTransient(it)) }
    }

    @Test
    fun causeCyclesAreBoundedAndSideEffectsRequirePreConnectFailure() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        assertEquals(2, NetworkRetryPolicy.causes(first).size)
        assertTrue(NetworkRetryPolicy.isBeforeSendFailure(ConnectException()))
        assertTrue(NetworkRetryPolicy.isBeforeSendFailure(UnknownHostException()))
        assertFalse(NetworkRetryPolicy.isBeforeSendFailure(EOFException()))
        assertFalse(NetworkRetryPolicy.isBeforeSendFailure(SocketTimeoutException()))
    }

    @Test
    fun sixAttemptsThenOneFailureAndFiveNotifications() = runTest {
        var attempts = 0
        val retries = mutableListOf<Int>()
        val failure = EOFException()
        try {
            NetworkRetryPolicy.retry(onRetry = { retries += it }) { attempts++; throw failure }
            fail("Expected exhaustion")
        } catch (actual: EOFException) {
            assertSame(failure, actual)
        }
        assertEquals(6, attempts)
        assertEquals(listOf(1, 2, 3, 4, 5), retries)
    }

    @Test
    fun permanentErrorsAndNestedCancellationNeverRetry() = runTest {
        listOf(IOException("bad request"), NetworkHttpException(403, null, "Forbidden")).forEach { failure ->
            var attempts = 0
            try {
                NetworkRetryPolicy.retry { attempts++; throw failure }
            } catch (actual: IOException) {
                assertSame(failure, actual)
            }
            assertEquals(1, attempts)
        }
        var attempts = 0
        try {
            NetworkRetryPolicy.retry {
                attempts++
                throw IOException("Unexpected end of stream", CancellationException("stopped"))
            }
        } catch (actual: CancellationException) {
            assertEquals("stopped", actual.message)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun cancellationDuringBackoffPreventsAnotherAttempt() = runTest {
        var attempts = 0
        val job = async { NetworkRetryPolicy.retry { attempts++; throw EOFException() } }
        runCurrent()
        assertEquals(1, attempts)
        job.cancelAndJoin()
        assertEquals(1, attempts)
    }

    @Test
    fun honorsSecondsAndHttpDateWithoutShorteningServerDelay() = runTest {
        assertEquals(120_000L, NetworkRetryPolicy.retryAfterMillis("120", 0))
        assertEquals(120_000L, NetworkRetryPolicy.retryAfterMillis("Thu, 01 Jan 1970 00:02:00 GMT", 0))
        assertEquals(0L, NetworkRetryPolicy.retryAfterMillis("-1", 0))
        assertNull(NetworkRetryPolicy.retryAfterMillis("bad header", 0))
        assertEquals(120_000L, NetworkRetryPolicy.delayMillis(1, "120", 0))
        var attempts = 0
        val start = testScheduler.currentTime
        val result = NetworkRetryPolicy.retry(retryAfter = { "120" }) {
            if (++attempts == 1) throw NetworkHttpException(429, "120", "Try again")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(120_000L, testScheduler.currentTime - start)
    }
}
