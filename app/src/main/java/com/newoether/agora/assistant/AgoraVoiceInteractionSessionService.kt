package com.newoether.agora.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Produces Agora's assistant session (captures screen context, runs a turn). */
class AgoraVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AgoraVoiceInteractionSession(this)
}
