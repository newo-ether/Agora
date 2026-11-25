package com.newoether.agora.speech

import android.content.Context
import com.newoether.agora.data.SpeechEngineType
import com.newoether.agora.data.SpeechRecognitionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One microphone capture bound to a recognizer. [start] begins capture on the main thread,
 * [finish] stops it and returns the transcript, and [cancel] drops everything. Level callbacks
 * report 0..1 loudness and may arrive on any thread.
 */
internal interface SpeechCaptureSession {
    /** True once the capture hit [SpeechAudioFormat.MAX_DURATION_MS] and stopped on its own. */
    val reachedLimit: Boolean

    fun start()

    suspend fun finish(): String

    fun cancel()
}

internal object SpeechCaptureSessions {
    fun create(
        context: Context,
        config: SpeechRecognitionConfig,
        onLevel: (Float) -> Unit,
    ): SpeechCaptureSession = when (config.type) {
        SpeechEngineType.CLOUD -> CloudSpeechCaptureSession(config, onLevel)
        SpeechEngineType.SYSTEM -> SystemSpeechRecognizerSession(context, config, onLevel)
        SpeechEngineType.LOCAL -> throw SpeechRecognitionException("Local models are not available yet")
    }
}

/** Records PCM locally, then uploads a WAV once the user stops. */
private class CloudSpeechCaptureSession(
    private val config: SpeechRecognitionConfig,
    onLevel: (Float) -> Unit,
) : SpeechCaptureSession {
    private val recorder = PcmSpeechRecorder(onLevel)

    override val reachedLimit: Boolean get() = recorder.reachedLimit

    override fun start() = recorder.start()

    override suspend fun finish(): String {
        val pcm = withContext(Dispatchers.IO) { recorder.stop() }
        if (pcm.size < SpeechAudioFormat.BYTES_PER_SECOND / 5) return ""
        return CloudSpeechTranscriber.transcribe(config, encodeWav(pcm)).trim()
    }

    override fun cancel() = recorder.cancel()
}
