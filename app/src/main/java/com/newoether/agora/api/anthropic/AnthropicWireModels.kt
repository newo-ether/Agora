package com.newoether.agora.api.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean = true,
    val thinking: AnthropicThinking? = null,
    @SerialName("output_config") val outputConfig: AnthropicOutputConfig? = null,
    val tools: List<AnthropicTool>? = null,
    @SerialName("cache_control") val cacheControl: AnthropicCacheControl? = null,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null
)

@Serializable
internal data class AnthropicCacheControl(
    val type: String = "ephemeral",
    val ttl: String = "1h",
)

@Serializable
internal data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject
)

@Serializable
internal data class AnthropicThinking(
    val type: String = "enabled",
    @SerialName("budget_tokens") val budgetTokens: Int? = null,
    val display: String? = null
)

@Serializable
internal data class AnthropicOutputConfig(
    val effort: String
)

@Serializable
internal data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContentPart>
)

@Serializable
internal data class AnthropicContentPart(
    val type: String,
    val text: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
    val source: AnthropicImageSource? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val content: String? = null
)

@Serializable
internal data class AnthropicImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String
)

@Serializable
internal data class AnthropicStreamEvent(
    val type: String,
    val delta: AnthropicDelta? = null,
    @SerialName("content_block") val contentBlock: AnthropicContentBlock? = null,
    val message: AnthropicMessageInfo? = null,
    val usage: AnthropicUsage? = null,
    val index: Int? = null,
    /** Non-standard relay outcome. `failed to generate` is a transient upstream failure. */
    val outcome: String? = null,
    // `event: error` is delivered INSIDE a 200 stream (overloaded_error, upstream 5xx, quota).
    // Without this field the event decoded to an all-null object, matched no `when` branch, and
    // the failure was silently discarded: the user only saw the generation "stop".
    val error: AnthropicStreamError? = null,
)

@Serializable
internal data class AnthropicStreamError(
    val type: String? = null,
    val message: String? = null,
)

@Serializable
internal data class AnthropicDelta(
    val text: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
    @SerialName("partial_json") val partialJson: String? = null,
    val citation: JsonElement? = null,
    val type: String? = null,
    // Protocol location of the terminal reason:
    //   {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{...}}
    // It is NOT on `message`, and `message_stop` carries no fields at all. Reading it off
    // `message` (what the previous code did) always yielded null, which is why the
    // premature-stop diagnostics never produced a single usable data point.
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_sequence") val stopSequence: String? = null,
)

@Serializable
internal data class AnthropicContentBlock(
    val type: String,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    val text: String? = null,
    val citations: List<JsonElement>? = null,
    val thinking: String? = null,
    val signature: String? = null
)

@Serializable
internal data class AnthropicMessageInfo(
    val usage: AnthropicUsage? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
internal data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int? = null,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int? = null,
)
