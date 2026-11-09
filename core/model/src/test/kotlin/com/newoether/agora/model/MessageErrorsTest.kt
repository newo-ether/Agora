package com.newoether.agora.model

import org.junit.Assert.*
import org.junit.Test

class MessageErrorsTest {
    @Test fun terminalDetailKeepsTheLastExplicitErrorAndCodeWithoutUsingPartialAnswer() {
        val failure = MessageSegment("error", "Connection closed", errorCode = "connection_closed")
        val segments = listOf(MessageSegment("answer", "Partial answer"),
            MessageSegment("error", "Old detail"), failure)
        assertEquals(failure, terminalErrorSegment(MessageStatus.ERROR, Participant.MODEL, "Partial answer", segments))
        assertNull(terminalErrorSegment(MessageStatus.SUCCESS, Participant.MODEL, "Answer", segments))
        assertNull(terminalErrorSegment(MessageStatus.ERROR, Participant.MODEL, "Partial answer", segments.take(1)))
    }

    @Test fun legacyErrorOnlyRowsRemainReadableAndBlankFailuresDoNotInventDetails() {
        assertEquals("Legacy failure", terminalErrorSegment(MessageStatus.ERROR, Participant.MODEL,
            "Legacy failure", emptyList())?.content)
        assertNull(terminalErrorSegment(MessageStatus.ERROR, Participant.MODEL, "", emptyList()))
        val legacy = SubagentCompletion.decode("""{"id":"old","status":"failed","text":"Legacy failure"}""")
        assertNotNull(legacy)
        assertNull(legacy?.errorText)
        assertNull(legacy?.errorCode)
    }
}
