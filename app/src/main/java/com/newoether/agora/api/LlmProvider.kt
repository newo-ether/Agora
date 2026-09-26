package com.newoether.agora.api

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.model.ModelThinkingCapabilityOverride
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.api.util.prepareMessages
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
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
     *  [maxAttempts] is the retry budget and excludes the initial request. */
    data class Retrying(val attempt: Int, val maxAttempts: Int) : StreamEvent()
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

suspend fun ProviderConfig.resolveRequest(messages: List<ChatMessage>): ProviderRequestInput =
    requestResolver?.resolve(messages, this)
        ?: ProviderRequestInput(
            messages = prepareMessages(
                messages,
                maxContextWindow,
                includeAssistantReasoning = includeAssistantReasoning,
            ),
            systemPrompt = systemPrompt,
        )

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
    /** User correction for the selected model's documented thinking capability. */
    val thinkingCapabilityOverride: ModelThinkingCapabilityOverride? = null,
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

@Serializable
data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, ToolProperty>,
    val required: List<String> = emptyList()
)

@Serializable
data class ToolProperty(
    val type: String,
    val description: String,
    val items: ToolProperty? = null
)

@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: OpenAiStreamOptions? = null,
    val tools: List<ToolDefinition>? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("thinking") val thinking: OpenAiThinking? = null,
    @SerialName("enable_thinking") val enableThinking: Boolean? = null,
    @SerialName("thinking_budget") val thinkingBudget: Int? = null,
    val reasoning: OpenAiReasoning? = null,
    /** Groq: `false` returns no reasoning for a model that cannot turn reasoning off. */
    @SerialName("include_reasoning") val includeReasoning: Boolean? = null,
    val plugins: List<OpenAiPlugin>? = null,
    @SerialName("service_tier") val serviceTier: String? = null,
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Float? = null,
    @SerialName("presence_penalty") val presencePenalty: Float? = null,
    @SerialName("prompt_cache_key") val promptCacheKey: String? = null,
)

@Serializable
data class OpenAiPlugin(
    val id: String
)

/** DeepSeek thinking toggle: `{"thinking": {"type": "enabled" | "disabled"}}`. */
@Serializable
data class OpenAiThinking(
    val type: String,
)

@Serializable
data class OpenAiReasoning(
    val effort: String? = null,
    val summary: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val enabled: Boolean? = null
)

@Serializable
data class OpenAiStreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true
)

@Serializable
data class OpenAiResponsesRequest(
    val model: String,
    val input: List<JsonObject>,
    val stream: Boolean = true,
    val tools: List<OpenAiResponseTool>? = null,
    val reasoning: OpenAiReasoning? = null,
    @SerialName("service_tier") val serviceTier: String? = null,
    val temperature: Float? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("prompt_cache_key") val promptCacheKey: String? = null,
)

@Serializable
data class OpenAiResponseInputItem(
    val type: String,
    val id: String? = null,
    val role: String? = null,
    val content: List<OpenAiResponseInputContent>? = null,
    val summary: JsonElement? = null,
    @SerialName("encrypted_content") val encryptedContent: String? = null,
    @SerialName("call_id") val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val output: JsonElement? = null,
)

@Serializable
data class OpenAiResponseInputContent(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val detail: String? = null,
)

@Serializable
data class OpenAiResponseTool(
    val type: String = "function",
    val name: String? = null,
    val description: String? = null,
    val parameters: ToolParameters? = null,
)

@Serializable
data class OpenAiResponseStreamEvent(
    val type: String,
    val delta: String? = null,
    val arguments: String? = null,
    val name: String? = null,
    @SerialName("item_id") val itemId: String? = null,
    @SerialName("output_index") val outputIndex: Int? = null,
    @SerialName("content_index") val contentIndex: Int? = null,
    @SerialName("summary_index") val summaryIndex: Int? = null,
    @SerialName("sequence_number") val sequenceNumber: Int? = null,
    val item: JsonObject? = null,
    val annotation: OpenAiResponseAnnotation? = null,
    val response: OpenAiResponseEnvelope? = null,
    val error: OpenAiError? = null,
)

@Serializable
data class OpenAiResponseAnnotation(
    val type: String,
    val title: String? = null,
    val url: String? = null,
    @SerialName("start_index") val startIndex: Int? = null,
    @SerialName("end_index") val endIndex: Int? = null,
    val index: Int? = null,
    @SerialName("file_id") val fileId: String? = null,
    val filename: String? = null,
    @SerialName("container_id") val containerId: String? = null,
)

@Serializable
data class OpenAiResponseOutputItem(
    val id: String? = null,
    val type: String? = null,
    val summary: JsonElement? = null,
    @SerialName("encrypted_content") val encryptedContent: String? = null,
    @SerialName("call_id") val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class OpenAiResponseEnvelope(
    val status: String? = null,
    val error: OpenAiError? = null,
    @SerialName("incomplete_details") val incompleteDetails: OpenAiResponseIncompleteDetails? = null,
    val usage: OpenAiResponseUsage? = null,
)

@Serializable
data class OpenAiResponseIncompleteDetails(val reason: String? = null)

@Serializable
data class OpenAiResponseUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Int? = null,
    @SerialName("input_tokens_details") val inputTokensDetails: OpenAiResponseInputTokenDetails? = null,
    @SerialName("output_tokens_details") val outputTokensDetails: OpenAiResponseOutputTokenDetails? = null,
)

@Serializable
data class OpenAiResponseInputTokenDetails(
    @SerialName("cached_tokens") val cachedTokens: Int? = null,
)

