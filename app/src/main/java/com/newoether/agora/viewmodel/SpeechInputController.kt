package com.newoether.agora.viewmodel

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.newoether.agora.R
import com.newoether.agora.data.SpeechEngineType
import com.newoether.agora.data.SpeechRecognitionConfig
import com.newoether.agora.data.SpeechRecognitionSettings
import com.newoether.agora.data.SpeechRecognitionSettingsStore
import com.newoether.agora.speech.LocalSpeechEngine
import com.newoether.agora.speech.LocalSpeechModelStore
import com.newoether.agora.speech.SpeechAudioFormat
import com.newoether.agora.speech.SpeechCaptureSession
import com.newoether.agora.speech.SpeechCaptureSessions
import com.newoether.agora.speech.SpeechCloudPresets
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** Interval between loudness samples in [SpeechInputController.levels]; the UI scrolls at this rate. */
internal const val SPEECH_LEVEL_TICK_MS = 70L

/**
 * Loudness history. Sample number [total] (1-based) belongs to the instant
 * [startNanos] + [total] ticks on the System.nanoTime clock, the clock Compose frames use, so the
 * UI can place every bar from the frame time alone. [samples] keeps only the newest ones.
 */
data class SpeechLevels(
    val samples: List<Float> = emptyList(),
    val total: Int = 0,
    val startNanos: Long = 0L,
)

/** Composer voice-input phase. Both active phases are bound to the composer that started them. */
sealed interface SpeechInputState {
    data object Idle : SpeechInputState
    data class Recording(val ownerId: String) : SpeechInputState
    data class Transcribing(val ownerId: String) : SpeechInputState

    val activeOwnerId: String?
        get() = when (this) {
            Idle -> null
            is Recording -> ownerId
            is Transcribing -> ownerId
        }
}

/**
 * Speech-recognition settings plus the single composer capture session. Only one session can
 * exist process-wide because the microphone is exclusive. Results are delivered through the
 * callback given to [finish] (or [start] for the auto-stop at the duration cap) on the main thread.
 */
