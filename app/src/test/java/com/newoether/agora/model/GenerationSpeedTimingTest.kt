package com.newoether.agora.model

import com.newoether.agora.ui.chat.message.formatLatency
import com.newoether.agora.ui.chat.message.tokenUsagePresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class GenerationSpeedTimingTest {
    @Test fun firstContentWaitToolGapsAndRepeatedUsageAreExcluded() {
        var clock = 0L
        val accumulator = RequestTokenUsageAccumulator { clock * 1_000_000L }
        accumulator.beginRequest()
        clock = 9_000
        accumulator.observeGenerationContent()
        clock = 10_000
        accumulator.observeGenerationContent()
        accumulator.pauseGeneration()
        clock = 50_000
        accumulator.observeGenerationContent()
        clock = 51_000
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(TokenUsage(120, inputTokenCount = 100, outputTokenCount = 20))
        accumulator.observeRequestSnapshot(TokenUsage(140, inputTokenCount = 100, outputTokenCount = 40))
        accumulator.finishRequest()
        assertEquals(2_000L, accumulator.snapshot()?.generationDurationMs)
        assertEquals(9_000L, accumulator.snapshot()?.timeToFirstTokenMs)
        assertEquals(51_000L, accumulator.snapshot()?.totalGenerationMs)
        assertEquals(20.0, tokenUsagePresentation(accumulator.snapshot()).generationTokensPerSecond!!, 0.001)
        clock = 100_000
        accumulator.beginRequest()
        accumulator.observeGenerationContent()
        clock = 101_000
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(TokenUsage(130, inputTokenCount = 100, outputTokenCount = 30))
        accumulator.finishRequest()
        // A tool round starts after the reader already saw the first token, so it adds nothing.
        assertEquals(9_000L, accumulator.snapshot()?.timeToFirstTokenMs)
        assertEquals(3_000L, accumulator.snapshot()?.generationDurationMs)
        // Each round is timed from its own issue; the tool gap between them is not generation.
        assertEquals(52_000L, accumulator.snapshot()?.totalGenerationMs)
        assertEquals(70.0 / 3.0, tokenUsagePresentation(accumulator.snapshot()).generationTokensPerSecond!!, 0.001)
    }

    @Test fun unmeasuredOrSingleChunkRequestsNeverInventSpeed() {
        val accumulator = RequestTokenUsageAccumulator { 42L }
        accumulator.beginRequest()
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(TokenUsage(100, outputTokenCount = 20))
        accumulator.finishRequest()
        assertNull(tokenUsagePresentation(accumulator.snapshot()).generationTokensPerSecond)
        assertNull(tokenUsagePresentation(TokenUsage(100, outputTokenCount = 20)).generationTokensPerSecond)
        assertNull(tokenUsagePresentation(TokenUsage(100, generationDurationMs = 1000)).generationTokensPerSecond)
        assertNull(tokenUsagePresentation(TokenUsage(100, outputTokenCount = 20, generationDurationMs = 0)).generationTokensPerSecond)
    }

    @Test fun mixedKnownAndUnknownRequestDurationsStayUnknown() {
        val measured = TokenUsage(100, outputTokenCount = 20, generationDurationMs = 1000)
        val unmeasured = TokenUsage(100, outputTokenCount = 30)
        assertNull(measured.plusRequest(unmeasured).generationDurationMs)
        assertNull(unmeasured.plusRequest(measured).generationDurationMs)
    }

    @Test fun retryDiscardsTheOldTimingInterval() {
        var clock = 0L
        val accumulator = RequestTokenUsageAccumulator { clock }
        accumulator.beginRequest()
        accumulator.observeGenerationContent()
        clock = 8_000_000_000L
        accumulator.resetCurrentAttempt()
        accumulator.observeGenerationContent()
        clock = 9_000_000_000L
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(TokenUsage(10, outputTokenCount = 10))
        accumulator.finishRequest()
        assertEquals(1000L, accumulator.snapshot()?.generationDurationMs)
        // The failed attempt is dropped, so its wait is not part of the total.
        assertEquals(1000L, accumulator.snapshot()?.totalGenerationMs)
    }

    @Test fun firstTokenWaitIsMeasuredOnceForTheWholeReply() {
        var clock = 0L
        val accumulator = RequestTokenUsageAccumulator { clock * 1_000_000L }
        accumulator.beginRequest()
        clock = 420
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(TokenUsage(12, inputTokenCount = 8, outputTokenCount = 4))
        accumulator.finishRequest()
        assertEquals(420L, accumulator.snapshot()?.timeToFirstTokenMs)
        clock = 10_000
        accumulator.beginRequest()
        clock = 11_000
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(TokenUsage(6, inputTokenCount = 3, outputTokenCount = 3))
        accumulator.finishRequest()
        assertEquals(420L, accumulator.snapshot()?.timeToFirstTokenMs)
        assertEquals(420L, tokenUsagePresentation(accumulator.snapshot()).timeToFirstTokenMs)
        assertEquals(1_420L, accumulator.snapshot()?.totalGenerationMs)
    }

    @Test fun requestsWithoutContentNeverReportAFirstTokenWait() {
        val accumulator = RequestTokenUsageAccumulator { 5L }
        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(TokenUsage(10, outputTokenCount = 2))
        accumulator.finishRequest()
        assertNull(accumulator.snapshot()?.timeToFirstTokenMs)
        assertNull(tokenUsagePresentation(accumulator.snapshot()).timeToFirstTokenMs)
        assertNull(tokenUsagePresentation(TokenUsage(10, outputTokenCount = 2)).timeToFirstTokenMs)
        assertNull(
            tokenUsagePresentation(TokenUsage(10, outputTokenCount = 2, timeToFirstTokenMs = 0))
                .timeToFirstTokenMs,
        )
    }

    @Test fun retryRemeasuresFirstTokenWaitFromTheRetriedAttempt() {
        var clock = 0L
        val accumulator = RequestTokenUsageAccumulator { clock * 1_000_000L }
        accumulator.beginRequest()
        clock = 7_000
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(TokenUsage(10, outputTokenCount = 10))
        accumulator.resetCurrentAttempt()
        clock = 20_000
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(TokenUsage(10, outputTokenCount = 10))
        accumulator.finishRequest()
        assertEquals(13_000L, accumulator.snapshot()?.timeToFirstTokenMs)
        assertEquals(13_000L, accumulator.snapshot()?.totalGenerationMs)
    }

    @Test fun totalGenerationTimeCoversTheWaitTheStreamAndEveryToolRound() {
        var clockMs = 0L
        val accumulator = RequestTokenUsageAccumulator { clockMs * 1_000_000L }
        accumulator.beginRequest()
        clockMs = 800L
        accumulator.observeGenerationContent()
        clockMs = 3_000L
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(TokenUsage(30, inputTokenCount = 20, outputTokenCount = 10))
        accumulator.finishRequest()
        clockMs = 6_000L
        accumulator.beginRequest()
        clockMs = 8_000L
        accumulator.observeGenerationContent()
        clockMs = 10_000L
        accumulator.observeGenerationContent()
        accumulator.observeRequestSnapshot(TokenUsage(20, inputTokenCount = 15, outputTokenCount = 5))
        accumulator.finishRequest()
        // 3 s first round (800 ms wait + 2.2 s stream) plus 4 s second round (2 s wait + 2 s).
        assertEquals(7_000L, accumulator.snapshot()?.totalGenerationMs)
    }

    @Test fun unmeasuredTotalGenerationTimeStaysUnknown() {
        val accumulator = RequestTokenUsageAccumulator { 7L }
        accumulator.beginRequest()
        accumulator.observeRequestSnapshot(TokenUsage(10, outputTokenCount = 2))
        accumulator.finishRequest()
        assertNull(accumulator.snapshot()?.totalGenerationMs)
        assertNull(tokenUsagePresentation(TokenUsage(10, totalGenerationMs = 0)).totalGenerationMs)
        assertNull(TokenUsage(10, totalGenerationMs = 4_200).plusRequest(TokenUsage(10)).totalGenerationMs)
    }

    @Test fun latencyFiguresReadInMillisecondsBelowASecond() {
        assertEquals("420 ms", formatLatency(420, Locale.US))
        assertEquals("1.2 s", formatLatency(1_249, Locale.US))
    }
}
