package com.newoether.agora.api.openai

import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiModelListResponse
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.decodeModelFetchResponse
import com.newoether.agora.api.openAiAuthHeaders
import com.newoether.agora.api.requireModelFetchBody
import com.newoether.agora.api.util.resolvedThinking
import com.newoether.agora.model.ThinkingProviderFamily
import com.newoether.agora.util.CloudDebugLog as DebugLog
import com.newoether.agora.model.ModelConstants as Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Requesty is an OpenAI-compatible gateway (https://docs.requesty.ai). Model ids use the
 * `vendor/model` form (e.g. `openai/gpt-4o-mini`, `anthropic/claude-sonnet-5`) or the short
 * id of a Requesty managed routing policy (e.g. `claude-sonnet-5`).
 */
class RequestyProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_REQUESTY
    override val defaultBaseUrl: String = "https://router.requesty.ai/v1"

    /**
     * Requesty forwards the standard `reasoning_effort` field to the upstream model. It also takes
     * `none` to turn thinking off and a numeric thinking budget as a string. `minimal` is spelled
     * `min`. Ids route to many vendors, so the capability is the compatible-endpoint one, which
     * keeps documented model refinements and the permissive default for everything else.
     */
    override fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest {
        val resolved = config.resolvedThinking(thinkingProviderFamily)
        val budget = resolved.budgetTokens
        val effort = when {
            resolved.disabled -> "none".takeIf { resolved.capability.supportsEffort }
            budget != null -> budget.toString()
            resolved.effort == "minimal" -> "min"
            else -> resolved.effort
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
