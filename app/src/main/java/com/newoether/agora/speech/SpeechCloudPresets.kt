package com.newoether.agora.speech

import com.newoether.agora.data.SpeechProtocols

/**
 * A cloud recognizer preset. [savedKeyProvider] names the chat Provider whose saved key can be
 * reused (case-insensitive match against the API key list); null means the vendor has no chat
 * Provider in Agora and always needs its own key.
 */
internal data class SpeechCloudPreset(
    val id: String,
    val displayName: String,
    val protocol: String,
    val baseUrl: String,
    val models: List<String>,
    val savedKeyProvider: String? = null,
    val keyRequired: Boolean = true,
    val custom: Boolean = false,
)

internal object SpeechCloudPresets {
    const val CUSTOM_ID = "custom"

    val all: List<SpeechCloudPreset> = listOf(
        SpeechCloudPreset(
            id = "openai",
            displayName = "OpenAI",
            protocol = SpeechProtocols.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            models = listOf("gpt-transcribe", "gpt-4o-transcribe", "gpt-4o-mini-transcribe", "whisper-1"),
            savedKeyProvider = "OpenAI",
        ),
        SpeechCloudPreset(
            id = "gemini",
            displayName = "Google Gemini",
            protocol = SpeechProtocols.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            models = listOf("gemini-3.5-transcribe"),
            savedKeyProvider = "Google",
        ),
        SpeechCloudPreset(
            id = "qwen",
            displayName = "Qwen (DashScope)",
            protocol = SpeechProtocols.DASHSCOPE,
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            models = listOf("qwen3-asr-flash"),
            savedKeyProvider = "Qwen",
        ),
        SpeechCloudPreset(
            id = "groq",
            displayName = "Groq",
            protocol = SpeechProtocols.OPENAI,
            baseUrl = "https://api.groq.com/openai/v1",
            models = listOf("whisper-large-v3-turbo", "whisper-large-v3"),
            savedKeyProvider = "Groq",
        ),
        SpeechCloudPreset(
            id = "siliconflow",
            displayName = "SiliconFlow",
            protocol = SpeechProtocols.OPENAI,
            baseUrl = "https://api.siliconflow.cn/v1",
            models = listOf("FunAudioLLM/SenseVoiceSmall", "TeleAI/TeleSpeechASR"),
            savedKeyProvider = "SiliconFlow",
        ),
        SpeechCloudPreset(
            id = "mistral",
            displayName = "Mistral",
            protocol = SpeechProtocols.OPENAI,
            baseUrl = "https://api.mistral.ai/v1",
            models = listOf("voxtral-mini-transcribe-3", "voxtral-small-transcribe-3"),
            savedKeyProvider = "Mistral",
        ),
        SpeechCloudPreset(
            id = "elevenlabs",
            displayName = "ElevenLabs",
            protocol = SpeechProtocols.ELEVENLABS,
            baseUrl = "https://api.elevenlabs.io/v1",
            models = listOf("scribe_v3", "scribe_v2"),
        ),
        SpeechCloudPreset(
            id = "deepgram",
            displayName = "Deepgram",
            protocol = SpeechProtocols.DEEPGRAM,
            baseUrl = "https://api.deepgram.com/v1",
            models = listOf("nova-3"),
        ),
        SpeechCloudPreset(
            id = CUSTOM_ID,
            displayName = "",
            protocol = SpeechProtocols.OPENAI,
            baseUrl = "",
            models = emptyList(),
            keyRequired = false,
            custom = true,
        ),
    )

    fun byId(id: String): SpeechCloudPreset = all.firstOrNull { it.id == id } ?: all.last()
}

/** Language choices offered in the editor; blank is automatic detection. */
internal object SpeechLanguages {
    val choices: List<Pair<String, String>> = listOf(
        "zh" to "中文",
        "en" to "English",
        "yue" to "粵語",
        "ja" to "日本語",
        "ko" to "한국어",
        "fr" to "Français",
        "de" to "Deutsch",
        "es" to "Español",
        "ru" to "Русский",
    )

    /** ISO-639 primary subtag, which every cloud protocol here accepts. */
    fun primary(language: String): String = language.trim().substringBefore('-').substringBefore('_').lowercase()

    /** Full tag for Android's recognizer, which expects a region for Chinese. */
    fun androidTag(language: String): String = when (primary(language)) {
        "" -> ""
        "zh" -> "zh-CN"
        "yue" -> "yue-Hant-HK"
        else -> language.trim()
    }
}

/** Splits the user's vocabulary field into distinct terms. */
internal fun speechVocabularyTerms(vocabulary: String): List<String> =
    vocabulary.split(',', '，', '\n', ';', '；')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
