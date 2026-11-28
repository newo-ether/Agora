package com.newoether.agora.ui.settings

import com.newoether.agora.api.ProviderDefaults
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.util.Constants

/**
 * A provider the user can pick models from, with its resolved base URL and the model ids known for
 * it. Models come from the live provider lists (fetched `availableModels`), not a hardcoded table,
 * so anything the user has fetched/enabled — including custom providers and newly released models
 * (e.g. embedding or Live-API models) — shows up for selection.
 */
internal data class ProviderModels(
    val name: String,
    val baseUrl: String,
    val models: List<String>,
    val isCustom: Boolean,
)

/**
 * Built-in remote providers Agora ships with. Shared so the embedding editor and the Live Voice
 * selector offer the same provider set the chat model picker does.
 */
internal val BuiltInRemoteProviders: List<String> = listOf(
    Constants.PROVIDER_GOOGLE,
    Constants.PROVIDER_OPENAI,
    Constants.PROVIDER_ANTHROPIC,
    Constants.PROVIDER_DEEPSEEK,
    Constants.PROVIDER_QWEN,
    Constants.PROVIDER_GROQ,
    Constants.PROVIDER_OLLAMA,
    Constants.PROVIDER_OPEN_ROUTER,
    Constants.PROVIDER_OPENCODE_GO,
)

/**
 * Build the unified provider -> models list from the current settings flows.
 *
 * @param availableModels fetched provider -> model-ids map (SettingsManager.availableModels.value)
 * @param customProviders user-configured providers (SettingsManager.customProviders.value)
 * @param providerBaseUrls per-provider base-URL overrides (SettingsManager.providerBaseUrls.value)
 * @param modelFilter optional predicate to keep only matching model ids (e.g. embedding- or
 *        live-capable); applied per model id. When it filters a built-in provider down to nothing
 *        the provider is still listed (so the user can type a model id by hand).
 */
internal fun buildProviderModels(
    availableModels: Map<String, List<String>>,
    customProviders: List<CustomProviderConfig>,
    providerBaseUrls: Map<String, String>,
    includeCustom: Boolean = true,
    modelFilter: (String) -> Boolean = { true },
): List<ProviderModels> {
    fun modelsFor(providerName: String): List<String> =
        (availableModels[providerName].orEmpty()).filter(modelFilter)

    val builtIns = BuiltInRemoteProviders.map { name ->
        ProviderModels(
            name = name,
            baseUrl = providerBaseUrls[name] ?: ProviderDefaults.embeddingBaseUrl(name),
            models = modelsFor(name),
            isCustom = false,
        )
    }
    val customs = if (includeCustom) {
        customProviders.map { cp ->
            ProviderModels(
                name = cp.name,
                baseUrl = providerBaseUrls[cp.name].orEmpty().ifBlank { providerBaseUrls[cp.providerId].orEmpty() },
                models = modelsFor(cp.name).ifEmpty { modelsFor(cp.providerId) },
                isCustom = true,
            )
        }
    } else {
        emptyList()
    }
    // Built-ins first (stable order), then custom providers; de-dupe by display name.
    return (builtIns + customs).distinctBy { it.name }
}
