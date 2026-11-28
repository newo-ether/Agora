package com.newoether.agora.api.openai

import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ModelFetchEmptyResultException
import com.newoether.agora.api.OAuthBackedProvider
import com.newoether.agora.api.OAuthTokenSource
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.decodeModelFetchResponse
import com.newoether.agora.api.requireModelFetchBody
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
 * DeepSeek through a signed-in DeepSeek account (the login DeepSeek Harness uses). The token goes
 * in `x-dsh-auth-token` without a Bearer prefix; it never expires on its own, so a 401 means the
 * user signed out elsewhere and has to sign in again.
 */
class DeepSeekAccountProvider(
    override val tokenSource: OAuthTokenSource,
    private val appVersion: String = "?",
) : DeepSeekProvider(), OAuthBackedProvider {
    override val name: String = Constants.PROVIDER_DEEPSEEK_ACCOUNT

    override suspend fun authHeaders(config: ProviderConfig): Map<String, String> = accountHeaders()

    override suspend fun reauthorize(config: ProviderConfig): Boolean = tokenSource.onUnauthorized()

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = withContext(Dispatchers.IO) {
        val base = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val body = HttpClient.fetchModelsResponse("$base/models", accountHeaders()).requireModelFetchBody()
        val ids = decodeModelFetchResponse {
            (json.parseToJsonElement(body).jsonObject["data"] as? JsonArray).orEmpty()
                .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
        }
        ids.ifEmpty { throw ModelFetchEmptyResultException() }.sorted()
    }

    private suspend fun accountHeaders(): Map<String, String> {
        val token = tokenSource.accessToken() ?: throw IOException("Not signed in to DeepSeek")
        return mapOf("x-dsh-auth-token" to token, "User-Agent" to "Agora/$appVersion")
    }
}
