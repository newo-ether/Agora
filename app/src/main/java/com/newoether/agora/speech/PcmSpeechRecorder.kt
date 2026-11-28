package com.newoether.agora.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/** 16 kHz mono 16-bit PCM: the input every supported cloud recognizer accepts without resampling. */
internal object SpeechAudioFormat {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
    /** Short enough for every provider's per-request limit (DashScope caps at 5 minutes). */
    const val MAX_DURATION_MS = 5 * 60 * 1000L
    const val MAX_PCM_BYTES = (BYTES_PER_SECOND * MAX_DURATION_MS / 1000L).toInt()
}

/** Wraps little-endian PCM in a canonical 44-byte RIFF/WAVE header. */
internal fun encodeWav(
    pcm: ByteArray,
    sampleRate: Int = SpeechAudioFormat.SAMPLE_RATE,
    channels: Int = SpeechAudioFormat.CHANNELS,
    bitsPerSample: Int = SpeechAudioFormat.BITS_PER_SAMPLE,
): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36 + pcm.size)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1)
        putShort(channels.toShort())
        putInt(sampleRate)
        putInt(byteRate)
        putShort(blockAlign.toShort())
        putShort(bitsPerSample.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(pcm.size)
    }.array()
    return header + pcm
}

/**
 * Maps one frame of 16-bit samples to a 0..1 loudness for the waveform. dBFS is linear between
 * -60 dB (silence floor) and 0 dB, which reads closer to perceived loudness than raw RMS.
 */
internal fun pcmFrameLevel(samples: ShortArray, count: Int): Float {
    if (count <= 0) return 0f
    var sum = 0.0
    for (i in 0 until count) {
        val s = samples[i] / 32768.0
        sum += s * s
    }
    val rms = sqrt(sum / count)
    if (rms <= 1e-6) return 0f
    val db = 20 * log10(rms)
    return ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Captures microphone PCM on a dedicated thread. [onLevel] runs on that thread every ~40 ms.
 * Callers must hold RECORD_AUDIO; [start] throws [SpeechRecognitionException] if the mic is busy.
 */
internal class PcmSpeechRecorder(private val onLevel: (Float) -> Unit) {
    private val running = AtomicBoolean(false)
    private val output = ByteArrayOutputStream(SpeechAudioFormat.BYTES_PER_SECOND * 8)
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile var reachedLimit: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SpeechAudioFormat.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw SpeechRecognitionException("Microphone is unavailable")
        val frameBytes = SpeechAudioFormat.BYTES_PER_SECOND / 25
        val audio = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SpeechAudioFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, frameBytes) * 4,
            )
        } catch (e: Exception) {
            throw SpeechRecognitionException(e.message ?: "Microphone is unavailable")
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            throw SpeechRecognitionException("Microphone is unavailable")
        }
        try {
            audio.startRecording()
        } catch (e: Exception) {
            audio.release()
            throw SpeechRecognitionException(e.message ?: "Microphone is busy")
        }
        if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            audio.release()
            throw SpeechRecognitionException("Microphone is busy")
        }
        record = audio
        running.set(true)
        worker = thread(name = "agora-speech-capture", isDaemon = true) {
            val samples = ShortArray(frameBytes / 2)
            val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (running.get()) {
                val read = audio.read(samples, 0, samples.size)
                if (read <= 0) {
                    if (read < 0) break
                    continue
                }
                bytes.clear()
                for (i in 0 until read) bytes.putShort(samples[i])
                synchronized(output) {
                    val room = SpeechAudioFormat.MAX_PCM_BYTES - output.size()
                    if (room <= 0) {
                        reachedLimit = true
                    } else {
                        output.write(bytes.array(), 0, minOf(read * 2, room))
                    }
                }
                onLevel(pcmFrameLevel(samples, read))
                if (reachedLimit) break
            }
        }
    }

    /** Stops capture and returns everything recorded. Safe to call more than once. */
    fun stop(): ByteArray {
        shutdown()
        return synchronized(output) { output.toByteArray() }
    }

    /** Stops capture and drops the audio. */
    fun cancel() {
        shutdown()
        synchronized(output) { output.reset() }
    }

    private fun shutdown() {
        running.set(false)
        val audio = record ?: return
        record = null
        runCatching { audio.stop() }
        worker?.join(500)
        worker = null
        audio.release()
    }
}
