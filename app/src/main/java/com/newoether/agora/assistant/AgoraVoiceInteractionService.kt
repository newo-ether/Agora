package com.newoether.agora.assistant

import android.service.voice.VoiceInteractionService

/**
 * The always-on voice-interaction service. Its presence (gated by
 * BIND_VOICE_INTERACTION) qualifies Agora as a full voice assistant and lets it
 * hold the ASSISTANT role via the voice-interaction path. The actual work
 * happens in [AgoraVoiceInteractionSessionService] / [AgoraVoiceInteractionSession].
 */
class AgoraVoiceInteractionService : VoiceInteractionService()
