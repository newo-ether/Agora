package com.newoether.agora.api.anthropic

import com.newoether.agora.api.util.MALFORMED_TOOL_CALL_NAME
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.StreamEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminal-state contract for the Anthropic SSE router.
 *
 * These cover the bug class where a `tool_use` block silently disappeared: `stop_reason` was read
 * from the wrong protocol location (so it was always null), in-band `error` events were discarded,
 * and a nameless tool block was dropped without a diagnostic.
 */
class AnthropicStreamTerminationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(payload: String): AnthropicStreamEvent =
        json.decodeFromString(AnthropicStreamEvent.serializer(), payload)

    @Test
    fun stopReason_isReadFromMessageDeltaDelta_notFromMessage() {
        // Wire shape per protocol; reading it off `message` (the old code) always yielded null.
        val router = AnthropicStreamEventRouter()
        router.route(
            decode(
                """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":89}}"""
            )
        )

        assertEquals("tool_use", router.stopReason)
        assertTrue(router.sawTerminalMarker)
    }

    @Test
    fun stopReason_isNormalizedToLowercase() {
        val router = AnthropicStreamEventRouter()
        router.route(decode("""{"type":"message_delta","delta":{"stop_reason":"MAX_TOKENS"}}"""))

        assertEquals("max_tokens", router.stopReason)
    }

    @Test
    fun maxTokensStopReason_isCaptured() {
        val router = AnthropicStreamEventRouter()
        router.route(decode("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}"""))
        router.route(decode("""{"type":"message_stop"}"""))

        assertEquals("max_tokens", router.stopReason)
        assertTrue(router.messageStopReceived)
    }

    @Test
    fun blankRelayStopReasonCannotEraseEffectiveValue() {
        val router = AnthropicStreamEventRouter()
        router.route(decode("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}"""))
        router.route(
            decode(
                """{"type":"message_stop","message":{"stop_reason":" "}}"""
            )
        )

        assertEquals("max_tokens", router.stopReason)
        assertTrue(router.messageStopReceived)
    }

    @Test
    fun fieldlessMessageStop_stillProvesSemanticCompletion() {
        // Spec-compliant message_stop carries no fields at all.
        val router = AnthropicStreamEventRouter()
        router.route(decode("""{"type":"message_stop"}"""))

        assertTrue(router.messageStopReceived)
        assertTrue(router.sawTerminalMarker)
        assertNull(router.stopReason)
    }

    @Test
    fun noTerminalEvent_leavesSawTerminalMarkerFalse() {
        val router = AnthropicStreamEventRouter()
        router.route(decode("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""))
        router.route(decode("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}"""))

        assertFalse(router.sawTerminalMarker)
        assertNull(router.stopReason)
    }

    @Test
    fun inStreamErrorEvent_isCapturedRatherThanDiscarded() {
        val router = AnthropicStreamEventRouter()
        router.route(
            decode("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        )

        val error = router.streamError as GenerationError.Api
        assertEquals("overloaded_error", error.type)
        assertEquals("Overloaded", error.message)
    }

    @Test
    fun failedToGenerateOutcome_isCapturedAsRetryableStreamError() {
        val router = AnthropicStreamEventRouter()
        router.route(decode("""{"type":"message_stop","outcome":"Failed to generate"}"""))

        val error = router.streamError as GenerationError.Api
        assertEquals("failed_to_generate", error.type)
        assertEquals("Failed to generate", error.message)
    }

    @Test
    fun errorPayloadOnUnknownEventType_isStillCaptured() {
        // A relay may attach the error payload without using the `error` event type.
        val router = AnthropicStreamEventRouter()
        router.route(
            decode("""{"type":"something_else","error":{"type":"rate_limit_error","message":"Slow down"}}""")
        )

        assertNotNull(router.streamError)
    }

    @Test
    fun openToolBlockAtStreamEnd_isReportedAsInFlight() {
        val router = AnthropicStreamEventRouter()
        router.route(
            decode("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_1","name":"file_read"}}""")
        )

        assertTrue(router.toolCallInFlight)
        assertTrue(router.reportIncompleteBlocks().isEmpty())
        assertTrue(router.toolCallInFlight)
    }

    @Test
    fun namelessToolBlockClosing_becomesAMalformedCallInsteadOfVanishing() {
        val router = AnthropicStreamEventRouter()
        router.route(
            decode("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_1"}}""")
        )
        val events = router.route(decode("""{"type":"content_block_stop","index":0}"""))

        // The model gets an error result for the damaged block and can re-issue the call.
        val call = events.single() as StreamEvent.ToolCallRequest
        assertEquals(MALFORMED_TOOL_CALL_NAME, call.name)
    }

    @Test
    fun truncatedArgumentsAtBlockStop_reportAnErrorAndNeverCompleteTheCall() {
        val router = AnthropicStreamEventRouter()
        router.route(
            decode("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_1","name":"file_read"}}""")
        )
        router.route(
            decode("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"unfinished"}}""")
        )

        val events = router.route(decode("""{"type":"content_block_stop","index":0}"""))

        // Truncated JSON is never executed as file_read; it is answered as a malformed call.
        val call = events.single() as StreamEvent.ToolCallRequest
        assertEquals(MALFORMED_TOOL_CALL_NAME, call.name)
    }

    @Test
    fun multipleNamelessOpenBlocksHoldOneParseFailureForRetryWithoutReportingIt() {
        val router = AnthropicStreamEventRouter()
        router.route(
            decode("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_1"}}""")
        )
        router.route(
            decode("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call_2"}}""")
        )

        assertTrue(router.reportIncompleteBlocks().isEmpty())
        val error = router.streamError as GenerationError.SseParse
        assertEquals("stream_end", error.rawLine)
        assertFalse(router.reportedError)
        assertTrue(router.reportIncompleteBlocks().isEmpty())
        assertEquals(error, router.streamError)
    }

    @Test
    fun duplicateToolCallIdsRejectTheSecondCall() {
        val router = AnthropicStreamEventRouter()
        repeat(2) { index ->
            router.route(
                decode("""{"type":"content_block_start","index":$index,"content_block":{"type":"tool_use","id":"call_1","name":"file_read","input":{}}}""")
            )
            val events = router.route(
                decode("""{"type":"content_block_stop","index":$index}""")
            )
            if (index == 0) {
                assertTrue(events.single() is StreamEvent.ToolCallRequest)
            } else {
                val call = events.single() as StreamEvent.ToolCallRequest
                assertEquals(MALFORMED_TOOL_CALL_NAME, call.name)
                assertTrue(call.id != "call_1")
            }
        }
    }

    @Test
    fun namelessToolBlockAtEofHoldsParseFailureForProviderRetry() {
        val router = AnthropicStreamEventRouter()
        router.route(
            decode("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_1"}}""")
        )
        val events = router.reportIncompleteBlocks()

        assertTrue(events.isEmpty())
        assertTrue(router.streamError is GenerationError.SseParse)
        assertFalse(router.reportedError)
        assertTrue(router.toolCallInFlight)
    }

    @Test
    fun emptyInputJsonDelta_neverErasesAccumulatedArguments() {
        val router = AnthropicStreamEventRouter()
        router.route(
            decode("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_1","name":"file_read"}}""")
        )
        router.route(
            decode("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a.txt\"}"}}""")
        )
        // Non-compliant relay: a placeholder delta with no content.
        router.route(
            decode("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":""}}""")
        )

        val completed = router.route(decode("""{"type":"content_block_stop","index":0}"""))
            .single() as StreamEvent.ToolCallRequest
        assertEquals("""{"path":"a.txt"}""", completed.arguments)
    }

    @Test
    fun snapshotInputJsonDeltas_doNotDuplicateArguments() {
        // Non-compliant relay: every delta resends the whole accumulated value.
        val router = AnthropicStreamEventRouter()
        router.route(
            decode("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_1","name":"file_read"}}""")
        )
        router.route(
            decode("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\""}}""")
        )
        router.route(
            decode("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a.txt\"}"}}""")
        )

        val completed = router.route(decode("""{"type":"content_block_stop","index":0}"""))
            .single() as StreamEvent.ToolCallRequest
        assertEquals("""{"path":"a.txt"}""", completed.arguments)
    }
}
