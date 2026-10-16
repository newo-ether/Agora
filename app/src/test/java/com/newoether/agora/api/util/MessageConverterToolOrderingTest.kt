package com.newoether.agora.api.util

import com.newoether.agora.api.OpenAiContentPart
import com.newoether.agora.api.OpenAiMessage
import com.newoether.agora.api.OpenAiRequestFunction
import com.newoether.agora.api.OpenAiRequestToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the tool-calling wire invariant some gateways validate hard: every tool result must
 * answer a pending call from the immediately preceding assistant tool_calls turn, and no
 * pending call may remain open when another role follows. See sanitizeToolCallOrdering.
 */
class MessageConverterToolOrderingTest {

    private fun textMessage(role: String, text: String) = OpenAiMessage(
        role = role,
        content = listOf(OpenAiContentPart(type = "text", text = text)),
    )

    private fun assistantWithToolCall(id: String) = OpenAiMessage(
        role = "assistant",
        content = null,
        toolCalls = listOf(
            OpenAiRequestToolCall(
                id = id,
                function = OpenAiRequestFunction(name = "generate_image", arguments = "{}"),
            ),
        ),
    )

    private fun toolResult(id: String, text: String = "ok") = OpenAiMessage(
        role = "tool",
        toolCallId = id,
        content = listOf(OpenAiContentPart(type = "text", text = text)),
    )

    @Test
    fun `well formed sequence passes through unchanged`() {
        val messages = listOf(
            textMessage("system", "sys"),
            textMessage("user", "draw a cat"),
            assistantWithToolCall("call_1"),
            toolResult("call_1"),
            textMessage("assistant", "here is your image"),
        )
        assertEquals(messages, sanitizeToolCallOrdering(messages))
    }

    @Test
    fun `orphan tool result is demoted to user text`() {
        val messages = listOf(
            textMessage("user", "hi"),
            toolResult("call_orphan", "image bytes"),
            textMessage("assistant", "done"),
        )
        val sanitized = sanitizeToolCallOrdering(messages)
        assertEquals(3, sanitized.size)
        assertEquals("user", sanitized[1].role)
        assertTrue(sanitized[1].content.orEmpty().first().text.orEmpty().contains("image bytes"))
        assertEquals("assistant", sanitized[2].role)
    }

    @Test
    fun `non tool message after unanswered call gets synthetic result first`() {
        val messages = listOf(
            textMessage("user", "hi"),
            assistantWithToolCall("call_1"),
            textMessage("assistant", "skipped the tool"),
        )
        val sanitized = sanitizeToolCallOrdering(messages)
        assertEquals(4, sanitized.size)
        assertEquals("tool", sanitized[2].role)
        assertEquals("call_1", sanitized[2].toolCallId)
        assertEquals("assistant", sanitized[3].role)
    }

    @Test
    fun `trailing pending call is answered at the end`() {
        val messages = listOf(
            textMessage("user", "hi"),
            assistantWithToolCall("call_1"),
        )
        val sanitized = sanitizeToolCallOrdering(messages)
        assertEquals(3, sanitized.size)
        assertEquals("tool", sanitized[2].role)
        assertEquals("call_1", sanitized[2].toolCallId)
    }

    @Test
    fun `second assistant turn closes prior pending calls before its own`() {
        val messages = listOf(
            textMessage("user", "hi"),
            assistantWithToolCall("call_1"),
            assistantWithToolCall("call_2"),
            toolResult("call_2"),
        )
        val sanitized = sanitizeToolCallOrdering(messages)
        assertEquals(5, sanitized.size)
        assertEquals("tool", sanitized[2].role)
        assertEquals("call_1", sanitized[2].toolCallId)
        assertEquals("assistant", sanitized[3].role)
        assertEquals("tool", sanitized[4].role)
        assertEquals("call_2", sanitized[4].toolCallId)
    }
}
