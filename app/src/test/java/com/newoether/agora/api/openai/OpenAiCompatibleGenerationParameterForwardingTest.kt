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
import com.newoether.agora.model.ModelThinkingCapabilities
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
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
    fun qwenThinkingOnlyModelKeepsThinkingOnInsteadOfFailing() = withServer { server ->
        val body = server.capture(
            QwenProvider(),
            config(server, "qwen3-235b-a22b-thinking-2507").copy(thinkingEnabled = false),
        )

        assertTrue(body["enable_thinking"]!!.jsonPrimitive.boolean)
        assertFalse(body.containsKey("thinking_budget"))
    }

    @Test
    fun groqForwardsEachDocumentedModelEffortRange() {
        assertGroqEffort("qwen/qwen3.6-27b", "xhigh", "default")
        assertGroqEffort("qwen/qwen3.8-27b", "xhigh", "high", checkParameters = true)
        assertGroqEffort("openai/gpt-oss-20b", "minimal", "low")
    }

    @Test
    fun groqGptOssOffHidesReasoningAtLowestEffort() = withServer { server ->
        val body = server.capture(
            GroqProvider(),
            config(server, "openai/gpt-oss-120b").copy(thinkingEnabled = false),
        )

        assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(body["include_reasoning"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun customQwen38ForwardsMappedEffortAndStandardParameters() = withServer { server ->
        val body = server.capture(
            CustomOpenAiProvider("Relay", server.baseUrl),
            config(server, "qwen/qwen3.8-27b").copy(thinkingLevel = "max"),
        )
        assertEquals("xhigh", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("xhigh", body["chat_template_kwargs"]!!.jsonObject["reasoning_effort"]!!.jsonPrimitive.content)
        assertTrue(body["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean)
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
        val capability = ModelThinkingCapabilities.forProvider(
            providerName = Constants.PROVIDER_DEEPSEEK,
            modelId = "deepseek-v4",
        )
        assertEquals(listOf("low", "high", "max"), capability.supportedEfforts)
        assertEquals("low", capability.nearestEffort("minimal"))
        assertEquals("high", capability.nearestEffort("medium"))
        assertEquals("max", capability.nearestEffort("xhigh"))
        assertEquals("max", capability.nearestEffort("max"))
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
    fun customEndpointClampsKnownEffortsAndPreservesUnknownOff() = withServer { server ->
        val enabled = server.capture(
            CustomOpenAiProvider("Relay", server.baseUrl),
            config(server, "DeepSeek/DeepSeek-V4").copy(thinkingLevel = "xhigh"),
        )
        // The transport stays OpenAI-compatible; only the documented effort range is applied.
        assertFalse(enabled.containsKey("thinking"))
        assertEquals("max", enabled["reasoning_effort"]!!.jsonPrimitive.content)
        assertStandardParameters(enabled)

        withServer { offServer ->
            val disabled = offServer.capture(
                CustomOpenAiProvider("Relay", offServer.baseUrl),
                config(offServer, "deepseek-chat").copy(thinkingEnabled = false),
            )
            assertFalse(disabled.containsKey("thinking"))
            assertEquals("none", disabled["reasoning_effort"]!!.jsonPrimitive.content)
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
    @Test
    fun unsetMaxTokensGetsTheClaudeDefaultForKnownModelsOnly() = withServer { deepSeekServer ->
        // DeepSeek's own default is small enough to cut off compacts, which run without thinking.
        val deepSeek = deepSeekServer.capture(
            DeepSeekProvider(),
            config(deepSeekServer, "deepseek-flash").copy(maxTokens = null, thinkingEnabled = false),
        )
        assertEquals(32768, deepSeek["max_tokens"]!!.jsonPrimitive.int)

        withServer { claudeServer ->
            val relayClaude = claudeServer.capture(
                CustomOpenAiProvider("Relay", claudeServer.baseUrl),
                config(claudeServer, "anthropic/claude-3-opus").copy(maxTokens = null),
            )
            // Lowered to the documented ceiling, as on the native Anthropic path.
            assertEquals(4096, relayClaude["max_tokens"]!!.jsonPrimitive.int)
        }
        withServer { budgetServer ->
            val budgeted = budgetServer.capture(
                CustomOpenAiProvider("Relay", budgetServer.baseUrl),
                config(budgetServer, "claude-opus-5").copy(
                    maxTokens = null,
                    thinkingBudgetEnabled = true,
                    thinkingBudgetTokens = 40000,
                ),
            )
            assertEquals(48192, budgeted["max_tokens"]!!.jsonPrimitive.int)
        }
        withServer { unknownServer ->
            val unknown = unknownServer.capture(
                CustomOpenAiProvider("Relay", unknownServer.baseUrl),
                config(unknownServer, "llama-3.3-70b").copy(maxTokens = null),
            )
            assertFalse(unknown.containsKey("max_tokens"))
        }
    }

    @Test
    fun customQwenTemplateOffBudgetAndResponsesShareResolvedControls() {
        listOf(false, true).forEach { responses ->
            withServer { server ->
                val provider = CustomOpenAiProvider("Strata relay", server.baseUrl)
                val off = server.capture(provider, config(server, "Qwen/Qwen3.8-27B").copy(
                    thinkingEnabled = false, responsesApiEnabled = responses))
                assertFalse(off["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean)
                assertEquals("none", off["reasoning_effort"]!!.jsonPrimitive.content)
                assertFalse(off["chat_template_kwargs"]!!.jsonObject.containsKey("reasoning_effort"))
            }
            withServer { server ->
                val provider = CustomOpenAiProvider("Strata relay", server.baseUrl)
                val on = server.capture(provider, config(server, "qwen3.8-27b").copy(
                    thinkingLevel = "max", responsesApiEnabled = responses))
                assertEquals("xhigh", on["reasoning_effort"]!!.jsonPrimitive.content)
                assertEquals("xhigh", on["chat_template_kwargs"]!!.jsonObject["reasoning_effort"]!!.jsonPrimitive.content)
            }
            withServer { server ->
                val budget = server.capture(CustomOpenAiProvider("Relay", server.baseUrl),
                    config(server, "qwen3.8-27b").copy(thinkingBudgetEnabled = true,
                        thinkingBudgetTokens = 8192, responsesApiEnabled = responses))
                assertEquals(8192, budget["thinking_budget"]!!.jsonPrimitive.int)
                assertFalse(budget.containsKey("reasoning_effort"))
                assertFalse(budget["chat_template_kwargs"]!!.jsonObject.containsKey("reasoning_effort"))
            }
        }
    }

    @Test
    fun knownGlmIdsClampSavedEffortsAndForcedOffInBothTransports() {
        listOf(false, true).forEach { responses ->
            listOf("glm-5-3-flash-260828", "z-ai/glm-5.3", "glm-5-2-260617").forEach { id ->
                withServer { server ->
                    val body = server.capture(CustomOpenAiProvider("Ark", server.baseUrl),
                        config(server, id).copy(thinkingLevel = "medium", responsesApiEnabled = responses))
                    val effort = if (responses) body["reasoning"]!!.jsonObject["effort"] else body["reasoning_effort"]
                    assertEquals("high", effort!!.jsonPrimitive.content)
                    assertFalse(body.containsKey("chat_template_kwargs"))
                }
            }
            withServer { server ->
                val body = server.capture(CustomOpenAiProvider("Ark", server.baseUrl),
                    config(server, "z-ai/glm-5.3").copy(thinkingEnabled = false,
                        responsesApiEnabled = responses))
                val effort = if (responses) body["reasoning"]!!.jsonObject["effort"] else body["reasoning_effort"]
                assertEquals("low", effort!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun nativeOpenAi61SolResponsesCannotSendDisabledOrIllegalEffort() = withServer { server ->
        val body = server.capture(OpenAiProvider(), config(server, "gpt-6.1-sol").copy(
            thinkingEnabled = false, thinkingLevel = "minimal", responsesApiEnabled = true))
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun claudeCompatibleRequestsOmitSamplingAndManualBudget() {
        listOf(false, true).forEach { responses ->
            withServer { server ->
                val body = server.capture(CustomOpenAiProvider("Relay", server.baseUrl),
                    config(server, "anthropic/claude-opus-5.5").copy(thinkingEnabled = false,
                        thinkingBudgetEnabled = true, responsesApiEnabled = responses))
                listOf("temperature", "top_p", "frequency_penalty", "presence_penalty", "thinking_budget")
                    .forEach { assertFalse(it, body.containsKey(it)) }
                val effort = if (responses) body["reasoning"]!!.jsonObject["effort"] else body["reasoning_effort"]
                assertEquals("low", effort!!.jsonPrimitive.content)
            }
        }
        withServer { server ->
            val body = server.capture(OpenRouterProvider(), config(server, "anthropic/claude-sonnet-5.5"))
            assertFalse(body.containsKey("temperature"))
            assertFalse(body.containsKey("top_p"))
            assertEquals("medium", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
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
                val response = (if (exchange.requestURI.path.endsWith("/responses")) {
                    "data: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{\"status\":\"completed\"}}\n\n"
                } else {
                    "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n"
                }).toByteArray()
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
