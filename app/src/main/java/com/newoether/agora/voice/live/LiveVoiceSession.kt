package com.newoether.agora.voice.live

/**
 * Process-scoped single owner of the live-voice session, so the composer phone button and any
 * other surface (e.g. the assistant overlay) share ONE session and can't open two at once.
 */
object LiveVoiceSession {
    val controller: LiveVoiceSessionController by lazy { LiveVoiceSessionController() }
}
