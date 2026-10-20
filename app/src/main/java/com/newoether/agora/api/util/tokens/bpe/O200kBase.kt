package com.newoether.agora.api.util.tokens.bpe

import android.content.Context
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The o200k_base vocabulary, shipped as an asset and held for the life of the process.
 *
 * Counting has to stay synchronous, so the vocabulary is loaded once in the background and only read
 * afterwards. Until it is in memory, [loadedOrNull] returns null and callers fall back to the offline
 * heuristic; that is why loading it early matters but never blocks anything.
 *
 * The asset is the file tiktoken publishes, unchanged, so it can be compared against upstream by
 * checksum.
 */
object O200kBase {

    private const val ASSET_PATH = "tokenizers/o200k_base.tiktoken"
    private const val TAG = "O200kBase"

    @Volatile
    private var vocabulary: BpeVocabulary? = null

    private val loading = AtomicBoolean(false)
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()

    /** The vocabulary if it is already in memory. Never loads, never blocks. */
    fun loadedOrNull(): BpeVocabulary? = vocabulary

    /**
     * Loads the vocabulary unless it is already loaded or another caller is loading it. Reading and
     * parsing three and a half megabytes belongs off the main thread, so it runs on IO.
     */
    suspend fun ensureLoaded(context: Context) {
        if (vocabulary != null) return
        if (!loading.compareAndSet(false, true)) return
        val loaded = withContext(Dispatchers.IO) {
            runCatching { read(context) }
                .onFailure { error -> DebugLog.w(TAG, "Failed to load $ASSET_PATH", error) }
                .getOrNull()
        }
        vocabulary = loaded
        _ready.value = loaded != null
        // A failed load may be retried; a successful one never needs to be.
        if (loaded == null) loading.set(false)
    }

    private fun read(context: Context): BpeVocabulary =
        context.assets.open(ASSET_PATH).bufferedReader().useLines { lines ->
            BpeVocabulary.parseTiktoken(lines)
        }
}
