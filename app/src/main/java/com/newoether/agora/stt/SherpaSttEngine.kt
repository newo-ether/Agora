package com.newoether.agora.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Offline streaming speech-to-text backed by sherpa-onnx ([OnlineRecognizer]) with a streaming
 * zipformer transducer. Feed 16 kHz mono PCM float samples via [accept]; read interim text with
 * [partialText]; call [isEndpoint] to detect end-of-utterance and [reset] to start the next one.
 *
 * All processing is fully on-device (no network). Thread-safety: use from a single worker thread.
 *
 * Ported from AIOPE (org.xnet.aiope.inference.SherpaSttEngine).
 */
class SherpaSttEngine(
    private val encoder: File,
    private val decoder: File,
    private val joiner: File,
    private val tokens: File,
    private val numThreads: Int = 2,
) : AutoCloseable {

    companion object { private const val TAG = "SherpaSttEngine" }

    val sampleRate = 16000

    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var stream: OnlineStream? = null

    fun isAvailable(): Boolean =
        encoder.isFile && decoder.isFile && joiner.isFile && tokens.isFile

    /** True once the (expensive) recognizer is loaded and resident in native memory. */
    @Synchronized
    fun isWarm(): Boolean = recognizer != null

    /**
     * Load the recognizer and keep it resident WITHOUT opening a capture stream. Use this to
     * preload the model at app startup so the first dictation has no model-load latency. Safe to
     * call repeatedly (idempotent). Returns true if the recognizer is loaded afterwards.
     */
    @Synchronized
    fun preload(): Boolean = start()

    /**
     * Begin a fresh capture session on the warm recognizer: ensure the model is loaded (loading it
     * now if this is the first use), then open a NEW stream, releasing any previous one. The
     * recognizer itself is reused across sessions, which is the whole point of keeping it warm.
     * Returns true if a stream is ready.
     */
    @Synchronized
    fun beginSession(): Boolean {
        if (!start()) return false
        val r = recognizer ?: return false
        return try {
            stream?.let { old -> runCatching { old.release() } }
            stream = r.createStream()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "beginSession failed", t)
            false
        }
    }

    /**
     * End the current capture session: release the stream but KEEP the recognizer warm for the
     * next session. This is the key difference from [close], which also frees the recognizer.
     */
    @Synchronized
    fun endSession() {
        try { stream?.release() } catch (_: Throwable) {}
        stream = null
    }

    @Synchronized
    fun start(): Boolean {
        if (recognizer != null) return true
        if (!isAvailable()) { Log.e(TAG, "model files missing"); return false }
        return try {
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath,
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = numThreads,
                    provider = "cpu",
                ),
                endpointConfig = EndpointConfig(),
                enableEndpoint = true,
            )
            // assetManager = null -> load from the filesystem paths above.
            val r = OnlineRecognizer(assetManager = null, config = config)
            recognizer = r
            stream = r.createStream()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            close()
            false
        }
    }

    /** Feed PCM float samples in [-1,1] at [sampleRate]; decodes as data becomes ready. */
    @Synchronized
    fun accept(samples: FloatArray) {
        val r = recognizer ?: return
        val s = stream ?: return
        try {
            s.acceptWaveform(samples, sampleRate)
            while (r.isReady(s)) r.decode(s)
        } catch (t: Throwable) {
            Log.e(TAG, "accept failed", t)
        }
    }

    /** Current interim/partial transcription for the active utterance. */
    @Synchronized
    fun partialText(): String {
        val r = recognizer ?: return ""
        val s = stream ?: return ""
        return try { r.getResult(s).text } catch (_: Throwable) { "" }
    }

    /** True when the recognizer detects an end-of-utterance (trailing silence). */
    @Synchronized
    fun isEndpoint(): Boolean {
        val r = recognizer ?: return false
        val s = stream ?: return false
        return try { r.isEndpoint(s) } catch (_: Throwable) { false }
    }

    /** Reset the stream state to begin the next utterance (call after reading a final result). */
    @Synchronized
    fun reset() {
        val r = recognizer ?: return
        val s = stream ?: return
        try { r.reset(s) } catch (t: Throwable) { Log.e(TAG, "reset failed", t) }
    }

    @Synchronized
    override fun close() {
        try { stream?.release() } catch (_: Throwable) {}
        try { recognizer?.release() } catch (_: Throwable) {}
        stream = null
        recognizer = null
    }
}
