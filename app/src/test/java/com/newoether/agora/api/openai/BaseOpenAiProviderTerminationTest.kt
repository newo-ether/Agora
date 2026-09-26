package com.newoether.agora.api.openai

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BaseOpenAiProviderTerminationTest : OpenAiSseTestFixture() {

    @Test
    fun finishReasonWithoutDone_completesWithinGraceWindow() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"content":"complete"},"finish_reason":"stop"}]}"""
            )
            release.await()
        },
    ) { provider, config, _ ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        assertEquals(
            "complete",
            events.filterIsInstance<StreamEvent.TextChunk>().joinToString("") { it.text },
        )
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun finishReason_stillAcceptsTrailingUsageAndDone() = withServer(
        terminalGraceMillis = 500L,
        response = { socket, _ ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"content":"complete"},"finish_reason":"stop"}]}"""
            )
            Thread.sleep(25L)
            socket.writeSse(
                """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":7,"total_tokens":17}}"""
            )
            socket.writeSse("[DONE]")
        },
    ) { provider, config, _ ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        val usage = events.filterIsInstance<StreamEvent.UsageUpdate>().single().usage
        assertEquals(17, usage.totalTokenCount)
        assertEquals(10, usage.inputTokenCount)
        assertEquals(7, usage.outputTokenCount)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun returnedChatReasoningIsSurfacedWhenRequestThinkingIsDisabled() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"reasoning_content":"reason"},"finish_reason":"stop"}]}"""
            )
            release.await()
        },
    ) { provider, config, _ ->
        val events = collect(provider, config)

        assertFalse(config.thinkingEnabled)
        assertEquals("reason", events.filterIsInstance<StreamEvent.ThoughtChunk>().single().thought)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun openRouterBlankReasoningDetailsDoNotSuppressEffectiveFallback() = withServer(
        terminalGraceMillis = 100L,
        providerFactory = { OpenRouterProvider() },
        response = { socket, release ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"reasoning_details":[{"type":"reasoning.text","text":" "}],"reasoning":"fallback"},"finish_reason":"stop"}]}"""
            )
            release.await()
        },
    ) { provider, config, _ ->
        val events = collect(provider, config)

        assertFalse(config.thinkingEnabled)
        // The whitespace detail is forwarded verbatim (it may be a real line break), and the
        // fallback field still reaches the UI because a blank detail is not effective reasoning.
        assertEquals(
            listOf(" ", "fallback"),
            events.filterIsInstance<StreamEvent.ThoughtChunk>().map { it.thought },
        )
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun returnedResponsesReasoningIsSurfacedWhenRequestThinkingIsDisabled() = withServer(
        terminalGraceMillis = 100L,
        responsesApiEnabled = true,
        response = { socket, _ ->
            socket.writeSse(
                """{"type":"response.reasoning_text.delta","sequence_number":1,"delta":"reason"}"""
            )
            socket.writeSse(
                """{"type":"response.completed","sequence_number":2,"response":{"status":"completed"}}"""
            )
        },
    ) { provider, config, _ ->
        val events = collect(provider, config)

        assertFalse(config.thinkingEnabled)
        assertEquals("reason", events.filterIsInstance<StreamEvent.ThoughtChunk>().single().thought)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun blankStructuredToolIdentityDeltaCannotEraseEffectiveValues() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{"}}]},"finish_reason":null}]}"""
            )
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":" ","type":"function","function":{"name":" ","arguments":"}"}}]},"finish_reason":"stop"}]}"""
            )
            release.await()
        },
    ) { provider, config, _ ->
        val events = collect(provider, config)
        val call = events.filterIsInstance<StreamEvent.ToolCallRequest>().single()

        assertEquals("call_1", call.id)
        assertEquals("lookup", call.name)
        assertEquals("{}", call.arguments)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun toolCallFinishReason_emitsCallAndDoesNotRequireDone() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
            )
            release.await()
        },
    ) { provider, config, _ ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        val call = events.filterIsInstance<StreamEvent.ToolCallRequest>().single()
        assertEquals("call_1", call.id)
        assertEquals("lookup", call.name)
        assertEquals("{}", call.arguments)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun structuredToolCall_streamsSnapshotsAndStopStillCompletesCall() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"file_edit","arguments":"{"}}]},"finish_reason":null}]}"""
            )
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]},"finish_reason":null}]}"""
            )
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
            )
            release.await()
        },
    ) { provider, config, _ ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        val updates = events.filterIsInstance<StreamEvent.ToolCallUpdate>()
        assertEquals(listOf("{", "{}"), updates.map { it.arguments })
        assertEquals(1, updates.map { it.streamKey }.distinct().size)
        val call = events.filterIsInstance<StreamEvent.ToolCallRequest>().single()
        assertEquals("call_1", call.id)
        assertEquals("file_edit", call.name)
        assertEquals("{}", call.arguments)
        assertEquals(updates.first().streamKey, call.streamKey)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun structuredToolCall_doneWithoutFinishReasonStillCompletesCall() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, _ ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_done","type":"function","function":{"name":"file_read","arguments":"{}"}}]},"finish_reason":null}]}"""
            )
            socket.writeSse("[DONE]")
        },
    ) { provider, config, _ ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        assertEquals(1, events.filterIsInstance<StreamEvent.ToolCallUpdate>().size)
        val call = events.filterIsInstance<StreamEvent.ToolCallRequest>().single()
        assertEquals("call_done", call.id)
        assertEquals("file_read", call.name)
        assertEquals("{}", call.arguments)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun invalidStructuredToolMetadataProducesOneErrorAndNoExecutableCall() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"bad name","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
            )
            release.await()
        },
    ) { provider, config, _ ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        assertTrue(events.none { it is StreamEvent.ToolCallRequest })
        assertEquals(1, events.filterIsInstance<StreamEvent.Error>().size)
    }

    @Test
    fun duplicateStructuredToolIdsRejectTheWholeBatch() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_same","type":"function","function":{"name":"file_read","arguments":"{}"}},{"index":1,"id":"call_same","type":"function","function":{"name":"file_write","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
            )
            release.await()
        },
    ) { provider, config, _ ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config).toList()
            }
        }

        assertTrue(events.none { it is StreamEvent.ToolCallRequest || it is StreamEvent.ToolCallsRequest })
        assertEquals(1, events.filterIsInstance<StreamEvent.Error>().size)
    }

    @Test
    fun taggedTextToolCall_isForwardedAsRawTextForDownstreamNormalization() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeContentSse("prefix <tool_")
            socket.writeContentSse("call>")
            socket.writeContentSse("{\"name\":\"file_edit\",\"arguments\":{\"path\":\"")
            socket.writeContentSse("a.txt\"}}</tool_call>", finishReason = "stop")
            release.await()
        },
    ) { provider, config, _ ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config.withTools()).toList()
            }
        }

        assertEquals(
            "prefix <tool_call>{\"name\":\"file_edit\",\"arguments\":{\"path\":\"a.txt\"}}</tool_call>",
            events.filterIsInstance<StreamEvent.TextChunk>().joinToString("") { it.text },
        )
        assertTrue(events.none { it is StreamEvent.ToolCallUpdate })
        assertTrue(events.none { it is StreamEvent.ToolCallRequest })
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun incompleteTextToolCall_isForwardedAsRawTextForDownstreamNormalization() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeContentSse("<tool_call>")
            socket.writeContentSse("{\"name\":\"file_edit\",\"arguments\":{\"path\":\"unfinished")
            socket.writeContentSse("", finishReason = "stop")
            release.await()
        },
    ) { provider, config, _ ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config.withTools()).toList()
            }
        }

        assertEquals(
            "<tool_call>{\"name\":\"file_edit\",\"arguments\":{\"path\":\"unfinished",
            events.filterIsInstance<StreamEvent.TextChunk>().joinToString("") { it.text },
        )
        assertTrue(events.none { it is StreamEvent.ToolCallUpdate })
        assertTrue(events.none { it is StreamEvent.ToolCallRequest })
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun bareJsonTextToolCall_isForwardedAsRawTextForDownstreamNormalization() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, release ->
            socket.writeContentSse("{\"name\":\"file_read\",\"arguments\":{\"path\":\"")
            socket.writeContentSse("a.txt\"}}", finishReason = "stop")
            release.await()
        },
    ) { provider, config, _ ->
        val events = runBlocking {
            withTimeout(2_000L) {
                provider.generateResponse(messages(), config.withTools()).toList()
            }
        }

        assertEquals(
            "{\"name\":\"file_read\",\"arguments\":{\"path\":\"a.txt\"}}",
            events.filterIsInstance<StreamEvent.TextChunk>().joinToString("") { it.text },
        )
        assertTrue(events.none { it is StreamEvent.ToolCallUpdate })
        assertTrue(events.none { it is StreamEvent.ToolCallRequest })
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun responsesTransportUsesResponsesPathBodyAndCompletedUsage() = withServer(
        terminalGraceMillis = 100L,
        responsesApiEnabled = true,
        response = { socket, _ ->
            socket.writeSse(
                """{"type":"response.output_text.delta","sequence_number":1,"delta":"complete"}"""
            )
            socket.writeSse(
                """{"type":"response.completed","sequence_number":2,"response":{"status":"completed","usage":{"input_tokens":10,"output_tokens":7,"total_tokens":17}}}"""
            )
        },
    ) { provider, config, server ->
        val events = collect(provider, config)
        assertEquals("POST /v1/responses HTTP/1.1", server.requests.single().requestLine)
        val body = WIRE_JSON.parseToJsonElement(server.requests.single().body).jsonObject
        assertTrue(body.containsKey("input"))
        assertFalse(body.containsKey("messages"))
        assertEquals("complete", events.filterIsInstance<StreamEvent.TextChunk>().single().text)
        assertEquals(17, events.filterIsInstance<StreamEvent.UsageUpdate>().single().usage.totalTokenCount)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun responsesRequestEmitsServiceTierSummaryAndHostedSearchEvents() = withServer(
        terminalGraceMillis = 100L,
        responsesApiEnabled = true,
        response = { socket, _ ->
            socket.writeSse(
                """{"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"id":"ws_1","type":"web_search_call","status":"in_progress"}}""",
            )
            socket.writeSse(
                """{"type":"response.web_search_call.in_progress","sequence_number":2,"output_index":0,"item_id":"ws_1"}""",
            )
            socket.writeSse(
                """{"type":"response.web_search_call.searching","sequence_number":3,"output_index":0,"item_id":"ws_1"}""",
            )
            socket.writeSse(
                """{"type":"response.web_search_call.completed","sequence_number":4,"output_index":0,"item_id":"ws_1"}""",
            )
            socket.writeSse(
                """{"type":"response.output_item.done","sequence_number":5,"output_index":0,"item":{"id":"ws_1","type":"web_search_call","status":"completed","action":{"type":"search","query":"latest Agora"}}}""",
            )
            socket.writeSse(
                """{"type":"response.reasoning_summary_text.delta","sequence_number":6,"output_index":1,"summary_index":0,"delta":"**Checked current sources**"}""",
            )
            socket.writeSse(
                """{"type":"response.output_text.delta","sequence_number":7,"delta":"Answer"}""",
            )
            socket.writeSse(
                """{"type":"response.completed","sequence_number":8,"response":{"status":"completed"}}""",
            )
        },
    ) { provider, config, server ->
        val events = collect(
            provider,
            config.copy(
                thinkingEnabled = true,
                thinkingLevel = "low",
                openAiServiceTier = "ultrafast",
                openAiWebSearchEnabled = true,
                temperature = 0.7f,
                maxTokens = 777,
                topP = 0.8f,
                frequencyPenalty = 0.2f,
                presencePenalty = -0.1f,
            ),
        )

        val body = WIRE_JSON.parseToJsonElement(server.requests.single().body).jsonObject
        assertEquals("ultrafast", body["service_tier"]?.jsonPrimitive?.content)
        assertEquals("0.7", body["temperature"]?.jsonPrimitive?.content)
        assertEquals("777", body["max_output_tokens"]?.jsonPrimitive?.content)
        assertEquals("0.8", body["top_p"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("frequency_penalty"))
        assertFalse(body.containsKey("presence_penalty"))
        assertEquals("auto", body["reasoning"]?.jsonObject?.get("summary")?.jsonPrimitive?.content)
        val hosted = events.filterIsInstance<StreamEvent.HostedToolCallUpdate>()
        assertEquals(2, hosted.size)
        assertEquals(null, hosted.first().result)
        assertTrue(hosted.last().arguments.contains("latest Agora"))
        assertTrue(hosted.last().result?.contains("web_search_call") == true)
        assertTrue(hosted.all { it.name == "openai_search" })
        assertEquals(
            "**Checked current sources**",
            events.filterIsInstance<StreamEvent.ThoughtChunk>().single().thought,
        )
        assertEquals(
            "Checked current sources",
            events.filterIsInstance<StreamEvent.ThoughtChunk>().single().title,
        )
        assertEquals("Answer", events.filterIsInstance<StreamEvent.TextChunk>().single().text)
        assertTrue(events.none { it is StreamEvent.ToolCallRequest })
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun responsesHostedWebSearchWithoutDoneFailsClosed() = withServer(
        terminalGraceMillis = 100L,
        responsesApiEnabled = true,
        response = { socket, _ ->
            socket.writeSse(
                """{"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"id":"ws_1","type":"web_search_call","status":"in_progress"}}""",
            )
            socket.writeSse(
                """{"type":"response.completed","sequence_number":2,"response":{"status":"completed"}}""",
            )
        },
    ) { provider, config, _ ->
        val events = collect(provider, config)

        assertEquals(1, events.filterIsInstance<StreamEvent.HostedToolCallUpdate>().size)
        assertEquals(1, events.filterIsInstance<StreamEvent.Error>().size)
        assertTrue(events.none { it is StreamEvent.ToolCallRequest })
    }

    @Test
    fun responsesHostedWebSearchKeepsAddedStreamKeyWhenDoneAddsId() = withServer(
        terminalGraceMillis = 100L,
        responsesApiEnabled = true,
        response = { socket, _ ->
            socket.writeSse(
                """{"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"type":"web_search_call","status":"in_progress"}}""",
            )
            socket.writeSse(
                """{"type":"response.output_item.done","sequence_number":2,"output_index":0,"item":{"id":"ws_late","type":"web_search_call","status":"completed","action":{"type":"search","query":"Agora"}}}""",
            )
            socket.writeSse(
                """{"type":"response.completed","sequence_number":3,"response":{"status":"completed"}}""",
            )
        },
    ) { provider, config, _ ->
        val events = collect(provider, config)
        val hosted = events.filterIsInstance<StreamEvent.HostedToolCallUpdate>()

        assertEquals(2, hosted.size)
        assertEquals(listOf("response_hosted_0"), hosted.map { it.streamKey }.distinct())
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun responsesHostedWebSearchRejectsDoneIdentityChange() = withServer(
        terminalGraceMillis = 100L,
        responsesApiEnabled = true,
        response = { socket, _ ->
            socket.writeSse(
                """{"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"id":"ws_1","type":"web_search_call","status":"in_progress"}}""",
            )
            socket.writeSse(
                """{"type":"response.output_item.done","sequence_number":2,"output_index":0,"item":{"id":"ws_2","type":"web_search_call","status":"completed","action":{"type":"search","query":"Agora"}}}""",
            )
        },
    ) { provider, config, _ ->
        val events = collect(provider, config)

        assertEquals(1, events.filterIsInstance<StreamEvent.HostedToolCallUpdate>().size)
        assertEquals(1, events.filterIsInstance<StreamEvent.Error>().size)
        assertTrue(events.none { it is StreamEvent.ToolCallRequest })
    }

    @Test
    fun responsesHostedWebSearchCoexistsWithFunctionTools() = withServer(
        terminalGraceMillis = 100L,
        responsesApiEnabled = true,
        response = { socket, _ ->
            socket.writeSse(
                """{"type":"response.completed","sequence_number":1,"response":{"status":"completed"}}""",
            )
        },
    ) { provider, config, server ->
        val events = collect(
            provider,
            config.withTools().copy(openAiWebSearchEnabled = true),
        )
        val body = WIRE_JSON.parseToJsonElement(server.requests.single().body).jsonObject
        val tools = body["tools"]?.jsonArray.orEmpty().map { it.jsonObject }
        assertEquals(listOf("function", "web_search"), tools.map { it["type"]?.jsonPrimitive?.content })
        assertEquals("file_edit", tools.first()["name"]?.jsonPrimitive?.content)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun responsesHostedWebSearchDisabledOmitsHostedTool() = withServer(
        terminalGraceMillis = 100L,
        responsesApiEnabled = true,
        response = { socket, _ ->
            socket.writeSse(
                """{"type":"response.completed","sequence_number":1,"response":{"status":"completed"}}""",
            )
        },
    ) { provider, config, server ->
        val events = collect(
            provider,
            config.withTools().copy(openAiWebSearchEnabled = false),
        )
        val body = WIRE_JSON.parseToJsonElement(server.requests.single().body).jsonObject
        val tools = body["tools"]?.jsonArray.orEmpty().map { it.jsonObject }
        assertEquals(listOf("function"), tools.map { it["type"]?.jsonPrimitive?.content })
        assertTrue(tools.none { it["type"]?.jsonPrimitive?.content == "web_search" })
        assertTrue(events.none { it is StreamEvent.Error })
    }
    @Test
    fun hostedWebSearchWithoutResponsesFailsBeforeNetworkDispatch() {
        val provider = object : BaseOpenAiProvider() {
            override val name: String = "test"
            override val defaultBaseUrl: String = "http://127.0.0.1:1/v1"
        }
        val events = collect(
            provider,
            ProviderConfig(
                apiKey = "",
                modelId = "test-model",
                baseUrl = provider.defaultBaseUrl,
                thinkingEnabled = false,
                responsesApiEnabled = false,
                openAiWebSearchEnabled = true,
            ),
        )

        val error = events.filterIsInstance<StreamEvent.Error>().single().error
        assertTrue(error is GenerationError.RequestFormat)
        assertTrue((error as GenerationError.RequestFormat).details.contains("requires Responses API"))
    }

    @Test
    fun chatTransportKeepsChatCompletionsPathAndBody() = withServer(
        terminalGraceMillis = 100L,
        response = { socket, _ ->
            socket.writeSse(
                """{"choices":[{"index":0,"delta":{"content":"complete"},"finish_reason":"stop"}]}"""
            )
            socket.writeSse("[DONE]")
        },
    ) { provider, config, server ->
        val events = collect(provider, config.copy(openAiServiceTier = "fast"))
        assertEquals("POST /v1/chat/completions HTTP/1.1", server.requests.single().requestLine)
        val body = WIRE_JSON.parseToJsonElement(server.requests.single().body).jsonObject
        assertTrue(body.containsKey("messages"))
        assertFalse(body.containsKey("input"))
        assertEquals("fast", body["service_tier"]!!.jsonPrimitive.content)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun responsesDoneWithoutCompletedRetriesThenReportsIncomplete() = withServer(
        terminalGraceMillis = 100L,
        responsesApiEnabled = true,
        connectionCount = 6,
        response = { socket, _ -> socket.writeSse("[DONE]") },
    ) { provider, config, server ->
        val events = collect(provider, config, timeoutMillis = 90_000L)
        assertEquals(6, server.requests.size)
        assertEquals(5, events.filterIsInstance<StreamEvent.Retrying>().size)
        assertEquals(1, events.filterIsInstance<StreamEvent.Error>().size)
        assertTrue(events.single { it is StreamEvent.Error }.let {
            (it as StreamEvent.Error).error is GenerationError.IncompleteStream
        })
    }

    @Test
    fun responsesFailedAndIncompleteAreExplicitWithoutRetry() {
        listOf(
            "response.failed" to "upstream rejected",
            "response.incomplete" to "max_output_tokens",
        ).forEach { (type, message) ->
            withServer(
                terminalGraceMillis = 100L,
                responsesApiEnabled = true,
                response = { socket, _ ->
                    val response = if (type == "response.failed") {
                        """{"status":"failed","error":{"message":"$message","type":"provider_error"}}"""
                    } else {
                        """{"status":"incomplete","incomplete_details":{"reason":"$message"}}"""
                    }
                    socket.writeSse(
                        """{"type":"$type","sequence_number":1,"response":$response}"""
                    )
                },
            ) { provider, config, server ->
                val events = collect(provider, config)
                assertEquals(1, server.requests.size)
                assertTrue(events.none { it is StreamEvent.Retrying })
                val error = events.filterIsInstance<StreamEvent.Error>().single().error
                assertTrue(error is GenerationError.Api)
                assertEquals(message, (error as GenerationError.Api).message)
            }
        }
    }

    @Test
    fun responseBodyReadRetryHonorsCurrentPassOutputBoundary() {
        val matching = IOException("The server response could not be read.")
        val success = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"complete\"},\"finish_reason\":\"stop\"}]}"

        val responses = collectWithMockedStream(
            listOf(matching, "data: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{\"status\":\"completed\"}}"),
            responsesApiEnabled = true,
        )
        assertEquals(StreamEvent.Retrying(1, 5), responses.filterIsInstance<StreamEvent.Retrying>().single())
        val partial = collectWithMockedStream(
            listOf("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}", matching),
        )
        assertTrue(partial.none { it is StreamEvent.Retrying })
        assertTrue(partial.filterIsInstance<StreamEvent.Error>().single().error is GenerationError.Network)
        val toolCall = ToolCallData("file_edit", "{}", "", toolCallId = "call-1")
        val afterTool = collectWithMockedStream(
            reads = listOf(matching, success, "data: [DONE]"),
            messages = listOf(
                messages().single(),
                ChatMessage("tool_call", text = "", participant = Participant.MODEL, toolCall = toolCall),
                ChatMessage("result_call", text = "done", participant = Participant.USER, toolCall = toolCall.copy(result = "done")),
            ),
        )
        assertEquals(1, afterTool.filterIsInstance<StreamEvent.Retrying>().size)
        assertEquals("complete", afterTool.filterIsInstance<StreamEvent.TextChunk>().single().text)
        val nonmatching = collectWithMockedStream(listOf(IOException("unexpected stream reset")))
        assertTrue(nonmatching.none { it is StreamEvent.Retrying })
        assertTrue(nonmatching.filterIsInstance<StreamEvent.Error>().single().error is GenerationError.Unknown)
    }

    @Test
    fun responsesPartialTextThenMalformedSseDoesNotRetryAndReportsOneParseError() = withServer(
        terminalGraceMillis = 100L,
        responsesApiEnabled = true,
        response = { socket, _ ->
            socket.writeSse(
                """{"type":"response.output_text.delta","sequence_number":1,"delta":"partial"}"""
            )
            socket.writeSse("not-json")
        },
    ) { provider, config, server ->
        val events = collect(provider, config)
        assertEquals(1, server.requests.size)
        assertTrue(events.none { it is StreamEvent.Retrying })
        assertEquals("partial", events.filterIsInstance<StreamEvent.TextChunk>().single().text)
        assertEquals(1, events.filterIsInstance<StreamEvent.Error>().size)
        assertTrue(events.filterIsInstance<StreamEvent.Error>().single().error is GenerationError.SseParse)
    }

    @Test
    fun responsesMalformedSseRetriesThenSucceeds() {
        val retried = collectWithMockedStream(
            listOf("data: not-json", "data: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{\"status\":\"completed\"}}"),
            responsesApiEnabled = true,
        )
        assertEquals(StreamEvent.Retrying(1, 5), retried.filterIsInstance<StreamEvent.Retrying>().single())
        assertTrue(retried.none { it is StreamEvent.Error })
    }

    @Test
    fun responsesFunctionCallBecomesExecutableOnlyAfterCompleted() = withServer(
        terminalGraceMillis = 100L,
        responsesApiEnabled = true,
        response = { socket, _ ->
            socket.writeSse(
                """{"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"id":"item_1","type":"function_call","call_id":"call_1","name":"lookup"}}"""
            )
            socket.writeSse(
                """{"type":"response.output_item.done","sequence_number":2,"output_index":0,"item":{"id":"item_1","type":"function_call","call_id":"call_1","name":"lookup","arguments":"{}"}}"""
            )
            socket.writeSse(
                """{"type":"response.completed","sequence_number":3,"response":{"status":"completed"}}"""
            )
        },
    ) { provider, config, _ ->
        val events = collect(provider, config)
        val executableIndex = events.indexOfFirst { it is StreamEvent.ToolCallRequest }
        assertTrue(executableIndex > events.indexOfLast { it is StreamEvent.ToolCallUpdate })
        assertEquals("call_1", events.filterIsInstance<StreamEvent.ToolCallRequest>().single().id)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun customProviderUnauthorizedDoesNotRetry() = withServer(
        terminalGraceMillis = 100L,
        statusCode = 401,
        errorBody = """{"error":{"message":"unauthorized","type":"authentication_error"}}""",
        providerFactory = { baseUrl -> CustomOpenAiProvider("Relay", baseUrl) },
        response = { _, _ -> },
    ) { provider, config, server ->
        val events = collect(provider, config)

        assertEquals(1, server.requests.size)
        assertTrue(events.none { it is StreamEvent.Retrying })
        val error = events.filterIsInstance<StreamEvent.Error>().single().error
        assertTrue(error is GenerationError.Api)
        assertEquals("unauthorized", (error as GenerationError.Api).message)
    }
}
