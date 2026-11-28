package com.newoether.agora.voice.live

import kotlinx.serialization.Serializable

/**
 * User-selected Live Voice (realtime bidirectional voice) configuration, persisted in settings.
 *
 * [provider] is the provider display name (initially only Google is supported by the direct
 * BidiGenerateContent route); [model] is a Live-capable model id selected from that provider's
 * model list; [voiceName] is a Google prebuilt voice (Aoede, Puck, Charon, Kore, Fenrir, ...).
 */
@Serializable
data class LiveVoiceConfig(
    val enabled: Boolean = false,
    val provider: String = "",
    val model: String = "",
    val voiceName: String = DEFAULT_VOICE,
    val systemInstruction: String = "",
) {
    companion object {
        const val DEFAULT_VOICE = "Aoede"

        /** Google prebuilt Live API voices. */
        val GOOGLE_VOICES = listOf("Aoede", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Zephyr")

        /**
         * Known Google Live-API model ids (bidiGenerateContent). Seeded so the picker is populated
         * even before a model refetch; unioned with whatever the provider's live model list returns.
         */
        val GOOGLE_LIVE_MODELS = listOf(
            "gemini-3.8-live",
            "gemini-3.8-live-extended-thinking",
            "gemini-3.1-flash-live-preview",
            "gemini-3.5-live-translate-preview",
            "gemini-3.5-transcribe-live",
            "gemini-2.5-flash-native-audio-latest",
            "gemini-2.5-flash-native-audio-preview-12-2025",
        )

        /**
         * Heuristic for Live-capable model ids so the model picker can filter. Google's Live API
         * models carry "live" in the id (e.g. gemini-2.0-flash-live-001, gemini-2.5-flash-...-live).
         */
        fun looksLiveCapable(modelId: String): Boolean {
            val id = modelId.lowercase()
            return id.contains("live") || id.contains("realtime") || id.contains("native-audio")
        }
    }
}
