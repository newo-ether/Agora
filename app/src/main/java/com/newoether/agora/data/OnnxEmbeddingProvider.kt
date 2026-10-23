package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.api.OnnxEmbeddingEngine

/**
 * Process-wide holder for the single [OnnxEmbeddingEngine] instance. The ONNX session load is
 * expensive, so every embedding call site (DocumentRagEngine for RAG corpora, EmbeddingCacheWorker
 * for conversation indexing, RagToolProvider for the live search query) shares one engine.
 *
 * The application context is captured once via [init] (from AppContainer) so stateless call sites
 * can embed without plumbing a Context. Returns null / no-ops when the Bekko model + tokenizer are
 * not installed, letting callers fall back to the cloud backend.
 */
object OnnxEmbeddingProvider {

    const val MODEL_ID = "onnx-bekko-a8m-384"
    const val DIMENSION = OnnxEmbeddingEngine.EMBED_DIM

    @Volatile private var appContext: Context? = null
    @Volatile private var engine: OnnxEmbeddingEngine? = null

    /** Capture the application context once (idempotent). Call from AppContainer. */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    @Synchronized
    fun engine(ctx: Context): OnnxEmbeddingEngine? {
        if (appContext == null) appContext = ctx.applicationContext
        if (!OnnxModelBootstrap.isInstalled(ctx)) return null
        val existing = engine
        if (existing != null && existing.isAvailable()) return existing
        val created = OnnxEmbeddingEngine(
            modelFile = OnnxModelBootstrap.modelFile(ctx),
            tokenizerFile = OnnxModelBootstrap.tokenizerFile(ctx),
        )
        engine = created
        return created
    }

    /** Embed using the captured app context; null if context not set or model not installed. */
    fun embed(text: String): FloatArray? {
        val ctx = appContext ?: return null
        return engine(ctx)?.embed(text)
    }

    /** True when the ONNX model is installed and ready to embed. */
    fun isReady(ctx: Context): Boolean = OnnxModelBootstrap.isInstalled(ctx)

    /** True using the captured context (for stateless sites). */
    fun isReady(): Boolean = appContext?.let { OnnxModelBootstrap.isInstalled(it) } ?: false

    /** Drop the cached engine (e.g. after (re)downloading or removing the model). */
    @Synchronized
    fun reset() {
        try {
            engine?.close()
        } catch (_: Throwable) {}
        engine = null
    }
}
