package com.newoether.agora.api

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHttpErrorTest {
    @Test
    fun `standard nested envelope preserves provider metadata`() {
        val error = providerHttpError(
            statusCode = 400,
            rawBody = """{"error":{"code":"invalid_request","type":"invalid_request_error","message":"Bad input"}}""",
        )

        assertEquals("invalid_request", error.code)
        assertEquals("invalid_request_error", error.type)
        assertEquals("Bad input", error.message)
    }

    @Test
    fun `primitive error value becomes concise API detail`() {
        val parsed = parseProviderHttpErrorBody("""{"error":"Invalid JSON"}""")
        val error = providerHttpError(400, """{"error":"Invalid JSON"}""")

        assertEquals("Invalid JSON", parsed?.message)
        assertTrue(parsed?.structured == true)
        assertEquals("400", error.code)
        assertNull(error.type)
        assertEquals("Invalid JSON", error.message)
    }

    @Test
    fun `common top level fields and nested status are supported`() {
        assertEquals(
            "top message",
            parseProviderHttpErrorBody(
                """{"message":"top message","detail":"detail","reason":"reason"}""",
            )?.message,
        )
        assertEquals(
            "detail",
            parseProviderHttpErrorBody("""{"detail":"detail","reason":"reason"}""")?.message,
        )
        assertEquals(
            "reason",
            parseProviderHttpErrorBody("""{"reason":"reason"}""")?.message,
        )
        assertEquals(
            "description",
            parseProviderHttpErrorBody("""{"error_description":"description"}""")?.message,
        )

        val gemini = providerHttpError(
            403,
            """{"error":{"code":403,"status":"PERMISSION_DENIED","message":"Denied"}}""",
        )
        assertEquals("403", gemini.code)
        assertEquals("PERMISSION_DENIED", gemini.type)
        assertEquals("Denied", gemini.message)
    }

    @Test
    fun `json string root is accepted without requiring an object envelope`() {
        val parsed = parseProviderHttpErrorBody(""""Service unavailable"""")

        assertEquals("Service unavailable", parsed?.message)
        assertTrue(parsed?.structured == true)
    }

    @Test
    fun `plain text and malformed json fall back to their raw text`() {
        val plain = parseProviderHttpErrorBody("  upstream unavailable  ")
        val malformed = parseProviderHttpErrorBody("""{"error":""")

        assertEquals("upstream unavailable", plain?.message)
        assertFalse(plain?.structured ?: true)
        assertEquals("""{"error":""", malformed?.message)
        assertFalse(malformed?.structured ?: true)

        val error = providerHttpError(502, "upstream unavailable")
        assertEquals("502", error.code)
        assertEquals("upstream unavailable", error.message)
    }

    @Test
    fun `empty response falls back to deterministic HTTP status`() {
        val nullBody = providerHttpError(503, null)
        val blankBody = providerHttpError(503, "   ")

        assertNull(nullBody.code)
        assertNull(nullBody.type)
        assertEquals("HTTP 503", nullBody.message)
        assertEquals(nullBody, blankBody)
    }

    @Test
    fun `all provider HTTP transports use the shared parser`() {
        val providerFiles = listOf(
            "app/src/main/java/com/newoether/agora/api/openai/BaseOpenAiProvider.kt",
            "app/src/main/java/com/newoether/agora/api/anthropic/AnthropicMessagesTransport.kt",
            "app/src/main/java/com/newoether/agora/api/gemini/GeminiProvider.kt",
            "app/src/main/java/com/newoether/agora/api/ollama/OllamaProvider.kt",
        )

        providerFiles.forEach { relativePath ->
            val source = sourceFile(relativePath)
            assertTrue("$relativePath must use providerHttpError", source.contains("providerHttpError("))
        }
    }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
