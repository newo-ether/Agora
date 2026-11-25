package com.newoether.agora.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.newoether.agora.data.SpeechRecognitionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Decoder inputs that change the native recognizer; a new key forces a reload. */
private data class LoadedKey(val modelId: String, val language: String, val hotwords: String)

/**
 * Runs sherpa-onnx offline recognition. Keeps one recognizer resident between dictations and frees
 * it after [IDLE_RELEASE_MS] so a 1 GB model does not stay in memory. Silero VAD splits the clip
 * into speech segments of at most [MAX_SEGMENT_SECONDS] seconds, which bounds per-call decoder
 * length and drops silence that some models would otherwise hallucinate over.
 */
internal class LocalSpeechEngine(
    context: Context,
    private val store: LocalSpeechModelStore,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var loadedKey: LoadedKey? = null
    private var releaseJob: Job? = null

    /** Fails fast before recording when the selected model is unknown or not fully downloaded. */
    fun requireReady(config: SpeechRecognitionConfig): LocalSpeechModel {
        val model = LocalSpeechModels.byId(config.presetId)
            ?: throw SpeechRecognitionException("Unknown local model")
        if (!store.isInstalled(model)) throw SpeechRecognitionException("${model.displayName} is not downloaded")
        return model
    }

    suspend fun transcribe(config: SpeechRecognitionConfig, samples: FloatArray): String =
        withContext(Dispatchers.Default) {
            val model = requireReady(config)
            mutex.withLock {
                releaseJob?.cancel()
                try {
                    val active = obtain(model, config)
                    val segments = speechSegments(samples)
                    joinSegments(segments.map { segment -> decode(active, segment) })
                } finally {
                    scheduleRelease()
                }
            }
        }

    private fun obtain(model: LocalSpeechModel, config: SpeechRecognitionConfig): OfflineRecognizer {
        val language = SpeechLanguages.primary(config.language).takeIf { it in model.languages }.orEmpty()
        val hotwords = if (model.supportsVocabulary) speechVocabularyTerms(config.vocabulary).joinToString(",") else ""
        val key = LoadedKey(model.id, language, hotwords)
        recognizer?.let { if (loadedKey == key) return it }
        unload()
        val dir = store.directory(model)
        val created = try {
            OfflineRecognizer(config = recognizerConfig(model, dir, language, hotwords))
        } catch (e: IllegalArgumentException) {
            throw SpeechRecognitionException("Cannot load ${model.displayName}")
        }
        recognizer = created
        loadedKey = key
        return created
    }

    private fun decode(active: OfflineRecognizer, samples: FloatArray): String {
        val stream = active.createStream()
        return try {
            stream.acceptWaveform(samples, SpeechAudioFormat.SAMPLE_RATE)
            active.decode(stream)
            active.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun speechSegments(samples: FloatArray): List<FloatArray> {
        val vad = Vad(
            assetManager = appContext.assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = VAD_ASSET,
                    threshold = 0.5f,
                    minSilenceDuration = 0.4f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW,
                    maxSpeechDuration = MAX_SEGMENT_SECONDS,
                ),
                sampleRate = SpeechAudioFormat.SAMPLE_RATE,
                numThreads = 1,
            ),
        )
        val segments = mutableListOf<FloatArray>()
        try {
            var offset = 0
            while (offset + VAD_WINDOW <= samples.size) {
                vad.acceptWaveform(samples.copyOfRange(offset, offset + VAD_WINDOW))
                offset += VAD_WINDOW
                while (!vad.empty()) {
                    segments += vad.front().samples
                    vad.pop()
                }
            }
            vad.flush()
            while (!vad.empty()) {
                segments += vad.front().samples
                vad.pop()
            }
        } finally {
            vad.release()
        }
        return segments
    }

    private fun scheduleRelease() {
        releaseJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            mutex.withLock { unload() }
        }
    }

    private fun unload() {
        recognizer?.release()
        recognizer = null
        loadedKey = null
    }

    private companion object {
        const val VAD_ASSET = "speech/silero_vad.onnx"
        const val VAD_WINDOW = 512
        const val MAX_SEGMENT_SECONDS = 20f
        const val IDLE_RELEASE_MS = 90_000L
    }
}

private fun recognizerConfig(
    model: LocalSpeechModel,
    dir: File,
    language: String,
    hotwords: String,
): OfflineRecognizerConfig {
    fun path(name: String) = File(dir, name).path
    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    val modelConfig = when (model.kind) {
        LocalSpeechModelKind.SENSE_VOICE -> OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = path("model.int8.onnx"),
                language = language,
                useInverseTextNormalization = true,
            ),
            tokens = path("tokens.txt"),
        )
        LocalSpeechModelKind.FIRE_RED_CTC -> OfflineModelConfig(
            fireRedAsrCtc = OfflineFireRedAsrCtcModelConfig(model = path("model.int8.onnx")),
            tokens = path("tokens.txt"),
        )
        LocalSpeechModelKind.QWEN3_ASR -> OfflineModelConfig(
            qwen3Asr = OfflineQwen3AsrModelConfig(
                convFrontend = path("conv_frontend.onnx"),
                encoder = path("encoder.int8.onnx"),
                decoder = path("decoder.int8.onnx"),
                tokenizer = path("tokenizer"),
                maxTotalLen = 1024,
                maxNewTokens = 384,
                hotwords = hotwords,
            ),
        )
        LocalSpeechModelKind.NEMO_TRANSDUCER -> OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = path("encoder.int8.onnx"),
                decoder = path("decoder.int8.onnx"),
                joiner = path("joiner.int8.onnx"),
            ),
            tokens = path("tokens.txt"),
            modelType = "nemo_transducer",
        )
    }.apply { numThreads = threads }
    return OfflineRecognizerConfig(modelConfig = modelConfig)
}

/** Joins segment transcripts, adding a space only between two Latin-script words. */
internal fun joinSegments(parts: List<String>): String {
    val out = StringBuilder()
    for (part in parts) {
        if (part.isEmpty()) continue
        val prev = out.lastOrNull()
        if (prev != null && prev.isLatinWordChar() && part.first().isLatinWordChar()) out.append(' ')
        else if (prev != null && prev in ".,!?;:" && part.first().isLatinWordChar()) out.append(' ')
        out.append(part)
    }
    return out.toString()
}

private fun Char.isLatinWordChar(): Boolean = this.code < 0x2E80 && isLetterOrDigit()

/** Little-endian 16-bit PCM to floats in [-1, 1), the input sherpa-onnx expects. */
internal fun pcm16ToFloat(pcm: ByteArray): FloatArray {
    val out = FloatArray(pcm.size / 2)
    for (i in out.indices) {
        val lo = pcm[2 * i].toInt() and 0xFF
        val hi = pcm[2 * i + 1].toInt()
        out[i] = ((hi shl 8) or lo).toShort() / 32768f
    }
    return out
}
