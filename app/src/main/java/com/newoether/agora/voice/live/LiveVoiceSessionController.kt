package com.newoether.agora.voice.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Observable realtime-voice session state. */
enum class LiveVoiceState { IDLE, CONNECTING, ACTIVE }

/** A turn event surfaced to observers (UI) during a live session. */
sealed interface LiveVoiceTurn {
    data class UserTranscript(val text: String) : LiveVoiceTurn
    data class AssistantTranscript(val text: String) : LiveVoiceTurn
    data class Error(val message: String) : LiveVoiceTurn
}

/**
 * Self-contained owner of one Live API voice session (direct Google route). Manual-DI friendly:
 * construct once, call [start] with a resolved [LiveVoiceConfig] + API key, [stop] to end.
 *
 * Minimal first version: audio in/out + transcripts, no tool calling and no DB persistence (a live
 * voice conversation is ephemeral). Playback is driven inside [LiveVoiceStreamer] via the audio
 * manager; this controller just manages lifecycle and surfaces state/turns.
 *
 * Ported/adapted from AIOPE's VoiceSessionController, dropping its Hilt + DB + gateway coupling.
 */
class LiveVoiceSessionController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // readTimeout 0 = no timeout: the WS stays open for the whole conversation.
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var streamer: LiveVoiceStreamer? = null
    private var audio: LiveVoiceAudioManager? = null
    private var collectJob: Job? = null

    private val _state = MutableStateFlow(LiveVoiceState.IDLE)
    val state: StateFlow<LiveVoiceState> = _state.asStateFlow()

    // Live video streaming (camera/screen) over the active session. Null when no source is active.
    private var videoManager: VideoStreamManager? = null
    private val _videoSource = MutableStateFlow(LiveVideoSource.NONE)
    val videoSource: StateFlow<LiveVideoSource> = _videoSource.asStateFlow()

    val turns = MutableSharedFlow<LiveVoiceTurn>(replay = 0, extraBufferCapacity = 64)

    // Accumulated running transcripts for live display. Reset on each new session.
    private val _userTranscript = MutableStateFlow("")
    val userTranscript: StateFlow<String> = _userTranscript.asStateFlow()
    private val _assistantTranscript = MutableStateFlow("")
    val assistantTranscript: StateFlow<String> = _assistantTranscript.asStateFlow()

    val isActive: Boolean get() = _state.value != LiveVoiceState.IDLE

    /**
     * Persistence hook (AIOPE messageSink parity). Invoked with the PLAIN-TEXT transcripts of one
     * completed exchange (what the user said, what the assistant replied) at each turn boundary and
     * once more on [stop] for any pending tail. The sink decides where/how to persist (e.g. into the
     * current chat conversation). Both strings may be blank; the sink should no-op on an empty pair.
     */
    var messageSink: ((userText: String, assistantText: String) -> Unit)? = null

    // Per-turn buffers: what the user said and what the assistant replied in the CURRENT turn.
    // Separate from the display StateFlows so persistence captures the user text before it is
    // cleared for the next utterance on TurnComplete.
    private val userTurnBuffer = StringBuilder()
    private val assistantTurnBuffer = StringBuilder()

    /**
     * Start a session. [apiKey] is the resolved key for the config's provider. No-op if a session
     * is already running or the config is incomplete.
     */
    @Synchronized
    fun start(context: android.content.Context, config: LiveVoiceConfig, apiKey: String, toolBridge: LiveToolBridge? = null) {
        if (_state.value != LiveVoiceState.IDLE) return
        if (apiKey.isBlank() || config.model.isBlank()) {
            scope.launch { turns.emit(LiveVoiceTurn.Error("Live Voice is not fully configured.")) }
            return
        }
        _state.value = LiveVoiceState.CONNECTING
        _userTranscript.value = ""
        _assistantTranscript.value = ""
        userTurnBuffer.setLength(0)
        assistantTurnBuffer.setLength(0)
        // Playback starts inside startCapture() once the shared audio session id is known, so the
        // AudioTrack is built once with the right session (no mid-session rebuild / underrun storm).
        val am = LiveVoiceAudioManager(context.applicationContext).also { audio = it }
        val s = LiveVoiceStreamer(
            okHttp = okHttp,
            apiKey = apiKey,
            modelId = config.model,
            audioManager = am,
            systemInstruction = config.systemInstruction,
            voiceName = config.voiceName.ifBlank { LiveVoiceConfig.DEFAULT_VOICE },
            toolBridge = toolBridge,
        ).also { streamer = it }

        collectJob = scope.launch {
            s.createStream().collect { event ->
                when (event) {
                    is LiveStreamEvent.Connected -> _state.value = LiveVoiceState.ACTIVE
                    is LiveStreamEvent.Disconnected -> stop()
                    is LiveStreamEvent.Error -> {
                        turns.emit(LiveVoiceTurn.Error(event.message)); stop()
                    }
                    is LiveStreamEvent.InputTranscription -> {
                        _userTranscript.value += event.text
                        userTurnBuffer.append(event.text)
                        turns.emit(LiveVoiceTurn.UserTranscript(event.text))
                    }
                    is LiveStreamEvent.OutputTranscription -> {
                        _assistantTranscript.value += event.text
                        assistantTurnBuffer.append(event.text)
                        turns.emit(LiveVoiceTurn.AssistantTranscript(event.text))
                    }
                    is LiveStreamEvent.TurnComplete -> {
                        // End of the assistant's reply: persist this completed exchange, then clear
                        // BOTH running transcripts. The persisted chat messages are now the record;
                        // the live bar should only ever show the CURRENT in-progress turn, otherwise
                        // the finished turn renders twice (once in the bar, once as a chat message).
                        flushExchange()
                        _userTranscript.value = ""
                        _assistantTranscript.value = ""
                    }
                    else -> Unit // AudioChunk handled by the audio manager
                }
            }
        }
    }

    /** Send a typed message into the active session (optional text input alongside voice). */
    fun sendText(text: String) {
        streamer?.sendText(text)
        streamer?.endTurn()
    }

    /** Lazily build the video manager bound to the active streamer. */
    @Synchronized
    private fun ensureVideoManager(context: android.content.Context): VideoStreamManager {
        val existing = videoManager
        if (existing != null) return existing
        val mgr = VideoStreamManager(context.applicationContext) { b64 -> streamer?.sendVideoFrame(b64) }
        videoManager = mgr
        return mgr
    }

    /** Start streaming camera frames to the model (no-op unless a session is active). */
    @Synchronized
    fun startCameraStream(context: android.content.Context, front: Boolean) {
        if (_state.value != LiveVoiceState.ACTIVE) return
        ensureVideoManager(context).startCamera(front)
        _videoSource.value = LiveVideoSource.CAMERA
    }

    /** Start streaming device-screen frames; [projection] comes from the consent Activity. */
    @Synchronized
    fun startScreenStream(context: android.content.Context, projection: android.media.projection.MediaProjection) {
        if (_state.value != LiveVoiceState.ACTIVE) {
            runCatching { projection.stop() }
            return
        }
        ensureVideoManager(context).startScreen(projection)
        _videoSource.value = LiveVideoSource.SCREEN
    }

    /** Stop any active video source (keeps the audio session running). */
    @Synchronized
    fun stopVideoStream() {
        videoManager?.stop()
        _videoSource.value = LiveVideoSource.NONE
    }

    @Synchronized
    fun stop() {
        flushExchange()
        videoManager?.stop(); videoManager = null
        _videoSource.value = LiveVideoSource.NONE
        collectJob?.cancel(); collectJob = null
        streamer?.stop(); streamer = null
        audio = null
        _state.value = LiveVoiceState.IDLE
    }

    /** Hand the current turn's buffered transcripts to [messageSink] and reset for the next turn. */
    private fun flushExchange() {
        val user = userTurnBuffer.toString().trim()
        val assistant = assistantTurnBuffer.toString().trim()
        userTurnBuffer.setLength(0)
        assistantTurnBuffer.setLength(0)
        if (user.isEmpty() && assistant.isEmpty()) return
        messageSink?.invoke(user, assistant)
    }
}
