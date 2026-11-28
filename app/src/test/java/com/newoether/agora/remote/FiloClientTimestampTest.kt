package com.newoether.agora.remote

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FiloClientTimestampTest {
    @Test fun nativeMillisecondsAndUnknownTimeDecodeIdenticallyInHistoryAndEvents() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var timestamp = "1792713600123"
        server.createContext("/") { exchange ->
            val page = """{"messages":[{"id":"native","turnId":"","clientId":null,"role":"assistant","text":"answer","timestamp":$timestamp}],"nodes":[{"id":"native","turnId":"","clientId":null,"role":"assistant","timestamp":$timestamp,"revision":"revision","textLength":6}],"queued":[],"nextCursor":null}"""
            val body = if (exchange.requestURI.path.endsWith("/events")) "data: $page\n\n" else page
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", "a".repeat(64))
            for (wire in listOf("1792713600123", "null")) {
                timestamp = wire
                val expected = wire.toLongOrNull()
                val history = client.conversation("00000000-0000-0000-0000-000000000001")
                val event = client.events("00000000-0000-0000-0000-000000000001").first()
                assertEquals(history, event)
                assertEquals(expected, history.messages.single().timestamp)
                assertEquals(expected, history.nodes.single().timestamp)
                assertEquals(expected ?: 0L, projectRemoteMessages(history.messages).single().timestamp)
                assertEquals(expected ?: 0L, projectRemoteTopology(history.nodes, null).single().stub.timestamp)
            }
            timestamp = "1792713600.123"
            try {
                client.conversation("00000000-0000-0000-0000-000000000001")
                fail("Decimal seconds are not the message millisecond contract")
            } catch (error: IllegalArgumentException) {
                assertEquals(RemoteFailure.PROTOCOL, classifyRemoteFailure(error))
            }
        } finally {
            server.stop(0)
        }
    }
}
