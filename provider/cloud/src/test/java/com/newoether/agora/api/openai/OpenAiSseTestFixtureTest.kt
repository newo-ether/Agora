package com.newoether.agora.api.openai

import java.net.URI
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiSseTestFixtureTest : OpenAiSseTestFixture() {
    @Test
    fun requestBodyUsesUtf8ByteLengthForChineseAndSupplementaryCharacters() {
        val body = "{\"message\":\"中文🙂𠀀\"}"
        assertEquals(body, captureRequestBody(body, fragmented = false))
    }

    @Test
    fun requestBodySurvivesFragmentsInsideMultibyteCharacters() {
        val body = "{\"message\":\"part 一🙂 two\"}"
        assertEquals(body, captureRequestBody(body, fragmented = true))
    }

    private fun captureRequestBody(body: String, fragmented: Boolean): String {
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        SseServer(
            connectionCount = 1,
            statusCode = 200,
            errorBody = null,
            response = { socket, _ -> socket.writeSse("[DONE]") },
        ).use { server ->
            val address = URI(server.baseUrl)
            Socket(address.host, address.port).use { socket ->
                socket.soTimeout = 2_000
                val output = socket.getOutputStream()
                output.write(
                    ("POST /v1/responses HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${payload.size}\r\n\r\n")
                        .toByteArray(StandardCharsets.US_ASCII),
                )
                if (fragmented) {
                    payload.forEach { byte ->
                        output.write(byte.toInt() and 0xff)
                        output.flush()
                    }
                } else {
                    output.write(payload)
                    output.flush()
                }
                socket.shutdownOutput()
                socket.getInputStream().readBytes()
            }
            server.throwIfFailed()
            return server.requests.single().body
        }
    }
}
