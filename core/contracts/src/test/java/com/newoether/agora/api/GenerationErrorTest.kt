package com.newoether.agora.api

import org.junit.Assert.*
import org.junit.Test

class GenerationErrorTest {

    @Test
    fun `Network error userMessage for 401`() {
        val msg = GenerationError.Network(401, "Unauthorized").userMessage()
        assertTrue(msg.contains("Authentication"))
    }

    @Test
    fun `Network error userMessage for 429`() {
        val msg = GenerationError.Network(429, "Rate limited").userMessage()
        assertTrue(msg.contains("Rate limit"))
    }

    @Test
    fun `Network error userMessage for 5xx`() {
        val msg = GenerationError.Network(500, "Internal error").userMessage()
        assertTrue(msg.contains("Server error"))
    }

    @Test
    fun `Network error userMessage generic`() {
        val msg = GenerationError.Network(418, "I'm a teapot").userMessage()
        assertTrue(msg.contains("418"))
    }

    @Test
    fun `Api error userMessage with code and type`() {
        val msg = GenerationError.Api("invalid_key", "AUTH_ERROR", "Bad key").userMessage()
        assertTrue(msg.contains("invalid_key"))
        assertTrue(msg.contains("AUTH_ERROR"))
        assertTrue(msg.contains("Bad key"))
    }

    @Test
    fun `Api error userMessage without type`() {
        val msg = GenerationError.Api("rate_limit", null, "Too many requests").userMessage()
        assertEquals("rate_limit: Too many requests", msg)
    }

    @Test
    fun `Api error userMessage without code`() {
        val msg = GenerationError.Api(null, null, "Something went wrong").userMessage()
        assertEquals("Something went wrong", msg)
    }

    @Test
    fun `SseParse error userMessage`() {
        val msg = GenerationError.SseParse("raw data", "Unexpected token").userMessage()
        assertEquals("Failed to parse server response.", msg)
    }

    @Test
    fun `ToolExecution error userMessage`() {
        val msg = GenerationError.ToolExecution("web_search", "{\"q\":\"test\"}", "API key missing").userMessage()
        assertEquals("Tool 'web_search' failed: API key missing", msg)
    }

    @Test
    fun `Transcription error userMessage`() {
        val msg = GenerationError.Transcription("/path/to/img.jpg", "Unsupported format").userMessage()
        assertEquals("Image transcription failed: Unsupported format", msg)
    }

    @Test
    fun `Embedding error userMessage`() {
        val msg = GenerationError.Embedding("emb-model-1", "Connection refused").userMessage()
        assertEquals("Embedding failed: Connection refused", msg)
    }

    @Test
    fun `LocalModel error userMessage`() {
        assertEquals("Model file not found", GenerationError.LocalModel("Model file not found").userMessage())
    }

    @Test
    fun `Configuration error userMessage`() {
        assertEquals("Base URL not set", GenerationError.Configuration("Base URL not set").userMessage())
    }

    @Test
    fun `Unknown error userMessage`() {
        val ex = RuntimeException("Boom!")
        assertEquals("Boom!", GenerationError.Unknown(ex).userMessage())
    }

    @Test
    fun `Unknown error userMessage keeps exception type when all messages are absent`() {
        val ex = RuntimeException()
        assertEquals("java.lang.RuntimeException", GenerationError.Unknown(ex).userMessage())
    }

    @Test
    fun `Unknown resolves first nonblank cause message without losing opaque detail`() {
        for (detail in listOf("connection reset", "invalid_request_error", "{\"message\":\"bad\"}", "https://example.com/error")) {
            val nested = IllegalStateException(detail)
            val outer = RuntimeException(" ").apply { initCause(nested) }
            assertEquals(detail, GenerationError.Unknown(outer).userMessage())
            assertEquals("Outer", GenerationError.Unknown(RuntimeException("Outer", nested)).detail())
        }
    }

    @Test
    fun `Unknown cause traversal uses identity and terminates on cycles`() {
        val first = RuntimeException(" ")
        val second = IllegalStateException()
        first.initCause(second)
        second.initCause(first)
        assertEquals("java.lang.RuntimeException", GenerationError.Unknown(first).detail())
        val equalOuter = object : RuntimeException(" ") {
            override fun equals(other: Any?) = other is Throwable
            override fun hashCode() = 0
        }
        equalOuter.initCause(RuntimeException("Concrete reason"))
        assertEquals("Concrete reason", GenerationError.Unknown(equalOuter).detail())
    }

    @Test
    fun `Cancelled userMessage`() {
        assertEquals("Generation cancelled.", GenerationError.Cancelled.userMessage())
    }

    @Test
    fun `Timeout userMessage`() {
        assertEquals("Request timed out.", GenerationError.Timeout.userMessage())
    }
}
