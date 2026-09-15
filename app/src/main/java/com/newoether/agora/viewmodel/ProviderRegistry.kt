package com.newoether.agora.viewmodel

import com.newoether.agora.api.DebugProvider
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ModelFetchEmptyResultException
import com.newoether.agora.api.ModelFetchTimeoutException
import com.newoether.agora.api.anthropic.AnthropicProvider
import com.newoether.agora.api.gemini.GeminiProvider
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.api.ollama.OllamaProvider
import com.newoether.agora.api.openai.CustomOpenAiProvider
import com.newoether.agora.api.openai.DeepSeekProvider
import com.newoether.agora.api.openai.GroqProvider
import com.newoether.agora.api.openai.OpenAiProvider
import com.newoether.agora.api.openai.OpenCodeGoProvider
import com.newoether.agora.api.openai.OpenRouterProvider
import com.newoether.agora.api.openai.QwenProvider
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomEndpointResolution
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.CustomProviderIdentityPolicy
import com.newoether.agora.data.CustomProviderNamePolicy
import com.newoether.agora.data.canonicalCustomModelId
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ModelId
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

internal fun createCustomProvider(
    config: CustomProviderConfig,
    baseUrl: String,
): LlmProvider? {
    if (!CustomProviderNamePolicy.isAllowed(config.name)) return null
    return when (config.protocol) {
        CustomEndpointProtocol.OPENAI -> CustomOpenAiProvider(config.name, baseUrl)
        CustomEndpointProtocol.GOOGLE -> GeminiProvider(config.name, baseUrl)
        CustomEndpointProtocol.ANTHROPIC -> AnthropicProvider(config.name, baseUrl)
        CustomEndpointProtocol.UNKNOWN -> null
    }
}

internal fun prefixFetchedModels(
    providerName: String,
    customConfig: CustomProviderConfig?,
    modelNames: List<String>,
): List<String> {
    val providerReference = customConfig?.providerId ?: providerName
    return modelNames.map { "$providerReference:${it.removePrefix("models/")}" }
}

internal fun customEndpointBaseUrlCandidates(
    protocol: CustomEndpointProtocol,
    baseUrl: String?,
): List<String?> {
    if (baseUrl.isNullOrBlank()) return listOf(baseUrl)
    val normalized = baseUrl.trim().trimEnd('/')
    val resolver = com.newoether.agora.api.BaseUrlResolver
    val unversioned = resolver.withoutTrailingVersion(normalized)
    return when (protocol) {
        CustomEndpointProtocol.OPENAI,
        CustomEndpointProtocol.ANTHROPIC -> buildList {
            add(normalized)
            if (!resolver.hasVersionSegment(normalized)) {
                add(0, resolver.withV1(normalized))
            } else if (unversioned != null) {
                add(resolver.withV1(unversioned))
                add(unversioned)
            }
        }.distinct()
        // GeminiProvider owns its v1beta completion so both model discovery and generation
        // use the same exact base URL semantics.
        CustomEndpointProtocol.GOOGLE -> buildList {
            add(normalized)
            // Compatibility with the former sync implementation, which may have persisted
            // its derived terminal `/v1` into the user-facing Base URL.
            if (unversioned != null) add(unversioned)
        }.distinct()
        CustomEndpointProtocol.UNKNOWN -> emptyList()
    }
}

internal fun CustomEndpointResolution.matches(
    protocol: CustomEndpointProtocol,
    configuredBaseUrl: String,
): Boolean =
    this.protocol == protocol &&
        this.configuredBaseUrl.trim().trimEnd('/') == configuredBaseUrl.trim().trimEnd('/') &&
        effectiveBaseUrl.isNotBlank()

