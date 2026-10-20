package com.newoether.agora.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.newoether.agora.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** Explicit start/stop recording; uploads only the stopped clip to the selected speech endpoint. */
internal class ApiVoiceRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onState: (VoiceTypingState) -> Unit,
    private val onText: (String) -> Unit,
    private val onError: (Int) -> Unit,
    private val client: AudioTranscriptionClient = AudioTranscriptionClient(),
) {
    private var recorder: MediaRecorder? = null
    private var audio: File? = null
    private var upload: Job? = null
    private var generation = 0L
    private var config = VoiceTypingConfig()
    private var key = ""

    fun start(config: VoiceTypingConfig, key: String) {
        cancel()
        try {
            config.transcriptionUrl()
            require(config.mode.usesApi && ((config.mode == VoiceTypingMode.COMPATIBLE && config.apiKeyId == null) || key.isNotBlank()))
        } catch (_: Exception) { onError(R.string.voice_typing_config_error); return }
        this.config = config
        this.key = key
        val token = ++generation
        try {
            val directory = File(context.cacheDir, "voice-typing").apply { mkdirs() }
            val file = File.createTempFile("dictation-", ".m4a", directory)
            audio = file
            @Suppress("DEPRECATION")
            val recording = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            recorder = recording
            recording.setAudioSource(MediaRecorder.AudioSource.MIC)
            recording.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recording.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recording.setAudioChannels(1)
            recording.setAudioSamplingRate(16000)
            recording.setAudioEncodingBitRate(64000)
            recording.setMaxDuration(120000)
            recording.setMaxFileSize(AudioTranscriptionClient.MAX_AUDIO_BYTES)
            recording.setOutputFile(file.absolutePath)
            recording.setOnInfoListener { _, what, _ ->
                if (token == generation && what in setOf(MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED,
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)) stop()
            }
            recording.setOnErrorListener { _, _, _ -> if (token == generation) { cancel(); onError(R.string.voice_typing_error) } }
            recording.prepare()
            recording.start()
            onState(VoiceTypingState.LISTENING)
        } catch (_: Exception) { cancel(); onError(R.string.voice_typing_error) }
    }

    fun stop() {
        val recording = recorder ?: return
        recorder = null
        try { recording.stop() }
        catch (_: Exception) { runCatching { recording.release() }; cancel(); onError(R.string.voice_typing_no_speech); return }
        finally { runCatching { recording.release() } }
        val file = audio ?: return
        val token = generation
        val selectedConfig = config
        val selectedKey = key
        key = ""
        onState(VoiceTypingState.FINISHING)
        upload = scope.launch {
            try {
                val text = client.transcribe(file, selectedConfig, selectedKey)
                if (token == generation) onText(text)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == generation) onError(R.string.voice_typing_error) }
            finally {
                file.delete()
                if (token == generation) { audio = null; upload = null; onState(VoiceTypingState.IDLE) }
            }
        }
    }

    fun cancel() {
        generation++
        upload?.cancel(); upload = null
        recorder?.let { recording -> runCatching { recording.reset() }; runCatching { recording.release() } }
        recorder = null
        audio?.delete(); audio = null
        key = ""
        onState(VoiceTypingState.IDLE)
    }
}
