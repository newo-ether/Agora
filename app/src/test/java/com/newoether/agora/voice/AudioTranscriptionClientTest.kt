package com.newoether.agora.voice

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/** Protocol fixtures only: no microphone, real API key or paid provider call. */
class AudioTranscriptionClientTest {
    @get:Rule val folder = TemporaryFolder()
    private val client = AudioTranscriptionClient(OkHttpClient())
    private fun audio() = folder.newFile().apply { writeText("synthetic recording fixture") }

    @Test fun aMissingExplicitKeyCannotBecomeAnAnonymousCustomRequest() = runBlocking {
        val config = VoiceTypingConfig(mode = VoiceTypingMode.COMPATIBLE, apiKeyId = "deleted-key",
            baseUrl = "http://127.0.0.1:1/v1")
        val result = runCatching { client.transcribe(audio(), config, "") }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun multipartUploadUsesOnlyAudioOptionsAndTheSelectedProviderKey() = runBlocking {
        val request = AtomicReference<String>()
        val auth = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/audio/transcriptions") { exchange ->
            auth.set(exchange.requestHeaders.getFirst("Authorization"))
            request.set(exchange.requestBody.bufferedReader().readText())
            val reply = """{"text":"A dictated answer"}""".toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val config = VoiceTypingConfig(mode = VoiceTypingMode.OPENAI, language = "en",
                baseUrl = "http://127.0.0.1:${server.address.port}/v1")
            assertEquals("A dictated answer", client.transcribe(audio(), config, "fixture-secret"))
            assertEquals("Bearer fixture-secret", auth.get())
            assertTrue(request.get().contains("name=\"model\""))
            assertTrue(request.get().contains("gpt-4o-mini-transcribe"))
            assertTrue(request.get().contains("name=\"response_format\""))
            assertTrue(request.get().contains("\r\njson\r\n"))
            assertTrue(request.get().contains("name=\"language\""))
            assertFalse(request.get().contains("messages"))
            assertFalse(request.get().contains("fixture-secret"))
        } finally { server.stop(0) }
    }

    @Test fun redirectsAndProviderErrorsDoNotBecomeTranscripts() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/audio/transcriptions") { exchange ->
            exchange.requestBody.close()
            exchange.responseHeaders.add("Location", "https://example.com/should-not-be-called")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            val config = VoiceTypingConfig(mode = VoiceTypingMode.COMPATIBLE,
                baseUrl = "http://127.0.0.1:${server.address.port}/v1")
            val result = runCatching { client.transcribe(audio(), config, "fixture-secret") }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("302"))
            assertFalse(result.exceptionOrNull()!!.message!!.contains("fixture-secret"))
        } finally { server.stop(0) }
    }
}
