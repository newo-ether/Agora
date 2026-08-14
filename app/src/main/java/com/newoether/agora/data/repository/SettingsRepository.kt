package com.newoether.agora.data.repository

import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ContextBudget

import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.DEFAULT_CONTEXT_COMPACT_ENABLED
import com.newoether.agora.data.DEFAULT_CONTEXT_COMPACT_RETAIN_COUNT
import com.newoether.agora.data.DEFAULT_CONTEXT_COMPACT_THRESHOLD_PERCENT
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomEndpointResolution
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.CustomProviderIdentityMigration
import com.newoether.agora.data.CustomProviderNamePolicy
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.PromptTemplateItem
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Repository wrapping DataStore-backed SettingsManager.
 *
 * Exposes every setting as a hot, eagerly-shared [StateFlow] (so the UI can
 * `collectAsState` and callers can read `.value` synchronously), plus the
 * setters and atomic batch mutations. This is the single shared owner of the
 * app settings surface; `ChatViewModel` and the settings pages both consume it
 * instead of re-exposing each setting individually.
 *
 * StateFlow initial values match the previous `ChatViewModel.stateIn` defaults
 * so observable behavior is unchanged.
 */
class SettingsRepository(
    private val settingsManager: SettingsManager,
    private val scope: CoroutineScope
) {
    /** One latch per eagerly-shared DataStore flow; populated completely during construction. */
    private val initialLoadSignals = mutableListOf<CompletableDeferred<Unit>>()

    private fun <T> hot(flow: kotlinx.coroutines.flow.Flow<T>, initial: T): StateFlow<T> {
        val loaded = CompletableDeferred<Unit>()
        initialLoadSignals += loaded
        val state = MutableStateFlow(initial)
        flow
            .onEach { value ->
                // Publish first: an awaiter must never resume while `.value` still exposes the
                // eager default for this particular setting.
                state.value = value
                loaded.complete(Unit)
            }
            .catch { error ->
                loaded.completeExceptionally(error)
                throw error
            }
            .launchIn(scope)
        return state.asStateFlow()
    }

    /**
     * Suspends until every settings StateFlow has received its first on-disk DataStore value.
     * Background workers must cross this barrier before reading `.value`; otherwise a cold-start
     * alarm can briefly observe repository defaults (for example the sample model or empty custom
     * providers) and permanently consume the scheduled occurrence with the wrong configuration.
     */
    suspend fun awaitInitialLoad() {
        initialLoadSignals.toList().forEach { it.await() }
    }

    // ── Read StateFlows (eagerly shared) ──────────────────────

    val selectedModel: StateFlow<String> = hot(settingsManager.selectedModel, Constants.EXAMPLE_MODEL_ID)
    val availableModels: StateFlow<Map<String, List<String>>> = hot(settingsManager.availableModels, emptyMap())
    val customModels: StateFlow<Set<String>> = hot(settingsManager.customModels, emptySet())
    val enabledModels: StateFlow<Set<String>> = hot(settingsManager.enabledModels, emptySet())
    val modelAliases: StateFlow<Map<String, String>> = hot(settingsManager.modelAliases, emptyMap())
    val apiKeys: StateFlow<List<ApiKeyEntry>> = hot(settingsManager.apiKeys, emptyList())
    val activeApiKeyIds: StateFlow<Map<String, String>> = hot(settingsManager.activeApiKeyIds, emptyMap())
    val systemPrompts: StateFlow<List<SystemPromptEntry>> = hot(settingsManager.systemPrompts, emptyList())
    val activeSystemPromptId: StateFlow<String?> = hot(settingsManager.activeSystemPromptId, null)
    val maxContextWindow: StateFlow<Int> =
        hot(settingsManager.maxContextWindow, ContextBudget.DEFAULT_TOKENS)
    val visualizeContextRollout: StateFlow<Boolean> = hot(settingsManager.visualizeContextRollout, false)
    val contextCompactEnabled: StateFlow<Boolean> = hot(
        settingsManager.contextCompactEnabled,
        DEFAULT_CONTEXT_COMPACT_ENABLED,
    )
    val contextCompactModel: StateFlow<String?> = hot(settingsManager.contextCompactModel, null)
    val contextCompactPrompt: StateFlow<String> = hot(settingsManager.contextCompactPrompt, BuiltInPrompts.CONTEXT_COMPACT_SYSTEM)
    val contextCompactRetainCount: StateFlow<Int> = hot(
        settingsManager.contextCompactRetainCount,
        DEFAULT_CONTEXT_COMPACT_RETAIN_COUNT,
    )
    val contextCompactThresholdPercent: StateFlow<Int> = hot(
        settingsManager.contextCompactThresholdPercent,
        DEFAULT_CONTEXT_COMPACT_THRESHOLD_PERCENT,
    )
    val codeExecutionEnabled: StateFlow<Boolean> = hot(settingsManager.codeExecutionEnabled, false)
    val googleSearchEnabled: StateFlow<Boolean> = hot(settingsManager.googleSearchEnabled, false)
    val thinkingEnabled: StateFlow<Boolean> = hot(settingsManager.thinkingEnabled, true)
    val thinkingLevel: StateFlow<String> = hot(settingsManager.thinkingLevel, "medium")
    val thinkingBudgetEnabled: StateFlow<Boolean> = hot(settingsManager.thinkingBudgetEnabled, false)
    val thinkingBudgetTokens: StateFlow<Int> = hot(settingsManager.thinkingBudgetTokens, 4096)
    val openAiServiceTierEnabled: StateFlow<Boolean> =
        hot(settingsManager.openAiServiceTierEnabled, false)
    val openAiServiceTier: StateFlow<String> =
        hot(settingsManager.openAiServiceTier, OpenAiServiceTiers.AUTO)
    val openAiResponsesApiEnabled: StateFlow<Boolean> =
        hot(settingsManager.openAiResponsesApiEnabled, false)
    val providerBaseUrls: StateFlow<Map<String, String>> = hot(settingsManager.providerBaseUrls, emptyMap())
    val customEndpointResolutions: StateFlow<Map<String, CustomEndpointResolution>> =
        hot(settingsManager.customEndpointResolutions, emptyMap())
    val titleGenerationEnabled: StateFlow<Boolean> = hot(settingsManager.titleGenerationEnabled, true)
    val titleGenerationModel: StateFlow<String?> = hot(settingsManager.titleGenerationModel, null)
    val titleGenerationPrompt: StateFlow<String> = hot(settingsManager.titleGenerationPrompt, BuiltInPrompts.TITLE_GENERATION_SYSTEM)
    val titleGenerationNotificationsEnabled: StateFlow<Boolean> =
        hot(settingsManager.titleGenerationNotificationsEnabled, true)
    val imageTranscriptionEnabled: StateFlow<Boolean> =
        hot(settingsManager.imageTranscriptionEnabled, true)
    val imageTranscriptionEnabledModels: StateFlow<Set<String>> = hot(settingsManager.imageTranscriptionEnabledModels, emptySet())
    val imageTranscriptionModel: StateFlow<String?> = hot(settingsManager.imageTranscriptionModel, null)
    val imageTranscriptionBatchSize: StateFlow<Int> = hot(settingsManager.imageTranscriptionBatchSize, 3)
    val imageTranscriptionPrompt: StateFlow<String> = hot(settingsManager.imageTranscriptionPrompt, BuiltInPrompts.IMAGE_TRANSCRIPTION_USER)
    val accessPastConversations: StateFlow<Boolean> = hot(settingsManager.accessPastConversations, true)
    val accessSavedMemories: StateFlow<Boolean> = hot(settingsManager.accessSavedMemories, true)
    val accessActiveMemory: StateFlow<Boolean> = hot(settingsManager.accessActiveMemory, true)
    val ragSearchEnabled: StateFlow<Boolean> = hot(settingsManager.ragSearchEnabled, false)
    val autoCacheEnabled: StateFlow<Boolean> = hot(settingsManager.autoCacheEnabled, true)
    val autoUpdateCheck: StateFlow<Boolean> = hot(settingsManager.autoUpdateCheck, true)
    val lastUpdateCheckTime: StateFlow<Long> = hot(settingsManager.lastUpdateCheckTime, 0L)
    val modelSearchMethod: StateFlow<String> = hot(settingsManager.modelSearchMethod, "keyword")
    val manualSearchMethod: StateFlow<String> = hot(settingsManager.manualSearchMethod, "keyword")
    val embeddingModels: StateFlow<List<EmbeddingModelConfig>> = hot(settingsManager.embeddingModels, emptyList())
    val activeEmbeddingModelId: StateFlow<String> = hot(settingsManager.activeEmbeddingModelId, "")
    val appLanguage: StateFlow<String> = hot(settingsManager.appLanguage, "system")
    val webSearchEnabled: StateFlow<Boolean> = hot(settingsManager.webSearchEnabled, false)
    val webSearchProvider: StateFlow<String> = hot(settingsManager.webSearchProvider, "duckduckgo")
    val webSearchApiKeys: StateFlow<Map<String, String>> = hot(settingsManager.webSearchApiKeys, emptyMap())
    val webSearchNumResults: StateFlow<Int> = hot(settingsManager.webSearchNumResults, 5)
    val webSearchBaseUrl: StateFlow<String> = hot(settingsManager.webSearchBaseUrl, "")
    val imageGenEnabled: StateFlow<Boolean> = hot(settingsManager.imageGenEnabled, false)
    val imageGenModel: StateFlow<String?> = hot(settingsManager.imageGenModel, null)
    val imageGenSize: StateFlow<String> = hot(settingsManager.imageGenSize, "1024x1024")
    val showDocumentationFab: StateFlow<Boolean> = hot(settingsManager.showDocumentationFab, true)
    val developerOptionsEnabled: StateFlow<Boolean> =
        hot(settingsManager.developerOptionsEnabled, false)
    val shellEnabled: StateFlow<Boolean> = hot(settingsManager.shellEnabled, false)
    val automationToolsEnabled: StateFlow<Boolean> = hot(settingsManager.automationToolsEnabled, false)
    val exactExecutionEnabled: StateFlow<Boolean> = hot(settingsManager.exactExecutionEnabled, false)
    val proxyEnabled: StateFlow<Boolean> = hot(settingsManager.proxyEnabled, false)
    val proxyType: StateFlow<String> = hot(settingsManager.proxyType, "http")
    val proxyHost: StateFlow<String> = hot(settingsManager.proxyHost, com.newoether.agora.data.SettingsManager.DEFAULT_PROXY_HOST)
    val proxyPort: StateFlow<String> = hot(settingsManager.proxyPort, com.newoether.agora.data.SettingsManager.DEFAULT_PROXY_PORT)
    val proxyUsername: StateFlow<String> = hot(settingsManager.proxyUsername, "")
    val proxyPassword: StateFlow<String> = hot(settingsManager.proxyPassword, "")
    val proxyBypass: StateFlow<String> = hot(settingsManager.proxyBypass, com.newoether.agora.data.SettingsManager.DEFAULT_PROXY_BYPASS)
    val shellConfirmEnabled: StateFlow<Boolean> = hot(settingsManager.shellConfirmEnabled, true)
    val shellDevices: StateFlow<List<ShellDeviceConfig>> = hot(settingsManager.shellDevices, emptyList())
    val mcpServers: StateFlow<List<McpServerConfig>> = hot(settingsManager.mcpServers, emptyList())
    val sandboxEnabled: StateFlow<Boolean> = hot(settingsManager.sandboxEnabled, false)
    val sandboxSharedStorageEnabled: StateFlow<Boolean> =
        hot(settingsManager.sandboxSharedStorageEnabled, false)
    val defaultTemperature: StateFlow<Float?> = hot(settingsManager.defaultTemperature, null)
    val defaultMaxTokens: StateFlow<Int?> = hot(settingsManager.defaultMaxTokens, null)
    val defaultTopP: StateFlow<Float?> = hot(settingsManager.defaultTopP, null)
    val defaultFrequencyPenalty: StateFlow<Float?> = hot(settingsManager.defaultFrequencyPenalty, null)
    val defaultPresencePenalty: StateFlow<Float?> = hot(settingsManager.defaultPresencePenalty, null)
    val conversationSettings: StateFlow<Map<String, ConversationSettings>> = hot(settingsManager.conversationSettings, emptyMap())
    val themeMode: StateFlow<String> = hot(settingsManager.themeMode, "FOLLOW_DEVICE")
    val colorScheme: StateFlow<String> = hot(settingsManager.colorScheme, "DEFAULT")
    val dynamicColor: StateFlow<Boolean> = hot(settingsManager.dynamicColor, false)
    val blurEffectsEnabled: StateFlow<Boolean> = hot(settingsManager.blurEffectsEnabled, true)
    val reduceMotion: StateFlow<Boolean> = hot(settingsManager.reduceMotion, false)
    val parseInlineDollarMath: StateFlow<Boolean> = hot(settingsManager.parseInlineDollarMath, false)
    val hapticsEnabled: StateFlow<Boolean> = hot(settingsManager.hapticsEnabled, true)
    val detailedTokenUsage: StateFlow<Boolean> =
        hot(settingsManager.detailedTokenUsage, false)
    val toolCallDisplayMode: StateFlow<String> = hot(settingsManager.toolCallDisplayMode, ToolCallDisplayModes.DEFAULT)
    val thinkingSegmentDisplayMode: StateFlow<String> = hot(
        settingsManager.thinkingSegmentDisplayMode,
        ThinkingSegmentDisplayModes.DEFAULT,
    )
    val autoExpandActiveGroup: StateFlow<Boolean> =
        hot(settingsManager.autoExpandActiveGroup, true)
    val schemeStyle: StateFlow<String> = hot(settingsManager.schemeStyle, "TONAL_SPOT")
    val fontPreference: StateFlow<String> = hot(settingsManager.fontPreference, "app_default")
    val customFontPath: StateFlow<String> = hot(settingsManager.customFontPath, "")
    val customFontName: StateFlow<String> = hot(settingsManager.customFontName, "")
    val searchContextWindow: StateFlow<Int> = hot(settingsManager.searchContextWindow, 8)
    val searchMatchLimit: StateFlow<Int> = hot(settingsManager.searchMatchLimit, 10)
    val ragThreshold: StateFlow<Float> = hot(settingsManager.ragThreshold, 0.5f)
    val localChatModels: StateFlow<List<LocalChatModelConfig>> = hot(settingsManager.localChatModels, emptyList())
    val customProviders: StateFlow<List<CustomProviderConfig>> = hot(settingsManager.customProviders, emptyList())
    val lastModelsFetchFingerprint: StateFlow<String> = hot(settingsManager.lastModelsFetchFingerprint, "")
    // ── Auto Backup ───────────────────────────────────────────
    val autoBackupEnabled: StateFlow<Boolean> = hot(settingsManager.autoBackupEnabled, true)
    val autoBackupPeriodHours: StateFlow<Int> = hot(settingsManager.autoBackupPeriodHours, 24)
    val autoBackupCategories: StateFlow<String> = hot(settingsManager.autoBackupCategories, "conversations,memories,system_prompts,settings")
    val autoBackupDirectory: StateFlow<String> = hot(settingsManager.autoBackupDirectory, "Download/Agora/Backup")
    val autoDeleteEnabled: StateFlow<Boolean> = hot(settingsManager.autoDeleteEnabled, true)
    val autoDeletePeriodHours: StateFlow<Int> = hot(settingsManager.autoDeletePeriodHours, 168)
    val lastBackupTimestamp: StateFlow<Long> = hot(settingsManager.lastBackupTimestamp, 0L)

    // ── Write (fire-and-forget; read current state from own StateFlows) ──
    //
    // These setters launch on [scope] and read "current" list/map state from this
    // repository's own `.value`, so callers no longer pass it in. Absorbed from the
    // former `SettingsDelegate`; logic is byte-for-byte equivalent.

    // Model selection
    fun setSelectedModel(model: String) {
        scope.launch { settingsManager.saveSelectedModel(model) }
    }

    fun setEnabledModels(models: Set<String>) {
        scope.launch {
            settingsManager.saveEnabledModels(models)
            if (!models.contains(selectedModel.value)) {
                settingsManager.saveSelectedModel(models.firstOrNull() ?: "")
            }
        }
    }

    fun updateModelAlias(model: String, alias: String) {
        scope.launch {
            settingsManager.updateModelAlias(model, alias)
        }
    }

    fun addCustomModel(provider: String, modelName: String, alias: String = "") {
        val normalizedProvider = stableProviderReference(provider)
        val normalizedName = modelName.trim()
        if (normalizedProvider.isEmpty() || normalizedName.isEmpty()) return
        val modelId = ModelId(normalizedProvider, normalizedName).prefixed
        scope.launch {
            settingsManager.addCustomModel(modelId, alias)
        }
    }

    fun removeCustomModel(modelId: String) {
        if (modelId !in customModels.value) return
        scope.launch {
            settingsManager.replaceCustomModel(modelId, null, "")
        }
    }

    suspend fun replaceCustomModel(
        oldModelId: String,
        newModelId: String?,
        alias: String,
    ) = settingsManager.replaceCustomModel(oldModelId, newModelId, alias)

    // API keys
    fun addApiKey(name: String, key: String, provider: String) {
        scope.launch {
            val entry = ApiKeyEntry(name = name, key = key, provider = provider)
            settingsManager.saveApiKeys(apiKeys.value + entry)
            settingsManager.setActiveApiKeyId(provider, entry.id)
        }
    }

    /**
     * Store exactly one key for [provider]: update the existing entry in place if there
     * is one, otherwise add it — and drop any extra entries for the same provider.
     * Idempotent, so onboarding never accumulates duplicates.
     */
    fun upsertApiKey(name: String, key: String, provider: String) {
        scope.launch {
            val current = apiKeys.value
            val existing = current.firstOrNull { it.provider == provider }
            val entry = existing?.copy(name = name, key = key) ?: ApiKeyEntry(name = name, key = key, provider = provider)
            settingsManager.saveApiKeys(current.filter { it.provider != provider } + entry)
            settingsManager.setActiveApiKeyId(provider, entry.id)
        }
    }

    fun deleteApiKey(id: String) {
        scope.launch {
            val current = apiKeys.value
            val entry = current.find { it.id == id } ?: return@launch
            val newList = current.filter { it.id != id }
            if (activeApiKeyIds.value[entry.provider] == id) {
                val other = newList.firstOrNull { it.provider == entry.provider }
                settingsManager.setActiveApiKeyId(entry.provider, other?.id)
            }
            settingsManager.saveApiKeys(newList)
        }
    }

    fun updateApiKey(id: String, name: String, key: String) {
        scope.launch {
            settingsManager.saveApiKeys(apiKeys.value.map { if (it.id == id) it.copy(name = name, key = key) else it })
        }
    }

    fun setActiveApiKey(provider: String, id: String) {
        scope.launch { settingsManager.setActiveApiKeyId(provider, id) }
    }

    // System prompts
    fun addSystemPrompt(
        title: String, systemItems: List<PromptTemplateItem>,
        userPrependItems: List<PromptTemplateItem>, userPostpendItems: List<PromptTemplateItem>
    ) {
        scope.launch {
            val newList = systemPrompts.value + SystemPromptEntry(title = title, systemItems = systemItems, userPrependItems = userPrependItems, userPostpendItems = userPostpendItems)
            settingsManager.saveSystemPrompts(newList)
            if (activeSystemPromptId.value == null) settingsManager.setActiveSystemPromptId(newList.last().id)
        }
    }

    fun deleteSystemPrompt(id: String) {
        scope.launch {
            val newList = systemPrompts.value.filter { it.id != id }
            settingsManager.saveSystemPrompts(newList)
            if (activeSystemPromptId.value == id) settingsManager.setActiveSystemPromptId(newList.firstOrNull()?.id)
        }
    }

    fun updateSystemPrompt(
        id: String, title: String, systemItems: List<PromptTemplateItem>,
        userPrependItems: List<PromptTemplateItem>, userPostpendItems: List<PromptTemplateItem>
    ) {
        scope.launch {
            settingsManager.saveSystemPrompts(systemPrompts.value.map { if (it.id == id) it.copy(title = title, content = "", systemItems = systemItems, userPrependItems = userPrependItems, userPostpendItems = userPostpendItems) else it })
        }
    }

    fun setActiveSystemPrompt(id: String) {
        scope.launch { settingsManager.setActiveSystemPromptId(id) }
    }

    // Custom provider CRUD. ProviderRegistry owns live instance construction.
    fun addCustomProvider(config: CustomProviderConfig, baseUrl: String) {
        if (
            CustomProviderNamePolicy.hasConflict(
                name = config.name,
                existingNames = customProviders.value.map { it.name },
            )
        ) return
        scope.launch {
            settingsManager.saveCustomEndpointResolution(config.name, null)
            settingsManager.saveProviderBaseUrl(config.name, baseUrl)
            settingsManager.saveCustomProviders(customProviders.value + config)
        }
    }

    fun renameCustomProvider(oldName: String, newName: String) {
        if (!CustomProviderNamePolicy.isAllowed(oldName)) return
        if (
            CustomProviderNamePolicy.hasConflict(
                name = newName,
                existingNames = customProviders.value.map { it.name },
                currentName = oldName,
            )
        ) return
        // A malformed/legacy custom provider may have no explicit URL entry. Renaming is still a
        // name-only operation and must not silently no-op after the dialog closes; preserve the
        // missing value as missing while remapping every other provider-keyed reference.
        val url = providerBaseUrls.value[oldName].orEmpty()
        scope.launch {
            val updated = customProviders.value.toMutableList()
            val idx = updated.indexOfFirst { it.name == oldName }
            if (idx >= 0) {
                updated[idx] = updated[idx].copy(name = newName)
                settingsManager.saveCustomProviders(updated)
                settingsManager.renameCustomEndpointResolution(oldName, newName)
                settingsManager.saveProviderBaseUrl(oldName, "")
                settingsManager.saveProviderBaseUrl(newName, url)
                val models = availableModels.value.toMutableMap()
                models[newName] = models.remove(oldName) ?: emptyList()
                settingsManager.saveAvailableModels(newName, models[newName] ?: emptyList())
                settingsManager.saveAvailableModels(oldName, emptyList())
                settingsManager.renameApiKeyProvider(oldName, newName)
            }
        }
    }

    fun setCustomProviderResponsesApiEnabled(name: String, enabled: Boolean) {
        if (!CustomProviderNamePolicy.isAllowed(name)) return
        scope.launch {
            settingsManager.saveCustomProviders(customProviders.value.map { config ->
                if (config.name == name) config.copy(responsesApiEnabled = enabled) else config
            })
        }
    }

    fun updateCustomProviderProtocol(name: String, protocol: CustomEndpointProtocol) {
        if (!CustomProviderNamePolicy.isAllowed(name)) return
        scope.launch {
            val updated = customProviders.value.map { config ->
                if (config.name == name) config.copy(protocol = protocol) else config
            }
            settingsManager.saveCustomEndpointResolution(name, null)
            settingsManager.saveCustomProviders(updated)
        }
    }

    fun deleteCustomProvider(name: String) {
        if (!CustomProviderNamePolicy.isAllowed(name)) return
        val providerId = customProviders.value.firstOrNull { it.name == name }
            ?.providerId
            ?: return
        scope.launch {
            settingsManager.saveCustomProviders(customProviders.value.filter { it.name != name })
            settingsManager.saveCustomEndpointResolution(name, null)
            settingsManager.saveAvailableModels(name, emptyList())
            settingsManager.saveCustomModels(
                customModels.value.filterTo(linkedSetOf()) {
                    ModelId.parse(it).providerName != providerId
                }
            )
            settingsManager.saveEnabledModels(
                enabledModels.value.filter { !it.startsWith("$providerId:") }.toSet(),
            )
            settingsManager.removeModelAliasesForProvider(providerId)
            settingsManager.saveProviderBaseUrl(name, "")
            settingsManager.saveApiKeys(apiKeys.value.filter { it.provider != name })
            settingsManager.setActiveApiKeyId(name, null)
        }
    }

    // Image transcription
    fun addImageTranscriptionModels(models: Set<String>) = scope.launch { settingsManager.saveImageTranscriptionEnabledModels(imageTranscriptionEnabledModels.value + models) }
    fun removeImageTranscriptionModel(model: String) = scope.launch { settingsManager.saveImageTranscriptionEnabledModels(imageTranscriptionEnabledModels.value - model) }

    // Shell devices
    fun removeShellDevice(deviceId: String) = scope.launch { settingsManager.saveShellDevices(shellDevices.value.filter { it.id != deviceId }) }
    fun removeMcpServer(serverId: String) =
        scope.launch { settingsManager.saveMcpServers(mcpServers.value.filter { it.id != serverId }) }

    fun setConversationSettings(convId: String, settings: ConversationSettings?) = scope.launch { settingsManager.saveConversationSettings(convId, settings) }

    // ── Simple setting toggles ────────────────────────────────
    fun setMaxContextWindow(window: Int) = scope.launch { settingsManager.saveMaxContextWindow(window) }
    fun setVisualizeContextRollout(enabled: Boolean) = scope.launch { settingsManager.saveVisualizeContextRollout(enabled) }
    fun setProviderBaseUrl(provider: String, url: String) = scope.launch {
        settingsManager.saveCustomEndpointResolution(provider, null)
        settingsManager.saveProviderBaseUrl(provider, url)
    }
    fun setTitleGenerationEnabled(enabled: Boolean) = scope.launch { settingsManager.saveTitleGenerationEnabled(enabled) }
    fun setTitleGenerationNotificationsEnabled(enabled: Boolean) =
        scope.launch { settingsManager.saveTitleGenerationNotificationsEnabled(enabled) }
    fun setContextCompactEnabled(enabled: Boolean) = scope.launch { settingsManager.saveContextCompactEnabled(enabled) }
    fun setContextCompactModel(model: String?) = scope.launch { settingsManager.saveContextCompactModel(model) }
    fun setContextCompactPrompt(prompt: String) = scope.launch { settingsManager.saveContextCompactPrompt(prompt) }
    fun setContextCompactRetainCount(count: Int) = scope.launch { settingsManager.saveContextCompactRetainCount(count) }
    fun setContextCompactThresholdPercent(percent: Int) = scope.launch {
        settingsManager.saveContextCompactThresholdPercent(percent)
    }

    fun setTitleGenerationModel(model: String?) = scope.launch { settingsManager.saveTitleGenerationModel(model) }
    fun setTitleGenerationPrompt(prompt: String) = scope.launch { settingsManager.saveTitleGenerationPrompt(prompt) }
    fun setImageTranscriptionEnabled(enabled: Boolean) =
        scope.launch { settingsManager.saveImageTranscriptionEnabled(enabled) }
    fun setImageTranscriptionModel(model: String?) = scope.launch { settingsManager.saveImageTranscriptionModel(model) }
    fun setImageTranscriptionBatchSize(size: Int) = scope.launch { settingsManager.saveImageTranscriptionBatchSize(size) }
    fun setImageTranscriptionPrompt(prompt: String) = scope.launch { settingsManager.saveImageTranscriptionPrompt(prompt) }
    fun setAccessPastConversations(enabled: Boolean) = scope.launch { settingsManager.saveAccessPastConversations(enabled) }
    fun setAccessSavedMemories(enabled: Boolean) = scope.launch { settingsManager.saveAccessSavedMemories(enabled) }
    fun setAccessActiveMemory(enabled: Boolean) = scope.launch { settingsManager.saveAccessActiveMemory(enabled) }
    fun setRagSearchEnabled(enabled: Boolean) = scope.launch { settingsManager.saveRagSearchEnabled(enabled) }
    fun setAutoCacheEnabled(enabled: Boolean) = scope.launch { settingsManager.saveAutoCacheEnabled(enabled) }
    fun setAutoUpdateCheck(enabled: Boolean) = scope.launch { settingsManager.saveAutoUpdateCheck(enabled) }
    fun setLastUpdateCheckTime(time: Long) = scope.launch { settingsManager.saveLastUpdateCheckTime(time) }
    fun setModelSearchMethod(method: String) = scope.launch { settingsManager.saveModelSearchMethod(method) }
    fun setManualSearchMethod(method: String) = scope.launch { settingsManager.saveManualSearchMethod(method) }
    fun setAppLanguage(language: String) = scope.launch { settingsManager.saveAppLanguage(language) }
    fun setWebSearchEnabled(enabled: Boolean) = scope.launch { settingsManager.saveWebSearchEnabled(enabled) }
    fun setWebSearchProvider(provider: String) = scope.launch { settingsManager.saveWebSearchProvider(provider) }
    fun setWebSearchApiKey(provider: String, apiKey: String) = scope.launch { settingsManager.saveWebSearchApiKey(provider, apiKey) }
    fun setWebSearchNumResults(n: Int) = scope.launch { settingsManager.saveWebSearchNumResults(n) }
    fun setWebSearchBaseUrl(url: String) = scope.launch { settingsManager.saveWebSearchBaseUrl(url) }
    fun setImageGenEnabled(enabled: Boolean) = scope.launch { settingsManager.saveImageGenEnabled(enabled) }
    fun setImageGenModel(model: String?) = scope.launch { settingsManager.saveImageGenModel(model) }
    fun setImageGenSize(size: String) = scope.launch { settingsManager.saveImageGenSize(size) }
    fun setShowDocumentationFab(enabled: Boolean) = scope.launch { settingsManager.saveShowDocumentationFab(enabled) }
    fun setDeveloperOptionsEnabled(enabled: Boolean) =
        scope.launch { settingsManager.saveDeveloperOptionsEnabled(enabled) }
    fun setShellEnabled(enabled: Boolean) = scope.launch { settingsManager.saveShellEnabled(enabled) }
    fun setAutomationToolsEnabled(enabled: Boolean) = scope.launch { settingsManager.saveAutomationToolsEnabled(enabled) }
    fun setExactExecutionEnabled(enabled: Boolean) = scope.launch { settingsManager.saveExactExecutionEnabled(enabled) }
    fun setProxyEnabled(enabled: Boolean) = scope.launch { settingsManager.saveProxyEnabled(enabled) }
    fun setProxyType(type: String) = scope.launch { settingsManager.saveProxyType(type) }
    fun setProxyHost(host: String) = scope.launch { settingsManager.saveProxyHost(host) }
    fun setProxyPort(port: String) = scope.launch { settingsManager.saveProxyPort(port) }
    fun setProxyUsername(user: String) = scope.launch { settingsManager.saveProxyUsername(user) }
    fun setProxyPassword(pass: String) = scope.launch { settingsManager.saveProxyPassword(pass) }
    fun setProxyBypass(bypass: String) = scope.launch { settingsManager.saveProxyBypass(bypass) }
    fun setSandboxEnabled(enabled: Boolean) = scope.launch { settingsManager.saveSandboxEnabled(enabled) }
    fun setSandboxSharedStorageEnabled(enabled: Boolean) =
        scope.launch { settingsManager.saveSandboxSharedStorageEnabled(enabled) }
    fun setThinkingEnabled(enabled: Boolean) = scope.launch { settingsManager.saveThinkingEnabled(enabled) }
    fun setThinkingLevel(level: String) = scope.launch { settingsManager.saveThinkingLevel(level) }
    fun setThinkingBudgetEnabled(enabled: Boolean) = scope.launch { settingsManager.saveThinkingBudgetEnabled(enabled) }
    fun setThinkingBudgetTokens(tokens: Int) = scope.launch { settingsManager.saveThinkingBudgetTokens(tokens) }
    fun setOpenAiServiceTierEnabled(enabled: Boolean) =
        scope.launch { settingsManager.saveOpenAiServiceTierEnabled(enabled) }
    fun setOpenAiServiceTier(tier: String) =
        scope.launch { settingsManager.saveOpenAiServiceTier(tier) }
    fun setOpenAiResponsesApiEnabled(enabled: Boolean) =
        scope.launch { settingsManager.saveOpenAiResponsesApiEnabled(enabled) }
    fun setDefaultTemperature(v: Float?) = scope.launch { settingsManager.saveDefaultTemperature(v) }
    fun setDefaultMaxTokens(v: Int?) = scope.launch { settingsManager.saveDefaultMaxTokens(v) }
    fun setDefaultTopP(v: Float?) = scope.launch { settingsManager.saveDefaultTopP(v) }
    fun setDefaultFrequencyPenalty(v: Float?) = scope.launch { settingsManager.saveDefaultFrequencyPenalty(v) }
    fun setDefaultPresencePenalty(v: Float?) = scope.launch { settingsManager.saveDefaultPresencePenalty(v) }
    fun setThemeMode(mode: String) = scope.launch { settingsManager.saveThemeMode(mode) }
    fun setColorScheme(scheme: String) = scope.launch { settingsManager.saveColorScheme(scheme) }
    fun setDynamicColor(enabled: Boolean) = scope.launch { settingsManager.saveDynamicColor(enabled) }
    fun setBlurEffectsEnabled(enabled: Boolean) = scope.launch { settingsManager.saveBlurEffectsEnabled(enabled) }
    fun setReduceMotion(enabled: Boolean) = scope.launch { settingsManager.saveReduceMotion(enabled) }
    fun setParseInlineDollarMath(enabled: Boolean) =
        scope.launch { settingsManager.saveParseInlineDollarMath(enabled) }
    fun setHapticsEnabled(enabled: Boolean) = scope.launch { settingsManager.saveHapticsEnabled(enabled) }
    fun setDetailedTokenUsage(enabled: Boolean) =
        scope.launch { settingsManager.saveDetailedTokenUsage(enabled) }
    fun setToolCallDisplayMode(mode: String) = scope.launch { settingsManager.saveToolCallDisplayMode(mode) }
    fun setThinkingSegmentDisplayMode(mode: String) =
        scope.launch { settingsManager.saveThinkingSegmentDisplayMode(mode) }

    fun setAutoExpandActiveGroup(enabled: Boolean) =
        scope.launch { settingsManager.saveAutoExpandActiveGroup(enabled) }
    fun setSchemeStyle(style: String) = scope.launch { settingsManager.saveSchemeStyle(style) }
    fun setFontPreference(value: String) = scope.launch { settingsManager.saveFontPreference(value) }
    fun setCustomFontPath(value: String) = scope.launch { settingsManager.saveCustomFontPath(value) }
    fun setCustomFontName(value: String) = scope.launch { settingsManager.saveCustomFontName(value) }
    fun setSearchMatchLimit(n: Int) = scope.launch { settingsManager.saveSearchMatchLimit(n) }
    fun setSearchContextWindow(n: Int) = scope.launch { settingsManager.saveSearchContextWindow(n) }
    fun setRagThreshold(threshold: Float) = scope.launch { settingsManager.saveRagThreshold(threshold) }

    fun setShellConfirmEnabled(enabled: Boolean) = scope.launch { settingsManager.saveShellConfirmEnabled(enabled) }
    fun addShellDevice(device: ShellDeviceConfig) = scope.launch { settingsManager.saveShellDevices(shellDevices.value + device) }
    fun updateShellDevice(device: ShellDeviceConfig) = scope.launch {
        settingsManager.saveShellDevices(shellDevices.value.map { if (it.id == device.id) device else it })
    }
    fun addMcpServer(server: McpServerConfig) =
        scope.launch { settingsManager.saveMcpServers(mcpServers.value + server) }
    fun updateMcpServer(server: McpServerConfig) = scope.launch {
        settingsManager.saveMcpServers(
            mcpServers.value.map { if (it.id == server.id) server else it },
        )
    }

    // ── Derived lookups ─────────────────────────────────────────
    /** Resolves the currently-active cleartext API key for [provider], or `null`. */
    fun resolveActiveKey(provider: String): String? =
        apiKeys.value.find { it.id == activeApiKeyIds.value[provider] }?.key

    /**
     * Like [resolveActiveKey] but awaits the on-disk DataStore values instead of
     * reading the eagerly-shared `.value`, which may still be the empty default
     * during the startup window before DataStore loads. Use this on the request-
     * build path: reading `.value` there races the load and yields a blank key →
     * an empty `Authorization` header → intermittent 401s on providers that are
     * considered configured by base-URL alone (custom / OpenAI-compatible / Ollama).
     */
    suspend fun awaitActiveKey(provider: String): String? {
        val activeIds = settingsManager.activeApiKeyIds.first()
        val keys = settingsManager.apiKeys.first()
        return keys.find { it.id == activeIds[provider] }?.key
    }

    // ── Suspending DataStore access ───────────────────────────
    //
    // The StateFlows above are eagerly-shared with a default initial value, so at app
    // startup `.value` may briefly be the default before DataStore loads. These suspend
    // accessors read/write DataStore directly (awaiting the on-disk value, preserving
    // write ordering) for callers that need the persisted value immediately or ordered,
    // read-after-write semantics. They keep [SettingsManager] encapsulated as an internal
    // detail of this repository — the single owner of the settings surface.

    suspend fun getAutoUpdateCheck(): Boolean = settingsManager.autoUpdateCheck.first()
    suspend fun getLastUpdateCheckTime(): Long = settingsManager.lastUpdateCheckTime.first()
    suspend fun getEmbeddingModels(): List<EmbeddingModelConfig> = settingsManager.embeddingModels.first()
    suspend fun getActiveEmbeddingModelId(): String = settingsManager.activeEmbeddingModelId.first()
    suspend fun getModelAliases(): Map<String, String> = settingsManager.modelAliases.first()
    suspend fun getProviderBaseUrls(): Map<String, String> = settingsManager.providerBaseUrls.first()
    suspend fun saveCustomEndpointResolution(
        provider: String,
        resolution: CustomEndpointResolution,
    ) = settingsManager.saveCustomEndpointResolution(provider, resolution)
    suspend fun getAvailableModels(): Map<String, List<String>> = settingsManager.availableModels.first()
    suspend fun getSystemPrompts(): List<SystemPromptEntry> = settingsManager.systemPrompts.first()

    internal suspend fun normalizeCustomProviderIdentities(): List<CustomProviderIdentityMigration> =
        settingsManager.normalizeCustomProviderIdentities()

    internal suspend fun clearLegacyCustomProviderNames(
        completed: List<CustomProviderIdentityMigration>,
    ) = settingsManager.clearLegacyCustomProviderNames(completed)

    suspend fun saveAvailableModels(provider: String, models: List<String>) = settingsManager.saveAvailableModels(provider, models)
    suspend fun saveCustomModels(models: Set<String>) = settingsManager.saveCustomModels(models)
    suspend fun saveModelAliases(aliases: Map<String, String>) = settingsManager.saveModelAliases(aliases)
    suspend fun updateStoredModelAlias(modelId: String, alias: String) =
        settingsManager.updateModelAlias(modelId, alias)
    suspend fun synchronizeLocalModelAliases(aliases: Map<String, String>) =
        settingsManager.synchronizeLocalModelAliases(aliases)
    suspend fun saveLastUpdateCheckTime(time: Long) = settingsManager.saveLastUpdateCheckTime(time)
    suspend fun saveLastModelsFetchFingerprint(fingerprint: String) = settingsManager.saveLastModelsFetchFingerprint(fingerprint)
    suspend fun incrementMessagesSent() = settingsManager.incrementMessagesSent()
    suspend fun saveLocalChatModels(models: List<LocalChatModelConfig>) = settingsManager.saveLocalChatModels(models)
    suspend fun saveEmbeddingModels(models: List<EmbeddingModelConfig>) = settingsManager.saveEmbeddingModels(models)
    suspend fun setActiveEmbeddingModelId(id: String) = settingsManager.setActiveEmbeddingModelId(id)
    suspend fun saveAutoBackupEnabled(enabled: Boolean) = settingsManager.saveAutoBackupEnabled(enabled)
    suspend fun saveAutoBackupPeriodHours(hours: Int) = settingsManager.saveAutoBackupPeriodHours(hours)
    suspend fun saveAutoBackupCategories(categories: String) = settingsManager.saveAutoBackupCategories(categories)
    suspend fun saveAutoBackupDirectory(path: String) = settingsManager.saveAutoBackupDirectory(path)
    suspend fun saveAutoDeleteEnabled(enabled: Boolean) = settingsManager.saveAutoDeleteEnabled(enabled)
    suspend fun saveAutoDeletePeriodHours(hours: Int) = settingsManager.saveAutoDeletePeriodHours(hours)

    fun stableProviderReference(reference: String): String {
        val normalized = reference.trim()
        return customProviders.value.firstOrNull { provider ->
            provider.name == normalized || provider.ownsIdentity(normalized)
        }?.providerId ?: normalized
    }
}
