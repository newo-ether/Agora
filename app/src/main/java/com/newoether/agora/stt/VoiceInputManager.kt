package com.newoether.agora.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * On-device speech-to-text driven DIRECTLY by sherpa-onnx ([SherpaSttEngine]) — fully offline, no
 * dependency on the system's selected voice-recognition service (which may be unset on GrapheneOS,
 * causing "no selected voice recognition service"). Captures mic audio via [AudioRecord] at 16 kHz
 * and streams partial/final transcriptions back to the caller.
 *
 * Ported from AIOPE (ngo.xnet.aiope.feature.chat.voice.VoiceInputManager).
 */
class VoiceInputManager(private val appContext: Context) {

    companion object { private const val TAG = "VoiceInputManager" }

    @Volatile private var capturing = false
    private var captureThread: Thread? = null
    @Volatile private var engine: SherpaSttEngine? = null

    /** Whether the offline STT model has been downloaded. */
    fun isModelInstalled(): Boolean = SherpaSttBootstrap.isInstalled(appContext)

    fun isAvailable(): Boolean = isModelInstalled()

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Start listening. [onPartial] streams interim text; [onFinal] fires with the final text at the
     * end of an utterance (endpoint) or when [stop] is called; [onError] reports a message;
     * [onEnd] fires when capture stops (so the UI can reset its mic state).
     */
    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        onEnd: () -> Unit,
    ) {
        if (!hasMicPermission()) { onError("Microphone permission is required."); onEnd(); return }
        if (!isModelInstalled()) {
            onError("Speech model not downloaded. Download it in Settings first."); onEnd(); return
        }
        if (capturing) return

        // Borrow the shared, preloaded recognizer instead of building one per session. If it
        // isn't warm yet (first use), acquire loads it now; subsequent sessions are instant.
        val e = SttEngineHolder.acquire(appContext)
        if (e == null) { onError("Could not initialize the speech recognizer."); onEnd(); return }
        engine = e
        capturing = true
        captureThread = thread(name = "sherpa-stt-ui") { loop(e, onPartial, onFinal, onError, onEnd) }
    }

    private fun loop(
        e: SherpaSttEngine,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        onEnd: () -> Unit,
    ) {
        val sr = e.sampleRate
        val minBuf = AudioRecord.getMinBufferSize(
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        // Small read chunk (~100ms) for low latency to the first partial; large record buffer to
        // avoid overrun. Big read chunks add noticeable lag before the first partial appears.
        val readChunk = (sr / 10).coerceAtLeast(minBuf / 2) // ~100ms
        val recordBuf = (minBuf * 4).coerceAtLeast(sr) // generous ring buffer
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recordBuf,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord init failed", t); onError("Microphone unavailable."); cleanup(); onEnd(); return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            onError("Microphone unavailable."); record.release(); cleanup(); onEnd(); return
        }
        val shorts = ShortArray(readChunk)
        var lastPartial = ""
        try {
            record.startRecording()
            // Warm up the encoder with a brief lead of silence so the first real audio doesn't
            // cold-start (which caused a spurious leading token + first-partial lag).
            e.accept(FloatArray(sr / 5)) // 200ms of silence
            while (capturing) {
                val n = record.read(shorts, 0, shorts.size)
                if (n <= 0) continue
                val floats = FloatArray(n) { shorts[it] / 32768f }
                e.accept(floats)
                val partial = cleanTranscript(e.partialText())
                if (partial.isNotBlank() && partial != lastPartial) {
                    lastPartial = partial
                    onPartial(partial)
                }
                if (e.isEndpoint()) {
                    val finalText = cleanTranscript(e.partialText())
                    e.reset()
                    if (finalText.isNotBlank()) { onFinal(finalText); lastPartial = "" }
                }
            }
            if (lastPartial.isNotBlank()) onFinal(lastPartial)
        } catch (t: Throwable) {
            Log.e(TAG, "capture failed", t); onError("Speech recognition error.")
        } finally {
            try { record.stop() } catch (_: Throwable) {}
            try { record.release() } catch (_: Throwable) {}
            cleanup()
            onEnd()
        }
    }

    fun stop() { capturing = false }

    /**
     * Clean the raw recognizer text. The Kroko streaming zipformer emits natural mixed-case text
     * WITH punctuation (sherpa converts the '▁' word marker to spaces), so we only normalize
     * whitespace and strip any residual marker — we do NOT lowercase (that would ruin its casing).
     */
    private fun cleanTranscript(raw: String): String {
        return raw
            .replace('\u2581', ' ')   // any residual word-boundary marker -> space
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanup() {
        capturing = false
        // Return the shared engine to the holder (stream released, recognizer kept warm) rather
        // than closing it, so the next session reuses the loaded recognizer.
        if (engine != null) { runCatching { SttEngineHolder.release() } }
        engine = null
    }
}
