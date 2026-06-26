package com.newoether.agora.util

object Constants {
    const val TOOL_MSG_PREFIX = "tool_"
    const val RESULT_MSG_PREFIX = "result_"
    const val TOOL_CALL_ID_PREFIX = "call_"

    /** Max characters per embedded text chunk */
    const val MAX_EMBEDDING_TEXT_LENGTH = 8000
    /** Max characters stored per embedding chunk for display */
    const val MAX_CHUNK_TEXT_LENGTH = 500
    /** Max file content to read from user-attached text files */
    const val MAX_FILE_CONTENT_READ_LENGTH = 500_000
    /** Browser-like User-Agent for web_fetch. Many sites (e.g. Wikimedia) reject the
     *  default OkHttp UA with 403, which surfaced as a "no_response" error. */
    const val WEB_FETCH_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Upper bound on raw HTML processed by web_fetch — a safety cap against pathological
     *  pages, not the content limit. Text is extracted from this whole window and then
     *  truncated by the caller's maxChars, so real article content past the boilerplate
     *  (head/scripts/nav, often tens of KB) is no longer cut off. */
    const val MAX_WEB_FETCH_HTML_LENGTH = 600_000
    /** Max characters per tool result (prevents CursorWindow 2MB overflow) */
    const val MAX_TOOL_RESULT_LENGTH = 100_000
    /** Timeout for fetching available models from a single provider (ms) */
    const val MODEL_FETCH_TIMEOUT_MS = 10_000L
    /** Search method identifier for RAG (vector/embedding) search */
    const val SEARCH_METHOD_RAG = "rag"

    // ── Provider name constants ────────────────────────────────
    const val PROVIDER_LOCAL = "Local"
    const val PROVIDER_OPENAI = "OpenAI"
    const val PROVIDER_OLLAMA = "Ollama"
    const val PROVIDER_GOOGLE = "Google"
    const val PROVIDER_ANTHROPIC = "Anthropic"
    const val PROVIDER_DEEPSEEK = "DeepSeek"
    const val PROVIDER_QWEN = "Qwen"
    const val PROVIDER_GROQ = "Groq"
    const val PROVIDER_OPEN_ROUTER = "Open Router"
    const val PROVIDER_UNKNOWN = "Unknown"
    /** Placeholder model ID used as StateFlow/DataStore cold-start fallback and
     *  template preview sample. NOT the real default model — it is overwritten
     *  as soon as the user selects a model or DataStore loads the persisted value. */
    const val EXAMPLE_MODEL_ID = "gemini-1.5-flash"
}
