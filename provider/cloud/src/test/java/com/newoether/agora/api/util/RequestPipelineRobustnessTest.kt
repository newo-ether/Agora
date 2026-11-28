package com.newoether.agora.api.util

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.openai.requireValidWireFormat
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestPipelineRobustnessTest {
    @Test
    fun everyMalformedStoredToolSequenceBecomesAValidOpenAiRequest() {
        malformedHistories().forEachIndexed { index, history ->
            val prepared = prepareMessages(history, contextTokenBudget = 32_768)
            val request = OpenAiChatRequest(
                model = "test-model",
                messages = convertToOpenAiMessages(
                    prepared,
                    base64Files = Base64FileRegistry(),
                ),
            )

            request.requireValidWireFormat("OpenAI scenario $index")
        }
    }

    @Test
    fun incompatibleProviderMetadataFlattensTheWholeRoundWithoutOrphans() {
        val prepared = prepareMessages(
            listOf(
                user("u0"),
                tool("tool_round", "call-a"),
                result("result_a", "call-a"),
                user("u1"),
            ),
            contextTokenBudget = 32_768,
        )

        val adapted = adaptToolRoundsForProvider(
            messages = prepared,
            providerName = "different provider",
            isCompatible = { false },
        )

        assertFalse(adapted.any(ChatMessage::isToolProtocolMessage))
        assertTrue(adapted.any { it.text.contains("inert historical data") })
        assertFalse(adapted.any { it.text.contains("\nArguments:") })
        OpenAiChatRequest(
            model = "test-model",
            messages = convertToOpenAiMessages(
                adapted,
                base64Files = Base64FileRegistry(),
            ),
        ).requireValidWireFormat("OpenAI fallback")
    }

    @Test
    fun serializedBodyGateRejectsMissingMandatoryWireFields() {
        requireValidSerializedRequest(
            provider = "test",
            body = """{"model":"m","messages":[{"role":"user"}]}""",
            requiredStringFields = setOf("model"),
            requiredArrayFields = setOf("messages"),
        )

        val error = runCatching {
            requireValidSerializedRequest(
                provider = "test",
                body = """{"model":"","messages":[]}""",
                requiredStringFields = setOf("model"),
                requiredArrayFields = setOf("messages"),
            )
        }.exceptionOrNull()
        assertTrue(error is RequestFormatException)
    }

    private fun malformedHistories(): List<List<ChatMessage>> {
        val prefix = listOf(user("u0"))
        val suffix = listOf(user("u1"))
        return listOf(
            prefix + tool("tool_missing_result", "call-a") + suffix,
            prefix + result("result_orphan", "call-a") + suffix,
            prefix + tool("tool_mismatch", "call-a") +
                result("result_mismatch", "different") + suffix,
            prefix + tool("tool_duplicate", "same", "same") +
                result("result_a", "same") + result("result_b", "same") + suffix,
            prefix + tool("tool_extra", "call-a") +
                result("result_a", "call-a") + result("result_extra", "extra") + suffix,
            prefix + tool("tool_bad_args", "call-a", arguments = "{not-json") +
                result("result_a", "call-a") + suffix,
            prefix + tool("tool_valid", "call-a", "call-b") +
                result("result_b", "call-b") + result("result_a", "call-a") + suffix,
        )
    }

    private fun user(id: String) = ChatMessage(
        id = id,
        text = id,
        participant = Participant.USER,
        status = MessageStatus.SUCCESS,
    )

    private fun tool(
        id: String,
        vararg callIds: String,
        arguments: String = "{}",
    ) = ChatMessage(
        id = id,
        text = "",
        participant = Participant.MODEL,
        status = MessageStatus.SUCCESS,
        segments = callIds.mapIndexed { index, callId ->
            MessageSegment(
                type = "tool",
                toolName = "tool-$index",
                toolArgs = arguments,
                toolCallId = callId,
            )
        },
    )

    private fun result(id: String, callId: String) = ChatMessage(
        id = id,
        text = "result-$id",
        participant = Participant.USER,
        status = MessageStatus.SUCCESS,
        segments = listOf(
            MessageSegment(
                type = "tool",
                toolName = "tool",
                toolArgs = "{}",
                toolResult = "result-$id",
                toolCallId = callId,
            )
        ),
    )
}
