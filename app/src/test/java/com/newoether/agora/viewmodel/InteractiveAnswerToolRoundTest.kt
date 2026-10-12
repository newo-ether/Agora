package com.newoether.agora.viewmodel

import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.model.interactiveAnswer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class InteractiveAnswerToolRoundTest {
    @Test fun `accepted tool round persists exact executable fields inside existing segment budgets`() {
        val answer = InteractiveAnswer("Diagram", "A diagram", "Two nodes", html = "<svg>" + "x".repeat(70000) + "</svg>")
        val call = ToolCallData(InteractiveAnswer.TOOL_NAME, answer.encode(), answer.fallback,
            toolCallId = "call", resultText = answer.fallback, structuredResult = answer.encode())
        val round = GenerationToolRoundBuilder().build("parent", "conversation", "run", "model", "provider", listOf(call), listOf(MessageSegment(type = "tool", toolName = InteractiveAnswer.TOOL_NAME,
            toolCallId = "call", toolArgs = call.arguments, toolResult = call.result,
            toolResultText = call.resultText, toolStructuredResult = call.structuredResult,
            toolState = ToolExecutionStates.SUCCEEDED)))
        val saved = Json.decodeFromString<List<MessageSegment>>(round.entities.first().toolCallJson!!).single()
        assertEquals(answer, saved.interactiveAnswer())
        assertEquals("call", saved.toolCallId)
        assertEquals(ToolExecutionStates.SUCCEEDED, saved.toolState)
        assertEquals("run", round.entities.first().runId)
        assertTrue(round.entities.first().toolCallJson!!.toByteArray().size < 600000)
    }
}
