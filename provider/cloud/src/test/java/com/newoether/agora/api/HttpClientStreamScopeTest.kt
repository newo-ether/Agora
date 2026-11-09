package com.newoether.agora.api

import com.newoether.agora.viewmodel.StreamScope
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException

class HttpClientStreamScopeTest {
    @Test
    fun errorBodyPreservesExactReadFailureThroughDirectWrappedAndTimeoutExceptions() {
        val message = "The server response could not be read."
        val failures = listOf(
            IOException(message),
            IOException("Outer response read failure", IllegalStateException(message)),
            SocketTimeoutException(message),
        )
        for (maxErrorBytes in listOf<Long?>(null, 1_024L)) {
            for (failure in failures) {
                val handle = streamHandleWithFailingBody(failure, maxErrorBytes)
                try {
                    assertEquals(message, handle.errorBody)
                } finally {
                    handle.close()
                }
            }
        }
    }

    @Test
    fun errorBodyKeepsOrdinaryReadFailuresNull() {
        val failures = listOf(
            IOException("Unrelated body read failure"),
            IOException("Outer failure", IllegalStateException("Invalid ordinary payload")),
        )
        for (maxErrorBytes in listOf<Long?>(null, 1_024L)) {
            for (failure in failures) {
                val handle = streamHandleWithFailingBody(failure, maxErrorBytes)
                try {
                    assertNull(handle.errorBody)
                } finally {
                    handle.close()
                }
            }
        }
    }

    @Test
    fun errorBodyPreservesTransientReadFailuresForProviderRetry() {
        val failures = listOf(
            SocketTimeoutException("Read timed out"),
            EOFException("unexpected end of stream"),
            IOException("socket is closed"),
        )
        for (maxErrorBytes in listOf<Long?>(null, 1_024L)) {
            for (failure in failures) {
                val handle = streamHandleWithFailingBody(failure, maxErrorBytes)
                try {
                    assertEquals(failure.message, handle.errorBody)
                } finally {
                    handle.close()
                }
            }
        }
    }

    @Test
    fun errorBodyPropagatesDirectCancellationEvenWhenItsMessageMatchesReadFailure() {
        for (maxErrorBytes in listOf<Long?>(null, 1_024L)) {
            val cancellation = CancellationException("The server response could not be read.")
            val handle = streamHandleWithFailingBody(cancellation, maxErrorBytes)
            try {
                try {
                    handle.errorBody
                    fail("Cancellation must propagate from the response body getter")
                } catch (actual: CancellationException) {
                    assertSame(cancellation, actual)
                }
            } finally {
                handle.close()
            }
        }
    }

    private fun streamHandleWithFailingBody(
        failure: Exception,
        maxErrorBytes: Long?,
    ): HttpClient.StreamHandle {
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw failure
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = source
        }
        val handle = HttpClient.StreamHandle(
            call = mockk(relaxed = true),
            scope = null,
            trace = null,
            diagnosticContext = null,
            maxErrorBytes = maxErrorBytes,
        )
        handle.attach(
            Response.Builder()
                .request(Request.Builder().url("https://example.invalid/fixture").build())
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Fixture error")
                .body(body)
                .build(),
        )
        return handle
    }

    @Test
    fun parallelCoroutines_keepIndependentStreamScopesAcrossSuspension() = runTest {
        val scopeA = StreamScope()
        val scopeB = StreamScope()
        val traceA = HttpClient.RequestTrace("request-a", "chat")
        val traceB = HttpClient.RequestTrace("request-b", "task")
        val aSuspended = CompletableDeferred<Unit>()
        val bObserved = CompletableDeferred<Unit>()
        var observedA: StreamScope? = null
        var observedB: StreamScope? = null
        var observedTraceA: HttpClient.RequestTrace? = null
        var observedTraceB: HttpClient.RequestTrace? = null

        val jobA = launch {
            HttpClient.withStreamScope(scopeA, traceA) {
                aSuspended.complete(Unit)
                bObserved.await()
                observedA = HttpClient.boundStreamScope()
                observedTraceA = HttpClient.boundRequestTrace()
            }
        }
        aSuspended.await()
        val jobB = launch {
            HttpClient.withStreamScope(scopeB, traceB) {
                observedB = HttpClient.boundStreamScope()
                observedTraceB = HttpClient.boundRequestTrace()
                bObserved.complete(Unit)
            }
        }

        jobA.join()
        jobB.join()

        assertSame(scopeA, observedA)
        assertSame(scopeB, observedB)
        assertSame(traceA, observedTraceA)
        assertSame(traceB, observedTraceB)
        assertNull(HttpClient.boundStreamScope())
        assertNull(HttpClient.boundRequestTrace())
    }
}
