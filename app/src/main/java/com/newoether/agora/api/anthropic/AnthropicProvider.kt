package com.newoether.agora.api.anthropic

import com.newoether.agora.api.*

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.api.util.RequestFormatException
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request-shape generations of the Claude model line. Unknown future families stay conservative:
 * adaptive when enabled, omitted when disabled, and never receive legacy sampling parameters. */
internal enum class ClaudeFamily {
    NO_THINKING,
    BUDGET_THINKING,
    TRANSITIONAL_4_6,
    CURRENT_ADAPTIVE,
    CURRENT_DEFAULT_ON,
    CURRENT_ALWAYS_THINKING,
}

internal fun classifyClaudeFamily(modelName: String): ClaudeFamily {
    val m = modelName.lowercase()
    if (!m.startsWith("claude")) return ClaudeFamily.CURRENT_ADAPTIVE
    if (m in setOf("claude-fable-5", "claude-mythos-5", "claude-mythos-preview")) {
        return ClaudeFamily.CURRENT_ALWAYS_THINKING
    }
    if (m in setOf("claude-opus-5", "claude-sonnet-5")) {
        return ClaudeFamily.CURRENT_DEFAULT_ON
    }
    // 3.0 / 3.5 predate extended thinking entirely.
    if (listOf("claude-3-opus", "claude-3-sonnet", "claude-3-haiku", "claude-3-5-")
            .any { m.startsWith(it) }
    ) return ClaudeFamily.NO_THINKING
    // 4.6: adaptive preferred; deprecated `budget_tokens` still functional (transitional).
    // Checked before the dated-4.x markers so a dated 4.6 id can't fall into the budget list.
    if (m.contains("4-6") || m.contains("4.6")) return ClaudeFamily.TRANSITIONAL_4_6
    // Closed list of budget_tokens generations: 3.7, 4.0 (incl. dated claude-*-4-2025xxxx),
    // 4.1, and the 4.5 tier (opus/sonnet/haiku).
    if (listOf("claude-3-7", "-4-0", "-4-1", "-4-5", "4.0", "4.1", "4.5", "-4-2025")
            .any { m.contains(it) }
    ) return ClaudeFamily.BUDGET_THINKING
    return ClaudeFamily.CURRENT_ADAPTIVE
}

