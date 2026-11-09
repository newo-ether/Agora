package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiContentPart
import com.newoether.agora.api.OpenAiMessage
import com.newoether.agora.api.OpenAiRequestFunction
import com.newoether.agora.api.OpenAiRequestToolCall
import com.newoether.agora.api.OpenAiResponseInputContent
import com.newoether.agora.api.OpenAiResponseInputItem
import com.newoether.agora.api.OpenAiResponseOutputItem
import com.newoether.agora.api.OpenAiResponsesRequest
import com.newoether.agora.api.util.RequestFormatException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRequestValidatorTest {
    @Test
    fun completeToolRound_isAccepted() {
        validRequest().requireValidWireFormat("OpenAI")
    }

    @Test
    fun normalUserCannotInterruptPendingToolResults() {
        val broken = validRequest().copy(
            messages = validRequest().messages.dropLast(1) + user("interrupt"),
        )

        val error = runCatching {
            broken.requireValidWireFormat("OpenAI")
        }.exceptionOrNull()

        assertTrue(error is RequestFormatException)
        assertTrue(error?.message.orEmpty().contains("pending tool results"))
    }

    @Test
    fun duplicateToolCallIds_areBlockedLocally() {
        val firstAssistant = assistantToolCall("same")
        val broken = OpenAiChatRequest(
            model = "gpt-test",
            messages = listOf(
                user("first"),
                firstAssistant,
                toolResult("same"),
                user("second"),
                assistantToolCall("same"),
                toolResult("same"),
            ),
        )

        val error = runCatching {
            broken.requireValidWireFormat("OpenAI")
        }.exceptionOrNull()

        assertTrue(error is RequestFormatException)
        assertTrue(error?.message.orEmpty().contains("reuses tool call id"))
    }

    @Test
    fun chatProjectionCreatesResponsesMessagesImagesAndPairedFunctionItems() {
        val input = listOf(
            user("start").copy(
                content = listOf(
                    OpenAiContentPart(type = "text", text = "start"),
                    OpenAiContentPart(
                        type = "image_url",
                        imageUrl = com.newoether.agora.api.OpenAiImageUrl("data:image/png;base64,AA=="),
                    ),
                ),
            ),
            assistantToolCall("call_1"),
            toolResult("call_1"),
        ).toResponsesInput()
        val request = OpenAiResponsesRequest(model = "gpt-test", input = input)

        request.requireValidWireFormat("OpenAI")
        assertEquals(
            listOf("message", "function_call", "function_call_output"),
            input.map { it["type"]?.jsonPrimitive?.content },
        )
        assertEquals(
            listOf("input_text", "input_image"),
            input.first()["content"]?.jsonArray?.map {
                it.jsonObject["type"]?.jsonPrimitive?.content
            },
        )
        assertEquals("call_1", input[1]["call_id"]?.jsonPrimitive?.content)
        assertEquals("call_1", input[2]["call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun responsesProjectionUsesOutputTextForPriorAssistantMessages() {
        val input = listOf(
            user("question"),
            OpenAiMessage(
                role = "assistant",
                content = listOf(OpenAiContentPart(type = "text", text = "answer")),
            ),
            user("follow-up"),
        ).toResponsesInput()
        OpenAiResponsesRequest(model = "gpt-test", input = input)
            .requireValidWireFormat("OpenAI")
        assertEquals(
            "output_text",
            input[1]["content"]
                ?.jsonArray
                ?.single()
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun responsesValidationRejectsTextTypesThatDoNotMatchMessageRole() {
        listOf(
            "user" to "output_text",
            "assistant" to "input_text",
        ).forEach { (role, contentType) ->
            val request = OpenAiResponsesRequest(
                model = "gpt-test",
                input = listOf(
                    responseItem(
                        OpenAiResponseInputItem(
                            type = "message",
                            role = role,
                            content = listOf(
                                OpenAiResponseInputContent(type = contentType, text = "invalid"),
                            ),
                        ),
                    ),
                    responseItem(
                        OpenAiResponseInputItem(
                            type = "message",
                            role = "user",
                            content = listOf(
                                OpenAiResponseInputContent(type = "input_text", text = "continue"),
                            ),
                        ),
                    ),
                ),
            )

            val error = runCatching { request.requireValidWireFormat("OpenAI") }.exceptionOrNull()

            assertTrue(error is RequestFormatException)
            assertTrue(error?.message.orEmpty().contains("must use"))
        }
    }

    @Test
    fun responsesValidationRejectsAssistantInputImage() {
        val request = OpenAiResponsesRequest(
            model = "gpt-test",
            input = listOf(
                responseItem(
                    OpenAiResponseInputItem(
                        type = "message",
                        role = "assistant",
                        content = listOf(
                            OpenAiResponseInputContent(
                                type = "input_image",
                                imageUrl = "data:image/png;base64,AA==",
                                detail = "auto",
                            ),
                        ),
                    ),
                ),
                listOf(user("continue")).toResponsesInput().single(),
            ),
        )

        val error = runCatching { request.requireValidWireFormat("OpenAI") }.exceptionOrNull()

        assertTrue(error is RequestFormatException)
        assertTrue(error?.message.orEmpty().contains("assistant message cannot contain input_image"))
    }

    @Test
    fun responsesProjectionRejectsMissingFunctionOutput() {
        val request = OpenAiResponsesRequest(
            model = "gpt-test",
            input = listOf(user("start"), assistantToolCall("call_1")).toResponsesInput(),
        )

        val error = runCatching { request.requireValidWireFormat("OpenAI") }.exceptionOrNull()

        assertTrue(error is RequestFormatException)
        assertTrue(error?.message.orEmpty().contains("missing outputs"))
    }

    @Test
    fun responsesContinuationReplaysCompleteProviderOutputWithoutDuplicatingCalls() {
        val reasoning = responseItem(
            OpenAiResponseOutputItem(
                id = "rs_1",
                type = "reasoning",
                summary = JsonArray(emptyList()),
                encryptedContent = "opaque-reasoning-state",
            ),
        )
        val functionCall = responseItem(
            OpenAiResponseOutputItem(
                id = "fc_1",
                type = "function_call",
                callId = "call_1",
                name = "lookup",
                arguments = "{}",
            ),
        )
        val input = listOf(
            user("start"),
            assistantToolCall("call_1").copy(
                responseOutputItems = listOf(reasoning, functionCall),
                responseOutputItemProvider = "OpenAI",
                responseOutputItemModel = "OpenAI:gpt-test",
            ),
            toolResult("call_1"),
        ).toResponsesInput(providerName = "OpenAI", targetModel = "gpt-test")
        val request = OpenAiResponsesRequest(model = "gpt-test", input = input)

        request.requireValidWireFormat("OpenAI")
        assertEquals(
            listOf("message", "reasoning", "function_call", "function_call_output"),
            input.map { it["type"]?.jsonPrimitive?.content },
        )
        assertEquals("opaque-reasoning-state", input[1]["encrypted_content"]?.jsonPrimitive?.content)
        assertEquals("fc_1", input[2]["id"]?.jsonPrimitive?.content)
        assertEquals("call_1", input[2]["call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun responsesContinuationDoesNotReplayOpaqueItemsToAnotherProvider() {
        val input = listOf(
            user("start"),
            assistantToolCall("call_1").copy(
                responseOutputItems = listOf(
                    responseItem(
                        OpenAiResponseOutputItem(
                            id = "rs_1",
                            type = "reasoning",
                            encryptedContent = "opaque-reasoning-state",
                        ),
                    ),
                ),
                responseOutputItemProvider = "OpenAI",
            ),
            toolResult("call_1"),
        ).toResponsesInput(providerName = "Relay")

        assertEquals(
            listOf("message", "function_call", "function_call_output"),
            input.map { it["type"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun responsesContinuationDoesNotReplayOpaqueItemsToAnotherModel() {
        val input = listOf(
            user("start"),
            assistantToolCall("call_1").copy(
                responseOutputItems = listOf(
                    responseItem(
                        OpenAiResponseOutputItem(
                            id = "rs_1",
                            type = "reasoning",
                            encryptedContent = "opaque-reasoning-state",
                        ),
                    ),
                ),
                responseOutputItemProvider = "OpenAI",
                responseOutputItemModel = "OpenAI:gpt-test",
            ),
            toolResult("call_1"),
        ).toResponsesInput(providerName = "OpenAI", targetModel = "gpt-other")

        assertEquals(
            listOf("message", "function_call", "function_call_output"),
            input.map { it["type"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun responsesContinuationDoesNotReplayOpaqueItemsWithoutARecordedModel() {
        val input = listOf(
            user("start"),
            assistantToolCall("call_1").copy(
                responseOutputItems = listOf(
                    responseItem(
                        OpenAiResponseOutputItem(
                            id = "rs_1",
                            type = "reasoning",
                            encryptedContent = "opaque-reasoning-state",
                        ),
                    ),
                ),
                responseOutputItemProvider = "OpenAI",
            ),
            toolResult("call_1"),
        ).toResponsesInput(providerName = "OpenAI", targetModel = "gpt-test")

        assertEquals(
            listOf("message", "function_call", "function_call_output"),
            input.map { it["type"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun responsesValidationRejectsOpaqueItemsWithToolFields() {
        val request = OpenAiResponsesRequest(
            model = "gpt-test",
            input = listOf(
                responseItem(
                    OpenAiResponseInputItem(
                        type = "reasoning",
                        id = "rs_1",
                        callId = "call_1",
                        encryptedContent = "opaque-reasoning-state",
                    ),
                ),
                listOf(user("continue")).toResponsesInput().single(),
            ),
        )

        val error = runCatching { request.requireValidWireFormat("OpenAI") }.exceptionOrNull()

        assertTrue(error is RequestFormatException)
        assertTrue(error?.message.orEmpty().contains("opaque item carries unrelated fields"))
    }

    @Test
    fun responsesValidationAcceptsStructuredFunctionOutput() {
        val request = OpenAiResponsesRequest(
            model = "gpt-test",
            input = listOf(
                responseItem(
                    OpenAiResponseInputItem(
                        type = "function_call",
                        callId = "call_1",
                        name = "lookup",
                        arguments = "{}",
                    ),
                ),
                Json.parseToJsonElement(
                    """{"type":"function_call_output","call_id":"call_1","output":{"type":"input_text","text":"ok"}}""",
                ).jsonObject,
            ),
        )

        request.requireValidWireFormat("OpenAI")
    }

    @Test
    fun responsesValidationPreservesUnknownOpaqueItemFields() {
        val request = OpenAiResponsesRequest(
            model = "gpt-test",
            input = listOf(
                Json.parseToJsonElement(
                    """{"id":"opaque_1","type":"computer_call","action":{"type":"click","x":10,"y":20},"provider_extension":{"enabled":true}}""",
                ).jsonObject,
                listOf(user("continue")).toResponsesInput().single(),
            ),
        )

        request.requireValidWireFormat("OpenAI")
    }

    private fun validRequest() = OpenAiChatRequest(
        model = "gpt-test",
        messages = listOf(
            user("start"),
            assistantToolCall("call_1"),
            toolResult("call_1"),
        ),
    )

    private fun user(text: String) = OpenAiMessage(
        role = "user",
        content = listOf(OpenAiContentPart(type = "text", text = text)),
    )

    private fun assistantToolCall(id: String) = OpenAiMessage(
        role = "assistant",
        content = null,
        toolCalls = listOf(
            OpenAiRequestToolCall(
                id = id,
                function = OpenAiRequestFunction(
                    name = "lookup",
                    arguments = "{}",
                ),
            )
        ),
    )

    private fun toolResult(id: String) = OpenAiMessage(
        role = "tool",
        content = listOf(OpenAiContentPart(type = "text", text = "ok")),
        toolCallId = id,
    )

    private fun responseItem(item: OpenAiResponseOutputItem): JsonObject =
        Json.encodeToJsonElement(OpenAiResponseOutputItem.serializer(), item).jsonObject

    private fun responseItem(item: OpenAiResponseInputItem): JsonObject =
        Json.encodeToJsonElement(OpenAiResponseInputItem.serializer(), item).jsonObject
}