class SpeechInputController internal constructor(
    context: Context,
    private val store: SpeechRecognitionSettingsStore,
    private val scope: CoroutineScope,
    private val emitSnackbar: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    /** On-device model files; downloads outlive the settings page because they run on [scope]. */
    internal val localModels = LocalSpeechModelStore(appContext, scope)
    private val localEngine = LocalSpeechEngine(appContext, localModels, scope)

    val settings: StateFlow<SpeechRecognitionSettings> =
        store.settings.stateIn(scope, SharingStarted.Eagerly, SpeechRecognitionSettings())

    private val _state = MutableStateFlow<SpeechInputState>(SpeechInputState.Idle)
    val state: StateFlow<SpeechInputState> = _state.asStateFlow()

    private val _levels = MutableStateFlow(SpeechLevels())
    /** Loudness history, oldest first, one sample per [TICK_MS]. */
    val levels: StateFlow<SpeechLevels> = _levels.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private var session: SpeechCaptureSession? = null
    private var tickJob: Job? = null
    private var transcribeJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null
    private val peak = AtomicReference(0f)

    fun setEnabled(enabled: Boolean) {
        scope.launch {
            if (!enabled) cancelAll()
            store.setEnabled(enabled)
        }
    }

    fun setActive(id: String) {
        scope.launch { store.setActive(id) }
    }

    fun upsert(config: SpeechRecognitionConfig) {
        scope.launch { store.upsert(config) }
    }

    fun delete(id: String) {
        scope.launch { store.delete(id) }
    }

    /**
     * Starts capture for [ownerId]. Caller must already hold RECORD_AUDIO. [onAutoStop] receives
     * the transcript when capture hits the duration cap; it goes to the draft, never auto-sends.
     */
    fun start(ownerId: String, onAutoStop: (ownerId: String, text: String) -> Unit): Boolean {
        if (_state.value != SpeechInputState.Idle) return false
        val config = settings.value.takeIf { it.usable }?.active ?: return false
        peak.set(0f)
        _levels.value = SpeechLevels()
        _elapsedMs.value = 0L
        val created = runCatching {
            SpeechCaptureSessions.create(appContext, config, localEngine) { level ->
                peak.accumulateAndGet(level) { a, b -> maxOf(a, b) }
            }.also { it.start() }
        }.getOrElse { error ->
            reportFailure(error)
            return false
        }
        session = created
        // The system recognizer manages audio focus itself.
        if (config.type != SpeechEngineType.SYSTEM) requestFocus()
        _state.value = SpeechInputState.Recording(ownerId)
        val startedAt = System.nanoTime()
        _levels.value = SpeechLevels(startNanos = startedAt)
        val maxDurationMs = if (config.type == SpeechEngineType.CLOUD) {
            SpeechCloudPresets.maxDurationMs(config.protocol)
        } else {
            SpeechAudioFormat.MAX_DURATION_MS
        }
        tickJob = scope.launch(Dispatchers.Main.immediate) {
            var tick = 0L
            while (isActive) {
                // Absolute schedule: a late tick shortens the next wait instead of drifting.
                tick++
                delay(((startedAt + tick * TICK_NANOS - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L))
                _elapsedMs.value = (System.nanoTime() - startedAt) / 1_000_000L
                val current = _levels.value
                _levels.value = current.copy(
                    samples = (current.samples + peak.getAndSet(0f)).takeLast(MAX_LEVELS),
                    total = tick.toInt(),
                )
                if (created.reachedLimit || _elapsedMs.value >= maxDurationMs) {
                    finish(ownerId, onAutoStop)
                    break
                }
            }
        }
        return true
    }

    /** Stops capture and transcribes. Empty results raise a Snackbar instead of calling [onText]. */
    fun finish(ownerId: String, onText: (ownerId: String, text: String) -> Unit) {
        val current = _state.value as? SpeechInputState.Recording ?: return
        if (current.ownerId != ownerId) return
        val active = session ?: return
        stopTicking()
        abandonFocus()
        _state.value = SpeechInputState.Transcribing(ownerId)
        transcribeJob = scope.launch(Dispatchers.Main.immediate) {
            val result = runCatching { active.finish() }
            if (session !== active) return@launch
            session = null
            transcribeJob = null
            _state.value = SpeechInputState.Idle
            result.fold(
                onSuccess = { text ->
                    if (text.isBlank()) {
                        emitSnackbar(appContext.getString(R.string.speech_input_no_result))
                    } else {
                        onText(ownerId, text)
                    }
                },
                onFailure = { error -> if (error !is CancellationException) reportFailure(error) },
            )
        }
    }

    /** Drops the capture or in-flight transcription for [ownerId]; null matches any owner. */
    fun cancel(ownerId: String? = null, reason: String = "user") {
        val owner = _state.value.activeOwnerId ?: return
        if (ownerId != null && owner != ownerId) return
        cancelAll(reason)
    }

    fun cancelAll(reason: String = "reset") {
        if (_state.value != SpeechInputState.Idle) DebugLog.w(TAG, "capture cancelled: $reason")
        stopTicking()
        abandonFocus()
        transcribeJob?.cancel()
        transcribeJob = null
        session?.cancel()
        session = null
        _state.value = SpeechInputState.Idle
        _levels.value = SpeechLevels()
        _elapsedMs.value = 0L
    }

    fun reportPermissionDenied() {
        emitSnackbar(appContext.getString(R.string.speech_input_permission_denied))
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun reportFailure(error: Throwable) {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        emitSnackbar(appContext.getString(R.string.speech_input_failed, detail))
    }

    /**
     * Focus only pauses other players while the user dictates. Losing it never ends capture: the
     * microphone records regardless, and a music app re-taking focus (next track, replay) must not
     * discard what the user is saying. During calls the platform silences other recorders itself.
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        DebugLog.d(TAG, "audio focus change: $change")
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        // Recording does not depend on focus, so a refused request only gets logged.
        val granted = manager.requestAudioFocus(request)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) DebugLog.w(TAG, "audio focus not granted: $granted")
        focusRequest = request
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        audioManager?.abandonAudioFocusRequest(request)
    }

    private companion object {
        const val TAG = "SpeechInput"
        const val TICK_MS = SPEECH_LEVEL_TICK_MS
        const val TICK_NANOS = TICK_MS * 1_000_000L
        const val MAX_LEVELS = 96
    }
}
