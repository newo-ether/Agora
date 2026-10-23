package com.newoether.agora.api

import ai.onnxruntime.genai.Generator
import ai.onnxruntime.genai.GeneratorParams
import ai.onnxruntime.genai.Model
import ai.onnxruntime.genai.Tokenizer
import com.newoether.agora.util.DebugLog
import java.io.File

/**
 * On-device LLM chat engine backed by onnxruntime-genai (the ONNX replacement for the GGUF
 * llama.cpp chat path). Loads a GenAI model directory (model.onnx + genai_config.json + tokenizer)
 * and streams generated tokens.
 *
 * genai dynamically binds to the vendored ONNX Runtime 1.28.2 (libonnxruntime.so) via the stable
 * OrtGetApiBase C ABI, so there is only one ORT native lib in the process.
 */
class OnnxLlmEngine(private val modelDir: File) : AutoCloseable {

    companion object {
        private const val TAG = "OnnxLlmEngine"
    }

    @Volatile private var model: Model? = null
    @Volatile private var tokenizer: Tokenizer? = null
    @Volatile private var initFailed = false

    fun isAvailable(): Boolean =
        modelDir.isDirectory &&
            File(modelDir, "genai_config.json").isFile &&
            File(modelDir, "model.onnx").isFile

    @Synchronized
    private fun ensureInit(): Boolean {
        if (model != null && tokenizer != null) return true
        if (initFailed) return false
        if (!isAvailable()) {
            initFailed = true
            return false
        }
        return try {
            val m = Model(modelDir.absolutePath)
            val t = Tokenizer(m)
            model = m
            tokenizer = t
            DebugLog.d(TAG, "Initialized ONNX GenAI model + tokenizer")
            true
        } catch (e: Throwable) {
            DebugLog.e(TAG, "init failed: ${e.javaClass.simpleName}")
            initFailed = true
            false
        }
    }

    /**
     * Stream a completion for the already-rendered [prompt] (the caller applies the chat template).
     * Calls [onToken] with each incremental text delta. Returns the full text, or null on failure.
     * [shouldStop] lets the caller cancel mid-generation.
     */
    fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        shouldStop: () -> Boolean,
        onToken: (String) -> Unit,
    ): String? {
        if (!ensureInit()) return null
        val m = model ?: return null
        val tok = tokenizer ?: return null
        return try {
            val sequences = tok.encode(prompt)
            val promptLen = sequences.getSequence(0).size
            val params = GeneratorParams(m).apply {
                // max_length is the TOTAL sequence (prompt + generated), so budget from the prompt.
                setSearchOption("max_length", (promptLen + maxTokens).toDouble())
                setSearchOption("temperature", temperature.toDouble())
                setSearchOption("top_p", topP.toDouble())
                // Small models degenerate into loops without a repetition penalty.
                setSearchOption("repetition_penalty", 1.1)
            }
            val sb = StringBuilder()
            var generated = 0
            Generator(m, params).use { gen ->
                tok.createStream().use { stream ->
                    gen.appendTokenSequences(sequences)
                    while (!gen.isDone()) {
                        if (shouldStop() || generated >= maxTokens) break
                        gen.generateNextToken()
                        generated++
                        val token = gen.getLastTokenInSequence(0)
                        val delta = stream.decode(token)
                        if (delta.isNotEmpty()) {
                            sb.append(delta)
                            onToken(delta)
                        }
                    }
                }
            }
            params.close()
            sequences.close()
            sb.toString()
        } catch (e: Throwable) {
            DebugLog.e(TAG, "generate failed: ${e.javaClass.simpleName}")
            null
        }
    }

    @Synchronized
    override fun close() {
        try { tokenizer?.close() } catch (_: Throwable) {}
        try { model?.close() } catch (_: Throwable) {}
        tokenizer = null
        model = null
    }
}
