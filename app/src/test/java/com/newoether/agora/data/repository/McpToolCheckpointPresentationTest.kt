package com.newoether.agora.data.repository

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class McpToolCheckpointPresentationTest {
    @Test
    fun `toolCall fallback preserves routed title target and result without a segment body`() {
        val call = ToolCallData(
            toolName = "mcp_call_tool",
            arguments = """{"tool_id":"mcp_deleted_read_ab1234","arguments":{}}""",
            result = "protocol result",
            displayName = "filesystem/read_file",
            target = "Deleted filesystem server",
            toolCallId = "call",
            resultText = "display result",
            structuredResult = """{"value":"content"}""",
        )
        val message = ChatMessage(id = "message", text = "", participant = Participant.MODEL,
            toolCall = call)
        val checkpoint = message.toStreamCheckpoint()
        val restored = Json.decodeFromString<List<MessageSegment>>(requireNotNull(checkpoint.toolCallJson))
            .single()
        assertEquals(call.toolName, restored.toolName)
        assertEquals(call.arguments, restored.toolArgs)
        assertEquals(call.result, restored.toolResult)
        assertEquals(call.displayName, restored.toolDisplayName)
        assertEquals(call.target, restored.toolTarget)
        assertEquals(call.resultText, restored.toolResultText)
        assertEquals(call.structuredResult, restored.toolStructuredResult)
    }
}
