package com.newoether.agora.api.util

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.CitationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTerminationTest {
    @Test
    fun citationAndEmptyTextUpdates_doNotCountAsModelOutput() {
        val event = StreamEvent.CitationUpdate(
            CitationRecord(
                sourceId = "citation_source",
                provider = "test",
                kind = "url",
                title = "Source",
            ),
        )

        assertFalse(event.carriesModelOutput())
        assertFalse(StreamEvent.TextChunk("").carriesModelOutput())
    }

    @Test
    fun formattingOnlyChunksKeepParseAndReadFailuresEligibleBeforeOutput() {
        listOf(
            StreamEvent.TextChunk(" \t\n"),
            StreamEvent.ThoughtChunk(" \t\n"),
        ).forEach { event ->
            assertFalse(event.toString(), event.carriesModelOutput())
            listOf(
                GenerationError.SseParse("broken-json", "invalid JSON"),
                GenerationError.Network(0, "The server response could not be read."),
                GenerationError.Network(0, "Unexpected end of stream"),
                GenerationError.Api(null, null, "The server response could not be read."),
            ).forEach { error ->
                val result = termination(
                    sawTerminalMarker = false,
                    stopReason = null,
                    producedContent = event.carriesModelOutput(),
                    streamError = error,
                )
                assertTrue(error.toString(), result.isRetryable)
                val requiresReadRetry = ProviderRetryPolicy.requiresResponseReadRetry(error)
                assertEquals(error.toString(), requiresReadRetry, result.copy(producedContent = true).isRetryable)
                assertFalse(result.copy(alreadyReportedError = true).isRetryable)
                assertEquals(error.toString(), requiresReadRetry, result.copy(stopReason = "max_tokens").isRetryable)
            }
            val unreadableWithQuotaFields = termination(
                sawTerminalMarker = false,
                stopReason = null,
                producedContent = event.carriesModelOutput(),
                streamError = GenerationError.Api("insufficient_quota", null, "The server response could not be read."),
            )
            assertFalse(unreadableWithQuotaFields.isRetryable)
        }
        assertTrue(StreamEvent.TextChunk(" answer ").carriesModelOutput())
        assertTrue(StreamEvent.ThoughtChunk(" reasoning ").carriesModelOutput())
        assertTrue(StreamEvent.ThoughtChunk(" \n", signature = "signed").carriesModelOutput())
        assertTrue(StreamEvent.ToolCallUpdate("call", id = null, name = "file_read", arguments = "{}").carriesModelOutput())
    }

    private fun termination(
        sawTerminalMarker: Boolean = true,
        stopReason: String? = "end_turn",
        producedContent: Boolean = true,
        toolCallInFlight: Boolean = false,
        streamError: GenerationError? = null,
        alreadyReportedError: Boolean = false,
        timedOut: Boolean = false,
        retryableStreamError: Boolean = true,
    ) = StreamTermination(
        sawTerminalMarker = sawTerminalMarker,
        stopReason = stopReason,
        producedContent = producedContent,
        toolCallInFlight = toolCallInFlight,
        streamError = streamError,
        alreadyReportedError = alreadyReportedError,
        timedOut = timedOut,
        retryableStreamError = retryableStreamError,
    )

    @Test
    fun cleanCompletion_reportsNoErrorAndIsNotRetried() {
        val result = termination()

        assertNull(result.toError("Anthropic"))
        assertFalse(result.isRetryable)
    }

    @Test
    fun toolUseStop_isACleanCompletion() {
        val result = termination(stopReason = "tool_use")

        assertNull(result.toError("Anthropic"))
        assertFalse(result.isRetryable)
    }

    @Test
    fun terminalMarkerCannotCompleteAnOpenToolCall() {
        val result = termination(
            sawTerminalMarker = true,
            stopReason = "tool_use",
            producedContent = true,
            toolCallInFlight = true,
        )

        val error = result.toError("Anthropic") as GenerationError.IncompleteStream
        assertTrue(error.toolCallInFlight)
        assertTrue(result.isRetryable)
    }

    @Test
    fun missingTerminalMarker_isReportedAsIncompleteEvenWithContent() {
        // The exact shape of the bug: text streamed fine, then the relay closed the connection
        // where the tool_use block should have started. HTTP 200 alone must not mean success.
        val result = termination(
            sawTerminalMarker = false,
            stopReason = null,
            producedContent = true,
            toolCallInFlight = true,
        )

        val error = result.toError("Anthropic") as GenerationError.IncompleteStream
        assertEquals("Anthropic", error.provider)
        assertTrue(error.toolCallInFlight)
        // Failed partial output is rolled back by the consumer before replay.
        assertTrue(result.isRetryable)
    }

    @Test
    fun missingTerminalMarkerWithNoContent_isRetried() {
        val result = termination(
            sawTerminalMarker = false,
            stopReason = null,
            producedContent = false,
        )

        assertTrue(result.isRetryable)
    }

    @Test
    fun tokenCapStop_isReportedAsTruncationAndNeverRetried() {
        val maxTokens = termination(stopReason = "max_tokens", producedContent = false)
        val length = termination(stopReason = "length", producedContent = false)

        assertTrue(maxTokens.truncatedByTokenCap)
        assertTrue(length.truncatedByTokenCap)
        assertTrue(maxTokens.toError("Anthropic") is GenerationError.OutputTruncated)
        assertTrue(length.toError("OpenAI") is GenerationError.OutputTruncated)
        // Replaying the same request would truncate at the same place.
        assertFalse(maxTokens.isRetryable)
        assertFalse(length.isRetryable)
    }

    @Test
    fun inStreamError_isSurfacedAndRetriedWhenNothingWasShown() {
        val apiError = GenerationError.Api(code = null, type = "overloaded_error", message = "Overloaded")
        val result = termination(
            sawTerminalMarker = false,
            stopReason = null,
            producedContent = false,
            streamError = apiError,
        )

        assertEquals(apiError, result.toError("Anthropic"))
        assertTrue(result.isRetryable)
    }

    @Test
    fun inStreamErrorAfterContent_isRolledBackAndRetried() {
        val apiError = GenerationError.Api(code = null, type = "overloaded_error", message = "Overloaded")
        val result = termination(producedContent = true, streamError = apiError)

        assertEquals(apiError, result.toError("Anthropic"))
        assertTrue(result.isRetryable)
    }

    @Test
    fun explicitUnreadableServerResponse_isRetriedBeforeOutput() {
        val apiError = GenerationError.Api(
            code = null,
            type = "server_error",
            message = "The server response could not be read.",
        )
        val result = termination(
            sawTerminalMarker = true,
            stopReason = "failed",
            producedContent = false,
            streamError = apiError,
            retryableStreamError = false,
        )

        assertEquals(apiError, result.toError("OpenAI"))
        assertTrue(result.isRetryable)
    }

    @Test
    fun explicitUnreadableServerResponseAfterContent_isRetried() {
        val result = termination(
            sawTerminalMarker = true,
            stopReason = "failed",
            producedContent = true,
            streamError = GenerationError.Api(
                code = null,
                type = "server_error",
                message = "The server response could not be read.",
            ),
            retryableStreamError = false,
        )

        assertTrue(result.isRetryable)
    }

    @Test
    fun responseReadRetryReplacesPartialOutputWithoutDuplicatingReportedErrors() {
        listOf(
            GenerationError.SseParse("broken", "invalid JSON"),
            GenerationError.Network(0, "socket is closed"),
            GenerationError.Unknown(IllegalStateException("The server response could not be read.")),
        ).forEach { error ->
            listOf(false, true).forEach { produced ->
                listOf(null, "max_tokens", "length", "max_output_tokens").forEach { reason ->
                    val result = termination(
                        sawTerminalMarker = true,
                        stopReason = reason,
                        producedContent = produced,
                        toolCallInFlight = true,
                        streamError = error,
                        retryableStreamError = false,
                    )
                    assertEquals(error, result.toError("test"))
                    assertTrue("$error / $produced / $reason", result.isRetryable)
                    assertFalse(result.copy(alreadyReportedError = true).isRetryable)
                    assertNull(result.copy(alreadyReportedError = true).toError("test"))
                }
            }
        }
    }

    @Test
    fun otherExplicitTerminalStreamError_isNotRetried() {
        val result = termination(
            sawTerminalMarker = true,
            stopReason = "failed",
            producedContent = false,
            streamError = GenerationError.Api(
                code = null,
                type = "provider_error",
                message = "upstream rejected",
            ),
            retryableStreamError = false,
        )

        assertFalse(result.isRetryable)
    }

    @Test
    fun wrappedUnreadableResponseIOException_isRetryableTransportFailure() {
        val failure = java.io.IOException(
            "request failed",
            java.io.IOException("The server response could not be read."),
        )

        val error = failure.asRetryableTransportError() as GenerationError.Network

        assertEquals("The server response could not be read.", error.message)
        assertNull(java.io.IOException("invalid response").asRetryableTransportError())
        listOf(
            java.io.IOException(
                "The server response could not be read.",
                java.net.UnknownHostException("host unavailable"),
            ),
            java.net.UnknownHostException("The server response could not be read."),
        ).forEach { exactReadFailure ->
            assertTrue(exactReadFailure.asRetryableTransportError() is GenerationError.Network)
            assertTrue(exactReadFailure.asRetryableResponseBodyReadError() is GenerationError.Network)
        }
        assertTrue(java.net.UnknownHostException("host unavailable").asRetryableTransportError() is GenerationError.Network)
    }

    @Test
    fun permanentOrUnknownApiErrorsCannotRetryThroughAnIncompleteStream() {
        listOf(
            GenerationError.Api("insufficient_quota", "rate_limit_error", "Failed to generate"),
            GenerationError.Api("429", "invalid_request_error", "Overloaded"),
            GenerationError.Api(null, "provider_error", "Rejected"),
        ).forEach { error ->
            listOf(false, true).forEach { terminal ->
                val result = termination(
                    sawTerminalMarker = terminal,
                    stopReason = null,
                    producedContent = false,
                    toolCallInFlight = true,
                    timedOut = true,
                    retryableStreamError = true,
                    streamError = error,
                )
                assertEquals(error, result.toError("test"))
                assertFalse(error.toString(), result.isRetryable)
            }
        }
    }

    @Test
    fun prematureEofInCauseChainMapsOpeningAndBodyReads() {
        listOf(
            java.io.EOFException(),
            java.io.IOException("Unexpected end of stream on https://example.invalid"),
            java.io.IOException("read failed", java.io.IOException("Unexpected end of file")),
            IllegalStateException("wrapper", java.io.EOFException()),
        ).forEach { failure ->
            val opening = failure.asRetryableTransportError()
            val body = failure.asRetryableResponseBodyReadError()
            assertTrue(failure.toString(), opening is GenerationError.Network)
            assertEquals(opening, body)
            val result = termination(producedContent = false, streamError = body)
            assertTrue(result.isRetryable)
            assertTrue(result.copy(producedContent = true).isRetryable)
            assertTrue(result.copy(stopReason = "max_tokens").isRetryable)
            assertFalse(result.copy(alreadyReportedError = true).isRetryable)
        }
    }

    @Test
    fun cancellationIsPropagatedAndUnrelatedIoIsNotRetried() {
        assertNull(java.io.IOException("disk or protocol failure").asRetryableResponseBodyReadError())
        listOf(
            java.io.IOException("Unexpected end of file", kotlinx.coroutines.CancellationException("stopped")),
            kotlinx.coroutines.CancellationException("Unexpected end of stream"),
        ).forEach { failure ->
            org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                failure.asRetryableResponseBodyReadError()
            }
            org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                failure.asRetryableTransportError()
            }
        }
        assertTrue(java.io.IOException("read failed", java.net.UnknownHostException("host"))
            .asRetryableResponseBodyReadError() is GenerationError.Network)
    }

    @Test
    fun timeout_isSurfacedAndRetriedWhenNothingWasShown() {
        val result = termination(
            sawTerminalMarker = false,
            stopReason = null,
            producedContent = false,
            timedOut = true,
        )

        assertEquals(GenerationError.Timeout, result.toError("OpenAI"))
        assertTrue(result.isRetryable)
    }

    @Test
    fun alreadyReportedError_isNotDuplicatedOrRetried() {
        val result = termination(
            sawTerminalMarker = false,
            stopReason = null,
            producedContent = false,
            alreadyReportedError = true,
        )

        assertNull(result.toError("OpenAI"))
        assertFalse(result.isRetryable)
    }

    @Test
    fun stopReasonWithoutMessageStop_countsAsSemanticCompletion() {
        // Some relays omit message_stop but still report stop_reason. Requiring both would
        // report false truncations on every one of those endpoints.
        val result = termination(sawTerminalMarker = true, stopReason = "end_turn")

        assertNull(result.toError("Anthropic"))
    }
}
