package com.newoether.agora.viewmodel

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.captureProviderRequest
import com.newoether.agora.api.openai.CustomOpenAiProvider
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationToolRoundBuilderTest {
    @Test
    fun `durable Room projection replays opaque Responses state before tool output`() {
        val ids = ArrayDeque(listOf("tool", "result"))
        val builder = GenerationToolRoundBuilder(
            newId = { ids.removeFirst() },
            nowMs = { 100L },
        )
        val reasoning = Json.parseToJsonElement(
            """{"id":"rs_1","type":"reasoning","encrypted_content":"opaque"}""",
        ).jsonObject
        val functionCall = Json.parseToJsonElement(
            """{"id":"fc_1","type":"function_call","call_id":"call_1","name":"lookup","arguments":"{}"}""",
        ).jsonObject
        val round = builder.build(
            previousMessageId = "assistant",
            conversationId = "conversation",
            runId = "run",
            modelName = "model",
            providerName = "OpenAI",
            calls = listOf(
                ToolCallData(
                    toolName = "lookup",
                    arguments = "{}",
                    result = "done",
                    toolCallId = "call_1",
                    responseOutputItems = listOf(reasoning, functionCall),
                    responseOutputItemProvider = "OpenAI",
                ),
            ),
            completedSegments = emptyList(),
        )

        val durablePath = ApiPathAssembler.assemble(round.entities, round.entities)
        val request = captureProviderRequest(
            provider = CustomOpenAiProvider("OpenAI", "https://example.test/v1"),
            messages = projectProviderMessages(durablePath, includeStoredTranscriptions = false),
            config = ProviderConfig(
                apiKey = "test-key", modelId = "model", responsesApiEnabled = true,
            ),
        )
        val input = request.getValue("input").jsonArray.map { it.jsonObject }

        assertEquals(
            listOf("reasoning", "function_call", "function_call_output"),
            input.map { it["type"]?.jsonPrimitive?.content },
        )
        assertEquals("opaque", input[0]["encrypted_content"]?.jsonPrimitive?.content)
        assertEquals("done", input[2]["output"]?.jsonPrimitive?.content)
        assertTrue(
            round.entities[1].toolCallJson.orEmpty().contains("responseOutputItems").not(),
        )
    }

    @Test
    fun `resolved router metadata survives Room result rows and offline provider replay`() {
        val original = ToolCallData(
            toolName = "mcp_call_tool",
            arguments = """{"arguments":{"path":"/tmp/a"},"tool_id":"mcp_deleted_read_ab1234"}""",
            result = "done",
            toolCallId = "call",
            displayName = "filesystem/read_file",
            target = "Deleted filesystem server",
        )
        val round = GenerationToolRoundBuilder().build(
            previousMessageId = "assistant",
            conversationId = "conversation",
            runId = "run",
            modelName = "model",
            providerName = "provider",
            calls = listOf(original),
            completedSegments = emptyList(),
        )
        val replay = projectProviderMessages(round.entities, includeStoredTranscriptions = false)
        assertEquals(2, replay.size)
        replay.forEach { message ->
            val call = requireNotNull(message.toolCall)
            assertEquals(original.toolName, call.toolName)
            assertEquals(original.arguments, call.arguments)
            assertEquals(original.displayName, call.displayName)
            assertEquals(original.target, call.target)
            assertTrue(round.entities.single { it.id == message.id }.toolCallJson.orEmpty()
                .contains("Deleted filesystem server"))
        }
        val legacy = Json.decodeFromString<ToolCallData>(
            """{"toolName":"mcp_legacy_read_ab1234","arguments":"{}","result":"done"}""",
        )
        assertEquals(null, legacy.target)
    }

    @Test(expected = IllegalStateException::class)
    fun `oversized committed body cannot degrade to a null reference target`() {
        GenerationToolRoundBuilder().build(
            previousMessageId = "root",
            conversationId = "conversation",
            runId = "run",
            modelName = "model",
            providerName = "provider",
            calls = listOf(ToolCallData("lookup", "x".repeat(600_001), "done", toolCallId = "call")),
            completedSegments = emptyList(),
        )
    }

    @Test
    fun `builds matching provider path and Room graph with deterministic hierarchy`() {
        val ids = ArrayDeque(listOf("tool", "result-1", "result-2"))
        val builder = GenerationToolRoundBuilder(
            newId = { ids.removeFirst() },
            nowMs = { 100L },
        )
        val calls = listOf(
            ToolCallData("first", "{}", "raw-one", toolCallId = "call-1"),
            ToolCallData(
                "second",
                "{\"value\":2}",
                "raw-two",
                signature = "signature",
                toolCallId = "call-2",
            ),
        )

        val round = builder.build(
            previousMessageId = "assistant",
            conversationId = "conversation",
            runId = "run",
            modelName = "model",
            providerName = "provider",
            calls = calls,
            completedSegments = emptyList(),
        )

        assertEquals(3, round.pathMessages.size)
        assertEquals(3, round.entities.size)
        assertEquals("tool_tool", round.pathMessages[0].id)
        assertEquals("assistant", round.pathMessages[0].parentId)
        assertEquals("result_result-1", round.pathMessages[1].id)
        assertEquals("tool_tool", round.pathMessages[1].parentId)
        assertEquals("raw-one", round.pathMessages[1].text)
        assertEquals(Participant.USER, round.pathMessages[1].participant)
        assertEquals("result_result-2", round.lastResultId)
        assertEquals(listOf(100L, 101L, 102L), round.entities.map { it.timestamp })
        assertEquals(round.pathMessages.map { it.id }, round.entities.map { it.id })
        assertTrue(round.entities[0].toolCallJson.orEmpty().contains("raw-one"))
        assertTrue(round.entities[2].toolCallJson.orEmpty().contains("signatureProvider"))
        assertTrue(round.entities[2].toolCallJson.orEmpty().contains("provider"))
    }
}
