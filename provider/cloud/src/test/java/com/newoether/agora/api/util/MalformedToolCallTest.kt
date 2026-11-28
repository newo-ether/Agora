package com.newoether.agora.api.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MalformedToolCallTest {

    @Test
    fun `stand-in is pairable and keeps what the model sent`() {
        val call = malformedToolCallRequest(
            cause = "duplicate tool call id",
            originalName = "file_read",
            originalArguments = "{\"path\":\"a\"}",
            streamKey = "s1",
            signature = "sig",
        )

        assertEquals(MALFORMED_TOOL_CALL_NAME, call.name)
        assertTrue(call.id.matches(safeWireToolCallId))
        assertTrue(call.name.matches(safeWireToolName))
        assertEquals("s1", call.streamKey)
        assertEquals("sig", call.signature)

        val text = malformedToolCallResultText(call.arguments)
        assertTrue(text.contains("not executed"))
        assertTrue(text.contains("duplicate tool call id"))
        assertTrue(text.contains("file_read"))
        assertTrue(text.contains("{\"path\":\"a\"}"))
    }

    @Test
    fun `each stand-in gets its own id and a stream key when none is usable`() {
        val first = malformedToolCallRequest(cause = "x", streamKey = " ")
        val second = malformedToolCallRequest(cause = "x")

        assertNotEquals(first.id, second.id)
        assertTrue(first.streamKey.isNotBlank())
        assertNotEquals(first.streamKey, second.streamKey)
    }

    @Test
    fun `long original arguments are cut so the replayed call stays small`() {
        val call = malformedToolCallRequest(cause = "x", originalArguments = "a".repeat(10_000))

        assertTrue(call.arguments.length < 2_500)
    }
}
