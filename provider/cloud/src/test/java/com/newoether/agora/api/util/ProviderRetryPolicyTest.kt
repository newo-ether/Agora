package com.newoether.agora.api.util

import com.newoether.agora.api.GenerationError
import java.io.EOFException
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRetryPolicyTest {
    @Test
    fun fiveRetriesUseBoundedExponentialJitter() {
        assertEquals(5, ProviderRetryPolicy.MAX_RETRIES)
        assertEquals(6, ProviderRetryPolicy.MAX_ATTEMPTS)
        (1..5).forEach { retry ->
            val ceiling = 1_000L shl (retry - 1)
            repeat(20) {
                assertTrue(ProviderRetryPolicy.delayMillis(retry) in ceiling / 2..ceiling)
            }
        }
        assertEquals(120_000L, ProviderRetryPolicy.delayMillis(1, "120"))
    }

    @Test
    fun transientHttpStatusesRetryButPermanentDetailsWin() {
        listOf(408, 425, 429, 500, 502, 503, 504, 529).forEach { status ->
            assertTrue("$status", ProviderRetryPolicy.shouldRetryHttp(status, "Try again", emptySet()))
            listOf("code", "type").forEach { field ->
                listOf("insufficient_quota", "invalid_api_key", "authentication_error", "invalid_request_error").forEach { type ->
                    val body = """{"error":{"$field":"$type","message":"The server response could not be read; overloaded"}}"""
                    assertFalse("$status/$field/$type", ProviderRetryPolicy.shouldRetryHttp(status, body, setOf(status)))
                }
            }
        }
        listOf(400, 401, 403, 404, 409, 422).forEach { status ->
            listOf("Failed to generate", "The server response could not be read.", "Unexpected end of stream").forEach { body ->
                assertFalse("$status/$body", ProviderRetryPolicy.shouldRetryHttp(status, body, setOf(status)))
            }
        }
    }

    @Test
    fun temporaryRateLimitsRemainTransient() {
        listOf(
            """{"error":{"code":"rate_limit_exceeded","message":"Per-minute quota exceeded"}}""",
            """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Request quota exceeded; retry in one minute"}}""",
            "Too many requests",
        ).forEach { body -> assertTrue(body, ProviderRetryPolicy.shouldRetryHttp(429, body, emptySet())) }
        listOf("Out of credits", "You exceeded your current quota", "Your credit balance is too low").forEach { body ->
            assertFalse(body, ProviderRetryPolicy.shouldRetryHttp(429, body, emptySet()))
        }
    }

    @Test
    fun streamStatusAndFieldsKeepPermanentPrecedence() {
        listOf(
            GenerationError.Api("insufficient_quota", "rate_limit_error", "Failed to generate"),
            GenerationError.Api("429", "invalid_request_error", "Overloaded"),
            GenerationError.Api(null, "authentication_error", "The server response could not be read."),
            GenerationError.Network(401, "The server response could not be read."),
            GenerationError.Network(403, "Unexpected end of stream"),
        ).forEach { assertFalse(it.toString(), ProviderRetryPolicy.shouldRetryStreamError(it)) }
        listOf(
            GenerationError.Api("rate_limit_exceeded", null, "Try again"),
            GenerationError.Api(null, "overloaded_error", "Try again"),
            GenerationError.Api("425", null, "Try again"),
            GenerationError.Network(429, "Try again"),
            GenerationError.Network(0, "software caused connection abort"),
        ).forEach { assertTrue(it.toString(), ProviderRetryPolicy.shouldRetryStreamError(it)) }
    }

    @Test
    fun responseReadRetryIncludesTransportFailuresAndExcludesCancellation() {
        listOf(
            GenerationError.SseParse("not-json", "invalid JSON"),
            GenerationError.Api(null, null, "The server response could not be read."),
            GenerationError.Network(0, "Unexpected end of stream"),
            GenerationError.Unknown(IOException("request failed", EOFException())),
            GenerationError.Unknown(UnknownHostException("host unavailable")),
            GenerationError.Timeout,
        ).forEach { assertTrue(it.toString(), ProviderRetryPolicy.requiresResponseReadRetry(it)) }
        listOf(
            null,
            GenerationError.Api("insufficient_quota", null, "The server response could not be read."),
            GenerationError.Network(401, "The server response could not be read."),
            GenerationError.Unknown(IllegalStateException("invalid response")),
            GenerationError.Unknown(IOException("Unexpected end of stream", CancellationException("stopped"))),
        ).forEach { assertFalse(it.toString(), ProviderRetryPolicy.requiresResponseReadRetry(it)) }
    }

    @Test
    fun encodedReadDiagnosticsCannotOverridePermanentFields() {
        val body = """{"error":{"type":"invalid_request_error","message":"The server response could not be \u0072ead."}}"""
        listOf(400, 401, 403, 429, 503).forEach { status ->
            assertFalse(ProviderRetryPolicy.shouldRetryHttp(status, body, setOf(429, 503)))
        }
        assertTrue(ProviderRetryPolicy.isFailedToGenerateOutcome("UPSTREAM: FAILED TO GENERATE"))
        assertFalse(ProviderRetryPolicy.isFailedToGenerateOutcome(null))
        assertTrue(ProviderRetryPolicy.isRetryableUpstreamFailure("temporarily unavailable"))
        assertFalse(ProviderRetryPolicy.isRetryableUpstreamFailure("completed"))
    }
}
