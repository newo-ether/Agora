package com.newoether.agora.api

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiUsageTokenUsageTest {
    @Test
    fun standardOpenAiUsageDerivesCacheMissAndKeepsReasoningInsideOutput() {
        val usage = OpenAiUsage(
            promptTokens = 100,
            completionTokens = 40,
            totalTokens = 140,
            promptTokensDetails = OpenAiPromptTokensDetails(cachedTokens = 25),
            completionTokensDetails = OpenAiCompletionTokensDetails(reasoningTokens = 10),
        ).toTokenUsage()

        assertEquals(140, usage.totalTokenCount)
        assertEquals(100, usage.inputTokenCount)
        assertEquals(25, usage.cachedInputTokenCount)
        assertEquals(75, usage.uncachedInputTokenCount)
        assertEquals(40, usage.outputTokenCount)
        assertEquals(10, usage.reasoningTokenCount)
    }

    @Test
    fun deepSeekTopLevelCacheCountersArePreferred() {
        val usage = OpenAiUsage(
            promptTokens = 100,
            completionTokens = 20,
            totalTokens = 120,
            promptTokensDetails = OpenAiPromptTokensDetails(cachedTokens = 1),
            promptCacheHitTokens = 60,
            promptCacheMissTokens = 40,
        ).toTokenUsage()

        assertEquals(60, usage.cachedInputTokenCount)
        assertEquals(40, usage.uncachedInputTokenCount)
    }

    @Test
    fun chatAndResponsesUsageMapCacheWriteTokens() {
        val chat = OpenAiUsage(
            promptTokens = 100,
            completionTokens = 20,
            totalTokens = 120,
            cacheWriteTokens = 35,
        ).toTokenUsage()
        val responses = OpenAiResponseUsage(
            inputTokens = 90,
            outputTokens = 10,
            totalTokens = 100,
            cacheWriteTokens = 24,
        ).toTokenUsage()

        assertEquals(35, chat.cacheWriteInputTokenCount)
        assertEquals(24, responses.cacheWriteInputTokenCount)
    }
}
