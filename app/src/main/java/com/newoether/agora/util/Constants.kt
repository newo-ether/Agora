package com.newoether.agora.util

object Constants {
    const val TOOL_MSG_PREFIX = "tool_"
    const val RESULT_MSG_PREFIX = "result_"
    const val COMPACT_MSG_PREFIX = "compact_"
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
    /** Max characters per tool result. Bounds a *single* tool result, but a model message row
     *  aggregates many tool rounds into one toolCallJson column — see
     *  MAX_PERSISTED_SEGMENTS_BYTES for the aggregate bound. */
    const val MAX_TOOL_RESULT_LENGTH = 100_000
    /** UTF-8 byte budget for the serialized segment column inside one message row. Text and
     *  thoughts have separate conservative caps below, so their combined worst case remains
     *  comfortably below Android's roughly 2 MB CursorWindow row limit. */
    const val MAX_PERSISTED_SEGMENTS_BYTES = 600_000
    /** Max UTF-16 code units persisted in either messages.text or messages.thoughts. At worst this
     *  is about 300 KB of UTF-8, leaving room for both columns, segments, and row metadata. */
    const val MAX_PERSISTED_TEXT_CHARS = 100_000
    /** Timeout for fetching available models from a single provider (ms) */
    const val MODEL_FETCH_TIMEOUT_MS = 10_000L
    /** Connection establishment and request writes should fail fast; long-running response work is
     *  governed separately by TOOL_EXECUTION_TIMEOUT_MS. */
    const val NETWORK_CONNECT_TIMEOUT_MS = 30_000L
    /** Total budget for short remote operations such as web requests and MCP control messages. */
    const val NETWORK_TOOL_TIMEOUT_MS = 60_000L
    /** Wall-clock budget for a single tool execution. Tools run inline on the stream-consuming
     *  coroutine (flow.emit suspends the producer until the collector returns), so a tool that
     *  blocks forever hangs the whole generation. This bound downgrades that to a recoverable
     *  tool error instead of a permanent hang (#49). Overridable via GenerationContext. */
    const val TOOL_EXECUTION_TIMEOUT_SECONDS = 300
    const val TOOL_EXECUTION_TIMEOUT_MS = TOOL_EXECUTION_TIMEOUT_SECONDS * 1_000L
    /** Image generation alone may wait up to ten minutes for generation and download. */
    const val IMAGE_GENERATION_TIMEOUT_MS = 600_000L
    /** Wall-clock budget for a shell-command confirmation prompt. The await hangs forever if the
     *  Activity is backgrounded/rebuilt (dialog never renders) or in a headless automation run,
     *  so failing safe (refusing) after this timeout unblocks the stream (#49). */
    const val SHELL_CONFIRM_TIMEOUT_MS = 300_000L
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
    const val PROVIDER_OPENCODE_GO = "OpenCode Go"
    const val PROVIDER_UNKNOWN = "Unknown"
    /** Placeholder model ID used as StateFlow/DataStore cold-start fallback and
     *  template preview sample. NOT the real default model — it is overwritten
     *  as soon as the user selects a model or DataStore loads the persisted value. */
    const val EXAMPLE_MODEL_ID = "gemini-1.5-flash"
}
