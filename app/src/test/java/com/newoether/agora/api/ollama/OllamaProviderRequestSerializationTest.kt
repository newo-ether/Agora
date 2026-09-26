package com.newoether.agora.api.ollama

import android.content.Context
import android.content.pm.ApplicationInfo
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.util.RequestFormatException
import com.newoether.agora.model.ChatMessage
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class OllamaProviderRequestSerializationTest {
    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        DebugLog.forceEnabled = false
        DebugLog.init(context)
    }

    @Test
    fun ordinaryModelsForwardBooleanThinkAndGenerationOptions() = withServer { server ->
        val enabled = server.capture(config(server, "qwen3:8b").copy(
            frequencyPenalty = 0.2f, presencePenalty = -0.1f,
        ))
        assertTrue(enabled["think"]!!.jsonPrimitive.boolean)
        val options = enabled["options"]!!.jsonObject
        assertEquals(0.7f, options["temperature"]!!.jsonPrimitive.float)
        assertEquals(0.8f, options["top_p"]!!.jsonPrimitive.float)
        assertEquals(777, options["num_predict"]!!.jsonPrimitive.int)
        assertFalse(options.containsKey("frequency_penalty"))
        assertFalse(options.containsKey("presence_penalty"))

        val disabled = server.capture(
            config(server, "llama3.2:3b").copy(thinkingEnabled = false),
        )
        assertFalse(disabled["think"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun gptOssForwardsOnlySupportedEffortStrings() = withServer { server ->
        assertEquals("low", server.capture(config(server, "gpt-oss:20b").copy(
            thinkingLevel = "minimal",
        ))["think"]!!.jsonPrimitive.content)
        assertEquals("medium", server.capture(config(server, "gpt-oss:20b"))["think"]!!.jsonPrimitive.content)
        assertEquals("high", server.capture(config(server, "library/gpt-oss:120b").copy(
            thinkingLevel = "max",
        ))["think"]!!.jsonPrimitive.content)
    }

    @Test
    fun thinkingOffIsSentAsFalseInsteadOfFailing() = withServer { server ->
        val off = server.capture(config(server, "gpt-oss:20b").copy(thinkingEnabled = false))
        val none = server.capture(config(server, "gpt-oss:120b").copy(thinkingLevel = "none"))

        assertEquals(false, off["think"]!!.jsonPrimitive.boolean)
        assertEquals("low", none["think"]!!.jsonPrimitive.content)
        assertTrue(server.bodies.isNotEmpty())
    }

    @Test
    fun multiToolSnapshotEmitsEveryUpdateBeforeTheBatchRequest() = withServer { server ->
        server.responseBody = (
            "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[" +
                "{\"id\":\"call-1\",\"function\":{\"name\":\"file_read\"," +
                "\"arguments\":{\"path\":\"a.txt\"}}}," +
                "{\"id\":\"call-2\",\"function\":{\"name\":\"file_read\"," +
                "\"arguments\":{\"path\":\"b.txt\"}}}]}," +
                "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":1,\"eval_count\":1}\n"
            )
        val events = collect(server, config(server, "qwen3:8b"))
        val toolEvents = events.filter {
            it is StreamEvent.ToolCallUpdate || it is StreamEvent.ToolCallsRequest
        }

        assertEquals(3, toolEvents.size)
        val updates = toolEvents.take(2).map { it as StreamEvent.ToolCallUpdate }
        val batch = toolEvents.last() as StreamEvent.ToolCallsRequest
        assertEquals(listOf("call-1", "call-2"), updates.map { it.id })
        assertEquals(updates.map { it.streamKey }, batch.calls.map { it.streamKey })
    }

    @Test
    fun returnedThinkingAndBlankToolIdRemainEffectiveWhenRequestThinkingIsDisabled() = withServer { server ->
        server.responseBody = (
            "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\"reason\"," +
                "\"tool_calls\":[{\"id\":\" \",\"function\":{\"name\":\"file_read\"," +
                "\"arguments\":{\"path\":\"a.txt\"}}}]}," +
                "\"done\":true,\"done_reason\":\" \",\"prompt_eval_count\":1,\"eval_count\":1}\n"
            )
        val events = collect(
            server,
            config(server, "qwen3:8b").copy(thinkingEnabled = false),
        )

        assertEquals("reason", events.filterIsInstance<StreamEvent.ThoughtChunk>().single().thought)
        val update = events.filterIsInstance<StreamEvent.ToolCallUpdate>().single()
        val call = events.filterIsInstance<StreamEvent.ToolCallRequest>().single()
        assertTrue(update.id?.isNotBlank() == true)
        assertEquals(update.id, call.id)
        assertEquals("file_read", call.name)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun blankTerminalDoneReasonCannotEraseEarlierEffectiveValue() = withServer { server ->
        server.responseBody =
            "{\"message\":{\"role\":\"assistant\",\"content\":\"partial\"}," +
                "\"done\":false,\"done_reason\":\"length\"}\n" +
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\"}," +
                "\"done\":true,\"done_reason\":\" \",\"prompt_eval_count\":1,\"eval_count\":1}\n"
        val events = collect(server, config(server, "qwen3:8b"))

        assertTrue(
            events.filterIsInstance<StreamEvent.Error>().single().error is GenerationError.OutputTruncated,
        )
    }

    @Test
    fun validatorAcceptsOnlyBooleanOrSupportedEffortStrings() {
        listOf(JsonPrimitive(true), JsonPrimitive(false), JsonPrimitive("low"), JsonPrimitive("medium"), JsonPrimitive("high"))
            .forEach { think -> request(think).requireValidWireFormat() }

        listOf(JsonPrimitive("none"), JsonPrimitive("xhigh"), JsonObject(emptyMap()))
            .forEach { think ->
                assertTrue(
                    runCatching { request(think).requireValidWireFormat() }.exceptionOrNull()
                        is RequestFormatException,
                )
            }
    }

    private fun assertRequestFormat(events: List<StreamEvent>) {
        val error = events.filterIsInstance<StreamEvent.Error>().single().error
        assertTrue(error is GenerationError.RequestFormat)
        assertTrue((error as GenerationError.RequestFormat).details.contains("cannot disable thinking"))
    }

    private fun request(think: kotlinx.serialization.json.JsonElement) = OllamaChatRequest(
        model = "test",
        messages = listOf(OllamaMessage(role = "user", content = "hello")),
        think = think,
    )

    private fun collect(server: RecordingServer, config: ProviderConfig): List<StreamEvent> =
        runBlocking {
            withTimeout(2_000L) {
                OllamaProvider().generateResponse(
                    listOf(ChatMessage(text = "hello", participant = Participant.USER)),
                    config,
                ).toList()
            }
        }

    private fun RecordingServer.capture(config: ProviderConfig) =
        collect(this, config).let { events ->
            assertTrue(events.none { it is StreamEvent.Error })
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
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        @Volatile
        var responseBody =
            "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}," +
                "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":1,\"eval_count\":1}\n"

        init {
            server.createContext("/") { exchange ->
                bodies += exchange.requestBody.bufferedReader().use { it.readText() }
                val response = responseBody.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/x-ndjson")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }
}
