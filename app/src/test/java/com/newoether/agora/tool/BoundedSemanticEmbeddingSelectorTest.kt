package com.newoether.agora.tool

import com.newoether.agora.data.EmbeddingIndexer
import com.newoether.agora.data.local.EmbeddingSearchRow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedSemanticEmbeddingSelectorTest {
    @Test
    fun multiPageRankingRetainsOnlyTopKAcrossPageBoundaries() = runTest {
        val rows = listOf(
            row(1, "first", floatArrayOf(0.8f, 0.6f)),
            row(2, "perfect", floatArrayOf(1f, 0f)),
            row(3, "lower", floatArrayOf(0.6f, 0.8f)),
            row(4, "strong", floatArrayOf(0.9f, 0.4358899f)),
            row(5, "opposite", floatArrayOf(-1f, 0f)),
        )
        val requestedAfterIds = mutableListOf<Long>()
        val selector = BoundedSemanticEmbeddingSelector(pageSize = 2)

        val result = selector.select(
            queryEmbedding = floatArrayOf(1f, 0f),
            threshold = 0.5f,
            limit = 2,
        ) { afterId, pageLimit ->
            assertEquals(2, pageLimit)
            requestedAfterIds += afterId
            rows.filter { it.id > afterId }.take(pageLimit)
        }

        assertEquals(listOf("perfect", "strong"), result.candidates.map { it.messageId })
        assertEquals(listOf(0L, 2L, 4L), requestedAfterIds)
        assertEquals(5, result.scannedRows)
        assertEquals(2, result.maxRetainedCandidates)
        assertEquals(1f, result.bestScore, 0.0001f)
    }

    @Test
    fun thresholdIsStrictAndEmptyResultsStayEmpty() = runTest {
        val rows = listOf(
            row(1, "below", floatArrayOf(0.8f, 0.6f)),
            row(2, "above", floatArrayOf(1f, 0f)),
        )

        val result = BoundedSemanticEmbeddingSelector(pageSize = 4).select(
            queryEmbedding = floatArrayOf(1f, 0f),
            threshold = 0.85f,
            limit = 4,
        ) { afterId, pageLimit ->
            rows.filter { it.id > afterId }.take(pageLimit)
        }

        assertEquals(listOf("above"), result.candidates.map { it.messageId })
        val empty = BoundedSemanticEmbeddingSelector(pageSize = 4).select(
            queryEmbedding = floatArrayOf(1f, 0f),
            threshold = 0f,
            limit = 4,
        ) { _, _ -> emptyList() }
        assertTrue(empty.candidates.isEmpty())
        assertEquals(0, empty.scannedRows)
    }

    @Test
    fun incompatibleMalformedAndNonFiniteVectorsAreSkipped() = runTest {
        val rows = listOf(
            EmbeddingSearchRow(
                id = 1,
                messageId = "wrong-dimension",
                embedding = EmbeddingIndexer.floatsToBytes(floatArrayOf(1f, 0f)),
                dimension = 3,
            ),
            EmbeddingSearchRow(
                id = 2,
                messageId = "truncated",
                embedding = byteArrayOf(0, 0, 0, 0),
                dimension = 2,
            ),
            row(3, "not-finite", floatArrayOf(Float.NaN, 0f)),
            row(4, "valid", floatArrayOf(1f, 0f)),
        )

        val result = BoundedSemanticEmbeddingSelector(pageSize = 2).select(
            queryEmbedding = floatArrayOf(1f, 0f),
            threshold = 0f,
            limit = 3,
        ) { afterId, pageLimit ->
            rows.filter { it.id > afterId }.take(pageLimit)
        }

        assertEquals(listOf("valid"), result.candidates.map { it.messageId })
        assertEquals(4, result.scannedRows)
        assertEquals(3, result.skippedInvalidRows)
    }

    @Test
    fun equalScoresUseStableEmbeddingIdOrder() = runTest {
        val rows = listOf(
            row(7, "later", floatArrayOf(1f, 0f)),
            row(8, "latest", floatArrayOf(1f, 0f)),
        )

        val result = BoundedSemanticEmbeddingSelector(pageSize = 1).select(
            queryEmbedding = floatArrayOf(1f, 0f),
            threshold = 0f,
            limit = 1,
        ) { afterId, pageLimit ->
            rows.filter { it.id > afterId }.take(pageLimit)
        }

        assertEquals(listOf("later"), result.candidates.map { it.messageId })
        assertEquals(1, result.maxRetainedCandidates)
    }

    @Test
    fun largeCorpusDefaultUsesAtMostEightyBoundedPages() = runTest {
        val totalRows = 20_000L
        var pageLoads = 0
        val result = BoundedSemanticEmbeddingSelector().select(
            queryEmbedding = floatArrayOf(1f, 0f),
            threshold = -2f,
            limit = 3,
        ) { afterId, pageLimit ->
            pageLoads += 1
            val count = minOf(pageLimit.toLong(), totalRows - afterId)
                .coerceAtLeast(0)
                .toInt()
            List(count) { offset ->
                val id = afterId + offset + 1
                row(id, "message-$id", floatArrayOf(1f, 0f))
            }
        }

        assertEquals(20_000, result.scannedRows)
        assertEquals(3, result.maxRetainedCandidates)
        assertEquals(listOf(1L, 2L, 3L), result.candidates.map { it.rowId })
        assertTrue("pageLoads=$pageLoads", pageLoads <= 80)
    }

    @Test
    fun invalidQueryDoesNotScanAndLargeFiniteValuesKeepCosineFinite() = runTest {
        val invalid = BoundedSemanticEmbeddingSelector().select(floatArrayOf(Float.NaN), 0f, 1) { _, _ ->
            error("Invalid queries must not scan the database")
        }
        assertTrue(invalid.candidates.isEmpty())
        val result = BoundedSemanticEmbeddingSelector().select(
            floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE), 0f, 1,
        ) { after, _ ->
            if (after == 0L) listOf(row(1, "large", floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE)))
            else emptyList()
        }
        assertEquals(1f, result.candidates.single().score, 0.00001f)
    }

    @Test
    fun cancelledImmediateLoaderCannotScoreOrReadAnotherPage() = runTest {
        var loads = 0
        var returned = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                BoundedSemanticEmbeddingSelector(pageSize = 1).select(floatArrayOf(1f), 0f, 1) { _, _ ->
                    loads++
                    currentCoroutineContext().cancel()
                    listOf(row(1, "cancelled", floatArrayOf(1f)))
                }
                returned = true
            } catch (_: CancellationException) {
                throw CancellationException("Expected cancellation")
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(1, loads)
    }

    private fun row(
        id: Long,
        messageId: String,
        vector: FloatArray,
    ) = EmbeddingSearchRow(
        id = id,
        messageId = messageId,
        embedding = EmbeddingIndexer.floatsToBytes(vector),
        dimension = vector.size,
    )
}
