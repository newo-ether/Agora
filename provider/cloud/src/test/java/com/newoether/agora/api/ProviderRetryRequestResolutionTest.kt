package com.newoether.agora.api

import com.newoether.agora.api.anthropic.AnthropicProvider
import com.newoether.agora.api.gemini.GeminiProvider
import com.newoether.agora.api.ollama.OllamaProvider
import com.newoether.agora.api.openai.BaseOpenAiProvider
import com.newoether.agora.api.openai.CustomOpenAiProvider
import com.newoether.agora.api.openai.OpenAiProvider
import com.newoether.agora.api.util.ProviderRetryPolicy
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.CloudDebugLog as DebugLog
import com.sun.net.httpserver.HttpServer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderRetryRequestResolutionTest {
    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        mockkObject(DebugLog)
        mockkObject(ProviderRetryPolicy)
        every { DebugLog.d(any(), any()) } just Runs
        every { DebugLog.e(any(), any()) } just Runs
        every { DebugLog.w(any(), any()) } just Runs
        every { ProviderRetryPolicy.delayMillis(any()) } returns 1L
        every { ProviderRetryPolicy.delayMillis(any(), any()) } returns 1L
    }

    @After
    fun restoreAndroidLogging() {
        unmockkObject(ProviderRetryPolicy)
        unmockkObject(DebugLog)
    }

    @Test
    fun openAiRetryResolvesRequestAgain() = withServer(
        contentType = "text/event-stream",
        successBody = listOf(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]",
        ).joinToString("\n\n", postfix = "\n\n"),
    ) { server ->
        val provider = object : BaseOpenAiProvider() {
            override val name = "test-openai"
            override val defaultBaseUrl = server.baseUrl
            override val terminalSseGraceMillis = 100L
            override fun retryDelayMillis(attempt: Int) = 1L
        }
        assertRetryResolvesAgain(
            provider = provider,
            config = config(server, modelId = "gpt-test"),
            server = server,
            timeoutMillis = 3_000L,
        )
    }

    @Test
    fun anthropicRetryResolvesRequestAgain() = withServer(
        contentType = "text/event-stream",
        successBody = listOf(
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}",
            "{\"type\":\"content_block_stop\",\"index\":0}",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}",
            "{\"type\":\"message_stop\"}",
        ).joinToString("\n\n", postfix = "\n\n") { "data: $it" },
    ) { server ->
        assertRetryResolvesAgain(
            provider = AnthropicProvider(defaultBaseUrl = server.baseUrl),
            config = config(
                server,
                modelId = "claude-3-5-sonnet-20240620",
            ).copy(anthropicCacheTtl = "5m"),
            server = server,
        )
        server.bodies.forEach { body ->
            val cache = Json.parseToJsonElement(body).jsonObject.getValue("cache_control").jsonObject
            assertEquals("5m", cache.getValue("ttl").jsonPrimitive.content)
        }
    }

    @Test
    fun geminiRetryResolvesRequestAgain() = withServer(
        contentType = "text/event-stream",
        successBody = """
            data: {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}

        """.trimIndent(),
    ) { server ->
        assertRetryResolvesAgain(
            provider = GeminiProvider(defaultBaseUrl = server.baseUrl),
            config = config(server, modelId = "gemini-test"),
            server = server,
        )
    }

    @Test
    fun ollamaRetryResolvesRequestAgain() = withServer(
        contentType = "application/x-ndjson",
        successBody = listOf(
            "{\"model\":\"test\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":false}",
            "{\"model\":\"test\",\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":1,\"eval_count\":1}",
        ).joinToString("\n", postfix = "\n"),
    ) { server ->
        assertRetryResolvesAgain(
            provider = OllamaProvider(),
            config = config(server, modelId = "llama3"),
            server = server,
        )
    }

    @Test
    fun exactBodyReadFailureUsesExistingRetryPipelineAcrossRemoteProviders() {
        val chatSuccess = listOf(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]",
        )
        val responsesSuccess = listOf(
            "data: {\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"delta\":\"ok\"}",
            "data: {\"type\":\"response.completed\",\"sequence_number\":2,\"response\":{\"status\":\"completed\"}}",
        )
        val anthropicSuccess = listOf(
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}",
            "data: {\"type\":\"content_block_stop\",\"index\":0}",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}",
            "data: {\"type\":\"message_stop\"}",
        )
        val geminiSuccess = listOf(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}",
        )
        val ollamaSuccess = listOf(
            "{\"model\":\"test\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":false}",
            "{\"model\":\"test\",\"done\":true,\"done_reason\":\"stop\"}",
        )

        assertReadFailureBoundary(
            CustomOpenAiProvider("Custom OpenAI", "https://example.invalid/v1"),
            streamConfig("gpt-test"),
            chatSuccess,
            listOf("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}"),
        )
        assertReadFailureBoundary(
            CustomOpenAiProvider("Custom OpenAI", "https://example.invalid/v1"),
            streamConfig("gpt-test", responsesApiEnabled = true),
            responsesSuccess,
            listOf("data: {\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"delta\":\"partial\"}"),
        )
        assertReadFailureBoundary(
            AnthropicProvider("Custom Anthropic", "https://example.invalid/v1"),
            streamConfig("claude-3-5-sonnet-20240620"),
            anthropicSuccess,
            listOf(
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"partial\"}}",
                "data: {\"type\":\"content_block_stop\",\"index\":0}",
            ),
        )
        assertReadFailureBoundary(
            GeminiProvider("Custom Google", "https://example.invalid/v1beta"),
            streamConfig("gemini-test"),
            geminiSuccess,
            listOf("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial\"}]}}]}"),
        )
        assertReadFailureBoundary(
            OllamaProvider(),
            streamConfig("llama3"),
            ollamaSuccess,
            listOf("{\"model\":\"test\",\"message\":{\"role\":\"assistant\",\"content\":\"partial\"},\"done\":false}"),
        )
    }

    @Test
    fun malformedZeroOutputUsesExistingRetryPipelineAcrossRemoteProviders() {
        val chatSuccess = listOf(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]",
        )
        val responsesSuccess = listOf(
            "data: {\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"delta\":\"ok\"}",
            "data: {\"type\":\"response.completed\",\"sequence_number\":2,\"response\":{\"status\":\"completed\"}}",
        )
        val anthropicSuccess = listOf(
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}",
            "data: {\"type\":\"content_block_stop\",\"index\":0}",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}",
            "data: {\"type\":\"message_stop\"}",
        )
        assertMalformedRetry(OpenAiProvider(), streamConfig("gpt-test"), "data: not-json", chatSuccess)
        assertMalformedRetry(
            OpenAiProvider(),
            streamConfig("gpt-test", responsesApiEnabled = true),
            "data: not-json",
            responsesSuccess,
        )
        assertMalformedRetry(
            AnthropicProvider(),
            streamConfig("claude-3-5-sonnet-20240620"),
            "data: not-json",
            anthropicSuccess,
        )
        assertMalformedRetry(
            GeminiProvider(),
            streamConfig("gemini-test"),
            "data: not-json",
            listOf("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}"),
        )
        assertMalformedRetry(
            OllamaProvider(),
            streamConfig("llama3"),
            "not-json",
            listOf(
                "{\"model\":\"test\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":false}",
                "{\"model\":\"test\",\"done\":true,\"done_reason\":\"stop\"}",
            ),
        )
    }

    private fun assertReadFailureBoundary(
        provider: LlmProvider,
        config: ProviderConfig,
        successReads: List<String>,
        partialReads: List<String>,
    ) {
        val retried = collectWithMockedStream(
            provider,
            config,
            listOf(IOException("The server response could not be read.")) + successReads,
        )
        assertEquals(2, retried.second)
        assertEquals(StreamEvent.Retrying(1, 5, resetCurrentAttempt = true), retried.first.filterIsInstance<StreamEvent.Retrying>().single())
        assertEquals("ok", retried.first.filterIsInstance<StreamEvent.TextChunk>().joinToString("") { it.text })
        assertTrue(retried.first.none { it is StreamEvent.Error })

        val partial = collectWithMockedStream(
            provider,
            config,
            partialReads + IOException("The server response could not be read.") + successReads,
        )
        assertEquals(2, partial.second)
        assertEquals(
            StreamEvent.Retrying(1, 5, resetCurrentAttempt = true),
            partial.first.filterIsInstance<StreamEvent.Retrying>().single(),
        )
        assertEquals(listOf("partial", "ok"), partial.first.filterIsInstance<StreamEvent.TextChunk>().map { it.text })
        assertTrue(partial.first.none { it is StreamEvent.Error })
        val retryIndex = partial.first.indexOfFirst { it is StreamEvent.Retrying }
        assertTrue(partial.first.take(retryIndex).any { it is StreamEvent.TextChunk && it.text == "partial" })
        assertTrue(partial.first.drop(retryIndex + 1).any { it is StreamEvent.TextChunk && it.text == "ok" })
    }

    private fun assertMalformedRetry(
        provider: LlmProvider,
        config: ProviderConfig,
        malformedRead: String,
        successReads: List<String>,
    ) {
        val result = collectWithMockedStream(provider, config, listOf(malformedRead) + successReads)
        assertEquals(2, result.second)
        assertEquals(StreamEvent.Retrying(1, 5, resetCurrentAttempt = true), result.first.filterIsInstance<StreamEvent.Retrying>().single())
        assertEquals("ok", result.first.filterIsInstance<StreamEvent.TextChunk>().joinToString("") { it.text })
        assertTrue(result.first.none { it is StreamEvent.Error })
    }

    private fun collectWithMockedStream(
        provider: LlmProvider,
        config: ProviderConfig,
        reads: List<Any?>,
    ): Pair<List<StreamEvent>, Int> {
        val readIndex = AtomicInteger()
        val opens = AtomicInteger()
        val handle = mockk<HttpClient.StreamHandle>(relaxed = true)
        every { handle.code } returns 200
        every { handle.readLine() } answers {
            reads.getOrNull(readIndex.getAndIncrement()).let { read ->
                if (read is Throwable) throw read else read as String?
            }
        }
        mockkObject(HttpClient)
        every { HttpClient.streamPost(any(), any(), any()) } answers {
            opens.incrementAndGet()
            handle
        }
        return try {
            val messages = listOf(
                ChatMessage(id = "user", text = "hello", participant = Participant.USER),
            )
            val events = runBlocking {
                withTimeout(3_000L) { provider.generateResponse(messages, config).toList() }
            }
            events to opens.get()
        } finally {
            unmockkObject(HttpClient)
        }
    }

    private fun streamConfig(
        modelId: String,
        responsesApiEnabled: Boolean = false,
    ) = ProviderConfig(
        apiKey = "",
        modelId = modelId,
        baseUrl = "https://example.invalid/v1",
        thinkingEnabled = false,
        responsesApiEnabled = responsesApiEnabled,
    )

    private fun assertRetryResolvesAgain(
        provider: LlmProvider,
        config: ProviderConfig,
        server: RetryServer,
        timeoutMillis: Long = 10_000L,
    ) {
        val resolverCalls = AtomicInteger()
        val messages = listOf(
            ChatMessage(id = "user", text = "hello", participant = Participant.USER),
        )
        val events = runBlocking {
            withTimeout(timeoutMillis) {
                provider.generateResponse(
                    messages,
                    config.copy(
                        requestResolver = ProviderRequestResolver { rawMessages, _ ->
                            ProviderRequestInput(
                                messages = rawMessages,
                                systemPrompt = "system-${resolverCalls.incrementAndGet()}",
                            )
                        },
                    ),
                ).toList()
            }
        }

        assertTrue(events.none { it is StreamEvent.Error })
        assertEquals(1, events.filterIsInstance<StreamEvent.Retrying>().size)
        assertEquals(2, resolverCalls.get())
        assertEquals(2, server.bodies.size)
        assertTrue(server.bodies[0].contains("system-1"))
        assertTrue(server.bodies[0].contains("system-2").not())
        assertTrue(server.bodies[1].contains("system-2"))
        assertTrue(server.bodies[1].contains("system-1").not())
    }

    private fun config(server: RetryServer, modelId: String) = ProviderConfig(
        apiKey = "",
        modelId = modelId,
        baseUrl = server.baseUrl,
        thinkingEnabled = false,
    )

    private fun withServer(
        contentType: String,
        successBody: String,
        test: (RetryServer) -> Unit,
    ) = RetryServer(contentType, successBody).use(test)

    private class RetryServer(
        private val contentType: String,
        private val successBody: String,
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val attempts = AtomicInteger()
        val bodies = CopyOnWriteArrayList<String>()
        val baseUrl = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/") { exchange ->
                bodies += exchange.requestBody.bufferedReader().use { it.readText() }
                val attempt = attempts.incrementAndGet()
                val status = if (attempt == 1) 503 else 200
                val response = if (status == 503) {
                    "{\"error\":\"retry\"}"
                } else {
                    successBody
                }.toByteArray()
                exchange.responseHeaders.add(
                    "Content-Type",
                    if (status == 503) "application/json" else contentType,
                )
                exchange.sendResponseHeaders(status, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }
}
