package com.newoether.agora.api.openai

import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ModelFetchEmptyResultException
import com.newoether.agora.api.OAuthBackedProvider
import com.newoether.agora.api.OAuthTokenSource
import com.newoether.agora.api.OpenAiResponseTool
import com.newoether.agora.api.OpenAiResponsesRequest
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.decodeModelFetchResponse
import com.newoether.agora.api.requireModelFetchBody
import com.newoether.agora.model.ThinkingProviderFamily
import com.newoether.agora.model.ModelConstants as Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/**
 * ChatGPT plan usage through Sign in with ChatGPT. The endpoint accepts only stateless, streamed
 * Responses requests, rejects sampling and output-limit fields, and expects function tools inside
 * a namespace. See developers.openai.com/siwc/token-sharing-open-source/models-and-inference.
 */
class ChatGptProvider(
    override val tokenSource: OAuthTokenSource,
) : BaseOpenAiProvider(), OAuthBackedProvider {
    override val name: String = Constants.PROVIDER_CHATGPT
    override val thinkingProviderFamily = ThinkingProviderFamily.OPENAI
    override val defaultBaseUrl: String = "https://api.openai.com/v1"

    // subscription_sharing_usage_limit_exceeded (429) must not be retried; 503 is a transient outage.
    override val retryableStatusCodes: Set<Int> = setOf(502, 503, 504)

    override fun usesResponsesApi(config: ProviderConfig): Boolean = true

    override suspend fun authHeaders(config: ProviderConfig): Map<String, String> {
        val token = tokenSource.accessToken() ?: throw IOException("Not signed in to ChatGPT")
        return mapOf("Authorization" to "Bearer $token")
    }

    override suspend fun reauthorize(config: ProviderConfig): Boolean = tokenSource.onUnauthorized()

    override fun customizeResponsesRequest(
        request: OpenAiResponsesRequest,
        config: ProviderConfig,
    ): OpenAiResponsesRequest = chatGptResponsesRequest(request)

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = withContext(Dispatchers.IO) {
        val token = tokenSource.accessToken() ?: throw IOException("Not signed in to ChatGPT")
        val base = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val body = HttpClient.fetchModelsResponse("$base/models", mapOf("Authorization" to "Bearer $token"))
            .requireModelFetchBody()
        val models = decodeModelFetchResponse { parseChatGptModels(json.parseToJsonElement(body).jsonObject) }
        models.ifEmpty { throw ModelFetchEmptyResultException() }
    }
}

internal const val CHATGPT_TOOL_NAMESPACE = "agora"

/** Applies the ChatGPT-plan constraints to a request built by the shared Responses builder. */
internal fun chatGptResponsesRequest(request: OpenAiResponsesRequest): OpenAiResponsesRequest {
    val instructions = mutableListOf<String>()
    val input = request.input.mapNotNull { item ->
        val type = item.string("type")
        when {
            type == "message" && item.string("role") == "system" -> {
                instructions += (item["content"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.string("text") }
                    .joinToString("\n")
                null
            }
            // Stateless requests cannot reference stored items, so reasoning survives only as
            // encrypted content and every replayed item travels without its server id.
            type == "reasoning" && item.string("encrypted_content").isNullOrBlank() -> null
            type == "function_call" && item["namespace"] == null ->
                JsonObject(item - "id" + ("namespace" to JsonPrimitive(CHATGPT_TOOL_NAMESPACE)))
            else -> JsonObject(item - "id")
        }
    }
    val tools = request.tools.orEmpty()
    val functions = tools.filter { it.type == "function" }
    val grouped = buildList {
        if (functions.isNotEmpty()) {
            add(
                OpenAiResponseTool(
                    type = "namespace",
                    name = CHATGPT_TOOL_NAMESPACE,
                    description = "Tools provided by the Agora app.",
                    tools = functions,
                ),
            )
        }
        addAll(tools.filter { it.type != "function" })
    }
    return request.copy(
        input = input,
        tools = grouped.ifEmpty { null },
        store = false,
        stream = true,
        include = listOf("reasoning.encrypted_content"),
        instructions = instructions.filter(String::isNotBlank).joinToString("\n\n").ifBlank { null },
        reasoning = request.reasoning?.copy(maxTokens = null),
        chatTemplateKwargs = null,
        reasoningEffort = null,
        thinkingBudget = null,
        serviceTier = null,
        temperature = null,
        maxOutputTokens = null,
        topP = null,
    )
}

/** `{"models":[{"slug":..,"visibility":"list"}]}`; hidden entries stay out of the picker. */
internal fun parseChatGptModels(root: JsonObject): List<String> {
    val models = (root["models"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    return models
        .filter { (it.string("visibility") ?: "list") == "list" }
        .mapNotNull { it.string("slug") ?: it.string("id") }
        .distinct()
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
