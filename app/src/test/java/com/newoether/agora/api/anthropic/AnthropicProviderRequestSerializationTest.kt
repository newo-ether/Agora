package com.newoether.agora.api.anthropic

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.sun.net.httpserver.HttpServer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class AnthropicProviderRequestSerializationTest {
    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        mockkObject(DebugLog)
        every { DebugLog.d(any(), any()) } just Runs
        every { DebugLog.e(any(), any()) } just Runs
        every { DebugLog.w(any(), any()) } just Runs
    }

    @After
    fun restoreAndroidLogging() {
        unmockkObject(DebugLog)
    }

    @Test
    fun currentDefaultOnModelsSerializeDisabledWithEffort() {
        assertDisabled("claude-sonnet-5", "medium")
        assertDisabled("claude-opus-5", "high")
    }

    @Test
    fun undocumentedModelsKeepTheOffSwitchTheUserChose() = withServer { server ->
        // No public reference documents these ids, so Agora must not force thinking on.
        listOf("claude-fable-5", "claude-mythos-5", "claude-mythos-preview").forEach { model ->
            val body = server.capture(config(server, model).copy(thinkingEnabled = false))
            assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
        val opus = server.capture(
            config(server, "claude-opus-5").copy(
                thinkingEnabled = false,
                thinkingLevel = "xhigh",
            ),
        )
        assertEquals("disabled", opus["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("xhigh", opus["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun alwaysThinkingEnabledUsesAdaptiveEffortAndOmitsSampling() = withServer { server ->
        val body = server.capture(config(server, "claude-fable-5").copy(thinkingLevel = "max"))

        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("summarized", body["thinking"]!!.jsonObject["display"]!!.jsonPrimitive.content)
        assertEquals("max", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
    }

    @Test
    fun legacyThinkingOffOmitsThinkingAndForwardsSupportedSampling() = withServer { server ->
        val body = server.capture(
            config(server, "claude-3-5-sonnet-20240620").copy(thinkingEnabled = false),
        )

        assertFalse(body.containsKey("thinking"))
        assertFalse(body.containsKey("output_config"))
        assertEquals(0.7f, body["temperature"]!!.jsonPrimitive.float)
        assertEquals(0.8f, body["top_p"]!!.jsonPrimitive.float)
        assertEquals(777, body["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun requestIncludesTopLevelEphemeralCacheControlWithOneHourTtl() = withServer { server ->
        val body = server.capture(
            config(server, "claude-3-5-sonnet-20240620").copy(thinkingEnabled = false),
        )

        assertEquals(
            "ephemeral",
            body["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "1h",
            body["cache_control"]!!.jsonObject["ttl"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun legacyManualThinkingOmitsTemperatureAndKeepsCompatibleTopP() = withServer { server ->
        val body = server.capture(
            config(server, "claude-haiku-4-5-20251001").copy(
                maxTokens = 8192,
                topP = 0.97f,
            ),
        )

        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("temperature"))
        assertEquals(0.97f, body["top_p"]!!.jsonPrimitive.float)
    }

    @Test
    fun unsetMaxTokensDefaultsTo32kWithinTheModelsDocumentedCeiling() = withServer { server ->
        fun maxTokensFor(model: String): Int = server.capture(
            config(server, model).copy(maxTokens = null, thinkingEnabled = false),
        )["max_tokens"]!!.jsonPrimitive.int

        assertEquals(32768, maxTokensFor("claude-sonnet-5"))
        assertEquals(32768, maxTokensFor("claude-mythos-preview"))
        assertEquals(32000, maxTokensFor("claude-opus-4-1-20250805"))
        assertEquals(8192, maxTokensFor("claude-3-5-sonnet-20240620"))
        assertEquals(4096, maxTokensFor("claude-3-haiku-20240307"))
    }

    @Test
    fun documentedCeilingsMatchOnVersionBoundariesOnly() {
        assertEquals(64_000, AnthropicOutputLimits.maxOutputTokens("claude-opus-4-5-20251101"))
        assertEquals(32_000, AnthropicOutputLimits.maxOutputTokens("claude-opus-4-20250514"))
        assertEquals(64_000, AnthropicOutputLimits.maxOutputTokens("anthropic/claude-sonnet-4.5"))
        assertEquals(128_000, AnthropicOutputLimits.maxOutputTokens("claude-opus-5-5"))
        assertEquals(null, AnthropicOutputLimits.maxOutputTokens("claude-opus-45"))
        assertEquals(null, AnthropicOutputLimits.maxOutputTokens("claude-mythos-preview"))
    }

    @Test
    fun currentDocumentedClaudeModelsUseAdaptiveOnlyAndNoSamplingOrBudget() = withServer { server ->
        listOf("claude-opus-5-5", "claude-sonnet-5.5", "anthropic/claude-fable-5-1").forEach { id ->
            val body = server.capture(config(server, id).copy(thinkingEnabled = false,
                thinkingLevel = "max", thinkingBudgetEnabled = true, thinkingBudgetTokens = 4096))
            val thinking = body["thinking"]!!.jsonObject
            assertEquals("adaptive", thinking["type"]!!.jsonPrimitive.content)
            assertFalse(thinking.containsKey("budget_tokens"))
            assertEquals("low", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertFalse(body.containsKey("temperature"))
            assertFalse(body.containsKey("top_p"))
        }
        val haiku = server.capture(config(server, "claude-haiku-5-5").copy(thinkingEnabled = false))
        assertEquals("disabled", haiku["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(haiku.containsKey("temperature"))
        assertFalse(haiku.containsKey("top_p"))
    }

    private fun assertDisabled(model: String, effort: String) = withServer { server ->
        val body = server.capture(
            config(server, model).copy(thinkingEnabled = false, thinkingLevel = effort),
        )
        val thinking = body["thinking"]!!.jsonObject
        assertEquals("disabled", thinking["type"]!!.jsonPrimitive.content)
        assertFalse(thinking.containsKey("display"))
        assertFalse(thinking.containsKey("budget_tokens"))
        assertEquals(effort, body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
    }
    @Test
    fun cacheSelectionControlsFinalHttpRequestWithoutChangingTheSavedConfig() = withServer { server ->
        for (ttl in listOf("5m", "1h")) {
            val selected = config(server, "claude-3-5-sonnet-20240620").copy(
                thinkingEnabled = false, anthropicCacheTtl = ttl,
            )
            val enabled = server.capture(selected)
            assertEquals(ttl, enabled["cache_control"]!!.jsonObject["ttl"]!!.jsonPrimitive.content)
            val disabled = server.capture(selected.copy(anthropicCacheEnabled = false))
            assertFalse(disabled.containsKey("cache_control"))
            assertEquals(enabled.filterKeys { it != "cache_control" }, disabled)
            assertEquals(ttl, server.capture(selected)["cache_control"]!!.jsonObject["ttl"]!!.jsonPrimitive.content)
        }
    }
    @Test
    fun invalidEnabledCacheDurationFailsBeforeHttp() = withServer { server ->
        val events = collect(server, config(server, "claude-3-5-sonnet-20240620").copy(
            thinkingEnabled = false, anthropicCacheTtl = "invalid",
        ))
        assertRequestFormat(events, "Invalid Anthropic cache duration")
        assertTrue(server.bodies.isEmpty())
    }

    @Test
    fun signedThinkingIsReplayedOnlyToTheModelThatSignedIt() = withServer { server ->
        val history = signedToolRoundHistory()

        val sameModel = server.capture(config(server, "claude-opus-5"), history)
        assertEquals(
            listOf("text", "thinking", "tool_use", "tool_result", "text"),
            sameModel.contentPartTypes(),
        )
        assertTrue(sameModel.toString().contains(SIGNED_THOUGHT))

        listOf("claude-sonnet-5", "claude-opus-4.6").forEach { otherModel ->
            val switched = server.capture(config(server, otherModel), history)
            val types = switched.contentPartTypes()
            assertFalse("$otherModel got a foreign thinking block: $types", "thinking" in types)
            assertFalse(
                "$otherModel got a foreign signature",
                switched.toString().contains(SIGNED_THOUGHT),
            )
        }
    }

    @Test
    fun signedThinkingIsNeverReplayedThroughAnotherProviderEntry() = withServer { server ->
        val body = server.capture(
            config = config(server, "claude-opus-5"),
            messages = signedToolRoundHistory(),
            providerName = "Relay Claude",
        )

        assertFalse("thinking" in body.contentPartTypes())
        assertFalse(body.toString().contains(SIGNED_THOUGHT))
        assertTrue(body.toString().contains("Archived tool activity"))
    }

    /** One Anthropic tool round whose thinking block carries a model-signed opaque signature. */
    private fun signedToolRoundHistory(): List<ChatMessage> = listOf(
        ChatMessage(id = "u1", text = "Read a.txt", participant = Participant.USER),
        ChatMessage(
            id = Constants.TOOL_MSG_PREFIX + "round",
            text = "",
            participant = Participant.MODEL,
            modelName = "Anthropic:claude-opus-5",
            segments = listOf(
                MessageSegment(
                    type = "thought",
                    content = "I should read the file first.",
                    signature = SIGNED_THOUGHT,
                    signatureProvider = Constants.PROVIDER_ANTHROPIC,
                ),
                MessageSegment(
                    type = "tool",
                    toolName = "read_file",
                    toolArgs = """{"path":"a.txt"}""",
                    toolCallId = "call-1",
                ),
            ),
        ),
        ChatMessage(
            id = Constants.RESULT_MSG_PREFIX + "round",
            text = "",
            participant = Participant.MODEL,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "read_file",
                    toolCallId = "call-1",
                    toolResult = "hello",
                ),
            ),
        ),
        ChatMessage(id = "u2", text = "Continue", participant = Participant.USER),
    )

    private fun JsonObject.contentPartTypes(): List<String> =
        this["messages"]!!.jsonArray.flatMap { message ->
            message.jsonObject["content"]!!.jsonArray
                .map { part -> part.jsonObject["type"]!!.jsonPrimitive.content }
        }

    private companion object {
        const val SIGNED_THOUGHT = "opus-5-issued-signature"
    }

    private fun assertRequestFormat(events: List<StreamEvent>, detail: String) {
        val error = events.filterIsInstance<StreamEvent.Error>().single().error
        assertTrue(error is GenerationError.RequestFormat)
        assertTrue((error as GenerationError.RequestFormat).details.contains(detail))
    }

    private fun collect(server: RecordingServer, config: ProviderConfig): List<StreamEvent> =
        collect(server, config, listOf(ChatMessage(text = "hello", participant = Participant.USER)))

    private fun collect(
        server: RecordingServer,
        config: ProviderConfig,
        messages: List<ChatMessage>,
        providerName: String = Constants.PROVIDER_ANTHROPIC,
    ): List<StreamEvent> = runBlocking {
        withTimeout(2_000L) {
            AnthropicProvider(name = providerName, defaultBaseUrl = server.baseUrl)
                .generateResponse(messages, config)
                .toList()
        }
    }

    private fun RecordingServer.capture(config: ProviderConfig) =
        capture(config, listOf(ChatMessage(text = "hello", participant = Participant.USER)))

    private fun RecordingServer.capture(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        providerName: String = Constants.PROVIDER_ANTHROPIC,
    ) = collect(this, config, messages, providerName).let { events ->
        val failure = events.filterIsInstance<StreamEvent.Error>().firstOrNull()
        assertTrue("unexpected provider error: ${failure?.error}", failure == null)
        Json.parseToJsonElement(bodies.last()).jsonObject
    }

    private fun config(server: RecordingServer, model: String) = ProviderConfig(
        apiKey = "",
        modelId = model,
        baseUrl = server.baseUrl,
        thinkingEnabled = true,
        thinkingLevel = "medium",
        temperature = 0.7f,
        maxTokens = 777,
        topP = 0.8f,
    )

    private fun withServer(test: (RecordingServer) -> Unit) = RecordingServer().use(test)

    private class RecordingServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bodies = CopyOnWriteArrayList<String>()
        val baseUrl = "http://127.0.0.1:${server.address.port}/v1"

        init {
            server.createContext("/") { exchange ->
                bodies += exchange.requestBody.bufferedReader().use { it.readText() }
                val response = listOf(
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}",
                    "{\"type\":\"content_block_stop\",\"index\":0}",
                    "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}",
                    "{\"type\":\"message_stop\"}",
                ).joinToString(separator = "\n\n", postfix = "\n\n") { "data: $it" }.toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }
}
