package com.newoether.agora.api

import android.content.Context
import com.newoether.agora.api.anthropic.AnthropicProvider
import com.newoether.agora.api.gemini.GeminiProvider
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.api.ollama.OllamaProvider
import com.newoether.agora.api.openai.CustomOpenAiProvider
import com.newoether.agora.api.util.prepareMessages
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.tool.BuiltInToolNames
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.CloudDebugLog
import com.sun.net.httpserver.HttpServer
import io.mockk.*
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BuiltInToolWireNamesTest {
    @Before
    fun logging() {
        mockkObject(DebugLog, CloudDebugLog)
        every { DebugLog.d(any(), any()) } just Runs
        every { DebugLog.w(any(), any()) } just Runs
        every { DebugLog.e(any(), any()) } just Runs
        every { CloudDebugLog.d(any(), any()) } just Runs
        every { CloudDebugLog.w(any(), any()) } just Runs
        every { CloudDebugLog.e(any(), any()) } just Runs
    }

    @After
    fun cleanup() = unmockkObject(DebugLog, CloudDebugLog)

    @Test
    fun everyRemoteWireAdapterUsesCanonicalNamesForLegacyRounds() = runBlocking {
        val history = history()
        val expected = BuiltInToolNames.legacyToCanonical.values.toSet()
        for (kind in listOf("chat", "responses", "anthropic", "gemini", "ollama")) {
            var captured = ""
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                captured = exchange.requestBody.bufferedReader().use { it.readText() }
                val body = when (kind) {
                    "responses" -> "data: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{\"status\":\"completed\"}}\n\n"
                    "anthropic" -> "data: {\"type\":\"message_stop\"}\n\n"
                    "gemini" -> "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}\n\n"
                    "ollama" -> "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true,\"done_reason\":\"stop\"}\n"
                    else -> "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                }.toByteArray()
                exchange.responseHeaders.add("Content-Type", if (kind == "ollama") "application/x-ndjson" else "text/event-stream")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val baseUrl = "http://127.0.0.1:${server.address.port}"
                val provider = when (kind) {
                    "anthropic" -> AnthropicProvider(defaultBaseUrl = baseUrl)
                    "gemini" -> GeminiProvider(defaultBaseUrl = baseUrl)
                    "ollama" -> OllamaProvider()
                    else -> CustomOpenAiProvider("wire-test", baseUrl)
                }
                val events = provider.generateResponse(history, ProviderConfig(
                    apiKey = "", modelId = if (kind == "anthropic") "claude-3-5-sonnet-20240620" else "test",
                    baseUrl = baseUrl, thinkingEnabled = false, responsesApiEnabled = kind == "responses",
                    tools = expected.map { ToolDefinition(function = ToolFunction(it, "test", ToolParameters(properties = emptyMap()))) },
                    requestResolver = ProviderRequestResolver { messages, _ ->
                        ProviderRequestInput(prepareMessages(messages, 100_000), null)
                    },
                )).toList()
                assertTrue("$kind: $events", events.none { it is StreamEvent.Error })
                val names = mutableSetOf<String>()
                fun collect(value: JsonElement) {
                    when (value) {
                        is JsonObject -> value.forEach { (key, child) ->
                            if (key == "name" && child is JsonPrimitive) names += child.content
                            collect(child)
                        }
                        is JsonArray -> value.forEach(::collect)
                        else -> Unit
                    }
                }
                collect(Json.parseToJsonElement(captured))
                assertTrue("$kind: missing ${expected - names}", names.containsAll(expected))
                assertTrue("$kind contains old names", names.intersect(BuiltInToolNames.legacyToCanonical.keys).isEmpty())
                assertFalse("$kind lost protocol", captured.contains("Archived tool activity"))
                // Each canonical name must occur in history too, not just the definitions.
                expected.forEach { assertTrue("$kind: $it", Regex("\"name\"\\s*:\\s*\"$it\"").findAll(captured).count() >= 2) }
            } finally {
                server.stop(0)
            }
        }
        assertEquals("read_memory_file", history[1].segments!!.first().toolName)
    }

    @Test
    fun customResolverAndLocalTemplatePreserveCallsAndResults() = runBlocking {
        val history = history()
        val config = ProviderConfig("", "test", requestResolver = ProviderRequestResolver { messages, _ ->
            ProviderRequestInput(messages, "fixed")
        })
        val resolved = config.resolveRequest(history)
        val provider = LocalProvider(mockk<Context>(), mockk<SettingsRepository>())
        val method = LocalProvider::class.java.getDeclaredMethod(
            "buildTemplateMessages", List::class.java, String::class.java, MutableList::class.java,
        ).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val template = method.invoke(provider, resolved.messages, resolved.systemPrompt, null) as List<ChatTemplateMessage>
        assertEquals(BuiltInToolNames.legacyToCanonical.values.toSet(), template.flatMap { it.toolCalls.toList() }.map { it.name }.toSet())
        assertEquals(BuiltInToolNames.legacyToCanonical.values.toSet(), template.filter { it.role == "tool" }.map { it.toolName }.toSet())
        assertEquals("fixed", template.first().content)
        assertEquals("read_memory_file", history[1].segments!!.first().toolName)
    }

    private fun history(): List<ChatMessage> {
        val calls = BuiltInToolNames.legacyToCanonical.keys.mapIndexed { index, name ->
            MessageSegment(type = "tool", toolName = name, toolArgs = "{}", toolCallId = "call_$index")
        }
        return listOf(
            ChatMessage(id = "user", text = "start", participant = Participant.USER),
            ChatMessage(id = "tool_round", text = "", participant = Participant.MODEL, segments = calls),
        ) + calls.mapIndexed { index, call ->
            ChatMessage(id = "result_$index", text = "ok", participant = Participant.USER,
                segments = listOf(call.copy(toolResult = "ok")))
        } + ChatMessage(id = "last", text = "continue", participant = Participant.USER)
    }
}
