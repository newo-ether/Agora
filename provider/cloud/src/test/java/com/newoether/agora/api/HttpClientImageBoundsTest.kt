package com.newoether.agora.api

import com.sun.net.httpserver.HttpServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class HttpClientImageBoundsTest {
    @Test fun byteBoundedImageDownloadRejectsOversizedBody() {
        withServer(200, ByteArray(33) { 1 }) { url, _ ->
            assertThrows(IOException::class.java) { HttpClient.getBytes(url, maxBytes = 32) }
            assertArrayEquals(ByteArray(33) { 1 }, HttpClient.getBytes(url, maxBytes = 33))
        }
    }

    @Test fun multipartPostRetainsHttpErrorWithoutReplayingTheGeneration() {
        withServer(400, "unsupported image option".toByteArray()) { url, count ->
            val response = HttpClient.postBodyResponse(
                url, "payload".toRequestBody("application/octet-stream".toMediaType()),
                replaySafe = false, maxResponseBytes = 1024,
            )
            assertFalse(response.isSuccessful)
            assertEquals(400, response.code)
            assertEquals("unsupported image option", response.body)
            assertEquals(1, count.get())
        }
    }

    @Test fun responseLimitRejectsOversizedJsonWithoutRetry() {
        withServer(200, ByteArray(65) { 65 }) { url, count ->
            assertThrows(IOException::class.java) {
                HttpClient.postBodyResponse(
                    url, "{}".toRequestBody("application/json".toMediaType()),
                    replaySafe = false, maxResponseBytes = 64,
                )
            }
            assertEquals(1, count.get())
        }
    }

    private fun withServer(status: Int, body: ByteArray, block: (String, AtomicInteger) -> Unit) {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/image") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.close()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}/image", calls) }
        finally { server.stop(0) }
    }
}