class AnthropicProvider(
    override val name: String = Constants.PROVIDER_ANTHROPIC,
    override val defaultBaseUrl: String = "https://api.anthropic.com/v1",
) : LlmProvider {

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val modelName = config.modelId

        // ── Model-generation classification ─────────────────────────────────
        // The legacy and current default-on/always-on sets are CLOSED lists. Every model not
        // matched below is treated conservatively: adaptive when enabled and no sampling params.
        // Rationale (API contract): `budget_tokens` and `temperature`/`top_p` are REMOVED
        // from Opus 4.7 onward (sending either returns a hard 400), so an unknown new
        // model must never fall back onto the legacy request shape.
        val family = classifyClaudeFamily(modelName)
        val effort = ThinkingLevels.anthropicEffort(config.thinkingLevel)
        val thinkingViolation = when {
            config.thinkingEnabled -> null
            family == ClaudeFamily.CURRENT_ALWAYS_THINKING ->
                "model $modelName cannot disable thinking"
            modelName.equals("claude-opus-5", ignoreCase = true) && effort in setOf("xhigh", "max") ->
                "model $modelName cannot disable thinking at effort $effort"
            else -> null
        }
        val thinkingBudget = (
            if (config.thinkingBudgetEnabled) config.thinkingBudgetTokens else ThinkingLevels.DefaultBudgetTokens
        ).coerceIn(1024, 128000)
        val thinking = when {
            !config.thinkingEnabled && family == ClaudeFamily.CURRENT_DEFAULT_ON ->
                AnthropicThinking(type = "disabled")
            !config.thinkingEnabled -> null
            family == ClaudeFamily.NO_THINKING -> null
            family == ClaudeFamily.BUDGET_THINKING ->
                AnthropicThinking(type = "enabled", budgetTokens = thinkingBudget, display = "summarized")
            // 4.6: adaptive preferred; the deprecated budget form is still functional there,
            // so honor an explicit user-enabled budget as the documented transitional escape hatch.
            family == ClaudeFamily.TRANSITIONAL_4_6 && config.thinkingBudgetEnabled ->
                AnthropicThinking(type = "enabled", budgetTokens = thinkingBudget, display = "summarized")
            else -> AnthropicThinking(type = "adaptive", display = "summarized")
        }
        val outputConfig = if (thinking?.type in setOf("adaptive", "disabled")) {
            AnthropicOutputConfig(effort = effort)
        } else null
        // temperature/top_p are rejected with a 400 on Opus 4.7+ / Sonnet 5 / Fable — only the
        // legacy and transitional families may carry user sampling overrides.
        val allowsLegacySamplingParams = family in setOf(
            ClaudeFamily.NO_THINKING,
            ClaudeFamily.BUDGET_THINKING,
            ClaudeFamily.TRANSITIONAL_4_6,
        )
        val anthropicTools = buildAnthropicTools(config.tools)

        streamAnthropicMessages(
            providerName = name,
            url = "$baseUrl/messages",
            apiKey = config.apiKey,
            buildRequest = {
                thinkingViolation?.let { throw RequestFormatException(name, listOf(it)) }
                if (config.anthropicCacheEnabled && config.anthropicCacheTtl !in setOf("5m", "1h")) {
                    throw RequestFormatException(name, listOf("Invalid Anthropic cache duration"))
                }
                val resolvedRequest = config.resolveRequest(messages)
                AnthropicRequest(
                    model = modelName,
                    messages = buildAnthropicMessages(
                        messages = resolvedRequest.messages,
                        modelName = modelName,
                        providerName = name,
                        includeImages = config.includeImages,
                        signedThinkingRequired = thinking?.type in setOf("enabled", "adaptive"),
                    ),
                    system = resolvedRequest.systemPrompt,
                    cacheControl = if (config.anthropicCacheEnabled) {
                        AnthropicCacheControl(ttl = config.anthropicCacheTtl)
                    } else null,
                    thinking = thinking,
                    outputConfig = outputConfig,
                    // On always-on/adaptive-thinking models max_tokens caps thinking + answer TOGETHER,
                    // so the legacy 4096 default truncates mid-answer once the model thinks. Streaming is
                    // always on here, so a generous default costs nothing (it is a cap, not a target).
                    //
                    // The answer headroom above the thinking budget must also leave room for a tool_use
                    // block: with only ~1KB of slack, a thinking model routinely exhausts the cap exactly
                    // where the tool call would begin, which surfaces as "the tool call vanished".
                    maxTokens = config.maxTokens ?: when {
                        thinking?.budgetTokens != null ->
                            maxOf(thinking.budgetTokens + ANSWER_HEADROOM_TOKENS, 16384)
                        thinking?.type == "adaptive" -> 32768
                        else -> 8192
                    },
                    tools = anthropicTools,
                    temperature = config.temperature.takeIf {
                        allowsLegacySamplingParams && thinking == null
                    },
                    topP = config.topP?.takeIf {
                        allowsLegacySamplingParams && (thinking == null || it in 0.95f..1f)
                    },
                )
            },
            emit = { emit(it) },
        )
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val effectiveBaseUrl = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val headers = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
        // /v1/models is paginated (default page ~20); follow has_more/last_id so accounts
        // with long model lists aren't silently truncated to the first page.
        val all = mutableListOf<String>()
        var afterId: String? = null
        var pages = 0
        while (pages < 10) {
            val url = buildString {
                append(effectiveBaseUrl).append("/models?limit=100")
                afterId?.let { append("&after_id=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
            }
            val responseText = HttpClient.fetchModelsResponse(url, headers)
                .requireModelFetchBody()
            val page = decodeModelFetchResponse {
                anthropicProtocolJson.decodeFromString<AnthropicModelsResponse>(responseText)
            }
            all += page.data.map { it.id }
            if (!page.hasMore || page.data.isEmpty()) break
            afterId = page.lastId ?: page.data.last().id
            pages++
        }
        if (all.isEmpty()) throw ModelFetchEmptyResultException()
        all
    }

    private companion object {
        /**
         * Headroom reserved above the thinking budget for the answer AND any tool_use block.
         * Anthropic's max_tokens covers thinking + output together, so this slack is what keeps a
         * tool call from being cut off at the block boundary.
         */
        const val ANSWER_HEADROOM_TOKENS = 8192
    }
}

@Serializable
internal data class AnthropicModelsResponse(
    val data: List<AnthropicModelInfo>,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("last_id") val lastId: String? = null
)

@Serializable
internal data class AnthropicModelInfo(
    val id: String,
    @SerialName("display_name") val displayName: String = ""
)
