package com.newoether.agora.api.anthropic

import com.newoether.agora.api.util.RequestFormatException
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicRequestValidatorTest {
    @Test
    fun signedThinkingToolRound_isAccepted() {
        request(toolUseIncludesThinking = true).requireValidWireFormat()
    }

    @Test
    fun disabledThinkingAcceptsToolUseWithoutSignedThinking() {
        request(toolUseIncludesThinking = false).copy(
            thinking = AnthropicThinking(type = "disabled"),
            outputConfig = AnthropicOutputConfig(effort = "high"),
        ).requireValidWireFormat()
    }

    @Test
    fun disabledThinkingRejectsDisplayOrBudget() {
        val error = runCatching {
            request(toolUseIncludesThinking = false).copy(
                thinking = AnthropicThinking(
                    type = "disabled",
                    budgetTokens = 1024,
                    display = "summarized",
                ),
            ).requireValidWireFormat()
        }.exceptionOrNull()

        assertTrue(error is RequestFormatException)
        assertTrue(error?.message.orEmpty().contains("cannot carry budget_tokens or display"))
    }

    @Test
    fun adaptiveThinkingToolRoundWithoutSignedThinking_isBlockedLocally() {
        val error = runCatching {
            request(toolUseIncludesThinking = false).requireValidWireFormat()
        }.exceptionOrNull()

        assertTrue(error is RequestFormatException)
        assertTrue(error?.message.orEmpty().contains("leading signed thinking"))
    }

    @Test
    fun toolResultSeparatedFromItsToolUse_isBlockedLocally() {
        val broken = request(toolUseIncludesThinking = true).copy(
            messages = listOf(
                userText("start"),
                assistantToolUse(includeThinking = true),
                userText("interruption"),
            )
        )

        val error = runCatching { broken.requireValidWireFormat() }.exceptionOrNull()

        assertTrue(error is RequestFormatException)
        assertTrue(error?.message.orEmpty().contains("pending tool_use"))
    }

    private fun request(toolUseIncludesThinking: Boolean) = AnthropicRequest(
        model = "claude-sonnet-5",
        thinking = AnthropicThinking(type = "adaptive"),
        messages = listOf(
            userText("start"),
            assistantToolUse(includeThinking = toolUseIncludesThinking),
            AnthropicMessage(
                role = "user",
                content = listOf(
                    AnthropicContentPart(
                        type = "tool_result",
                        toolUseId = "call_1",
                        content = "ok",
                    )
                ),
            ),
        ),
    )

    private fun userText(text: String) = AnthropicMessage(
        role = "user",
        content = listOf(AnthropicContentPart(type = "text", text = text)),
    )

    private fun assistantToolUse(includeThinking: Boolean) = AnthropicMessage(
        role = "assistant",
        content = buildList {
            if (includeThinking) {
                add(
                    AnthropicContentPart(
                        type = "thinking",
                        thinking = "reasoning",
                        signature = "signed",
                    )
                )
            }
            add(
                AnthropicContentPart(
                    type = "tool_use",
                    id = "call_1",
                    name = "lookup",
                    input = JsonObject(emptyMap()),
                )
            )
        },
    )
}
