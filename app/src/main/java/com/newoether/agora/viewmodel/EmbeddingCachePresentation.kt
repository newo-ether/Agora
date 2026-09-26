package com.newoether.agora.viewmodel

internal enum class EmbeddingCacheRowPhase {
    LOADING, CACHING, CACHE, RECACHE,
}
internal data class EmbeddingCacheWorkSnapshot(
    val generationRevision: Long,
    val kind: String,
    val processed: Int,
    val total: Int,
    val remaining: Int,
    val progressPermille: Int,
) {
    init {
        require(
            generationRevision >= 0L && kind.isNotBlank() && total > 0 &&
                processed in 0..total && remaining == total - processed &&
                progressPermille == ((processed.toLong() * 1000L) / total).toInt(),
        )
    }

    val fraction: Float get() = progressPermille / 1000f
}
internal fun embeddingCacheWorkSnapshotOrNull(
    generationRevision: Long,
    kind: String?,
    processed: Int,
    total: Int,
    remaining: Int,
    progressPermille: Int,
): EmbeddingCacheWorkSnapshot? = runCatching {
    EmbeddingCacheWorkSnapshot(
        generationRevision, kind.orEmpty(), processed, total, remaining, progressPermille,
    )
}.getOrNull()
internal data class EmbeddingCacheRowSnapshot(
    val progress: EmbeddingCacheWorkSnapshot? = null,
    val cached: Int? = null,
    val indexableTotal: Int? = null,
    val countLoading: Boolean = false,
    val workActive: Boolean = false,
    val countFailed: Boolean = false,
) {
    init {
        require((cached == null) == (indexableTotal == null))
        require(cached == null || indexableTotal != null && cached in 0..indexableTotal)
    }

    val phase: EmbeddingCacheRowPhase?
        get() = when {
            workActive -> EmbeddingCacheRowPhase.CACHING
            cached != null && indexableTotal != null ->
                if (cached < indexableTotal) EmbeddingCacheRowPhase.CACHE
                else EmbeddingCacheRowPhase.RECACHE
            countLoading -> EmbeddingCacheRowPhase.LOADING
            else -> null
        }
}
/**
 * A model whose counts have not been resolved yet is still loading them, so an absent snapshot
 * reads as [EmbeddingCacheRowPhase.LOADING] instead of leaving the row without any status. Only a
 * failed refresh drops the phase, because the page reports that failure separately.
 */
internal fun EmbeddingCacheRowSnapshot?.rowPhase(): EmbeddingCacheRowPhase? =
    this?.phase ?: EmbeddingCacheRowPhase.LOADING.takeUnless { this?.countFailed == true }

internal object EmbeddingCacheRowReducer {
    fun refreshRequested(previous: EmbeddingCacheRowSnapshot?) =
        (previous ?: EmbeddingCacheRowSnapshot()).copy(countLoading = true, countFailed = false)

    fun workChanged(
        previous: EmbeddingCacheRowSnapshot?,
        running: Boolean,
        progress: EmbeddingCacheWorkSnapshot? = null,
    ) = (previous ?: EmbeddingCacheRowSnapshot()).copy(
        workActive = running,
        progress = progress.takeIf { running },
    )

    fun refreshed(previous: EmbeddingCacheRowSnapshot?, cached: Int, total: Int) =
        (previous ?: EmbeddingCacheRowSnapshot()).copy(
            cached = cached,
            indexableTotal = total,
            countLoading = false,
            countFailed = false,
        )

    fun refreshFailed(previous: EmbeddingCacheRowSnapshot?) =
        (previous ?: EmbeddingCacheRowSnapshot()).copy(countLoading = false, countFailed = true)
}
