package com.newoether.agora.remote

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class FiloObservedReceiptTest {
    @Test fun validatesNonExactReceiptWithoutInventingMissingNativeIdentity() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val id = "00000000-0000-0000-0000-000000000001"
        var reply = ""
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", "a".repeat(64))
            reply = """{"clientId":"input","kind":"submitted_observed","state":"submitted"}"""
            val submitted = client.sendReceipt(id, "hello", "input")
            assertEquals("submitted", submitted.state)
            assertEquals("", submitted.turnId)
            reply = """{"clientId":"input","kind":"submitted_observed","state":"observed","messageId":"native-user"}"""
            assertEquals("native-user", client.sendReceipt(id, "hello", "input").messageId)
            for (invalid in listOf(
                """{"clientId":"input","kind":"submitted_observed","state":"observed"}""",
                """{"clientId":"input","turnId":"turn","state":"submitted"}""",
                """{"clientId":"other","kind":"submitted_observed","state":"submitted"}""")) {
                reply = invalid
                try { client.sendReceipt(id, "hello", "input"); fail("Invalid evidence accepted") }
                catch (_: IllegalArgumentException) { }
            }
            reply = """{"messages":[],"nextCursor":null,"queued":[],"inputReceipts":[{"clientId":"input","kind":"submitted_observed","state":"observed","turnId":"native-turn"}]}"""
            assertEquals("observed", client.conversation(id).inputReceipts.single().state)
            reply = """{"messages":[],"nextCursor":null,"queued":[],"inputReceipts":[{"clientId":"input","turnId":"turn","messageId":"user","state":"observed"}]}"""
            try { client.conversation(id); fail("Exact evidence accepted non-exact state") }
            catch (_: IllegalArgumentException) { }
            val nativeId = "n".repeat(1024)
            reply = """{"clientId":"input","turnId":"$nativeId","messageId":"$nativeId"}"""
            assertEquals(nativeId, client.sendReceipt(id, "hello", "input").turnId)
            reply = """{"messages":[],"nextCursor":null,"queued":[],"inputReceipts":[{"clientId":"input","turnId":"$nativeId","messageId":"$nativeId"}]}"""
            assertEquals(nativeId, client.conversation(id).inputReceipts.single().messageId)
            reply = """{"messages":[],"nextCursor":null,"queued":[],"runtime":{"status":"idle","settingsChange":{"id":"change","state":"failed","requested":{"model":"sonnet"},"error":"native command rejected"}}}"""
            assertEquals("failed", client.conversation(id).runtime?.settingsChange?.state)
            reply = """{"messages":[],"nextCursor":null,"queued":[],"runtime":{"status":"idle","settingsChange":{"id":"change","state":"confirmed","requested":{"model":"sonnet"}}}}"""
            try { client.conversation(id); fail("Confirmed settings accepted without a native turn") }
            catch (_: IllegalArgumentException) { }
        } finally { server.stop(0) }
    }
}
