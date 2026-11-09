package com.newoether.agora.api.openai

import android.content.Context
import android.content.pm.ApplicationInfo
import com.newoether.agora.util.CloudDebugLog as DebugLog
import com.newoether.agora.util.NetworkRetryPolicy
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BaseOpenAiProviderPaginationTest {
    private data class TestResponse(
        val status: String = "200 OK",
        val body: String,
    )

    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        DebugLog.forceEnabled = false
        DebugLog.init(context)
    }

    @Test
    fun followsHasMoreCursorAndReturnsSortedDistinctModels() {
        withModelServer(
            responses = listOf(
                TestResponse(
                    body = """
                        {
                          "data": [{"id":"model-b"},{"id":"model-a"}],
                          "has_more": true,
                          "last_id": "provider/model one"
                        }
                    """.trimIndent(),
                ),
                TestResponse(
                    body = """
                        {
                          "data": [{"id":"model-c"},{"id":"model-a"}],
                          "has_more": false
                        }
                    """.trimIndent(),
                ),
            ),
        ) { baseUrl, requests ->
            val models = runBlocking {
                testProvider(baseUrl).fetchModels(apiKey = "secret", baseUrl = null)
            }

            assertEquals(listOf("model-a", "model-b", "model-c"), models)
            assertEquals(
                listOf(
                    "GET /v1/models HTTP/1.1",
                    "GET /v1/models?after=provider%2Fmodel%20one HTTP/1.1",
                ),
                requests.map { it.first },
            )
            assertEquals(
                listOf("Bearer secret", "Bearer secret"),
                requests.map { it.second["authorization"] },
            )
        }
    }

    @Test
    fun repeatedCursorStopsPaginationAndReturnsAccumulatedModels() {
        withModelServer(
            responses = listOf(
                TestResponse(
                    body = """
                        {"data":[{"id":"model-a"}],"has_more":true,"last_id":"cursor"}
                    """.trimIndent(),
                ),
                TestResponse(
                    body = """
                        {"data":[{"id":"model-b"}],"has_more":true,"last_id":"cursor"}
                    """.trimIndent(),
                ),
            ),
        ) { baseUrl, requests ->
            val models = runBlocking {
                testProvider(baseUrl).fetchModels(apiKey = "", baseUrl = null)
            }

            assertEquals(listOf("model-a", "model-b"), models)
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun laterPageFailureRetriesFiveTimesAndReturnsModelsFromCompletedPages() {
        mockkObject(NetworkRetryPolicy)
        every { NetworkRetryPolicy.delayMillis(any(), any(), any(), any()) } returns 0L
        try {
            withModelServer(
                responses = listOf(
                    TestResponse(
                        body = """
                            {"data":[{"id":"model-a"}],"has_more":true,"last_id":"cursor"}
                        """.trimIndent(),
                    ),
                ) + List(6) {
                    TestResponse(
                        status = "500 Internal Server Error",
                        body = """{"error":{"message":"temporary failure"}}""",
                    )
                },
            ) { baseUrl, requests ->
                val models = runBlocking {
                    testProvider(baseUrl).fetchModels(apiKey = "secret", baseUrl = null)
                }

                assertEquals(listOf("model-a"), models)
                assertEquals(7, requests.size)
                assertEquals(
                    List(6) { "GET /v1/models?after=cursor HTTP/1.1" },
                    requests.drop(1).map { it.first },
                )
            }
        } finally {
            unmockkObject(NetworkRetryPolicy)
        }
    }

    private fun testProvider(baseUrl: String): BaseOpenAiProvider =
        object : BaseOpenAiProvider() {
            override val name: String = "Test"
            override val defaultBaseUrl: String = baseUrl
        }

    private fun withModelServer(
        responses: List<TestResponse>,
        block: (baseUrl: String, requests: List<Pair<String, Map<String, String>>>) -> Unit,
    ) {
        val server = ServerSocket(0, responses.size, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 5_000
        }
        val requests = Collections.synchronizedList(
            mutableListOf<Pair<String, Map<String, String>>>(),
        )
        val serverFailure = AtomicReference<Throwable?>(null)
        val worker = thread(name = "model-pagination-test-server", isDaemon = true) {
            try {
                responses.forEach { response ->
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
                        requests += requestLine to headers
                        val responseBytes = response.body.toByteArray()
                        val rawResponse = buildString {
                            append("HTTP/1.1 ${response.status}\r\n")
                            append("Content-Type: application/json\r\n")
                            append("Content-Length: ${responseBytes.size}\r\n")
                            append("Connection: close\r\n\r\n")
                            append(response.body)
                        }
                        socket.getOutputStream().apply {
                            write(rawResponse.toByteArray())
                            flush()
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!server.isClosed) serverFailure.set(error)
            } finally {
                server.close()
            }
        }

        try {
            block("http://127.0.0.1:${server.localPort}/v1", requests)
        } finally {
            server.close()
            worker.join(5_000)
        }

        assertFalse("HTTP server did not finish", worker.isAlive)
        assertNull(serverFailure.get())
    }
}
