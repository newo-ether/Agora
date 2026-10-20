package com.newoether.agora.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class InteractiveAnswerPersistenceTest {
    private val answer = InteractiveAnswer("Diagram", "Two nodes", "A connects to B", html = "<svg></svg>")
    private val segment = MessageSegment(type = "tool", toolName = InteractiveAnswer.TOOL_NAME,
        toolCallId = "call", toolState = ToolExecutionStates.SUCCEEDED, toolStructuredResult = answer.encode())

    @Test fun `existing segment serialization preserves completed artifacts`() {
        val restored = Json.decodeFromString<List<MessageSegment>>(Json.encodeToString(listOf(segment)))
        assertEquals(answer, restored.single().interactiveAnswer())
    }
    @Test fun `arbitrary text foreign tool failed stopped and partial results are never executable`() {
        for (value in listOf(
            segment.copy(toolStructuredResult = null, toolResult = answer.encode()),
            segment.copy(toolName = "other"), segment.copy(type = "answer"),
            segment.copy(toolState = ToolExecutionStates.FAILED), segment.copy(toolState = ToolExecutionStates.STOPPED),
            segment.copy(toolState = ToolExecutionStates.RUNNING), segment.copy(toolState = null),
            segment.copy(toolStructuredResult = "{}"),
        )) assertNull(value.interactiveAnswer())
    }
    @Test fun `older messages remain readable and do not acquire an executable view`() {
        val legacy = Json.decodeFromString<MessageSegment>("""{"type":"answer","content":"Plain answer"}""")
        assertEquals("Plain answer", legacy.content)
        assertNull(legacy.interactiveAnswer())
    }
}
