package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.data.local.DocumentEmbeddingDao
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One ranked manual page returned to `introspect`: full page body plus how it was found. */
data class ManualSearchResult(
    val docId: String,
    val title: String,
    val body: String,
    val score: Float,
    val semantic: Boolean,
)

/**
 * Owns the bundled-manual corpus and its semantic index. Reuses [DocumentRagEngine] for embedding
 * storage/ranking and falls back to [ManualKeywordScorer] whenever no embedding model is available
 * or indexing has not completed — so `introspect` always returns a page.
 *
 * [ensureIndexed] is idempotent and version/model-aware: it re-embeds only when the corpus
 * fingerprint or embedding model changes, and is a no-op otherwise.
 */
class ManualIndexer(
    private val context: Context,
    dao: DocumentEmbeddingDao,
) {
    private val engine = DocumentRagEngine(dao, ManualCorpus.CORPUS, context)
    private val documents: List<CorpusDocument> by lazy { ManualCorpus.load(context) }
    private val indexMutex = Mutex()

    val pageCount: Int get() = documents.size

    /**
     * Indexes the manual for the active embedding model if needed. Safe to call at startup off the
     * main thread; returns the number of chunks indexed (0 means "using keyword fallback").
     */
    suspend fun ensureIndexed(config: EmbeddingModelConfig?, apiKey: String?): Int =
        withContext(Dispatchers.IO) {
            if (config == null || documents.isEmpty()) return@withContext 0
            indexMutex.withLock {
                val fingerprint = DocumentRagEngine.corpusFingerprint(documents)
                if (engine.isIndexed(config.id, fingerprint)) {
                    DebugLog.d(TAG, "manual already indexed for model=${config.id}")
                    return@withContext engine_countNoop()
                }
                val count = engine.reindex(documents, config, apiKey)
                DebugLog.d(TAG, "manual indexed: $count chunks (model=${config.id})")
                count
            }
        }

    private fun engine_countNoop(): Int = -1 // sentinel: already indexed, nothing re-embedded

    /**
     * Ranks the manual for [query]. Uses semantic ranking when a model + index are available,
     * otherwise keyword fallback. Returns at most [limit] full pages, best first.
     */
    suspend fun search(
        query: String,
        limit: Int,
        threshold: Float,
        config: EmbeddingModelConfig?,
        apiKey: String?,
    ): List<ManualSearchResult> = withContext(Dispatchers.IO) {
        if (documents.isEmpty() || query.isBlank()) return@withContext emptyList()
        if (config != null) {
            // Index on first use (and after a model/corpus change) with the live config/key.
            ensureIndexed(config, apiKey)
            val hits = engine.search(query, limit, threshold, config, apiKey, documents)
            if (hits.isNotEmpty()) {
                return@withContext hits.map {
                    ManualSearchResult(it.docId, it.title, it.body, it.score, semantic = true)
                }
            }
            DebugLog.d(TAG, "semantic manual search empty; falling back to keyword")
        }
        ManualKeywordScorer.rank(query, documents, limit).map {
            ManualSearchResult(
                docId = it.doc.docId,
                title = it.doc.title,
                body = it.doc.body,
                score = it.score.toFloat(),
                semantic = false,
            )
        }
    }

    private companion object {
        const val TAG = "ManualIndexer"
    }
}
