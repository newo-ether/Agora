package com.newoether.agora.voice.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Mic capture → WebSocket (base64 PCM) and playback of PCM chunks for the Live API session.
 * [LiveVoiceStreamer] owns this and sets the WebSocket after connection.
 *
 * Ported from AIOPE (feature-chat/engine/RealtimeAudioManager.kt), trimmed to the Google-direct
 * path: input PCM at 16 kHz is sent as realtimeInput.audio; model audio plays back at 24 kHz.
 * AcousticEchoCanceler + shared audio session give barge-in-friendly echo cancellation.
 */
class LiveVoiceAudioManager(
    private val appContext: android.content.Context,
    private val config: LiveAudioConfig = LiveAudioConfig(),
) {
    private val audioManager =
        appContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    private var priorAudioMode = android.media.AudioManager.MODE_NORMAL
    private var priorSpeakerphone = false
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var webSocket: WebSocket? = null
    private var aec: AcousticEchoCanceler? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()
    private var sharedSessionId = 0

    // Half-duplex gate: while the model is speaking (playback active), suppress sending mic audio
    // so the open speakerphone output does not feed back into VOICE_COMMUNICATION capture and get
    // transcribed as the user interrupting — the cause of the model "talking over" / cutting itself
    // off after a few lines on speakerphone. [modelSpeakingUntil] holds the gate briefly past the
    // last played chunk to cover the speaker's acoustic tail.
    @Volatile private var modelSpeakingUntil = 0L

    private fun micGatedNow(): Boolean =
        playbackQueue.isNotEmpty() || System.currentTimeMillis() < modelSpeakingUntil

    fun setWebSocket(ws: WebSocket) { webSocket = ws }

    /** Clear queued playback on interruption / barge-in. */
    fun clearPlayback() {
        playbackQueue.clear()
        // Barge-in: the user is interrupting, so open the mic gate immediately.
        modelSpeakingUntil = 0L
    }

    @Synchronized
    fun startCapture() {
        if (audioRecord != null) {
            stopCapture()
        }
        val rate = config.inputSampleRate
        // Route the voice-communication stream to the speaker; without this, VOICE_COMMUNICATION
        // audio plays through the earpiece at near-zero volume ("frames delivered but silent").
        runCatching {
            priorAudioMode = audioManager.mode
            priorSpeakerphone = audioManager.isSpeakerphoneOn
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
        val bufSize = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (bufSize <= 0) return
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord init failed", t); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        audioRecord = rec
        sharedSessionId = rec.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sharedSessionId)?.apply { enabled = true }
        }
        rec.startRecording()
        // Start playback ONCE, now that the capture session id is known, so the AudioTrack is built
        // with the shared session for AEC pairing. Rebuilding the track after it already started
        // caused an underrun storm (track torn down + recreated mid-stream) that broke playback and
        // made the model "stutter/hang on its own output". If playback somehow started earlier with
        // the wrong session, replace it once here.
        if (audioTrack == null) {
            startPlayback()
        } else if (audioTrack?.audioSessionId != sharedSessionId) {
            playbackJob?.cancel(); playbackJob = null
            runCatching { audioTrack?.stop() }; runCatching { audioTrack?.release() }; audioTrack = null
            playbackQueue.clear(); startPlayback()
        }
        val sampleRate = rate
        captureJob = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(bufSize)
            while (isActive) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: break
                if (read > 0 && !micGatedNow()) {
                    val encoded = Base64.encodeToString(buf.copyOf(read), Base64.NO_WRAP)
                    webSocket?.send(
                        """{"realtimeInput":{"audio":{"mimeType":"audio/pcm;rate=$sampleRate","data":"$encoded"}}}""",
                    )
                }
            }
        }
    }

    @Synchronized
    fun stopCapture() {
        captureJob?.cancel(); captureJob = null
        // Detach the field first so the capture loop's `audioRecord?.read` sees null and exits,
        // then stop/release defensively. AudioRecord.stop() throws IllegalStateException if the
        // recorder isn't RECORDING (or was already released) — a real race on shutdown — so guard
        // on state and swallow the benign failure instead of crashing the process.
        val rec = audioRecord
        audioRecord = null
        if (rec != null) {
            try {
                if (rec.state == AudioRecord.STATE_INITIALIZED &&
                    rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
                ) {
                    rec.stop()
                }
            } catch (_: IllegalStateException) {
            } catch (_: Throwable) {
            }
            try { rec.release() } catch (_: Throwable) {}
        }
    }

    fun startPlayback(outputRate: Int = config.outputSampleRate) {
        val bufSize = AudioTrack.getMinBufferSize(
            outputRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(outputRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(bufSize * 8)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (sharedSessionId != 0) builder.setSessionId(sharedSessionId)
        audioTrack = builder.build().apply { play() }
        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val chunk = playbackQueue.poll(100, TimeUnit.MILLISECONDS)
                if (chunk != null) audioTrack?.write(chunk, 0, chunk.size)
            }
        }
    }

    fun playAudio(pcmData: ByteArray) {
        playbackQueue.offer(pcmData)
        // Hold the mic gate for this chunk's playback duration (24 kHz, 16-bit mono => bytes/48 ms)
        // plus a ~600 ms acoustic tail so the speaker's decay doesn't leak back into capture.
        val chunkMs = pcmData.size / 48L
        val until = System.currentTimeMillis() + chunkMs + 600L
        if (until > modelSpeakingUntil) modelSpeakingUntil = until
    }

    @Synchronized
    fun stop() {
        stopCapture()
        playbackJob?.cancel(); playbackJob = null
        playbackQueue.clear()
        try { aec?.release() } catch (_: Throwable) {}
        aec = null
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            try {
                if (track.state == AudioTrack.STATE_INITIALIZED &&
                    track.playState != AudioTrack.PLAYSTATE_STOPPED
                ) {
                    track.stop()
                }
            } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
        }
        webSocket = null
        // Restore the device audio routing we changed for the session.
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = priorSpeakerphone
            audioManager.mode = priorAudioMode
        }
    }

    companion object { private const val TAG = "LiveVoiceAudio" }
}
