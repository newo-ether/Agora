package com.newoether.agora.viewmodel

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.captureProviderRequest
import com.newoether.agora.api.gemini.GeminiProvider
import com.newoether.agora.api.openai.CustomOpenAiProvider
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiPathAssemblerTest {
    @Test
    fun protocolRowsReachableFromAncestryAndSideChain_areEmittedExactlyOnce() {
        val user = message("u", null, Participant.USER, 0)
        val model = message("m", "u", Participant.MODEL, 1, toolJson = "aggregated")
        val tool = message("tool_round", "m", Participant.MODEL, 2)
        val result = message("result_round", "tool_round", Participant.USER, 3)
        val queued = message("queued", "result_round", Participant.USER, 4)

        val assembled = ApiPathAssembler.assemble(
            ancestorPath = listOf(user, model, tool, result, queued),
            allMessages = listOf(user, model, tool, result, queued),
        )

        assertEquals(
            listOf("u", "tool_round", "result_round", "m", "queued"),
            assembled.map { it.id },
        )
        assertEquals(assembled.size, assembled.map { it.id }.distinct().size)
        assertNull(assembled.first { it.id == "m" }.toolCallJson)
    }

    @Test
    fun terminalAggregate_preservesNonToolSegmentsWhenProtocolRowsAreExpanded() {
        val errorDetail = "Formatted provider failure"
        val aggregate = Json.encodeToString(
            listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "shell",
                    toolArgs = "{}",
                    toolCallId = "call-1",
                ),
                MessageSegment(type = "answer", content = "partial answer"),
                MessageSegment(type = "error", content = errorDetail),
            ),
        )
        val user = message("u", null, Participant.USER, 0)
        val model = message(
            "m",
            "u",
            Participant.MODEL,
            1,
            toolJson = aggregate,
            status = MessageStatus.ERROR,
        )
        val tool = message(
            "tool_round",
            "m",
            Participant.MODEL,
            2,
            toolJson = toolSegments(toolSegment("call-1", result = "done")),
        )
        val result = message("result_round", "tool_round", Participant.USER, 3)

        val assembled = ApiPathAssembler.assemble(
            ancestorPath = listOf(user, model),
            allMessages = listOf(user, model, tool, result),
        )

        assertEquals(
            listOf("u", "tool_round", "result_round", "m"),
            assembled.map { it.id },
        )
        val retained = Json.decodeFromString<List<MessageSegment>>(
            requireNotNull(assembled.last().toolCallJson),
        )
        assertEquals(listOf("answer", "error"), retained.map { it.type })
        assertEquals(errorDetail, retained.last().content)
    }

    @Test
    fun interventionPersistedBeforeToolRoundStillReceivesCompletedSideChain() {
        val user = message("u", null, Participant.USER, 0)
        val model = message("m", "u", Participant.MODEL, 1, toolJson = "aggregated")
        val queued = message("queued", "m", Participant.USER, 2)
        val tool = message("tool_round", "m", Participant.MODEL, 3)
        val result = message("result_round", "tool_round", Participant.USER, 4)

        val assembled = ApiPathAssembler.assemble(
            ancestorPath = listOf(user, model, queued),
            allMessages = listOf(user, model, queued, tool, result),
        )

        assertEquals(
            listOf("u", "tool_round", "result_round", "m", "queued"),
            assembled.map { it.id },
        )
    }

    @Test
    fun activeToolContinuationEndsAtDurableToolResult() {
        val user = message("u", null, Participant.USER, 0)
        val model = message(
            "m",
            "u",
            Participant.MODEL,
            1,
            toolJson = "aggregated",
            status = MessageStatus.SENDING,
        )
        val tool = message("tool_round", "m", Participant.MODEL, 2)
        val result = message("result_round", "tool_round", Participant.USER, 3)

        val assembled = ApiPathAssembler.assemble(
            ancestorPath = listOf(user, model, tool, result),
            allMessages = listOf(user, model, tool, result),
        )

        assertEquals(listOf("u", "tool_round", "result_round"), assembled.map { it.id })
        assertEquals("result_round", assembled.last().id)
    }

    @Test
    fun guidancePublishedAtResponseBoundaryEndsAtNewUserInput() {
        val user = message("u", null, Participant.USER, 0)
        val completedModel = message("m", "u", Participant.MODEL, 1)
        val guidance = message("guidance", "m", Participant.USER, 2)

        val assembled = ApiPathAssembler.assemble(
            ancestorPath = listOf(user, completedModel, guidance),
            allMessages = listOf(user, completedModel, guidance),
        )

        assertEquals(listOf("u", "m", "guidance"), assembled.map { it.id })
        assertEquals(Participant.USER, assembled.last().participant)
    }

    @Test
    fun stoppedCallWithoutPersistedRound_isClosedWithAStoppedResult() {
        val user = message("u", null, Participant.USER, 0)
        val model = message(
            "m",
            "u",
            Participant.MODEL,
            1,
            toolJson = toolSegments(
                toolSegment("call-1", result = "done"),
                toolSegment("call-2", question = "Which rope burns first?"),
                MessageSegment(type = "answer", content = "partial"),
            ),
            status = MessageStatus.STOPPED,
        )
        val tool = message(
            "tool_round",
            "m",
            Participant.MODEL,
            2,
            toolJson = toolSegments(toolSegment("call-1", result = "done")),
        )
        val result = message("result_round", "tool_round", Participant.USER, 3)
        val next = message("next", "m", Participant.USER, 4)

        val assembled = ApiPathAssembler.assemble(
            ancestorPath = listOf(user, model, next),
            allMessages = listOf(user, model, tool, result, next),
        )

        assertEquals(
            listOf(
                "u",
                "tool_round",
                "result_round",
                "tool_interrupted-m",
                "result_interrupted-m-0",
                "m",
                "next",
            ),
            assembled.map { it.id },
        )
        val call = Json.decodeFromString<List<MessageSegment>>(
            requireNotNull(assembled[3].toolCallJson),
        ).single()
        // Only the unpaired call is synthesised; the question survives in its arguments.
        assertEquals("call-2", call.toolCallId)
        assertEquals(true, call.toolArgs?.contains("Which rope burns first?"))
        assertEquals(InterruptedToolRounds.STOPPED_RESULT, assembled[4].text)
        assertEquals(Participant.USER, assembled[4].participant)
        assertEquals("tool_interrupted-m", assembled[4].parentId)
        // The aggregate no longer carries tool segments, so nothing is replayed twice.
        val retained = Json.decodeFromString<List<MessageSegment>>(
            requireNotNull(assembled[5].toolCallJson),
        )
        assertEquals(listOf("answer"), retained.map { it.type })
    }

    @Test
    fun failedGenerationWithOnlyAnUnpairedCall_isClosedWithAFailedResult() {
        val user = message("u", null, Participant.USER, 0)
        val model = message(
            "m",
            "u",
            Participant.MODEL,
            1,
            toolJson = toolSegments(toolSegment("call-1")),
            status = MessageStatus.ERROR,
        )

        val assembled = ApiPathAssembler.assemble(
            ancestorPath = listOf(user, model),
            allMessages = listOf(user, model),
        )

        assertEquals(
            listOf("u", "tool_interrupted-m", "result_interrupted-m-0", "m"),
            assembled.map { it.id },
        )
        assertEquals(InterruptedToolRounds.FAILED_RESULT, assembled[2].text)
        assertNull(assembled[3].toolCallJson)
    }

    @Test
    fun callWhoseToolFinishedBeforeTheStop_keepsItsRealResult() {
        val user = message("u", null, Participant.USER, 0)
        val model = message(
            "m",
            "u",
            Participant.MODEL,
            1,
            toolJson = toolSegments(toolSegment("call-1", result = "real output")),
            status = MessageStatus.STOPPED,
        )

        val assembled = ApiPathAssembler.assemble(
            ancestorPath = listOf(user, model),
            allMessages = listOf(user, model),
        )

        assertEquals("real output", assembled[2].text)
    }

    @Test
    fun successfulOrLiveRows_areNeverGivenSyntheticResults() {
        MessageStatus.entries
            .filterNot { it == MessageStatus.STOPPED || it == MessageStatus.ERROR }
            .forEach { status ->
                val user = message("u", null, Participant.USER, 0)
                val model = message(
                    "m",
                    "u",
                    Participant.MODEL,
                    1,
                    toolJson = toolSegments(toolSegment("call-1")),
                    status = status,
                )
                val assembled = ApiPathAssembler.assemble(
                    ancestorPath = listOf(user, model),
                    allMessages = listOf(user, model),
                )
                assertEquals(status.name, listOf("u", "m"), assembled.map { it.id })
            }
    }

    @Test
    fun endedMixedHostedAndClientRound_replaysOnlyPairedClientOutputs() {
        val rawItems = listOf(
            """{"id":"rs_1","type":"reasoning","encrypted_content":"opaque"}""",
            """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"Checking tools"}]}""",
            """{"id":"fc_1","type":"function_call","call_id":"call_1","name":"wait_for_job","arguments":"{}"}""",
            """{"id":"fc_2","type":"function_call","call_id":"call_2","name":"file_edit","arguments":"{}"}""",
        ).map { Json.parseToJsonElement(it).jsonObject }
        val hosted = (1..4).map { index ->
            MessageSegment(
                type = "tool",
                toolName = "openai_search",
                toolArgs = "{}",
                toolResult = "{}",
                toolCallId = "ws_$index",
            )
        }
        val clients = listOf("wait_for_job", "file_edit").mapIndexed { index, name ->
            toolSegment("call_${index + 1}").copy(
                toolName = name,
                toolArgs = "{}",
                responseOutputItems = rawItems,
                responseOutputItemProvider = "OpenAI",
            )
        }
        listOf(MessageStatus.STOPPED, MessageStatus.ERROR).forEach { status ->
            val user = message("u", null, Participant.USER, 0)
            val model = message(
                "m", "u", Participant.MODEL, 1,
                toolJson = Json.encodeToString(
                    hosted + clients + MessageSegment(type = "answer", content = "partial"),
                ),
                status = status,
            ).copy(modelName = "gpt-test")
            val next = message("next", "m", Participant.USER, 2)
            val path = listOf(user, model, next)
            val assembled = ApiPathAssembler.assemble(path, path)
            val request = captureProviderRequest(
                provider = CustomOpenAiProvider("OpenAI", "https://example.test/v1"),
                messages = projectProviderMessages(assembled, includeStoredTranscriptions = false),
                config = ProviderConfig(
                    apiKey = "test-key", modelId = "gpt-test", responsesApiEnabled = true,
                ),
            )
            val input = request.getValue("input").jsonArray.map { it.jsonObject }
            val outputs = input.filter {
                it["type"]?.jsonPrimitive?.content == "function_call_output"
            }
            assertEquals(listOf("call_1", "call_2"), outputs.map {
                it["call_id"]?.jsonPrimitive?.content
            })
            val expected = if (status == MessageStatus.STOPPED) {
                InterruptedToolRounds.STOPPED_RESULT
            } else {
                InterruptedToolRounds.FAILED_RESULT
            }
            assertEquals(listOf(expected, expected), outputs.map {
                it["output"]?.jsonPrimitive?.content
            })
            // The public provider also applies the existing legacy tool-name wire mapping.
            val wireItems = rawItems.map { item ->
                if (item["name"]?.jsonPrimitive?.content == "wait_for_job") {
                    kotlinx.serialization.json.JsonObject(
                        item + ("name" to kotlinx.serialization.json.JsonPrimitive("shell_job_wait")),
                    )
                } else {
                    item
                }
            }
            assertEquals(wireItems, input.subList(1, 5))
            assertEquals(2, input.count { it["type"]?.jsonPrimitive?.content == "function_call" })
            val retained = Json.decodeFromString<List<MessageSegment>>(
                requireNotNull(assembled.first { it.id == "m" }.toolCallJson),
            )
            assertEquals(hosted, retained.filter { it.type == "tool" })
        }
    }

    @Test
    fun hostedSegments_surviveEndedRecoveryAndPersistedClientDeduplication() {
        val hosted = listOf("openai_search", "google_search", "code_execution")
            .mapIndexed { index, name ->
                MessageSegment(
                    type = "tool",
                    toolName = name,
                    toolCallId = "hosted_$index",
                    toolArgs = """{"language":"PYTHON","code":"print(1)"}""",
                    toolResult = if (name == "google_search") null else {
                        """{"outcome":"OUTCOME_OK","output":"1"}"""
                    },
                )
            }
        val user = message("u", null, Participant.USER, 0)
        listOf(MessageStatus.STOPPED, MessageStatus.ERROR, MessageStatus.SUCCESS).forEach { status ->
            listOf(false, true).forEach { withClient ->
                val client = toolSegment("call_1", result = "done")
                val aggregate = hosted + if (withClient) listOf(client) else emptyList()
                val model = message(
                    "m", "u", Participant.MODEL, 1,
                    toolJson = Json.encodeToString(aggregate),
                    status = status,
                )
                val protocol = if (withClient) {
                    listOf(
                        message(
                            "tool_round", "m", Participant.MODEL, 2,
                            toolJson = toolSegments(client),
                        ),
                        message("result_round", "tool_round", Participant.USER, 3),
                    )
                } else {
                    emptyList()
                }
                val assembled = ApiPathAssembler.assemble(
                    ancestorPath = listOf(user, model),
                    allMessages = listOf(user, model) + protocol,
                )

                assertEquals(listOf("u") + protocol.map { it.id } + "m", assembled.map { it.id })
                assertEquals(
                    hosted,
                    Json.decodeFromString<List<MessageSegment>>(
                        requireNotNull(assembled.last().toolCallJson),
                    ),
                )
                val projected = projectProviderMessages(
                    assembled, includeStoredTranscriptions = false,
                ).last()
                assertEquals(hosted, projected.segments)
                val request = captureProviderRequest(
                    provider = GeminiProvider(),
                    messages = listOf(
                        ChatMessage(id = "wire-start", text = "Run the code", participant = Participant.USER),
                        projected,
                        ChatMessage(id = "wire-end", text = "Continue", participant = Participant.USER),
                    ),
                    config = ProviderConfig(apiKey = "test-key", modelId = "gemini-test"),
                )
                val nativeParts = request.getValue("contents").jsonArray
                    .single { it.jsonObject["role"]?.jsonPrimitive?.content == "model" }.jsonObject
                    .getValue("parts").jsonArray.map { it.jsonObject }
                assertEquals(
                    "print(1)",
                    nativeParts.single { "executableCode" in it }
                        .getValue("executableCode").jsonObject["code"]?.jsonPrimitive?.content,
                )
                assertEquals(
                    "1",
                    nativeParts.single { "codeExecutionResult" in it }
                        .getValue("codeExecutionResult").jsonObject["output"]?.jsonPrimitive?.content,
                )
                assertTrue(model.toolCallJson.orEmpty().contains("isProviderHostedTool").not())
            }
        }
    }

    @Test
    fun genericWebSearch_remainsAnInterruptedClientCall() {
        val user = message("u", null, Participant.USER, 0)
        val model = message(
            "m", "u", Participant.MODEL, 1,
            toolJson = toolSegments(toolSegment("call_1").copy(toolName = "web_search")),
            status = MessageStatus.STOPPED,
        )
        val assembled = ApiPathAssembler.assemble(listOf(user, model), listOf(user, model))

        assertEquals(
            listOf("u", "tool_interrupted-m", "result_interrupted-m-0", "m"),
            assembled.map { it.id },
        )
        assertEquals(InterruptedToolRounds.STOPPED_RESULT, assembled[2].text)
    }

    private fun toolSegment(
        callId: String,
        question: String = "q",
        result: String? = null,
    ) = MessageSegment(
        type = "tool",
        toolName = "ask_user",
        toolArgs = """{"question":"$question","options":["a","b"]}""",
        toolCallId = callId,
        toolResult = result,
    )

    private fun toolSegments(vararg segments: MessageSegment): String =
        Json.encodeToString(segments.toList())

    private fun message(
        id: String,
        parentId: String?,
        participant: Participant,
        sequence: Long,
        toolJson: String? = null,
        status: MessageStatus = MessageStatus.SUCCESS,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = if (participant == Participant.USER) id else "",
        status = status,
        participant = participant,
        timestamp = sequence,
        modelName = "claude-sonnet-5",
        toolCallJson = toolJson,
        runId = "run",
        runSequence = sequence,
    )
}
