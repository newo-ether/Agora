package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.tool.BuiltInToolNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CanonicalToolNamesTest {
    @Test
    fun everyLegacyRoundReplaysAsCanonicalWithoutMutatingStoredInput() {
        BuiltInToolNames.legacyToCanonical.forEach { (legacy, canonical) ->
            val raw = Json.parseToJsonElement(
                """{"type":"function_call","name":"$legacy","call_id":"call","arguments":"{}","opaque":"retained"}""",
            ).jsonObject
            val segment = MessageSegment(type = "tool", toolName = legacy, toolArgs = "{}",
                toolCallId = "call", signature = "opaque-signature", responseOutputItems = listOf(raw))
            val call = ToolCallData(legacy, "{}", "", signature = "opaque-signature",
                toolCallId = "call", responseOutputItems = listOf(raw))
            val history = listOf(
                ChatMessage(id = "user", text = "request", participant = Participant.USER),
                ChatMessage(id = "tool_round", text = "", participant = Participant.MODEL,
                    toolCall = call, segments = listOf(segment)),
                ChatMessage(id = "result_round", text = "result", participant = Participant.USER,
                    toolCall = call.copy(result = "result"),
                    segments = listOf(segment.copy(toolResult = "result"))),
            )
            val projected = canonicalToolNames(history)
            projected.drop(1).forEach { message ->
                val toolCall = requireNotNull(message.toolCall)
                assertEquals(canonical, toolCall.toolName)
                assertEquals(canonical, message.segments!!.single().toolName)
                assertEquals(canonical, toolCall.responseOutputItems.single()["name"]!!.jsonPrimitive.content)
                assertEquals("opaque-signature", toolCall.signature)
                assertEquals("{}", toolCall.arguments)
                assertEquals("retained", toolCall.responseOutputItems.single()["opaque"]!!.jsonPrimitive.content)
            }
            val prepared = prepareMessages(history, 100_000)
            assertTrue("Missing replay for $legacy", prepared.any { message ->
                message.segments.orEmpty().any { it.toolName == canonical }
            })
            assertFalse(prepared.any { it.text.contains("Archived tool activity") })
            assertEquals(legacy, history[1].toolCall!!.toolName)
            assertEquals(legacy, history[1].segments!!.single().toolName)
            assertEquals(legacy, raw["name"]!!.jsonPrimitive.content)
            assertEquals(projected, canonicalToolNames(projected))
        }
    }

    @Test
    fun unknownNamesAndOpaqueResponseItemsKeepOriginalMeaning() {
        val raw = Json.parseToJsonElement("""{"type":"reasoning","name":"read_memory_file","encrypted_content":"abc"}""").jsonObject
        val call = ToolCallData("mcp_custom_read_memory_file", "{}", "read_memory_file", responseOutputItems = listOf(raw))
        val message = ChatMessage(text = "read_memory_file", participant = Participant.MODEL, toolCall = call)
        assertEquals(listOf(message), canonicalToolNames(listOf(message)))
        assertEquals("unknown", BuiltInToolNames.canonical("unknown"))
    }
}
