package com.newoether.agora.api.util

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiFunctionCall
import com.newoether.agora.api.OpenAiMessage
import com.newoether.agora.api.OpenAiRequestFunction
import com.newoether.agora.api.OpenAiRequestToolCall
import com.newoether.agora.api.OpenAiToolCall
import com.newoether.agora.api.anthropic.AnthropicContentPart
import com.newoether.agora.api.anthropic.AnthropicImageSource
import com.newoether.agora.api.anthropic.coalesceAnthropicMessages
import com.newoether.agora.api.anthropic.AnthropicMessage
import com.newoether.agora.api.anthropic.AnthropicRequest
import com.newoether.agora.api.anthropic.requireValidWireFormat
import com.newoether.agora.api.gemini.ApiGenerateContentRequest
import com.newoether.agora.api.gemini.ApiInlineData
import com.newoether.agora.api.gemini.coalesceGeminiContents
import com.newoether.agora.api.gemini.ApiRequestContent
import com.newoether.agora.api.gemini.ApiRequestPart
import com.newoether.agora.api.gemini.GeminiFunctionCall
import com.newoether.agora.api.gemini.GeminiFunctionResponse
import com.newoether.agora.api.gemini.requireValidWireFormat
import com.newoether.agora.api.ollama.OllamaChatRequest
import com.newoether.agora.api.ollama.OllamaMessage
import com.newoether.agora.api.ollama.requireValidWireFormat
import com.newoether.agora.api.openai.requireValidWireFormat
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for provider requests that terminate in tool or Compact input. */
class ProviderContinuationRequestValidatorTest {
    private val emptyObject = JsonObject(emptyMap())

    @Test
    fun openAiAcceptsCompleteToolResultAsTerminalInput() {
        OpenAiChatRequest(
            model = "deepseek-chat",
            messages = listOf(
                OpenAiMessage("user", content = listOf(text("start"))),
                OpenAiMessage(
                    "assistant",
                    toolCalls = listOf(
                        OpenAiRequestToolCall(
                            id = "call_1",
                            function = OpenAiRequestFunction("file_read", "{}"),
                        )
                    ),
                ),
                OpenAiMessage("tool", content = listOf(text("result")), toolCallId = "call_1"),
            ),
        ).requireValidWireFormat("DeepSeek")
    }

    @Test
    fun anthropicAcceptsCompleteToolResultAsTerminalInput() {
        AnthropicRequest(
            model = "claude-sonnet-5",
            messages = listOf(
                AnthropicMessage("user", listOf(AnthropicContentPart("text", text = "start"))),
                AnthropicMessage(
                    "assistant",
                    listOf(
                        AnthropicContentPart(
                            type = "tool_use",
                            id = "call_1",
                            name = "file_read",
                            input = emptyObject,
                        )
                    ),
                ),
                AnthropicMessage(
                    "user",
                    listOf(
                        AnthropicContentPart(
                            type = "tool_result",
                            toolUseId = "call_1",
                            content = "result",
                        )
                    ),
                ),
            ),
        ).requireValidWireFormat()
    }

    @Test
    fun geminiAcceptsCompleteFunctionResponseAsTerminalInput() {
        ApiGenerateContentRequest(
            contents = listOf(
                ApiRequestContent("user", listOf(ApiRequestPart(text = "start"))),
                ApiRequestContent(
                    "model",
                    listOf(
                        ApiRequestPart(
                            functionCall = GeminiFunctionCall(
                                id = "call_1",
                                name = "file_read",
                                args = emptyObject,
                            )
                        )
                    ),
                ),
                ApiRequestContent(
                    "user",
                    listOf(
                        ApiRequestPart(
                            functionResponse = GeminiFunctionResponse(
                                id = "call_1",
                                name = "file_read",
                                response = emptyObject,
                            )
                        )
                    ),
                ),
            ),
        ).requireValidWireFormat("gemini-2.5-pro")
    }

    @Test
    fun ollamaAcceptsCompleteToolResultAsTerminalInput() {
        OllamaChatRequest(
            model = "qwen3",
            messages = listOf(
                OllamaMessage("user", content = "start"),
                OllamaMessage(
                    "assistant",
                    toolCalls = listOf(
                        OpenAiToolCall(
                            index = 0,
                            id = "call_1",
                            type = "function",
                            function = OpenAiFunctionCall("file_read", emptyObject),
                        )
                    ),
                ),
                OllamaMessage("tool", content = "result", toolName = "file_read"),
            ),
        ).requireValidWireFormat()
    }

