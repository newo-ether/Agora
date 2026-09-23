package com.newoether.agora.api.openai

import android.content.Context
import android.content.pm.ApplicationInfo
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class OpenAiCompatibleGenerationParameterForwardingTest {
    @Test
    fun openRouterOffSendsExplicitDisable() = withServer { server ->
        val body = server.capture(OpenRouterProvider(), config(server, "test-model").copy(
            thinkingEnabled = false,
        ))
        assertFalse(body["reasoning"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun requestyForwardsEffortBudgetAndExplicitNone() = withServer { server ->
        val effort = server.capture(RequestyProvider(), config(server, "openai/gpt-4o-mini").copy(
            thinkingLevel = "max",
        ))
        assertEquals("max", effort["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(effort.containsKey("reasoning"))
        assertStandardParameters(effort)

        withServer { budgetServer ->
            val budget = budgetServer.capture(
                RequestyProvider(),
                config(budgetServer, "anthropic/claude-sonnet-4-5").copy(
                    thinkingBudgetEnabled = true,
                    thinkingBudgetTokens = 8192,
                ),
            )
            assertEquals("8192", budget["reasoning_effort"]!!.jsonPrimitive.content)
        }

        withServer { offServer ->
            val off = offServer.capture(RequestyProvider(), config(offServer, "openai/gpt-4o-mini").copy(
                thinkingEnabled = false,
            ))
            assertEquals("none", off["reasoning_effort"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun chatForwardsConfiguredServiceTier() = withServer { server ->
        val body = server.capture(OpenAiProvider(), config(server, "gpt-4o").copy(
            thinkingEnabled = false,
            openAiServiceTier = "priority",
        ))
        assertEquals("priority", body["service_tier"]!!.jsonPrimitive.content)
    }

    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        DebugLog.forceEnabled = false
        DebugLog.init(context)
    }

    @Test
    fun qwenHybridForwardsToggleBudgetAndEveryApplicableChatParameter() = withServer { server ->
        val body = server.capture(
            QwenProvider(),
            config(server, "qwen-plus").copy(thinkingEnabled = false),
        )
        assertFalse(body["enable_thinking"]!!.jsonPrimitive.boolean)
        assertFalse(body.containsKey("thinking_budget"))
        assertStandardParameters(body)

        withServer { enabledServer ->
            val enabledBody = enabledServer.capture(
                QwenProvider(),
                config(enabledServer, "qwen3.6-plus").copy(
                    thinkingBudgetEnabled = true,
                    thinkingBudgetTokens = 8192,
                ),
            )
            assertTrue(enabledBody["enable_thinking"]!!.jsonPrimitive.boolean)
            assertEquals(8192, enabledBody["thinking_budget"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun qwen38UsesEffortOrBudgetButNeverBoth() = withServer { effortServer ->
        val effortBody = effortServer.capture(
            QwenProvider(),
            config(effortServer, "qwen3.8-max").copy(thinkingLevel = "high"),
        )
        assertEquals("xhigh", effortBody["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(effortBody.containsKey("thinking_budget"))

        withServer { budgetServer ->
            val budgetBody = budgetServer.capture(
                QwenProvider(),
                config(budgetServer, "qwen3.8-flash").copy(
                    thinkingBudgetEnabled = true,
                    thinkingBudgetTokens = 16384,
                ),
            )
            assertEquals(16384, budgetBody["thinking_budget"]!!.jsonPrimitive.int)
            assertFalse(budgetBody.containsKey("reasoning_effort"))
        }
    }

    @Test
    fun qwenThinkingOnlyOffFailsBeforeHttp() = withServer { server ->
        val events = collect(
            QwenProvider(),
            config(server, "qwen3-235b-a22b-thinking-2507").copy(thinkingEnabled = false),
        )

        assertRequestFormat(events, "cannot disable thinking")
        assertTrue(server.bodies.isEmpty())
    }

    @Test
    fun groqForwardsEachDocumentedModelEffortRange() {
        assertGroqEffort("qwen/qwen3.6-27b", "xhigh", "default")
        assertGroqEffort("qwen/qwen3.8-27b", "xhigh", "high", checkParameters = true)
        assertGroqEffort("openai/gpt-oss-20b", "minimal", "low")
    }

    @Test
    fun groqGptOssOffFailsBeforeHttp() = withServer { server ->
        val events = collect(
            GroqProvider(),
            config(server, "openai/gpt-oss-120b").copy(thinkingEnabled = false),
        )

        assertRequestFormat(events, "cannot disable reasoning")
        assertTrue(server.bodies.isEmpty())
    }

    @Test
    fun customQwen38ForwardsMappedEffortAndStandardParameters() = withServer { server ->
        val body = server.capture(
            CustomOpenAiProvider("Relay", server.baseUrl),
            config(server, "qwen/qwen3.8-27b").copy(thinkingLevel = "max"),
        )
        assertEquals("xhigh", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertStandardParameters(body)
    }

    @Test
    fun deepSeekForwardsThinkingToggleAndMappedEffort() = withServer { server ->
        val enabled = server.capture(
            DeepSeekProvider(),
            config(server, "deepseek-v4").copy(thinkingLevel = "medium"),
        )
        assertEquals("enabled", enabled["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", enabled["reasoning_effort"]!!.jsonPrimitive.content)
        assertStandardParameters(enabled)

        withServer { offServer ->
            val disabled = offServer.capture(
                DeepSeekProvider(),
                config(offServer, "deepseek-v4").copy(thinkingEnabled = false),
            )
            assertEquals("disabled", disabled["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertFalse(disabled.containsKey("reasoning_effort"))
        }
    }

    @Test
    fun deepSeekEffortMappingMatchesOfficialTable() {
        assertEquals("low", deepSeekReasoningEffort("minimal"))
        assertEquals("low", deepSeekReasoningEffort("low"))
        assertEquals("high", deepSeekReasoningEffort("medium"))
        assertEquals("high", deepSeekReasoningEffort("high"))
        assertEquals("high", deepSeekReasoningEffort("xhigh"))
        assertEquals("max", deepSeekReasoningEffort("max"))
        assertEquals("high", deepSeekReasoningEffort("balanced"))
        assertNull(deepSeekReasoningEffort("none"))
    }

    @Test
    fun otherOpenAiCompatibleProvidersNeverSendDeepSeekThinkingField() = withServer { server ->
        val qwenBody = server.capture(QwenProvider(), config(server, "qwen-plus"))
        assertFalse(qwenBody.containsKey("thinking"))

        withServer { relayServer ->
            val relayBody = relayServer.capture(
                CustomOpenAiProvider("Relay", relayServer.baseUrl),
                config(relayServer, "llama-3.3-70b"),
            )
            assertFalse(relayBody.containsKey("thinking"))
        }
    }

    @Test
    fun customEndpointSendsThinkingFieldsForRelayedDeepSeekModels() = withServer { server ->
        val enabled = server.capture(
            CustomOpenAiProvider("Relay", server.baseUrl),
            config(server, "DeepSeek/DeepSeek-V4").copy(thinkingLevel = "xhigh"),
        )
        assertEquals("enabled", enabled["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", enabled["reasoning_effort"]!!.jsonPrimitive.content)
        assertStandardParameters(enabled)

        withServer { offServer ->
            val disabled = offServer.capture(
                CustomOpenAiProvider("Relay", offServer.baseUrl),
                config(offServer, "deepseek-chat").copy(thinkingEnabled = false),
            )
            assertEquals("disabled", disabled["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertFalse(disabled.containsKey("reasoning_effort"))
        }
    }

    @Test
    fun deepSeekReplaysOrdinaryReasoningOnlyForToolEnabledChat() = withServer { server ->
        val messages = listOf(
            ChatMessage(text = "question", participant = Participant.USER),
            ChatMessage(
                text = "answer",
                participant = Participant.MODEL,
                segments = listOf(MessageSegment(type = "thought", content = "stored reasoning")),
            ),
            ChatMessage(text = "follow up", participant = Participant.USER),
        )
        val enabled = server.capture(
            DeepSeekProvider(),
            config(server, "deepseek-v4").copy(
                tools = listOf(toolDefinition()),
                includeAssistantReasoning = true,
            ),
            messages,
        )
        assertTrue(enabled.toString().contains("stored reasoning"))
        withServer { plainServer ->
            val plain = plainServer.capture(
                DeepSeekProvider(),
                config(plainServer, "deepseek-v4"),
                messages,
            )
            assertFalse(plain.toString().contains("stored reasoning"))
        }
    }
    private fun assertStandardParameters(body: JsonObject) {
        assertEquals(0.7f, body["temperature"]!!.jsonPrimitive.float)
        assertEquals(777, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(0.8f, body["top_p"]!!.jsonPrimitive.float)
        assertEquals(0.2f, body["frequency_penalty"]!!.jsonPrimitive.float)
        assertEquals(-0.1f, body["presence_penalty"]!!.jsonPrimitive.float)
    }

    private fun assertRequestFormat(events: List<StreamEvent>, detail: String) {
        val error = events.filterIsInstance<StreamEvent.Error>().single().error
        assertTrue(error is GenerationError.RequestFormat)
        assertTrue((error as GenerationError.RequestFormat).details.contains(detail))
    }

    private fun assertGroqEffort(
        model: String,
        level: String,
        expected: String,
        checkParameters: Boolean = false,
    ) = withServer { server ->
        val body = server.capture(GroqProvider(), config(server, model).copy(thinkingLevel = level))
        assertEquals(expected, body["reasoning_effort"]!!.jsonPrimitive.content)
        if (checkParameters) assertStandardParameters(body)
    }

    private fun collect(
        provider: LlmProvider,
        config: ProviderConfig,
        messages: List<ChatMessage> =
            listOf(ChatMessage(text = "hello", participant = Participant.USER)),
    ): List<StreamEvent> =
        runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages, config).toList()
            }
        }

    private fun RecordingServer.capture(
        provider: LlmProvider,
        config: ProviderConfig,
        messages: List<ChatMessage> =
            listOf(ChatMessage(text = "hello", participant = Participant.USER)),
    ): JsonObject {
        val events = collect(provider, config, messages)
        assertTrue(events.none { it is StreamEvent.Error })
        return singleBody()
    }
    private fun toolDefinition() = ToolDefinition(
        function = ToolFunction(
            name = "fixture_tool",
            description = "Fixture",
            parameters = ToolParameters(properties = emptyMap()),
        ),
    )

    private fun config(server: RecordingServer, model: String) = ProviderConfig(
        apiKey = "",
        modelId = model,
        baseUrl = server.baseUrl,
        thinkingEnabled = true,
        thinkingLevel = "medium",
        temperature = 0.7f,
        maxTokens = 777,
        topP = 0.8f,
        frequencyPenalty = 0.2f,
        presencePenalty = -0.1f,
    )

    private fun withServer(test: (RecordingServer) -> Unit) {
        RecordingServer().use(test)
    }

    private class RecordingServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bodies = CopyOnWriteArrayList<String>()
        val baseUrl = "http://127.0.0.1:${server.address.port}/v1"

        init {
            server.createContext("/") { exchange ->
                bodies += exchange.requestBody.bufferedReader().use { it.readText() }
                val response = (
                    "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n"
                    ).toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
        }

        fun singleBody() = Json.parseToJsonElement(bodies.single()).jsonObject

        override fun close() = server.stop(0)
    }
}
