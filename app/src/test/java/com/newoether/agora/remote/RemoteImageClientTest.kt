package com.newoether.agora.remote

import com.newoether.agora.model.ToolImageAttachment
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

class RemoteImageClientTest {
    @Test fun imageUsesDedicatedAuthenticationAndStreamsOnlyAfterExplicitRequest() = runTest {
        val bytes = ByteArray(8193) { (it % 256).toByte() }
        var reads = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            reads++
            assertEquals("Bearer " + "a".repeat(64), exchange.requestHeaders.getFirst("Authorization"))
            assertTrue(exchange.requestURI.path.endsWith("/image"))
            assertTrue(exchange.requestURI.query.startsWith("messages="))
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val file = File.createTempFile("filo-image-client-", ".png")
        try {
            val client = applicationFixtureClient("http://127.0.0.1:" + server.address.port, "a".repeat(64))
            assertEquals(0, reads)
            val result = client.image("11111111-1111-4111-8111-111111111111",
                RemotePayloadRequest("native-image", "b".repeat(64))) { input, mime ->
                assertEquals("image/png", mime)
                file.outputStream().use { input.copyTo(it, 97) }
                ToolImageAttachment(file.path, mime, file.length())
            }
            assertEquals(file.path, result.path)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(1, reads)
        } finally { server.stop(0); file.delete() }
    }

    @Test fun oversizedResponseIsRejectedBeforeImagePersistence() = runTest {
        var persisted = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, com.newoether.agora.tool.ToolImageStore.MAX_IMAGE_BYTES + 1)
            runCatching { exchange.responseBody.close() }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:" + server.address.port, "a".repeat(64))
            try {
                client.image("11111111-1111-4111-8111-111111111111",
                    RemotePayloadRequest("native-image", "b".repeat(64))) { _, _ ->
                    persisted = true
                    error("Must reject before reading bytes")
                }
                fail("Oversized image must fail")
            } catch (_: RemoteContentLimitException) {}
            assertFalse(persisted)
        } finally { server.stop(0) }
    }
}
