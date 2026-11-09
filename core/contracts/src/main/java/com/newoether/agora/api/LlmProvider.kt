package com.newoether.agora.api

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.model.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

sealed class StreamEvent {
    data class TextChunk(val text: String) : StreamEvent()
    data class CitationUpdate(val citation: CitationRecord) : StreamEvent()
    data class ThoughtChunk(val thought: String, val title: String? = null, val signature: String? = null) : StreamEvent()
    data class UsageUpdate(val usage: TokenUsage) : StreamEvent() {
        constructor(tokenCount: Int, thoughtsTokenCount: Int = 0) : this(
            TokenUsage(
                totalTokenCount = tokenCount.coerceAtLeast(0),
                reasoningTokenCount = thoughtsTokenCount
                    .takeIf { it > 0 },
            )
        )

        val tokenCount: Int
            get() = usage.totalTokenCount

        val thoughtsTokenCount: Int
            get() = usage.reasoningTokenCount ?: 0
    }
    data class Error(val error: GenerationError) : StreamEvent() {
        val message: String get() = error.userMessage()
    }
    /**
     * Full accumulated snapshot of a tool call while the model is still writing it.
     *
     * [streamKey] is stable even when a compatible provider sends the protocol [id] in a later
     * delta. UI code keys the live segment by [streamKey], then replaces its protocol id when the
     * matching [ToolCallRequest] completes.
     */
    data class ToolCallUpdate(
        val streamKey: String,
        val id: String?,
        val name: String,
        val arguments: String,
        val signature: String? = null,
    ) : StreamEvent()

    /**
     * Display-only progress for a tool executed inside the provider transport.
     *
     * Unlike [ToolCallRequest], this event never authorizes local execution or a continuation
     * round. A non-null [result] closes the provider-hosted call into a terminal UI segment.
     */
    data class HostedToolCallUpdate(
        val streamKey: String,
        val name: String,
        val arguments: String,
        val result: String? = null,
        val isError: Boolean = false,
    ) : StreamEvent()

    data class ToolCallRequest(
        val id: String,
        val name: String,
        val arguments: String,
        val signature: String? = null,
        val streamKey: String = id,
        /** Raw Responses output items that must precede this call during local-tool continuation. */
        val responseOutputItems: List<JsonObject> = emptyList(),
    ) : StreamEvent()
    data class ToolCallsRequest(val calls: List<ToolCallRequest>) : StreamEvent()
    /** Emitted before one provider retry. [attempt] is the 1-based retry number, while
     *  [maxAttempts] is the retry budget and excludes the initial request.
     *  [resetCurrentAttempt] replaces only this request's failed partial output. */
    data class Retrying(
        val attempt: Int,
        val maxAttempts: Int,
        val resetCurrentAttempt: Boolean = false,
    ) : StreamEvent()
}

data class ProviderRequestInput(
    val messages: List<ChatMessage>,
    val systemPrompt: String?,
)

fun interface ProviderRequestResolver {
    suspend fun resolve(
        messages: List<ChatMessage>,
        config: ProviderConfig,
    ): ProviderRequestInput
}

data class ProviderConfig(
    val apiKey: String,
    val modelId: String,
    val systemPrompt: String? = null,
    /** Estimated provider-visible conversation token budget. */
    val maxContextWindow: Int = ContextBudget.DEFAULT_TOKENS,
    val codeExecutionEnabled: Boolean = false,
    val googleSearchEnabled: Boolean = false,
    val thinkingEnabled: Boolean = true,
    val thinkingLevel: String = "medium",
    val thinkingBudgetEnabled: Boolean = false,
    val thinkingBudgetTokens: Int = 4096,
    val openAiServiceTier: String? = null,
    val responsesApiEnabled: Boolean = false,
    val anthropicCacheEnabled: Boolean = true,
    val anthropicCacheTtl: String = "1h",
    val openAiWebSearchEnabled: Boolean = false,
    val baseUrl: String? = null,
    val tools: List<ToolDefinition>? = null,
    val userPrepend: String? = null,
    val userPostpend: String? = null,
    val includeImages: Boolean = true,
    /** Counts and retains ordinary assistant thought segments that this request serializes. */
    val includeAssistantReasoning: Boolean = false,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    /** Stable cache partition key. Set only for the official OpenAI provider. */
    val promptCacheKey: String? = null,
    /** Stable per-conversation session id. Sent as the `x-opencode-session` header. */
    val sessionId: String? = null,
    /** Resolves ordinary-generation prompt variables and rollout immediately before dispatch. */
    val requestResolver: ProviderRequestResolver? = null,
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: ToolFunction
)

@Serializable
data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: ToolParameters
)

@Serializable(with = ToolParametersSerializer::class)
data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, ToolProperty>,
    val required: List<String> = emptyList(),
    /**
     * The parameter schema exactly as an external tool source (an MCP server) declared it. When set,
     * every provider sends this schema instead of one rebuilt from [properties], so nested fields,
     * maps, enums and other JSON Schema keywords survive; [properties] and [required] then only
     * summarize its top level for token estimates and validation.
     */
    val schema: JsonObject? = null,
)

@Serializable
data class ToolProperty(
    val type: String,
    val description: String,
    /** For an array: the schema of one element. */
    val items: ToolProperty? = null,
    /** For an object: the schema of each field it carries. */
    val properties: Map<String, ToolProperty>? = null,
    /** For an object: which of its own fields are mandatory. */
    val required: List<String>? = null,
)

interface LlmProvider {
    val name: String
    val defaultBaseUrl: String
    val nativeTextParsingAuthoritative: Boolean
        get() = false
    val baseUrlPlaceholder: String
        get() = defaultBaseUrl

    fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent>

    suspend fun fetchModels(apiKey: String, baseUrl: String? = null): List<String>
}
