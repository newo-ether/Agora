package com.newoether.agora.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.newoether.agora.data.SpeechRecognitionConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android's platform recognizer. It owns the microphone itself, so loudness comes from
 * [RecognitionListener.onRmsChanged]. The platform ends a session after a pause; until the user
 * stops, each finished segment is kept and listening restarts so one capture can span pauses.
 * Every SpeechRecognizer call must happen on the main thread.
 */
internal class SystemSpeechRecognizerSession(
    context: Context,
    private val config: SpeechRecognitionConfig,
    private val onLevel: (Float) -> Unit,
) : SpeechCaptureSession {
    private val appContext = context.applicationContext
    private val segments = mutableListOf<String>()
    private var partial = ""
    private var recognizer: SpeechRecognizer? = null
    private var stopping = false
    private var done: CompletableDeferred<String>? = null
    private var failure: String? = null

    override val reachedLimit: Boolean = false

    override fun start() {
        val onDevice = config.preferOffline &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(appContext)) {
            throw SpeechRecognitionException("No speech recognition service is installed")
        }
        val created = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
        created.setRecognitionListener(listener)
        recognizer = created
        listen()
    }

    override suspend fun finish(): String = withContext(Dispatchers.Main.immediate) {
        val active = recognizer
        val result = if (active == null) {
            transcript()
        } else {
            val deferred = CompletableDeferred<String>().also { done = it }
            stopping = true
            active.stopListening()
            // Some vendor services never deliver a final callback after stopListening.
            withTimeoutOrNull(FINAL_RESULT_TIMEOUT_MS) { deferred.await() } ?: transcript()
        }
        release()
        failure?.takeIf { result.isBlank() }?.let { throw SpeechRecognitionException(it) }
        result
    }

    override fun cancel() {
        stopping = true
        recognizer?.cancel()
        release()
        done?.complete("")
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            SpeechLanguages.androidTag(config.language).takeIf { it.isNotEmpty() }?.let {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, it)
            }
            if (config.preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer?.startListening(intent)
    }

    private fun transcript(): String =
        (segments + partial).filter { it.isNotBlank() }.joinToString(" ").trim()

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun Bundle?.bestResult(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onRmsChanged(rmsdB: Float) {
            // Platform services report roughly -2..10 dB.
            onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults.bestResult().takeIf { it.isNotEmpty() }?.let { partial = it }
        }

        override fun onResults(results: Bundle?) {
            val text = results.bestResult().ifEmpty { partial }
            if (text.isNotEmpty()) segments += text
            partial = ""
            if (stopping) done?.complete(transcript()) else listen()
        }

        override fun onError(error: Int) {
            onLevel(0f)
            val silent = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!stopping && silent) {
                if (partial.isNotEmpty()) segments += partial
                partial = ""
                listen()
                return
            }
            if (!silent && error != SpeechRecognizer.ERROR_CLIENT) failure = errorText(error)
            if (stopping) {
                done?.complete(transcript())
            } else {
                // A hard failure while recording: keep what we have and surface it on stop.
                stopping = true
                release()
            }
        }
    }

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error"
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Recognition service error"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language is not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language model is not downloaded"
        else -> "Speech recognition error ($error)"
    }

    private companion object {
        const val FINAL_RESULT_TIMEOUT_MS = 8_000L
    }
}
