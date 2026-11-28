package com.newoether.agora.api.openai

import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseOpenAiProviderModelFetchTest {
    private data class RecordedRequest(
        val requestLine: String,
        val headers: Map<String, String>,
    )

    @Test
    fun blankAndTrailingSlashBaseUrlsUseCanonicalModelsPathAndBearerKey() {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 5_000
        }
        val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
        val serverFailure = AtomicReference<Throwable?>(null)
        val responseBody =
            """{"object":"list","data":[{"id":"model-b"},{"id":"model-a"}]}"""
        val worker = thread(name = "model-fetch-test-server", isDaemon = true) {
            try {
                repeat(2) {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val requestLine = reader.readLine()
                            ?: error("Missing HTTP request line")
                        val headers = buildMap {
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                val separator = line.indexOf(':')
                                if (separator > 0) {
                                    put(
                                        line.substring(0, separator).trim().lowercase(),
                                        line.substring(separator + 1).trim(),
                                    )
                                }
                            }
                        }
                        requests += RecordedRequest(requestLine, headers)
                        val response = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: application/json\r\n")
                            append("Content-Length: ${responseBody.toByteArray().size}\r\n")
                            append("Connection: close\r\n\r\n")
                            append(responseBody)
                        }
                        socket.getOutputStream().apply {
                            write(response.toByteArray())
                            flush()
                        }
                    }
                }
            } catch (error: Throwable) {
                serverFailure.set(error)
            }
        }

        try {
            val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
            val provider = object : BaseOpenAiProvider() {
                override val name: String = "Test"
                override val defaultBaseUrl: String = baseUrl
            }

            val defaultModels = runBlocking {
                provider.fetchModels(apiKey = "secret", baseUrl = "")
            }
            val trailingSlashModels = runBlocking {
                provider.fetchModels(apiKey = "secret", baseUrl = "$baseUrl/")
            }

            assertEquals(listOf("model-a", "model-b"), defaultModels)
            assertEquals(listOf("model-a", "model-b"), trailingSlashModels)
        } finally {
            worker.join(5_000)
            server.close()
        }

        assertFalse("HTTP server did not finish", worker.isAlive)
        assertNull(serverFailure.get())
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals("GET /v1/models HTTP/1.1", request.requestLine)
            assertEquals("Bearer secret", request.headers["authorization"])
            assertTrue(request.headers.containsKey("host"))
        }
    }
}
