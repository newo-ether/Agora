package com.newoether.agora.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFetchFailureTest {
    @Test
    fun httpFailurePreservesStatusAndExtractsNestedProviderMessage() {
        val response = HttpClient.TextResponse(
            code = 401,
            body = """
                {
                  "error": {
                    "type": "authentication_error",
                    "message": "Invalid API key"
                  }
                }
            """.trimIndent(),
            isSuccessful = false,
        )

        val error = runCatching { response.requireModelFetchBody() }.exceptionOrNull()

        assertTrue(error is ModelFetchHttpException)
        error as ModelFetchHttpException
        assertEquals(401, error.statusCode)
        assertEquals("Invalid API key", error.detail)
        assertEquals("HTTP 401 — Invalid API key", error.message)
    }

    @Test
    fun rawProviderFailureIsCollapsedToOneBoundedLine() {
        val detail = extractModelFetchErrorDetail(
            "  first line\r\nsecond\tline " + "x".repeat(400),
        )

        assertTrue(detail?.startsWith("first line second line ") == true)
        assertTrue(detail?.contains('\n') == false)
        assertEquals(240, detail?.length)
        assertTrue(detail?.endsWith("…") == true)
    }

    @Test
    fun successfulEmptyBodyIsAnInvalidResponse() {
        val response = HttpClient.TextResponse(
            code = 200,
            body = "",
            isSuccessful = true,
        )

        val error = runCatching { response.requireModelFetchBody() }.exceptionOrNull()

        assertTrue(error is ModelFetchInvalidResponseException)
        assertEquals("Empty response body", error?.cause?.message)
    }
}
