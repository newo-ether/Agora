package com.newoether.agora.model
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNull

import org.junit.Assert.assertEquals
import org.junit.Test

class RunRecoveryPolicyTest {
    @Test
    fun recoverMessageStatusStopsOnlyInFlightModelMessages() {
        val inFlight = listOf(
            MessageStatus.SENDING,
            MessageStatus.THINKING,
            MessageStatus.TOOL_CALLING,
            MessageStatus.TRANSCRIBING,
        )

        inFlight.forEach { status ->
            assertEquals(
                MessageStatus.STOPPED,
                RunRecoveryPolicy.recoverMessageStatus(Participant.MODEL, status),
            )
            assertEquals(
                status,
                RunRecoveryPolicy.recoverMessageStatus(Participant.USER, status),
            )
        }
        listOf(MessageStatus.SUCCESS, MessageStatus.ERROR, MessageStatus.STOPPED).forEach { status ->
            assertEquals(
                status,
                RunRecoveryPolicy.recoverMessageStatus(Participant.MODEL, status),
            )
        }
    }

    @Test
    fun stopIncompleteTools_onlyTerminalizesLiveToolStates() {
        val recovered = RunRecoveryPolicy.stopIncompleteTools(
            listOf(
                MessageSegment(type = "answer", content = "kept"),
                MessageSegment(
                    type = "tool",
                    toolName = "file_write",
                    toolState = ToolExecutionStates.CALLING,
                ),
                MessageSegment(
                    type = "tool",
                    toolName = "shell",
                    toolState = ToolExecutionStates.RUNNING,
                ),
                MessageSegment(
                    type = "tool",
                    toolName = "background",
                    toolState = ToolExecutionStates.BACKGROUND_RUNNING,
                ),
                MessageSegment(
                    type = "tool",
                    toolName = "done",
                    toolState = ToolExecutionStates.SUCCEEDED,
                ),
            )
        )

        assertEquals(null, recovered[0].toolState)
        assertEquals(ToolExecutionStates.STOPPED, recovered[1].toolState)
        assertEquals(ToolExecutionStates.STOPPED, recovered[2].toolState)
        assertEquals(ToolExecutionStates.BACKGROUND_RUNNING, recovered[3].toolState)
        assertEquals(ToolExecutionStates.SUCCEEDED, recovered[4].toolState)
    }
    @Test
    fun stopIncompleteToolsJsonPreservesUnknownFields() {
        val raw =
            """[{"type":"tool","toolState":"running","future":{"nested":1}},""" +
                """{"type":"tool","toolState":"background_running","futureFlag":true},""" +
                """{"type":"answer","content":"kept","futureText":"keep"}]"""
        val recovered = requireNotNull(RunRecoveryPolicy.stopIncompleteToolsJson(raw))
        val segments = Json.parseToJsonElement(recovered).jsonArray
        assertEquals(
            ToolExecutionStates.STOPPED,
            segments[0].jsonObject["toolState"]?.jsonPrimitive?.content,
        )
        assertEquals("{\"nested\":1}", segments[0].jsonObject["future"]?.toString())
        assertEquals(
            ToolExecutionStates.BACKGROUND_RUNNING,
            segments[1].jsonObject["toolState"]?.jsonPrimitive?.content,
        )
        assertEquals("true", segments[1].jsonObject["futureFlag"]?.toString())
        assertEquals("\"keep\"", segments[2].jsonObject["futureText"]?.toString())
        assertNull(RunRecoveryPolicy.stopIncompleteToolsJson("{not-json"))
    }
}
