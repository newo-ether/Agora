package com.newoether.agora.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.newoether.agora.R
import java.util.Locale

internal enum class VoiceTypingState { IDLE, LISTENING, FINISHING }

/** Main-thread, one-utterance Android recognizer. No keyboard, recording file or chat send. */
internal class AndroidVoiceRecognizer(
    private val context: Context,
    private val onState: (VoiceTypingState) -> Unit,
    private val onText: (String) -> Unit,
    private val onError: (Int) -> Unit,
    private val onPartialText: (String) -> Unit = {},
) {
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0L

    fun start(language: String, onDevice: Boolean = false) {
        cancel()
        if (onDevice && (android.os.Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context))) {
            onError(R.string.voice_typing_unavailable); return
        }
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) { onError(R.string.voice_typing_unavailable); return }
        val token = ++generation
        try {
            val instance = if (onDevice && android.os.Build.VERSION.SDK_INT >= 31)
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = instance
            instance.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if (token == generation) onState(VoiceTypingState.FINISHING) }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (token != generation) return
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(onPartialText)
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    if (token != generation) return
                    finish()
                    this@AndroidVoiceRecognizer.onError(when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_typing_permission
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_typing_no_speech
                        else -> R.string.voice_typing_error
                    })
                }
                override fun onResults(results: Bundle?) {
                    if (token != generation) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    finish()
                    if (text.isNullOrBlank()) this@AndroidVoiceRecognizer.onError(R.string.voice_typing_no_speech) else onText(text)
                }
            })
            onState(VoiceTypingState.LISTENING)
            instance.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.trim().ifBlank { Locale.getDefault().toLanguageTag() })
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        } catch (_: Exception) { finish(); onError(R.string.voice_typing_error) }
    }

    fun stop() {
        if (recognizer == null) return
        onState(VoiceTypingState.FINISHING)
        try { recognizer?.stopListening() } catch (_: Exception) { finish(); onError(R.string.voice_typing_error) }
    }

    fun cancel() {
        generation++
        runCatching { recognizer?.cancel() }
        release()
    }
    private fun finish() { generation++; release() }
    private fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        onState(VoiceTypingState.IDLE)
    }
}
