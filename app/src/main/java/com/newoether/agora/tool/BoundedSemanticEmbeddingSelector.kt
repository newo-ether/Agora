package com.newoether.agora.tool

import com.newoether.agora.data.local.EmbeddingSearchRow
import java.util.PriorityQueue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

internal data class SemanticEmbeddingCandidate(
    val rowId: Long,
    val messageId: String,
    val score: Float,
)

internal data class BoundedSemanticEmbeddingSelection(
    val candidates: List<SemanticEmbeddingCandidate>,
    val scannedRows: Int,
    val skippedInvalidRows: Int,
    val bestScore: Float,
    val maxRetainedCandidates: Int,
)

/**
 * Scores one keyset page at a time and retains only the best requested candidates.
 *
 * The loader must return rows in strictly increasing id order. Invalid vector shape and non-finite
 * scores are ignored so one corrupt cache row cannot abort the whole search.
 */
internal class BoundedSemanticEmbeddingSelector(
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(pageSize > 0)
    }

    suspend fun select(
        queryEmbedding: FloatArray,
        threshold: Float,
        limit: Int,
        loadPage: suspend (afterId: Long, limit: Int) -> List<EmbeddingSearchRow>,
    ): BoundedSemanticEmbeddingSelection {
        val context = currentCoroutineContext()
        context.ensureActive()
        if (queryEmbedding.isEmpty() || queryEmbedding.any { !it.isFinite() } ||
            threshold.isNaN() || limit <= 0) {
            return BoundedSemanticEmbeddingSelection(
                candidates = emptyList(),
                scannedRows = 0,
                skippedInvalidRows = 0,
                bestScore = 0f,
                maxRetainedCandidates = 0,
            )
        }

        val scorer = DirectEmbeddingCosineScorer(queryEmbedding)
        val worstFirst = compareBy<SemanticEmbeddingCandidate> { it.score }
            .thenByDescending { it.rowId }
        val retained = PriorityQueue(worstFirst)
        var afterId = 0L
        var scannedRows = 0
        var skippedInvalidRows = 0
        var bestScore: Float? = null
        var maxRetainedCandidates = 0

        while (true) {
            context.ensureActive()
            val page = loadPage(afterId, pageSize)
            require(page.size <= pageSize) { "Embedding page exceeded the requested bound" }
            if (page.isEmpty()) break

            var previousId = afterId
            page.forEach { row ->
                context.ensureActive()
                require(row.id > previousId) {
                    "Embedding keyset page must be strictly ordered after id $afterId"
                }
                previousId = row.id
                scannedRows += 1

                val expectedBytes = queryEmbedding.size.toLong() * Float.SIZE_BYTES
                if (
                    row.dimension != queryEmbedding.size ||
                    row.dimension <= 0 ||
                    row.embedding.size.toLong() != expectedBytes
                ) {
                    skippedInvalidRows += 1
                    return@forEach
                }
                val score = scorer.score(row.embedding)
                if (!score.isFinite()) {
                    skippedInvalidRows += 1
                    return@forEach
                }
                bestScore = maxOf(bestScore ?: score, score)
                if (score <= threshold) return@forEach

                val candidate = SemanticEmbeddingCandidate(row.id, row.messageId, score)
                if (retained.size < limit) {
                    retained += candidate
                } else {
                    val worst = checkNotNull(retained.peek())
                    if (
                        candidate.score > worst.score ||
                        candidate.score == worst.score && candidate.rowId < worst.rowId
                    ) {
                        retained.poll()
                        retained += candidate
                    }
                }
                if (retained.size > maxRetainedCandidates) {
                    maxRetainedCandidates = retained.size
                }
            }

            afterId = page.last().id
            if (page.size < pageSize) break
            yield()
        }

        return BoundedSemanticEmbeddingSelection(
            candidates = retained.toList().sortedWith(
                compareByDescending<SemanticEmbeddingCandidate> { it.score }
                    .thenBy { it.rowId },
            ),
            scannedRows = scannedRows,
            skippedInvalidRows = skippedInvalidRows,
            bestScore = bestScore ?: 0f,
            maxRetainedCandidates = maxRetainedCandidates,
        )
    }

    private class DirectEmbeddingCosineScorer(
        private val query: FloatArray,
    ) {
        private val queryMagnitude = run {
            var norm = 0.0
            query.forEach { value -> norm += value.toDouble() * value }
            kotlin.math.sqrt(norm)
        }

        fun score(bytes: ByteArray): Float {
            require(bytes.size == query.size * Float.SIZE_BYTES)
            var dot = 0.0
            var normB = 0.0
            query.indices.forEach { index ->
                val offset = index * Float.SIZE_BYTES
                val bits =
                    ((bytes[offset].toInt() and 0xff) shl 24) or
                        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                        (bytes[offset + 3].toInt() and 0xff)
                val valueB = Float.fromBits(bits)
                dot += query[index].toDouble() * valueB
                normB += valueB.toDouble() * valueB
            }
            val denominator = queryMagnitude * kotlin.math.sqrt(normB)
            return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
        }
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 256
    }
}
