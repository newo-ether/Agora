package com.newoether.agora.api.gemini

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiTokenUsageTest {
    @Test
    fun candidatesAndThoughtsTogetherFormOutput() {
        val usage = ApiUsageMetadata(
            promptTokenCount = 80,
            cachedContentTokenCount = 30,
            candidatesTokenCount = 15,
            thoughtsTokenCount = 5,
            totalTokenCount = 100,
        ).toTokenUsage()

        assertEquals(100, usage.totalTokenCount)
        assertEquals(80, usage.inputTokenCount)
        assertEquals(30, usage.cachedInputTokenCount)
        assertEquals(50, usage.uncachedInputTokenCount)
        assertEquals(20, usage.outputTokenCount)
        assertEquals(5, usage.reasoningTokenCount)
    }
}
