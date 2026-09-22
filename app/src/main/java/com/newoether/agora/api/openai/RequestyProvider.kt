package com.newoether.agora.api.openai

import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiModelListResponse
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.decodeModelFetchResponse
import com.newoether.agora.api.openAiAuthHeaders
import com.newoether.agora.api.requireModelFetchBody
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Requesty is an OpenAI-compatible gateway (https://docs.requesty.ai). Model ids use the
 * `vendor/model` form (e.g. `openai/gpt-4o-mini`, `anthropic/claude-sonnet-4-5`) or the short
 * id of a Requesty managed routing policy (e.g. `claude-sonnet-4-5`).
 */
class RequestyProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_REQUESTY
    override val defaultBaseUrl: String = "https://router.requesty.ai/v1"

    override fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest {
        // Requesty forwards the standard `reasoning_effort` field to the upstream provider and
        // also accepts `none`, `min`, `max`, and a numeric thinking budget as a string.
        val effort = when {
            !config.thinkingEnabled -> "none"
            config.thinkingBudgetEnabled -> config.thinkingBudgetTokens.toString()
            else -> ThinkingLevels.requestyEffort(config.thinkingLevel)
        }
        return request.copy(reasoningEffort = effort)
    }

    /**
     * Requesty serves two catalogs: `/models/managed` lists the curated routing policies with
     * short stable ids, and `/models` lists every `vendor/model` id the key may use. Both id
     * forms are valid in `model`, so the sync result offers the managed ids first, then the
     * full catalog.
     */
    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> {
        val effectiveBaseUrl = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val managed = fetchManagedModels(effectiveBaseUrl, openAiAuthHeaders(apiKey))
        val catalog = try {
            super.fetchModels(apiKey, baseUrl)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (managed.isEmpty()) throw error
            emptyList()
        }
        return (managed + catalog).distinct()
    }

    private suspend fun fetchManagedModels(
        baseUrl: String,
        headers: Map<String, String>,
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val responseText = HttpClient.fetchModelsResponse("$baseUrl/models/managed", headers)
                .requireModelFetchBody()
            decodeModelFetchResponse {
                json.decodeFromString<OpenAiModelListResponse>(responseText)
            }.data.map { it.id }.sorted()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.w(
                "AgoraAPI",
                "Failed to fetch $name managed models exception=${error.javaClass.simpleName}",
            )
            emptyList()
        }
    }
}
