package com.newoether.agora.api.ollama

import com.newoether.agora.api.GenerationError
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaTokenUsageTest {
    @Test
    fun promptAndOutputAreRecordedWithoutInventingCacheBreakdown() {
        val usage = OllamaStreamResponse(
            done = true,
            promptEvalCount = 12,
            evalCount = 8,
        ).toTokenUsage()

        assertEquals(20, usage.totalTokenCount)
        assertEquals(12, usage.inputTokenCount)
        assertNull(usage.cachedInputTokenCount)
        assertNull(usage.uncachedInputTokenCount)
        assertEquals(8, usage.outputTokenCount)
    }

    @Test
    fun transportEofWithoutDoneIsIncompleteAndRetryableBeforeOutput() {
        val termination = ollamaStreamTermination(
            sawDone = false,
            doneReason = null,
            producedContent = false,
        )

        assertTrue(termination.toError("Ollama") is GenerationError.IncompleteStream)
        assertTrue(termination.isRetryable)
    }

    @Test
    fun lengthDoneReasonIsVisibleTruncationAndNeverRetried() {
        val termination = ollamaStreamTermination(
            sawDone = true,
            doneReason = "length",
            producedContent = true,
        )

        assertTrue(termination.toError("Ollama") is GenerationError.OutputTruncated)
        assertFalse(termination.isRetryable)
    }

    @Test
    fun blankDoneReasonIsAbsent() {
        val termination = ollamaStreamTermination(
            sawDone = true,
            doneReason = " ",
            producedContent = true,
        )

        assertNull(termination.stopReason)
        assertNull(termination.toError("Ollama"))
    }

    @Test
    fun errorOnlyNdjsonPayloadDoesNotRequireDoneField() {
        val decoded = Json.decodeFromString<OllamaStreamResponse>(
            """{"error":"failed to generate"}"""
        )

        assertFalse(decoded.done)
        assertEquals("failed to generate", decoded.error)
    }
}
