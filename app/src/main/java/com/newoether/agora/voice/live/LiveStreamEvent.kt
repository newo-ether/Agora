package com.newoether.agora.voice.live

/**
 * Stream events for a realtime (Live API) voice session.
 *
 * Ported from AIOPE (feature-chat/engine/StreamEvent.kt), trimmed to the direct Google
 * BidiGenerateContent path Agora uses — no gateway-specific events removed since the set is shared.
 */
sealed class LiveStreamEvent {
    data class TextDelta(val text: String) : LiveStreamEvent()
    data class AudioChunk(val pcmData: ByteArray) : LiveStreamEvent()
    data object TurnComplete : LiveStreamEvent()
    data object Interrupted : LiveStreamEvent()
    data class InputTranscription(val text: String) : LiveStreamEvent()
    data class OutputTranscription(val text: String) : LiveStreamEvent()
    data class Error(val message: String) : LiveStreamEvent()
    data object Connected : LiveStreamEvent()
    data object Disconnected : LiveStreamEvent()
}

/** Audio capture/playback format for the live session. Google Live API: 16 kHz in, 24 kHz out. */
data class LiveAudioConfig(
    val inputSampleRate: Int = 16000,
    val outputSampleRate: Int = 24000,
)
