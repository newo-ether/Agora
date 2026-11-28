package com.newoether.agora.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.newoether.agora.stt.SherpaSttBootstrap
import com.newoether.agora.stt.SherpaSttEngine
import com.newoether.agora.stt.SttEngineHolder
import kotlin.concurrent.thread

/**
 * Real, fully-offline [RecognitionService] backed by sherpa-onnx ([SherpaSttEngine]). Captures
 * microphone audio with [AudioRecord] at 16 kHz mono, streams it to the streaming zipformer
 * recognizer, and reports interim ([Callback.partialResults]) and final ([Callback.results])
 * transcriptions — the same contract the platform [SpeechRecognizer] expects, so any app on the
 * device (including Agora's own assistant/mic surfaces) can use this as the recognition provider.
 *
 * Ported from AIOPE (ngo.xnet.aiope.AiopeRecognitionService). Replaces the former stub.
 */
class AgoraRecognitionService : RecognitionService() {

    companion object { private const val TAG = "AgoraRecognitionSvc" }

    @Volatile private var capturing = false
    private var captureThread: Thread? = null
    @Volatile private var engine: SherpaSttEngine? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val cb = listener ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            safeError(cb, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return
        }
        if (!SherpaSttBootstrap.isInstalled(this)) {
            Log.w(TAG, "STT model not installed")
            safeError(cb, SpeechRecognizer.ERROR_CLIENT); return
        }
        if (capturing) { safeError(cb, SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return }

        // Borrow the shared, preloaded recognizer from the holder (loads it now if not yet warm);
        // a null means the model is missing or another session holds it.
        val e = SttEngineHolder.acquire(this)
        if (e == null) { safeError(cb, SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return }
        engine = e

        capturing = true
        captureThread = thread(name = "sherpa-stt-capture") { captureLoop(e, cb) }
    }

    private fun captureLoop(e: SherpaSttEngine, cb: Callback) {
        val sampleRate = e.sampleRate
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate) // ~1s floor
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord init failed", t); safeError(cb, SpeechRecognizer.ERROR_AUDIO); cleanup(); return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            safeError(cb, SpeechRecognizer.ERROR_AUDIO); record.release(); cleanup(); return
        }

        val shorts = ShortArray(minBuf)
        var lastPartial = ""
        try {
            record.startRecording()
            try { cb.readyForSpeech(Bundle()) } catch (_: Throwable) {}
            try { cb.beginningOfSpeech() } catch (_: Throwable) {}

            while (capturing) {
                val n = record.read(shorts, 0, shorts.size)
                if (n <= 0) continue
                // PCM16 -> float [-1,1]
                val floats = FloatArray(n) { shorts[it] / 32768f }
                e.accept(floats)

                val partial = e.partialText()
                if (partial.isNotBlank() && partial != lastPartial) {
                    lastPartial = partial
                    val b = Bundle().apply {
                        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(partial))
                    }
                    try { cb.partialResults(b) } catch (_: Throwable) {}
                }

                if (e.isEndpoint()) {
                    val finalText = e.partialText()
                    e.reset()
                    lastPartial = ""
                    if (finalText.isNotBlank()) {
                        emitFinal(cb, finalText)
                        break // one utterance per session, matching SpeechRecognizer semantics
                    }
                }
            }
            // Stopped without an endpoint (e.g. onStopListening): flush whatever we have.
            if (lastPartial.isNotBlank()) emitFinal(cb, lastPartial)
        } catch (t: Throwable) {
            Log.e(TAG, "capture loop failed", t)
            safeError(cb, SpeechRecognizer.ERROR_CLIENT)
        } finally {
            try { record.stop() } catch (_: Throwable) {}
            try { record.release() } catch (_: Throwable) {}
            try { cb.endOfSpeech() } catch (_: Throwable) {}
            cleanup()
        }
    }

    private fun emitFinal(cb: Callback, text: String) {
        val b = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        }
        try { cb.results(b) } catch (_: Throwable) {}
    }

    override fun onStopListening(listener: Callback?) {
        capturing = false
    }

    override fun onCancel(listener: Callback?) {
        capturing = false
        cleanup()
    }

    private fun cleanup() {
        capturing = false
        // Return the shared engine (stream released, recognizer kept warm) instead of closing it.
        if (engine != null) { runCatching { SttEngineHolder.release() } }
        engine = null
    }

    private fun safeError(cb: Callback, code: Int) {
        try { cb.error(code) } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