/** Pure policy boundary used by both production code and JVM tests. Missing providers fail shut. */
internal fun providerConfigurationIsValid(
    providerName: String,
    activeKey: String,
    registered: Boolean,
    builtIn: Boolean,
    effectiveBaseUrl: String?,
    debugProviderEnabled: Boolean = false,
): Boolean = when {
    providerName == Constants.PROVIDER_UNKNOWN -> false
    !registered -> false
    providerName == DebugProvider.PROVIDER_NAME -> debugProviderEnabled
    providerName == Constants.PROVIDER_LOCAL -> true
    !builtIn || providerName == Constants.PROVIDER_OLLAMA -> !effectiveBaseUrl.isNullOrBlank()
    else -> activeKey.isNotBlank()
}

/**
 * Owns the set of LLM providers — built-in plus user-defined custom OpenAI-compatible
 * ones — and all logic for resolving a model/provider to a concrete [LlmProvider]
 * instance, its effective base URL, and its configured/credentialed status.
 *
 * Extracted from [ChatViewModel] so provider lifecycle (registration, rename, delete,
 * credential reconciliation) and model discovery live in one cohesive place. The live
 * [all] map is shared by reference with the generation pipeline, so it is a
 * [ConcurrentHashMap]: mutated by the sync collectors while read on `Dispatchers.IO`
 * during generation.
 */
