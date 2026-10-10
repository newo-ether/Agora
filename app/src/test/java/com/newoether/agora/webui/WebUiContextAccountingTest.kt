package com.newoether.agora.webui

import com.newoether.agora.api.util.ContextWindowUsage
import com.newoether.agora.viewmodel.ConversationContextProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [contextAccountingEvent] is the whole view one browser gets of a projection, so it is tested
 * against the phone's canonical arithmetic: parts add up, the reserve follows the compaction
 * settings, and a figure is only shown while it belongs to the browser's own target.
 */
class WebUiContextAccountingTest {
    private val request = WebContextAccountingRequest(
        conversationId = "c1", seq = 3L, selectedModelId = "provider:model", tokenBudget = 100_000,
        systemPromptId = null,
    )

    private fun projection(usage: ContextWindowUsage?, loading: Boolean = false, completed: Boolean = true, failed: Boolean = false) =
        ConversationContextProjection("c1", null, usage, null, loading, completed, failed)

    private val usage = ContextWindowUsage(
        estimatedTokenCount = 40_000, tokenBudget = 100_000, logicalMessageCount = 4, hasCompactBoundary = false,
        systemPromptTokens = 1_200, toolTokens = 8_000,
    )

    @Test
    fun settledProjectionSendsEveryPartInTheProvidersOrder() {
        val event = contextAccountingEvent(request, projection(usage), null, 90, true)
        assertEquals(listOf("system", "tools", "messages", "free", "reserved"), event.parts.map { it.key })
        assertEquals(listOf(1_200, 8_000, 30_800, 50_000, 10_000), event.parts.map { it.tokens })
        // Labels are the phone's ContextBudget formatting, so "40000" never reaches the browser.
        assertEquals(listOf("1.2K", "7.8K", "30.1K", "48.8K", "9.8K"), event.parts.map { it.label })
        assertEquals("39.1K", event.estimatedLabel)
        assertEquals("97.7K", event.budgetLabel)
        assertEquals(3L, event.seq)
        assertFalse(event.overCompactThreshold)
        assertFalse(event.loading)
    }

    @Test
    fun crossingTheCompactThresholdMarksTheFigureAndTurnsTheReserveOffWithCompaction() {
        val over = contextAccountingEvent(
            request,
            projection(usage.copy(estimatedTokenCount = 95_000, systemPromptTokens = 1_200, toolTokens = 8_000)),
            null, 90, true,
        )
        assertTrue(over.overCompactThreshold)
        assertTrue(over.parts.any { it.key == "reserved" })
        val noCompaction = contextAccountingEvent(request, projection(usage), null, 90, false)
        assertFalse(noCompaction.parts.any { it.key == "reserved" })
        // Without automatic compaction nothing claims the tail, so the whole remainder is free.
        assertEquals(60_000, noCompaction.parts.first { it.key == "free" }.tokens)
    }

    @Test
    fun aFigureBelongsToTheBrowserItWasSettledForAndNotToTheNextTarget() {
        val repricing = projection(usage, loading = true, completed = false)
        // The projector keeps the previous figure while it works; only the target it was settled for
        // may still show it.
        assertEquals(40_000, contextAccountingEvent(request, repricing, "c1", 90, true).estimatedTokens)
        assertNull(contextAccountingEvent(request, repricing, null, 90, true).estimatedTokens)
        assertNull(contextAccountingEvent(request, repricing, "other", 90, true).estimatedTokens)
        assertTrue(contextAccountingEvent(request, repricing, null, 90, true).loading)
    }

    @Test
    fun aFailedProjectionShowsNothingAndOnlyTheOwnTargetReportsFailure() {
        val failed = projection(usage, completed = true, failed = true)
        val event = contextAccountingEvent(request, failed, "c1", 90, true)
        assertNull(event.estimatedTokens)
        assertTrue(event.failed)
        assertEquals(emptyList<WebContextPart>(), event.parts)
        val other = failed.copy(conversationId = "c2")
        assertFalse(contextAccountingEvent(request, other, "c1", 90, true).failed)
    }
}
