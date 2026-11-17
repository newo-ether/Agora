package com.newoether.agora.util

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class ConchNetworkTest {
    @Test fun onlyTransferReadsCanBeReplayed() {
        for (action in listOf("capabilities", "info", "read", "status")) {
            assertTrue(action, ConchNetwork.isReadOnly("/file/transfer/$action"))
        }
        for (action in listOf("begin", "chunk", "commit", "abort")) {
            assertFalse(action, ConchNetwork.isReadOnly("/file/transfer/$action"))
        }
    }

    @Test fun unsignedChunkedHandshakeIsBoundedBeforeVerification() {
        withServer(200, ByteArray(65_537) { 'x'.code.toByte() }) { url ->
            assertThrows(IOException::class.java) { ConchNetwork.getTextResponse(url, emptyMap()) }
        }
    }

    @Test fun unsignedErrorAndDecompressedHandshakeHaveTheSameBound() {
        withServer(400, ByteArray(65_537)) { url ->
            assertThrows(IOException::class.java) { ConchNetwork.getTextResponse(url, emptyMap()) }
        }
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(ByteArray(65_537)) }
        }.toByteArray()
        withServer(200, compressed, mapOf("Content-Encoding" to "gzip")) { url ->
            assertThrows(IOException::class.java) { ConchNetwork.getTextResponse(url, emptyMap()) }
        }
    }

    @Test fun redirectsDoNotForwardAMutatingRequest() {
        val redirectedRequests = AtomicInteger()
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        target.createContext("/") { response ->
            redirectedRequests.incrementAndGet()
            response.sendResponseHeaders(200, -1)
            response.close()
        }
        target.start()
        try {
            withServer(307, byteArrayOf(), mapOf("Location" to "http://127.0.0.1:${target.address.port}/")) { url ->
                assertEquals(307, ConchNetwork.postTextResponse(url, "fixture", emptyMap()).code)
            }
            assertEquals(0, redirectedRequests.get())
            assertFalse(ConchNetwork.client.retryOnConnectionFailure)
        } finally { target.stop(0) }
    }

    private fun withServer(
        status: Int,
        bytes: ByteArray,
        headers: Map<String, String> = emptyMap(),
        block: (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { response ->
            response.requestBody.use { it.readBytes() }
            headers.forEach { (key, value) -> response.responseHeaders.set(key, value) }
            response.sendResponseHeaders(status, 0) // Deliberately chunked, no trusted Content-Length.
            response.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}/") }
        finally { server.stop(0) }
    }
}
