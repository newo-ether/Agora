package com.newoether.agora.api.gemini

import android.content.Context
import android.content.pm.ApplicationInfo
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GeminiStreamTerminationTest {
    @Test
    fun flashThinkingOffSendsZeroBudget() {
        val requests = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.add(exchange.requestBody.bufferedReader().use { it.readText() })
            val response = ("data: " + """{"candidates":[{"finishReason":"STOP"}]}""" + "\n\n").toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            runBlocking {
                withTimeout(5_000L) {
                    GeminiProvider().generateResponse(
                        listOf(ChatMessage(text = "hi", participant = Participant.USER)),
                        ProviderConfig(
                            apiKey = "test-key",
                            modelId = "gemini-2.5-flash",
                            baseUrl = "http://127.0.0.1:${server.address.port}",
                            thinkingEnabled = false,
                        ),
                    ).toList()
                }
            }
            val thinking = Json.parseToJsonElement(checkNotNull(requests.poll(1, TimeUnit.SECONDS)))
                .jsonObject.getValue("generationConfig").jsonObject
                .getValue("thinkingConfig").jsonObject
            assertEquals("0", thinking.getValue("thinkingBudget").jsonPrimitive.content)
            assertEquals("false", thinking.getValue("includeThoughts").jsonPrimitive.content)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun systemInstructionUsesCanonicalWireNameForTitlesAndOrdinaryChat() {
        val requests = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.add(exchange.requestBody.bufferedReader().use { it.readText() })
            val response = ("data: {\"candidates\":[{\"content\":{\"role\":\"model\"," +
                "\"parts\":[{\"text\":\"Hello\"}]},\"finishReason\":\"STOP\"}]}\n\n").toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            for (prompt in listOf("You are a title generator.", "Answer in the user's language.", null)) {
                val events = runBlocking {
                    withTimeout(5_000L) {
                        GeminiProvider().generateResponse(
                            messages = listOf(ChatMessage(text = "hi", participant = Participant.USER)),
                            config = ProviderConfig(
                                apiKey = "test-key",
                                modelId = "gemini-2.5-flash",
                                baseUrl = "http://127.0.0.1:${server.address.port}",
                                systemPrompt = prompt,
                            ),
                        ).toList()
                    }
                }
                assertTrue(events.none { it is StreamEvent.Error })
                val body = Json.parseToJsonElement(
                    checkNotNull(requests.poll(1, TimeUnit.SECONDS)),
                ).jsonObject
                assertFalse(body.containsKey("system_instruction"))
                if (prompt == null) {
                    assertFalse(body.containsKey("systemInstruction"))
                } else {
                    assertEquals(
                        prompt,
                        body.getValue("systemInstruction").jsonObject.getValue("parts")
                            .jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
                    )
                }
            }
        } finally {
            server.stop(0)
        }
    }

    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        DebugLog.forceEnabled = false
        DebugLog.init(context)
    }

    @Test
    fun finishReasonProvesSemanticCompletion() {
        val termination = geminiStreamTermination(
            sawDone = false,
            finishReason = normalizeGeminiFinishReason("STOP"),
            producedContent = true,
        )

        assertTrue(termination.sawTerminalMarker)
        assertEquals("stop", termination.stopReason)
        assertNull(termination.toError("Gemini"))
    }

    @Test
    fun eofWithoutFinishReasonIsIncompleteAndOnlyEmptyAttemptRetries() {
        val empty = geminiStreamTermination(false, null, false)
        val partial = geminiStreamTermination(false, null, true)

        assertTrue(empty.isRetryable)
        assertFalse(partial.isRetryable)
        assertTrue(partial.toError("Gemini") is GenerationError.IncompleteStream)
    }

    @Test
    fun outputCapIsReportedWithoutPointlessReplay() {
        val termination = geminiStreamTermination(
            sawDone = false,
            finishReason = normalizeGeminiFinishReason("MAX_TOKENS"),
            producedContent = true,
        )

        assertFalse(termination.isRetryable)
        assertTrue(termination.toError("Gemini") is GenerationError.OutputTruncated)
    }

    @Test
    fun streamErrorRetriesOnlyBeforeVisibleOutput() {
        val error = GenerationError.Api(null, "failed_to_generate", "failed to generate")

        assertTrue(geminiStreamTermination(false, null, false, error).isRetryable)
        assertFalse(geminiStreamTermination(false, null, true, error).isRetryable)
    }

    @Test
    fun timeoutUsesSharedTerminationPolicy() {
        val termination = geminiStreamTermination(
            sawDone = false,
            finishReason = null,
            producedContent = false,
            timedOut = true,
        )

        assertTrue(termination.isRetryable)
        assertEquals(GenerationError.Timeout, termination.toError("Gemini"))
    }

    @Test
    fun functionCallPartsEmitStreamingUpdatesBeforeTheirRequests() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val response = (
                "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[" +
                    "{\"functionCall\":{\"id\":\"call-1\",\"name\":\"file_read\"," +
                    "\"args\":{\"path\":\"a.txt\"}}}," +
                    "{\"functionCall\":{\"id\":\"call-2\",\"name\":\"file_read\"," +
                    "\"args\":{\"path\":\"b.txt\"}}}]},\"finishReason\":\"STOP\"}]}\n\n"
                ).toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val events = runBlocking {
                withTimeout(2_000L) {
                    GeminiProvider().generateResponse(
                        messages = listOf(
                            ChatMessage(text = "inspect", participant = Participant.USER),
                        ),
                        config = ProviderConfig(
                            apiKey = "test-key",
                            modelId = "gemini-2.5-flash",
                            baseUrl = "http://127.0.0.1:${server.address.port}",
                        ),
                    ).toList()
                }
            }

            val toolEvents = events.filter {
                it is StreamEvent.ToolCallUpdate || it is StreamEvent.ToolCallRequest
            }
            assertEquals(4, toolEvents.size)
            val firstUpdate = toolEvents[0] as StreamEvent.ToolCallUpdate
            val firstRequest = toolEvents[1] as StreamEvent.ToolCallRequest
            val secondUpdate = toolEvents[2] as StreamEvent.ToolCallUpdate
            val secondRequest = toolEvents[3] as StreamEvent.ToolCallRequest
            assertEquals(listOf("call-1", "call-2"), listOf(firstUpdate.id, secondUpdate.id))
            assertEquals(firstUpdate.streamKey, firstRequest.streamKey)
            assertEquals(secondUpdate.streamKey, secondRequest.streamKey)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun blankThoughtFragmentsAndIdsDoNotSuppressEffectiveGeminiMetadata() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val response = (
                "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[" +
                    "{\"thought\":true,\"text\":\" \",\"thoughtSignature\":\"sig-1\"}," +
                    "{\"text\":\"reason\"}," +
                    "{\"thoughtSignature\":\" \",\"reasoning_content\":\" \"," +
                    "\"functionCall\":{\"id\":\" \",\"name\":\"file_read\"," +
                    "\"args\":{\"path\":\"a.txt\"},\"thought_signature\":\"sig-tool\"}}" +
                    "]},\"finishReason\":\"STOP\"}]}\n\n"
                ).toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val events = runBlocking {
                withTimeout(2_000L) {
                    GeminiProvider().generateResponse(
                        messages = listOf(
                            ChatMessage(text = "inspect", participant = Participant.USER),
                        ),
                        config = ProviderConfig(
                            apiKey = "test-key",
                            modelId = "gemini-2.5-flash",
                            baseUrl = "http://127.0.0.1:${server.address.port}",
                            thinkingEnabled = false,
                        ),
                    ).toList()
                }
            }

            // Whitespace inside a thought part is real formatting and is forwarded verbatim, while
            // the blank thoughtSignature and blank functionCall id stay rejected. Each part carries
            // its own thought identity, so the unflagged "reason" part is answer text, not thinking.
            val thoughts = events.filterIsInstance<StreamEvent.ThoughtChunk>()
            assertEquals(listOf(" ", " "), thoughts.map { it.thought })
            assertTrue(thoughts.all { it.signature == "sig-1" })
            assertEquals(
                listOf("reason"),
                events.filterIsInstance<StreamEvent.TextChunk>().map { it.text },
            )
            val update = events.filterIsInstance<StreamEvent.ToolCallUpdate>().single()
            val call = events.filterIsInstance<StreamEvent.ToolCallRequest>().single()
            assertTrue(update.id?.isNotBlank() == true)
            assertEquals(update.id, call.id)
            assertEquals("sig-tool", update.signature)
            assertEquals("sig-tool", call.signature)
            assertTrue(events.none { it is StreamEvent.Error })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun terminalMarkerCannotCompletePendingCodeExecution() {
        val termination = geminiStreamTermination(
            sawDone = true,
            finishReason = "stop",
            producedContent = true,
            toolCallInFlight = true,
        )

        assertFalse(termination.isRetryable)
        assertTrue(termination.toError("Gemini") is GenerationError.IncompleteStream)
    }

    @Test
    fun terminalMarkerCannotCompleteAnInvalidOpenFunctionCall() {
        val error = GenerationError.SseParse(
            rawLine = "functionCall",
            cause = "Gemini returned a blank or invalid tool name",
        )
        val termination = geminiStreamTermination(
            sawDone = true,
            finishReason = "stop",
            producedContent = false,
            streamError = error,
            toolCallInFlight = true,
        )

        assertTrue(termination.isRetryable)
        assertEquals(error, termination.toError("Gemini"))
    }
}
