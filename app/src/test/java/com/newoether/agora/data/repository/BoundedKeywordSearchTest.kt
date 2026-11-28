package com.newoether.agora.data.repository

import com.newoether.agora.data.local.KeywordSearchRow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BoundedKeywordSearchTest {
    @Test fun stopsAfterNewestWinnersAndBreaksTiesById() = runTest {
        val rows = listOf(row("z", 3, false), row("c", 2), row("b", 2), row("a", 1))
        var calls = 0
        val result = boundedKeywordMatches("needle", 2, 2) { before, size ->
            calls++
            rows.filter { before == null || it.timestamp < before.timestamp ||
                it.timestamp == before.timestamp && it.id < before.id }.take(size)
        }
        assertEquals(listOf("c", "b"), result)
        assertEquals(2, calls)
    }

    @Test fun emptyQueriesAndZeroLimitsNeverLoad() = runTest {
        for ((query, limit) in listOf(" " to 2, "needle" to 0)) {
            assertTrue(boundedKeywordMatches(query, limit) { _, _ ->
                error("No database work should be admitted")
            }.isEmpty())
        }
    }

    @Test fun cancellationDuringPageLoadPropagatesWithoutSuccessor() = runTest {
        var calls = 0
        val search = async {
            boundedKeywordMatches("needle", 2) { _, _ ->
                calls++
                awaitCancellation()
            }
        }
        runCurrent()
        search.cancelAndJoin()
        assertTrue(search.isCancelled)
        assertEquals(1, calls)
    }

    @Test fun cancellationBetweenPagesStopsEvenWithImmediateLoader() = runTest {
        var calls = 0
        val search = async {
            boundedKeywordMatches("needle", 2, 1) { _, _ ->
                calls++
                currentCoroutineContext().cancel()
                listOf(row("row", -calls.toLong(), false))
            }
        }
        runCurrent()
        search.cancelAndJoin()
        assertTrue(search.isCancelled)
        assertEquals(1, calls)
    }

    @Test fun repeatedCursorFailsInsteadOfLooping() = runTest {
        try {
            boundedKeywordMatches("needle", 2, 1) { _, _ -> listOf(row("same", 1, false)) }
            fail("A non-advancing page must fail")
        } catch (_: IllegalStateException) { }
    }

    private fun row(id: String, timestamp: Long, match: Boolean = true) =
        KeywordSearchRow(id, timestamp, match, null)
}