class ProviderRegistry(
    private val settings: SettingsRepository,
    private val conversations: ConversationRepository,
    localProvider: LocalProvider,
    private val scope: CoroutineScope,
    appVersion: String = "?",
) {
    private val builtInProviders: Map<String, LlmProvider> = mapOf(
        Constants.PROVIDER_GOOGLE to GeminiProvider(),
        Constants.PROVIDER_OPENAI to OpenAiProvider(),
        Constants.PROVIDER_ANTHROPIC to AnthropicProvider(),
        Constants.PROVIDER_DEEPSEEK to DeepSeekProvider(),
        Constants.PROVIDER_QWEN to QwenProvider(),
        Constants.PROVIDER_GROQ to GroqProvider(),
        Constants.PROVIDER_OLLAMA to OllamaProvider(),
        Constants.PROVIDER_OPEN_ROUTER to OpenRouterProvider(),
        Constants.PROVIDER_OPENCODE_GO to OpenCodeGoProvider(appVersion),
        Constants.PROVIDER_LOCAL to localProvider
    )

    private val debugProvider = DebugProvider()

    // Declared as MutableMap so `in`/`contains` keep Map (containsKey) semantics (KT-18053).
    private val providers: MutableMap<String, LlmProvider> = ConcurrentHashMap(builtInProviders)
    private val runtimeEndpointResolutions = ConcurrentHashMap<String, CustomEndpointResolution>()
    private val initialCustomProviderSync = CompletableDeferred<Unit>()
    @Volatile
    private var syncStarted = false

    /** Live, thread-safe read view shared with normal provider synchronization and settings UI. */
    val all: Map<String, LlmProvider> get() = providers

    /** Immutable provider set captured for one generation admission. */
    fun generationSnapshot(): Map<String, LlmProvider> =
        if (isDebugProviderEnabled()) {
            providers.toMap() + (DebugProvider.PROVIDER_NAME to debugProvider)
        } else {
            providers.toMap()
        }

    private fun isDebugProviderEnabled(): Boolean =
        settings.developerOptionsEnabled.value && settings.debugModelEnabled.value

    fun isBuiltIn(name: String): Boolean =
        name == DebugProvider.PROVIDER_NAME || name in builtInProviders

    fun getInstance(name: String): LlmProvider = requireNotNull(getInstanceOrNull(name)) {
        "Provider is not registered: $name"
    }

    /** Null-tolerant lookup for UI reads: a settings page can recompose one frame after
     *  its provider was deleted, which must render gracefully, not crash. */
    fun getInstanceOrNull(name: String): LlmProvider? =
        if (name == DebugProvider.PROVIDER_NAME) {
            debugProvider.takeIf { isDebugProviderEnabled() }
        } else {
            providers[name]
        }

    fun getEffectiveBaseUrl(providerName: String): String? {
        if (providerName == DebugProvider.PROVIDER_NAME) return null
        val configuredBaseUrl = settings.providerBaseUrls.value[providerName]?.takeIf { it.isNotBlank() }
            ?: return providers[providerName]?.takeIf { !isBuiltIn(providerName) }?.defaultBaseUrl
        val customConfig = settings.customProviders.value.firstOrNull { it.name == providerName }
            ?: return configuredBaseUrl
        val resolution = sequenceOf(
            runtimeEndpointResolutions[providerName],
            settings.customEndpointResolutions.value[providerName],
        ).filterNotNull().firstOrNull {
            it.matches(customConfig.protocol, configuredBaseUrl)
        }
        return resolution?.effectiveBaseUrl ?: configuredBaseUrl
    }

    fun isConfigured(providerName: String, activeKey: String): Boolean =
        providerConfigurationIsValid(
            providerName = providerName,
            activeKey = activeKey,
            registered = getInstanceOrNull(providerName) != null,
            builtIn = isBuiltIn(providerName),
            effectiveBaseUrl = getEffectiveBaseUrl(providerName),
            debugProviderEnabled = isDebugProviderEnabled(),
        )

    fun providerForModel(modelId: String): String {
        if (modelId == DebugProvider.MODEL_ID) return DebugProvider.PROVIDER_NAME
        // Custom model IDs carry the provider's immutable ID. Resolve it to the mutable display
        // name only at the live connection boundary; renaming can no longer reclassify the model.
        if (modelId.contains(":")) {
            val reference = ModelId.parse(canonicalModelId(modelId)).providerName
            return providerDisplayName(reference, settings.customProviders.value)
        }
        // Unprefixed IDs: user-registered providers take priority over heuristics
        settings.availableModels.value.forEach { (providerName, models) ->
            if (models.contains(modelId)) return providerName
        }
        // Heuristic fallback for legacy unprefixed IDs
        return ModelId.parse(modelId).providerName
    }

    /** Canonicalizes legacy name-prefixed IDs, including historical names containing `:`. */
    fun canonicalModelId(modelId: String): String =
        canonicalCustomModelId(modelId, settings.customProviders.value)

    // ── Custom provider CRUD ──────────────────────────────────
    // Settings persists the config; the callbacks keep the live `providers` map in sync.

    fun addCustom(
        name: String,
        baseUrl: String,
        protocol: CustomEndpointProtocol = CustomEndpointProtocol.OPENAI,
    ) {
        val normalizedName = name.trim()
        if (
            CustomProviderNamePolicy.hasConflict(
                name = normalizedName,
                existingNames = settings.customProviders.value.map { it.name },
            )
        ) return
        val config = CustomProviderConfig(
            name = normalizedName,
            protocol = protocol,
            id = CustomProviderIdentityPolicy.newId(),
        )
        val provider = createCustomProvider(config, baseUrl) ?: return
        runtimeEndpointResolutions.remove(normalizedName)
        providers[normalizedName] = provider
        settings.addCustomProvider(config, baseUrl)
    }

    fun renameCustom(oldName: String, newName: String): Boolean {
        if (!CustomProviderNamePolicy.isAllowed(oldName)) return false
        val normalizedNewName = newName.trim()
        if (
            CustomProviderNamePolicy.hasConflict(
                name = normalizedNewName,
                existingNames = settings.customProviders.value.map { it.name },
                currentName = oldName,
            )
        ) return false
        val url = settings.providerBaseUrls.value[oldName].orEmpty()
        val oldConfig = settings.customProviders.value.firstOrNull { it.name == oldName }
            ?: return false
        val newConfig = oldConfig.copy(name = normalizedNewName)
        val provider = createCustomProvider(newConfig, url)
        // Keep the old connection key until the DataStore-backed collector observes the rename.
        // During that short handoff model IDs still resolve through the old config snapshot; eager
        // removal would create a transient "provider not registered" failure for an accepted Send.
        if (provider != null) providers[normalizedNewName] = provider
        runtimeEndpointResolutions.remove(oldName)?.let {
            runtimeEndpointResolutions[normalizedNewName] = it
        }
        settings.renameCustomProvider(oldName, normalizedNewName)
        return true
    }

    fun updateCustomProtocol(name: String, protocol: CustomEndpointProtocol) {
        if (!CustomProviderNamePolicy.isAllowed(name)) return
        val current = settings.customProviders.value.firstOrNull { it.name == name } ?: return
        val updated = current.copy(protocol = protocol)
        val url = settings.providerBaseUrls.value[name].orEmpty()
        val provider = createCustomProvider(updated, url)
        if (provider == null) providers.remove(name) else providers[name] = provider
        runtimeEndpointResolutions.remove(name)
        settings.updateCustomProviderProtocol(name, protocol)
    }

    fun deleteCustom(name: String) {
        if (!CustomProviderNamePolicy.isAllowed(name)) return
        providers.remove(name)
        runtimeEndpointResolutions.remove(name)
        settings.deleteCustomProvider(name)
    }

    /** Registers any persisted custom provider not yet present in the live map. */
    fun ensureCustomProvidersRegistered() {
        settings.customProviders.value.forEach { config ->
            if (config.name !in providers) {
                createCustomProvider(
                    config,
                    settings.providerBaseUrls.value[config.name].orEmpty(),
                )?.let { providers[config.name] = it }
            }
        }
    }

    /** Starts and waits for the single identity-migration and live-map synchronization path. */
    suspend fun awaitInitialSync() {
        ensureStarted()
        initialCustomProviderSync.await()
    }

    /**
     * Fetches the live model list for a single provider and caches it. Unlike a full
     * sync this carries no global side effects (no snackbar, no syncing flag).
     */
    suspend fun fetchModelsForProvider(name: String): List<String> {
        if (name == Constants.PROVIDER_LOCAL) return emptyList()
        ensureCustomProvidersRegistered()
        val provider = providers[name] ?: return emptyList()
        val activeKey = settings.apiKeys.value.find { it.id == settings.activeApiKeyIds.value[name] }?.key ?: ""
        if (!isConfigured(name, activeKey)) return emptyList()
        val baseUrl = if (!isBuiltIn(name)) {
            settings.providerBaseUrls.value[name]?.takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl
        } else {
            // Built-in: a blank persisted entry is the same as absent (use the provider default).
            // Without this guard, "" flows straight to the provider, which only elvis-checks null
            // and would build a malformed relative endpoint.
            settings.providerBaseUrls.value[name]?.takeIf { it.isNotBlank() }
        }

        // Resolve protocol-specific versioning once during model sync. The successful
        // effective URL is cached separately from the user's Base URL and is only reusable
        // while both the configured URL and protocol still match.
        val customConfig = settings.customProviders.value.firstOrNull { it.name == name }
        val candidates: List<String?> = if (customConfig != null) {
            customEndpointBaseUrlCandidates(customConfig.protocol, baseUrl)
        } else {
            listOf(baseUrl)
        }

        var lastFailure: Exception? = null
        for (candidate in candidates) {
            try {
                val raw = withTimeout(Constants.MODEL_FETCH_TIMEOUT_MS) {
                    provider.fetchModels(activeKey, candidate)
                }
                if (raw.isEmpty()) throw ModelFetchEmptyResultException()
                if (customConfig != null && candidate != null && baseUrl != null) {
                    val resolution = CustomEndpointResolution(
                        protocol = customConfig.protocol,
                        configuredBaseUrl = baseUrl,
                        effectiveBaseUrl = candidate,
                    )
                    runtimeEndpointResolutions[name] = resolution
                    settings.saveCustomEndpointResolution(name, resolution)
                }
                val prefixed = prefixFetchedModels(name, customConfig, raw)
                settings.saveAvailableModels(name, prefixed)
                return prefixed
            } catch (error: TimeoutCancellationException) {
                lastFailure = ModelFetchTimeoutException()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        throw lastFailure ?: ModelFetchEmptyResultException()
    }

    /** Identity fingerprint of all providers' credentials/URLs — used to skip redundant syncs. */
    fun computeFingerprint(): String = providers.map { (name, _) ->
        val keyId = settings.activeApiKeyIds.value[name] ?: ""
        val url = settings.providerBaseUrls.value[name] ?: ""
        val protocol = settings.customProviders.value
            .firstOrNull { it.name == name }
            ?.protocol
            ?.wireValue
            .orEmpty()
        "$name|$keyId|$url|$protocol"
    }.sorted().joinToString(",").hashCode().toString()

    /** Starts the process-wide provider lifecycle once, from UI post-list or headless demand. */
    @Synchronized
    fun ensureStarted() {
        if (syncStarted) return
        syncStarted = true
        scope.launch {
            try {
                val pending = settings.normalizeCustomProviderIdentities()
                val migrationResults = pending.map { migration ->
                    migration to runCatching {
                        conversations.renameConfiguredProviderModelReferences(
                            migration.legacyReference,
                            migration.providerId,
                        )
                    }
                }
                settings.clearLegacyCustomProviderNames(
                    migrationResults.filter { (_, result) -> result.isSuccess }.map { it.first },
                )
                migrationResults.firstOrNull { (_, result) -> result.isFailure }
                    ?.second
                    ?.getOrThrow()
                settings.awaitInitialLoad()
                settings.customProviders.first { custom ->
                    custom.all { CustomProviderIdentityPolicy.isStableId(it.id) }
                }
                settings.customProviders.collect { custom ->
                    providers.keys.filter { !isBuiltIn(it) }.forEach { providers.remove(it) }
                    val baseUrls = settings.getProviderBaseUrls()
                    custom.forEach { config ->
                        createCustomProvider(
                            config,
                            baseUrls[config.name] ?: "",
                        )?.let { providers[config.name] = it }
                    }
                    initialCustomProviderSync.complete(Unit)
                }
            } catch (error: Throwable) {
                initialCustomProviderSync.completeExceptionally(error)
                throw error
            }
        }
        scope.launch {
            initialCustomProviderSync.await()
            collectCredentialChanges()
        }
    }

    private suspend fun collectCredentialChanges() {
        var prevConfigured = emptyMap<String, Boolean>()
        combine(
            settings.apiKeys,
            settings.activeApiKeyIds,
            settings.providerBaseUrls
        ) { keys, activeIds, baseUrls -> Triple(keys, activeIds, baseUrls) }
            .collect { (keys, activeIds, _) ->
                if (keys.isEmpty() && activeIds.isEmpty()) return@collect

                val current = mutableMapOf<String, Boolean>()
                providers.toMap().forEach { (name, _) ->
                    val activeKey = keys.find { it.id == activeIds[name] }?.key ?: ""
                    current[name] = isConfigured(name, activeKey)
                }

                var changed = false
                current.forEach { (name, configured) ->
                    if (prevConfigured[name] == true && !configured) {
                        val existing = settings.getAvailableModels()[name]
                        if (!existing.isNullOrEmpty()) {
                            settings.saveAvailableModels(name, emptyList())
                            changed = true
                        }
                    }
                }
                prevConfigured = current

                if (changed) {
                    val allKnownModels =
                        settings.getAvailableModels().values.flatten().toSet() +
                            settings.customModels.value
                    val newEnabled = settings.enabledModels.value.intersect(allKnownModels)
                    if (newEnabled != settings.enabledModels.value) {
                        settings.setEnabledModels(newEnabled)
                    }
                }
            }
    }
}
