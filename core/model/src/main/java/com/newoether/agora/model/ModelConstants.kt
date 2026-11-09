package com.newoether.agora.model

/** Stable identifiers shared by persisted messages and provider model names. */
object ModelConstants {
    const val TOOL_MSG_PREFIX = "tool_"
    const val RESULT_MSG_PREFIX = "result_"
    const val COMPACT_MSG_PREFIX = "compact_"
    const val TOOL_CALL_ID_PREFIX = "call_"

    const val PROVIDER_LOCAL = "Local"
    const val PROVIDER_OLLAMA = "Ollama"
    const val PROVIDER_OPEN_ROUTER = "Open Router"
    const val PROVIDER_OPENCODE_GO = "OpenCode Go"
    const val PROVIDER_OPENAI = "OpenAI"
    const val PROVIDER_GOOGLE = "Google"
    const val PROVIDER_ANTHROPIC = "Anthropic"
    const val PROVIDER_DEEPSEEK = "DeepSeek"
    const val PROVIDER_QWEN = "Qwen"
    const val PROVIDER_GROQ = "Groq"
    const val PROVIDER_UNKNOWN = "Unknown"
}
