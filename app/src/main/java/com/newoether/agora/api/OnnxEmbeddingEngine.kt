package com.newoether.agora.api

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.newoether.agora.util.DebugLog
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * On-device embedding engine for the Bekko (ModernBERT) ONNX model — the ONNX replacement for the
 * GGUF/llama.cpp local embedding path.
 *
 * Model I/O contract (bekko-embedding-v1-a8m):
 *  - Inputs:  input_ids (int64), attention_mask (int64)   [no token_type_ids]
 *  - Output:  last_hidden_state [batch, seq, 384]          (NOT pre-pooled)
 *  - Pooling: masked mean over tokens, then L2-normalize   (similarity_fn = cosine)
 *  - No query/document prefixes.
 *
 * Loads lazily and is safe to reuse across calls. Construct with the files produced by
 * [com.newoether.agora.data.OnnxModelBootstrap]: model.onnx and tokenizer.json.
 */
class OnnxEmbeddingEngine(
    private val modelFile: File,
    private val tokenizerFile: File,
    private val maxSeqLen: Int = 8192,
) : AutoCloseable {

    companion object {
        private const val TAG = "OnnxEmbeddingEngine"
        const val EMBED_DIM = 384
        /** Char cap before tokenizing (well within the 8192-token truncation budget). */
        private const val MAX_INPUT_CHARS = 16_000
        private const val IN_IDS = "input_ids"
        private const val IN_MASK = "attention_mask"
        private const val OUT_HIDDEN = "last_hidden_state"
    }

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: HuggingFaceTokenizer? = null
    @Volatile private var initFailed = false

    /** Whether the required files are present on disk. */
    fun isAvailable(): Boolean =
        modelFile.isFile && modelFile.length() > 0 &&
            tokenizerFile.isFile && tokenizerFile.length() > 0

    @Synchronized
    private fun ensureInit(): Boolean {
        if (session != null && tokenizer != null) return true
        if (initFailed) return false
        if (!isAvailable()) {
            initFailed = true
            return false
        }
        return try {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val s = e.createSession(modelFile.absolutePath, opts)
            val t = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerFile.toPath())
                .optAddSpecialTokens(true)
                .optTruncation(true)
                .optMaxLength(maxSeqLen)
                .build()
            env = e
            session = s
            tokenizer = t
            DebugLog.d(TAG, "Initialized ONNX session + tokenizer")
            true
        } catch (ex: Throwable) {
            DebugLog.e(TAG, "init failed: ${ex.javaClass.simpleName}")
            initFailed = true
            false
        }
    }

    /**
     * Embed a single text into a 384-dim, L2-normalized vector.
     * Returns null on any failure (missing files, init error, runtime error).
     *
     * Synchronized: the DJL tokenizer and ORT session native handles are NOT thread-safe. The
     * engine is a process-wide singleton shared by the conversation-index worker, the RAG query
     * path, and document import; concurrent calls crash the native tokenizer (getTokenCharSpans).
     */
    @Synchronized
    fun embed(text: String): FloatArray? {
        if (!ensureInit()) return null
        val s = session ?: return null
        val e = env ?: return null
        val tok = tokenizer ?: return null

        // Defensive input guard: a blank string, or a pathologically long one, can trip the DJL
        // native tokenizer's offset mapping (getTokenCharSpans) into a native abort that cannot be
        // caught. Truncation is configured, but hard-cap the char length here as defense-in-depth.
        val input = text.trim()
        if (input.isEmpty()) return null
        val capped = if (input.length > MAX_INPUT_CHARS) input.substring(0, MAX_INPUT_CHARS) else input

        return try {
            val enc = tok.encode(capped)
            val ids = enc.ids                 // long[]
            val mask = enc.attentionMask      // long[]
            val seq = ids.size
            if (seq == 0) return null

            val idsTensor = OnnxTensor.createTensor(
                e, LongBuffer.wrap(ids), longArrayOf(1, seq.toLong()),
            )
            val maskTensor = OnnxTensor.createTensor(
                e, LongBuffer.wrap(mask), longArrayOf(1, seq.toLong()),
            )

            idsTensor.use { idsT ->
                maskTensor.use { maskT ->
                    val inputs = mapOf(IN_IDS to idsT, IN_MASK to maskT)
                    s.run(inputs).use { result ->
                        val out = result.get(OUT_HIDDEN).orElseGet { result.get(0) } as OnnxTensor
                        @Suppress("UNCHECKED_CAST")
                        val hidden = out.value as Array<Array<FloatArray>> // [1, seq, 384]
                        meanPoolAndNormalize(hidden[0], mask)
                    }
                }
            }
        } catch (ex: Throwable) {
            DebugLog.e(TAG, "embed failed: ${ex.javaClass.simpleName}")
            null
        }
    }

    /** Masked mean pooling over the sequence dimension, then L2-normalize. */
    private fun meanPoolAndNormalize(tokens: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = if (tokens.isNotEmpty()) tokens[0].size else EMBED_DIM
        val sum = FloatArray(dim)
        var count = 0f
        for (i in tokens.indices) {
            val m = if (i < mask.size) mask[i] else 0L
            if (m == 0L) continue
            val row = tokens[i]
            for (d in 0 until dim) sum[d] += row[d]
            count += 1f
        }
        if (count > 0f) {
            for (d in 0 until dim) sum[d] /= count
        }
        var norm = 0f
        for (d in 0 until dim) norm += sum[d] * sum[d]
        norm = sqrt(norm)
        if (norm > 0f) {
            for (d in 0 until dim) sum[d] /= norm
        }
        return sum
    }

    @Synchronized
    override fun close() {
        try {
            session?.close()
        } catch (_: Throwable) {}
        try {
            tokenizer?.close()
        } catch (_: Throwable) {}
        session = null
        tokenizer = null
        // OrtEnvironment is a shared singleton; do not close it here.
    }
}
