package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenUsageTest {
    @Test
    fun cumulativeSnapshotsReplaceWithinRequestAndRequestsAddAcrossToolRounds() {
        val accumulator = RequestTokenUsageAccumulator()

        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(usage(total = 12, input = 8, output = 4))
        accumulator.observeRequestSnapshot(usage(total = 20, input = 13, output = 7))
        assertEquals(20, accumulator.snapshot()?.totalTokenCount)
        accumulator.finishRequest()

        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(usage(total = 9, input = 6, output = 3))
        accumulator.finishRequest()

        val total = accumulator.snapshot()
        assertEquals(29, total?.totalTokenCount)
        assertEquals(19, total?.inputTokenCount)
        assertEquals(10, total?.outputTokenCount)
    }

    @Test
    fun missingBreakdownInAnyRequestKeepsAggregateBreakdownUnknown() {
        val accumulator = RequestTokenUsageAccumulator()

        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(usage(total = 10, input = 7, output = 3))
        accumulator.finishRequest()
        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(TokenUsage(totalTokenCount = 5))
        accumulator.finishRequest()

        val total = accumulator.snapshot()
        assertEquals(15, total?.totalTokenCount)
        assertNull(total?.inputTokenCount)
        assertNull(total?.outputTokenCount)
    }

    @Test
    fun everyReportedCategoryAddsAcrossToolCallProviderRequests() {
        var clockMs = 0L
        val accumulator = RequestTokenUsageAccumulator { clockMs * 1_000_000L }

        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(
            TokenUsage(
                totalTokenCount = 30,
                inputTokenCount = 20,
                cachedInputTokenCount = 8,
                cacheWriteInputTokenCount = 3,
                uncachedInputTokenCount = 12,
                outputTokenCount = 10,
                reasoningTokenCount = 4,
            ),
        )
        accumulator.observeRequestSnapshot(
            TokenUsage(
                totalTokenCount = 36,
                inputTokenCount = 24,
                cachedInputTokenCount = 10,
                cacheWriteInputTokenCount = 4,
                uncachedInputTokenCount = 14,
                outputTokenCount = 12,
                reasoningTokenCount = 5,
            ),
        )
        clockMs = 2_500L
        accumulator.finishRequest()
        clockMs = 2_500L
        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(
            TokenUsage(
                totalTokenCount = 18,
                inputTokenCount = 13,
                cachedInputTokenCount = 6,
                cacheWriteInputTokenCount = 2,
                uncachedInputTokenCount = 7,
                outputTokenCount = 5,
                reasoningTokenCount = 1,
            ),
        )
        clockMs = 4_000L
        accumulator.finishRequest()

        assertEquals(
            TokenUsage(
                totalTokenCount = 54,
                inputTokenCount = 37,
                cachedInputTokenCount = 16,
                cacheWriteInputTokenCount = 6,
                uncachedInputTokenCount = 21,
                outputTokenCount = 17,
                reasoningTokenCount = 6,
                totalGenerationMs = 4_000L,
            ),
            accumulator.snapshot(),
        )
    }

    @Test
    fun cacheWriteTokensAddAcrossCompletedRequests() {
        val accumulator = RequestTokenUsageAccumulator()

        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(
            usage(total = 20, input = 15, output = 5, cacheWrite = 7),
        )
        accumulator.finishRequest()
        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(
            usage(total = 12, input = 9, output = 3, cacheWrite = 4),
        )
        accumulator.finishRequest()

        assertEquals(11, accumulator.snapshot()?.cacheWriteInputTokenCount)
    }

    @Test
    fun resetCurrentAttemptDropsFailedSnapshotAndTimingButKeepsCompletedRequests() {
        var clockMs = 10L
        val accumulator = RequestTokenUsageAccumulator { clockMs * 1_000_000L }
        val completed = TokenUsage(
            totalTokenCount = 20, inputTokenCount = 15, outputTokenCount = 5,
            cachedInputTokenCount = 4, cacheWriteInputTokenCount = 2,
            uncachedInputTokenCount = 11, reasoningTokenCount = 1,
        )
        accumulator.beginRequest()
        accumulator.observeGenerationContent()
        clockMs = 110L
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(completed)
        accumulator.finishRequest()
        val baseline = completed.copy(generationDurationMs = 100L, totalGenerationMs = 100L)
        assertEquals(baseline, accumulator.snapshot())

        accumulator.beginRequest()
        clockMs = 1_000L
        accumulator.observeGenerationContent()
        clockMs = 1_500L
        accumulator.observeGenerationContent()
        accumulator.pauseGeneration()
        clockMs = 2_000L
        accumulator.observeGenerationContent()
        clockMs = 2_500L
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(
            completed.copy(totalTokenCount = 900, generationDurationMs = 9_000L),
        )

        accumulator.resetCurrentAttempt()

        assertEquals(baseline, accumulator.snapshot())
        clockMs = 10_000L
        accumulator.observeGenerationContent()
        clockMs = 10_050L
        accumulator.observeGenerationContent()
        val replacement = completed.copy(totalTokenCount = 12, inputTokenCount = 9, outputTokenCount = 3)
        accumulator.observeRequestSnapshot(replacement)
        accumulator.finishRequest()
        assertEquals(
            baseline.plusRequest(
                replacement.copy(
                    generationDurationMs = 50L,
                    timeToFirstTokenMs = 7_500L,
                    totalGenerationMs = 7_550L,
                ),
            ),
            accumulator.snapshot(),
        )
    }

    @Test
    fun resetCurrentAttemptWithoutReplacementUsageCannotCommitAFailedSnapshot() {
        var clockMs = 0L
        val accumulator = RequestTokenUsageAccumulator { clockMs * 1_000_000L }
        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(
            usage(total = 90, input = 60, output = 30).copy(generationDurationMs = 1_000L),
        )

        accumulator.resetCurrentAttempt()

        assertNull(accumulator.snapshot())
        accumulator.finishRequest()
        assertNull(accumulator.snapshot())
        clockMs = 1_000L
        accumulator.beginRequest()
        val nextRequest = usage(total = 8, input = 6, output = 2)
        accumulator.observeRequestSnapshot(nextRequest)
        clockMs = 3_500L
        accumulator.finishRequest()
        assertEquals(nextRequest.copy(totalGenerationMs = 2_500L), accumulator.snapshot())
    }

    @Test
    fun resetCurrentAttemptDoesNotGiveASingleChunkReplacementFailedGenerationTime() {
        var clockMs = 0L
        val accumulator = RequestTokenUsageAccumulator { clockMs * 1_000_000L }
        accumulator.beginRequest()
        accumulator.observeGenerationContent()
        clockMs = 500L
        accumulator.observeGenerationContent()
        accumulator.pauseGeneration()
        clockMs = 1_000L
        accumulator.observeGenerationContent()
        clockMs = 1_500L
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(usage(total = 100, input = 60, output = 40))

        accumulator.resetCurrentAttempt()

        clockMs = 10_000L
        accumulator.observeGenerationContent()
        val replacement = usage(total = 10, input = 6, output = 4)
        accumulator.observeRequestSnapshot(replacement)
        accumulator.finishRequest()
        assertEquals(
            replacement.copy(timeToFirstTokenMs = 8_500L, totalGenerationMs = 8_500L),
            accumulator.snapshot(),
        )
        assertNull(accumulator.snapshot()?.generationDurationMs)
    }

    private fun usage(total: Int, input: Int, output: Int, cacheWrite: Int? = null) = TokenUsage(
        totalTokenCount = total,
        inputTokenCount = input,
        cacheWriteInputTokenCount = cacheWrite,
        outputTokenCount = output,
    )
}