@Serializable
data class OpenAiResponseOutputTokenDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null,
)

internal fun OpenAiResponseUsage.toTokenUsage(): TokenUsage {
    val input = inputTokens?.coerceAtLeast(0)
    val cached = inputTokensDetails?.cachedTokens?.coerceAtLeast(0)
    val output = outputTokens?.coerceAtLeast(0)
    return TokenUsage(
        totalTokenCount = (totalTokens ?: listOfNotNull(input, output).sum()).coerceAtLeast(0),
        inputTokenCount = input,
        cachedInputTokenCount = cached,
        cacheWriteInputTokenCount = cacheWriteTokens?.coerceAtLeast(0),
        uncachedInputTokenCount = if (input != null && cached != null) {
            (input - cached).coerceAtLeast(0)
        } else null,
        outputTokenCount = output,
        reasoningTokenCount = outputTokensDetails?.reasoningTokens?.coerceAtLeast(0),
    )
}

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: List<OpenAiContentPart>? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiRequestToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    /** Provider-scoped raw Responses output items restored only by the Responses transport. */
    @Transient val responseOutputItems: List<JsonObject>? = null,
    @Transient val responseOutputItemProvider: String? = null,
)

@Serializable
data class OpenAiRequestToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiRequestFunction
)

@Serializable
data class OpenAiRequestFunction(
    val name: String,
    val arguments: String
)

@Serializable
data class OpenAiContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: OpenAiImageUrl? = null
)

@Serializable
data class OpenAiImageUrl(
    val url: String
)



@Serializable
data class OpenAiTool(
    val type: String,
    val function: OpenAiFunction? = null
)

@Serializable
data class OpenAiFunction(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null
)

@Serializable
data class OpenAiStreamResponse(
    val id: String? = null,
    val choices: List<OpenAiChoice>? = null,
    /** Non-standard relay outcome. Some gateways return `failed to generate` here on HTTP 200. */
    val outcome: String? = null,
    // Relays deliver a mid-stream failure as a bare {"error":{...}} chunk on an HTTP 200 response.
    // Without this field `ignoreUnknownKeys` discarded it, choices stayed null, and the generation
    // simply stopped with no diagnostic at all.
    val error: OpenAiError? = null,
    val usage: OpenAiUsage? = null
)

@Serializable
data class OpenAiChoice(
    val index: Int,
    val delta: OpenAiDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class OpenAiDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    // Bare `reasoning` string: OpenRouter and many Claude/DeepSeek-in-OpenAI relays emit this
    // instead of (or duplicated alongside) reasoning_content. Read as a fallback so a
    // non-standard endpoint's thinking is never silently dropped.
    val reasoning: String? = null,
    @SerialName("reasoning_details") val reasoningDetails: List<OpenAiReasoningDetail>? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null
)

@Serializable
data class OpenAiReasoningDetail(
    val type: String? = null,
    val text: String? = null
)

@Serializable
data class OpenAiToolCall(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAiFunctionCall? = null
)

@Serializable
data class OpenAiFunctionCall(
    val name: String? = null,
    val arguments: JsonElement? = null
)

@Serializable
data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("prompt_tokens_details") val promptTokensDetails: OpenAiPromptTokensDetails? = null,
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int? = null,
    @SerialName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int? = null,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Int? = null,
    @SerialName("completion_tokens_details") val completionTokensDetails: OpenAiCompletionTokensDetails? = null
)

@Serializable
data class OpenAiPromptTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Int? = null
)

@Serializable
data class OpenAiCompletionTokensDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null
)

internal fun OpenAiUsage.toTokenUsage(): TokenUsage {
    val input = promptTokens?.coerceAtLeast(0)
    val cached = (
        promptCacheHitTokens
            ?: promptTokensDetails?.cachedTokens
        )?.coerceAtLeast(0)
    val uncached = (
        promptCacheMissTokens
            ?: if (input != null && cached != null) {
                (input - cached).coerceAtLeast(0)
            } else {
                null
            }
        )?.coerceAtLeast(0)
    val output = completionTokens?.coerceAtLeast(0)
    val reasoning = completionTokensDetails?.reasoningTokens?.coerceAtLeast(0)
    val derivedTotal = listOfNotNull(input, output).sum()
    return TokenUsage(
        totalTokenCount = (totalTokens ?: derivedTotal).coerceAtLeast(0),
        inputTokenCount = input ?: if (cached != null && uncached != null) {
            TokenUsage.addCounts(cached, uncached)
        } else {
            null
        },
        cachedInputTokenCount = cached,
        cacheWriteInputTokenCount = cacheWriteTokens?.coerceAtLeast(0),
        uncachedInputTokenCount = uncached,
        outputTokenCount = output,
        reasoningTokenCount = reasoning,
    )
}

@Serializable
data class OpenAiModelListResponse(
    val data: List<OpenAiModelInfo>,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("last_id") val lastId: String? = null
)

@Serializable
data class OpenAiModelInfo(val id: String)

@Serializable
data class OpenAiErrorResponse(val error: OpenAiError)

@Serializable
data class OpenAiError(val message: String, val type: String? = null, val code: String? = null)

class PendingToolCall(
    val streamKey: String = "call_stream_${java.util.UUID.randomUUID()}",
    var id: String = "",
    var name: String = "",
    /** Snapshot-tolerant accumulator: a relay that resends the whole value in every delta, or
     *  interleaves empty placeholder deltas, must not corrupt or erase the arguments. */
    val args: com.newoether.agora.api.util.ToolArgumentAccumulator =
        com.newoether.agora.api.util.ToolArgumentAccumulator()
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
