package com.newoether.agora.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
enum class VoiceTypingMode {
    ANDROID, ANDROID_OFFLINE, OPENAI, GROQ, COMPATIBLE;
    val usesApi: Boolean get() = this == OPENAI || this == GROQ || this == COMPATIBLE
    val defaultModel: String get() = when (this) {
        OPENAI -> "gpt-4o-mini-transcribe"
        GROQ -> "whisper-large-v3-turbo"
        else -> "whisper-1"
    }
    val defaultUrl: String get() = when (this) {
        OPENAI -> "https://api.openai.com/v1"
        GROQ -> "https://api.groq.com/openai/v1"
        else -> ""
    }
}

/** Stores only a reference to an existing credential; no key or transcript enters preferences. */
@Serializable
data class VoiceTypingConfig(
    val enabled: Boolean = false,
    val mode: VoiceTypingMode = VoiceTypingMode.ANDROID,
    val apiKeyId: String? = null,
    val baseUrl: String = "",
    val model: String = "",
    val language: String = "",
) {
    fun select(mode: VoiceTypingMode) = copy(mode = mode, apiKeyId = null, baseUrl = mode.defaultUrl, model = mode.defaultModel)
    fun transcriptionUrl(): HttpUrl {
        val url = baseUrl.trim().ifBlank { mode.defaultUrl }.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Invalid transcription URL" }
        require(url.isHttps || url.host in setOf("localhost", "127.0.0.1", "::1")) { "Use HTTPS for remote transcription" }
        return if (url.encodedPath.trimEnd('/').endsWith("/audio/transcriptions")) url else
            url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/audio/transcriptions").build()
    }
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(text: String?): VoiceTypingConfig = text?.let { runCatching { json.decodeFromString<VoiceTypingConfig>(it) }.getOrNull() } ?: VoiceTypingConfig()
    }
}