    @Test
    fun compactContinuationIsValidTerminalUserInputForEveryProvider() {
        val content = prepareMessages(
            listOf(
                ChatMessage(
                    id = "compact_boundary",
                    text = "summary",
                    participant = Participant.MODEL,
                )
            ),
            contextTokenBudget = 4_096,
        ).single().text

        OpenAiChatRequest(
            model = "deepseek-chat",
            messages = listOf(OpenAiMessage("user", content = listOf(text(content)))),
        ).requireValidWireFormat("DeepSeek")
        AnthropicRequest(
            model = "claude-sonnet-5",
            messages = listOf(
                AnthropicMessage("user", listOf(AnthropicContentPart("text", text = content)))
            ),
        ).requireValidWireFormat()
        ApiGenerateContentRequest(
            contents = listOf(
                ApiRequestContent("user", listOf(ApiRequestPart(text = content)))
            ),
        ).requireValidWireFormat("gemini-2.5-pro")
        OllamaChatRequest(
            model = "qwen3",
            messages = listOf(OllamaMessage("user", content = content)),
        ).requireValidWireFormat()
    }

    @Test
    fun anthropicCoalescesAdjacentUserPayloadsLosslesslyBeforeValidation() {
        val payloads = listOf(
            AnthropicMessage("user", listOf(AnthropicContentPart("text", text = "typed"))),
            AnthropicMessage("user", listOf(
                AnthropicContentPart("text", text = "<ask_user_answers>answer</ask_user_answers>"),
                AnthropicContentPart("image", source = AnthropicImageSource(
                    mediaType = "image/png", data = "aW1hZ2U=",
                )),
            )),
            AnthropicMessage("user", listOf(
                AnthropicContentPart("text", text = "<subagent_result>done</subagent_result>"),
            )),
        )

        val adapted = coalesceAnthropicMessages(payloads)

        assertEquals(payloads.flatMap { it.content }, adapted.single().content)
        assertEquals(3, payloads.size)
        AnthropicRequest(model = "claude-sonnet-5", messages = adapted).requireValidWireFormat()
    }

    @Test
    fun geminiCoalescesAdjacentUserPayloadsLosslesslyBeforeValidation() {
        val payloads = listOf(
            ApiRequestContent("user", listOf(ApiRequestPart(text = "typed"))),
            ApiRequestContent("user", listOf(
                ApiRequestPart(text = "<ask_user_answers>answer</ask_user_answers>"),
                ApiRequestPart(inlineData = ApiInlineData(mimeType = "image/png", data = "aW1hZ2U=")),
            )),
            ApiRequestContent("user", listOf(
                ApiRequestPart(text = "<subagent_result>done</subagent_result>"),
            )),
        )

        val adapted = coalesceGeminiContents(payloads)

        assertEquals(payloads.flatMap { it.parts }, adapted.single().parts)
        assertEquals(3, payloads.size)
        ApiGenerateContentRequest(contents = adapted).requireValidWireFormat("gemini-2.5-pro")
    }

    @Test
    fun anthropicCoalescingPreservesLeadingToolResultsAndRejectsInterruptedPairing() {
        val call = AnthropicMessage("assistant", listOf(AnthropicContentPart(
            type = "tool_use", id = "call_1", name = "file_read", input = emptyObject,
        )))
        val result = AnthropicMessage("user", listOf(AnthropicContentPart(
            type = "tool_result", toolUseId = "call_1", content = "result",
        )))
        val first = AnthropicMessage("user", listOf(AnthropicContentPart("text", text = "first")))
        val last = AnthropicMessage("user", listOf(AnthropicContentPart("text", text = "last")))
        val adapted = coalesceAnthropicMessages(listOf(first, call, result, first, last))

        assertEquals(result.content + first.content + last.content, adapted.last().content)
        AnthropicRequest(model = "claude-sonnet-5", messages = adapted).requireValidWireFormat()
        val error = runCatching {
            AnthropicRequest(
                model = "claude-sonnet-5",
                messages = coalesceAnthropicMessages(listOf(first, call, first, result, last)),
            ).requireValidWireFormat()
        }.exceptionOrNull()
        assertTrue(error is RequestFormatException)
    }

    @Test
    fun geminiCoalescingPreservesLeadingResponsesAndRejectsInterruptedPairing() {
        val call = ApiRequestContent("model", listOf(ApiRequestPart(
            functionCall = GeminiFunctionCall(id = "call_1", name = "file_read", args = emptyObject),
        )))
        val result = ApiRequestContent("user", listOf(ApiRequestPart(
            functionResponse = GeminiFunctionResponse(
                id = "call_1", name = "file_read", response = emptyObject,
            ),
        )))
        val first = ApiRequestContent("user", listOf(ApiRequestPart(text = "first")))
        val last = ApiRequestContent("user", listOf(ApiRequestPart(text = "last")))
        val adapted = coalesceGeminiContents(listOf(first, call, result, first, last))

        assertEquals(result.parts + first.parts + last.parts, adapted.last().parts)
        ApiGenerateContentRequest(contents = adapted).requireValidWireFormat("gemini-2.5-pro")
        val error = runCatching {
            ApiGenerateContentRequest(
                contents = coalesceGeminiContents(listOf(first, call, first, result, last)),
            ).requireValidWireFormat("gemini-2.5-pro")
        }.exceptionOrNull()
        assertTrue(error is RequestFormatException)
    }

    private fun text(value: String) = com.newoether.agora.api.OpenAiContentPart(
        type = "text",
        text = value,
    )
}
