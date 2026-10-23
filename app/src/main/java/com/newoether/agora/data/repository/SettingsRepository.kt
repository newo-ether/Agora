package com.newoether.agora.data.repository

import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ContextBudget

import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.DEFAULT_COLOR_SCHEME
import com.newoether.agora.data.DEFAULT_CONTEXT_COMPACT_ENABLED
import com.newoether.agora.data.DEFAULT_CONTEXT_COMPACT_PRESERVE_SYSTEM_PROMPT
import com.newoether.agora.data.DEFAULT_CONTEXT_COMPACT_RETAIN_COUNT
import com.newoether.agora.data.DEFAULT_CONTEXT_COMPACT_THRESHOLD_PERCENT
import com.newoether.agora.data.DEFAULT_DYNAMIC_COLOR
import com.newoether.agora.data.DEFAULT_LOCAL_MODEL_IDLE_RETENTION_MINUTES
import com.newoether.agora.data.DEFAULT_LOCAL_LOW_CONTEXT_MODE_ENABLED
import com.newoether.agora.data.DEFAULT_SUBAGENT_MAX_TOTAL
import com.newoether.agora.data.DEFAULT_SUBAGENT_MAX_CONCURRENT
import com.newoether.agora.data.DEFAULT_SCHEME_STYLE
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomEndpointResolution
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.CustomProviderIdentityMigration
import com.newoether.agora.data.CustomProviderNamePolicy
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.PredefinedVariables
import com.newoether.agora.data.PromptTemplateItem
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    settingsManager: SettingsManager,
    scope: CoroutineScope,
    private val touchConversationData: suspend (String) -> Unit = {},
) : SettingsRepositoryAppearanceBase(settingsManager, scope) {
    private val publication = SettingsFlowPublication(scope)

    private val conversationSettingsState = ConversationSettingsState()
    private val conversationSettingsWriteMutex = Mutex()

    /**
     * Suspends until every settings StateFlow has received its first on-disk DataStore value.
     * Background workers must cross this barrier before reading `.value`; otherwise a cold-start
     * alarm can briefly observe repository defaults (for example the sample model or empty custom
     * providers) and permanently consume the scheduled occurrence with the wrong configuration.
     */
    suspend fun awaitInitialLoad() {
        publication.awaitInitialLoad()
    }

    // ── Read StateFlows (eagerly shared) ──────────────────────

    val selectedModel: StateFlow<String> = publication.hot(settingsManager.selectedModel, Constants.EXAMPLE_MODEL_ID)
    val anthropicCacheEnabled: StateFlow<Boolean> = publication.hot(settingsManager.anthropicCacheEnabled, true)
    val anthropicCacheTtl: StateFlow<String> = publication.hot(settingsManager.anthropicCacheTtl, "1h")
    fun setAnthropicCacheEnabled(providerId: String, enabled: Boolean) = scope.launch {
        if (providerId == Constants.PROVIDER_ANTHROPIC) settingsManager.saveAnthropicCacheEnabled(enabled)
        else settingsManager.updateCustomProviderCache(providerId, enabled = enabled)
    }
    fun setAnthropicCacheTtl(providerId: String, ttl: String) = scope.launch {
        if (providerId == Constants.PROVIDER_ANTHROPIC) settingsManager.saveAnthropicCacheTtl(ttl)
        else settingsManager.updateCustomProviderCache(providerId, ttl = ttl)
    }
    val availableModels: StateFlow<Map<String, List<String>>> = publication.hot(settingsManager.availableModels, emptyMap())
    val customModels: StateFlow<Set<String>> = publication.hot(settingsManager.customModels, emptySet())
    val enabledModels: StateFlow<Set<String>> = publication.hot(settingsManager.enabledModels, emptySet())
    val modelAliases: StateFlow<Map<String, String>> = publication.hot(settingsManager.modelAliases, emptyMap())
    val modelProviderNames: StateFlow<Map<String, Boolean>> = publication.hot(settingsManager.modelProviderNames, emptyMap())
    val apiKeys: StateFlow<List<ApiKeyEntry>> = publication.hot(settingsManager.apiKeys, emptyList())
    val activeApiKeyIds: StateFlow<Map<String, String>> = publication.hot(settingsManager.activeApiKeyIds, emptyMap())
    val systemPrompts: StateFlow<List<SystemPromptEntry>> = publication.hot(settingsManager.systemPrompts, emptyList())
    val activeSystemPromptId: StateFlow<String?> = publication.hot(settingsManager.activeSystemPromptId, null)
    val maxContextWindow: StateFlow<Int> =
        publication.hot(settingsManager.maxContextWindow, ContextBudget.DEFAULT_TOKENS)
    val visualizeContextRollout: StateFlow<Boolean> = publication.hot(settingsManager.visualizeContextRollout, false)
    val contextCompactEnabled: StateFlow<Boolean> = publication.hot(
        settingsManager.contextCompactEnabled,
        DEFAULT_CONTEXT_COMPACT_ENABLED,
    )
    val contextCompactModel: StateFlow<String?> = publication.hot(settingsManager.contextCompactModel, null)
    val contextCompactPrompt: StateFlow<String> = publication.hot(settingsManager.contextCompactPrompt, BuiltInPrompts.CONTEXT_COMPACT_SYSTEM)
    val contextCompactRetainCount: StateFlow<Int> = publication.hot(
        settingsManager.contextCompactRetainCount,
        DEFAULT_CONTEXT_COMPACT_RETAIN_COUNT,
    )
    val contextCompactPreserveSystemPrompt: StateFlow<Boolean> =
        publication.hot(settingsManager.contextCompactPreserveSystemPrompt, DEFAULT_CONTEXT_COMPACT_PRESERVE_SYSTEM_PROMPT)
    val contextCompactThresholdPercent: StateFlow<Int> = publication.hot(
        settingsManager.contextCompactThresholdPercent,
        DEFAULT_CONTEXT_COMPACT_THRESHOLD_PERCENT,
    )
    val codeExecutionEnabled: StateFlow<Boolean> = publication.hot(settingsManager.codeExecutionEnabled, false)
    val googleSearchEnabled: StateFlow<Boolean> = publication.hot(settingsManager.googleSearchEnabled, false)
    val thinkingEnabled: StateFlow<Boolean> = publication.hot(settingsManager.thinkingEnabled, true)
    val thinkingLevel: StateFlow<String> = publication.hot(settingsManager.thinkingLevel, "medium")
    val thinkingBudgetEnabled: StateFlow<Boolean> = publication.hot(settingsManager.thinkingBudgetEnabled, false)
    val thinkingBudgetTokens: StateFlow<Int> = publication.hot(settingsManager.thinkingBudgetTokens, 4096)
    val openAiServiceTierEnabled: StateFlow<Boolean> = publication.hot(settingsManager.openAiServiceTierEnabled, false)
    val openAiServiceTier: StateFlow<String> = publication.hot(settingsManager.openAiServiceTier, OpenAiServiceTiers.AUTO)
    val openAiResponsesApiEnabled: StateFlow<Boolean> =
        publication.hot(settingsManager.openAiResponsesApiEnabled, false)
    val providerBaseUrls: StateFlow<Map<String, String>> = publication.hot(settingsManager.providerBaseUrls, emptyMap())
    val customEndpointResolutions: StateFlow<Map<String, CustomEndpointResolution>> =
        publication.hot(settingsManager.customEndpointResolutions, emptyMap())
    val titleGenerationEnabled: StateFlow<Boolean> = publication.hot(settingsManager.titleGenerationEnabled, true)
    val titleGenerationModel: StateFlow<String?> = publication.hot(settingsManager.titleGenerationModel, null)
    val titleGenerationPrompt: StateFlow<String> = publication.hot(settingsManager.titleGenerationPrompt, BuiltInPrompts.TITLE_GENERATION_SYSTEM)
    val titleGenerationNotificationsEnabled: StateFlow<Boolean> =
        publication.hot(settingsManager.titleGenerationNotificationsEnabled, true)
    val imageTranscriptionEnabled: StateFlow<Boolean> =
        publication.hot(settingsManager.imageTranscriptionEnabled, true)
    val imageTranscriptionEnabledModels: StateFlow<Set<String>> = publication.hot(settingsManager.imageTranscriptionEnabledModels, emptySet())
    val imageTranscriptionModel: StateFlow<String?> = publication.hot(settingsManager.imageTranscriptionModel, null)
    val imageTranscriptionBatchSize: StateFlow<Int> = publication.hot(settingsManager.imageTranscriptionBatchSize, 3)
    val imageTranscriptionPrompt: StateFlow<String> = publication.hot(settingsManager.imageTranscriptionPrompt, BuiltInPrompts.IMAGE_TRANSCRIPTION_USER)
    val accessPastConversations: StateFlow<Boolean> = publication.hot(settingsManager.accessPastConversations, true)
    val accessSavedMemories: StateFlow<Boolean> = publication.hot(settingsManager.accessSavedMemories, true)
    val accessActiveMemory: StateFlow<Boolean> = publication.hot(settingsManager.accessActiveMemory, true)
    val accessSkills: StateFlow<Boolean> = publication.hot(settingsManager.accessSkills, true)
    val accessSkillsModify: StateFlow<Boolean> = publication.hot(settingsManager.accessSkillsModify, true)
    val ragSearchEnabled: StateFlow<Boolean> = publication.hot(settingsManager.ragSearchEnabled, false)
    val autoCacheEnabled: StateFlow<Boolean> = publication.hot(settingsManager.autoCacheEnabled, true)
    val showUncachedNotification: StateFlow<Boolean> = publication.hot(settingsManager.showUncachedNotification, true)
    val autoUpdateCheck: StateFlow<Boolean> = publication.hot(settingsManager.autoUpdateCheck, true)
    val lastUpdateCheckTime: StateFlow<Long> = publication.hot(settingsManager.lastUpdateCheckTime, 0L)
    val modelSearchMethod: StateFlow<String> = publication.hot(settingsManager.modelSearchMethod, "keyword")
    val manualSearchMethod: StateFlow<String> = publication.hot(settingsManager.manualSearchMethod, "keyword")
    val embeddingModels: StateFlow<List<EmbeddingModelConfig>> = publication.hot(settingsManager.embeddingModels, emptyList())
    val activeEmbeddingModelId: StateFlow<String> = publication.hot(settingsManager.activeEmbeddingModelId, "")
    val appLanguage: StateFlow<String> = publication.hot(settingsManager.appLanguage, "system")
    val webSearchEnabled: StateFlow<Boolean> = publication.hot(settingsManager.webSearchEnabled, false)
    val webSearchProvider: StateFlow<String> = publication.hot(settingsManager.webSearchProvider, "duckduckgo")
    val webSearchApiKeys: StateFlow<Map<String, String>> = publication.hot(settingsManager.webSearchApiKeys, emptyMap())
    val webSearchNumResults: StateFlow<Int> = publication.hot(settingsManager.webSearchNumResults, 5)
    val webSearchBaseUrl: StateFlow<String> = publication.hot(settingsManager.webSearchBaseUrl, "")
    val imageGenEnabled: StateFlow<Boolean> = publication.hot(settingsManager.imageGenEnabled, false)
    val imageGenModel: StateFlow<String?> = publication.hot(settingsManager.imageGenModel, null)
    val imageGenSize: StateFlow<String> = publication.hot(settingsManager.imageGenSize, "1024x1024")
    val showDocumentationFab: StateFlow<Boolean> = publication.hot(settingsManager.showDocumentationFab, true)
    val developerOptionsEnabled: StateFlow<Boolean> =
        publication.hot(settingsManager.developerOptionsEnabled, false)
    val debugModelEnabled: StateFlow<Boolean> =
        publication.hot(settingsManager.debugModelEnabled, false)
    val shellEnabled: StateFlow<Boolean> = publication.hot(settingsManager.shellEnabled, false)
    val automationToolsEnabled: StateFlow<Boolean> = publication.hot(settingsManager.automationToolsEnabled, false)
    val exactExecutionEnabled: StateFlow<Boolean> = publication.hot(settingsManager.exactExecutionEnabled, false)
    val automationWakeLockEnabled: StateFlow<Boolean> =
        publication.hot(settingsManager.automationWakeLockEnabled, false)
    val proxyEnabled: StateFlow<Boolean> = publication.hot(settingsManager.proxyEnabled, false)
    val proxyType: StateFlow<String> = publication.hot(settingsManager.proxyType, "http")
    val proxyHost: StateFlow<String> = publication.hot(settingsManager.proxyHost, com.newoether.agora.data.SettingsManager.DEFAULT_PROXY_HOST)
    val proxyPort: StateFlow<String> = publication.hot(settingsManager.proxyPort, com.newoether.agora.data.SettingsManager.DEFAULT_PROXY_PORT)
    val proxyUsername: StateFlow<String> = publication.hot(settingsManager.proxyUsername, "")
    val proxyPassword: StateFlow<String> = publication.hot(settingsManager.proxyPassword, "")
    val proxyBypass: StateFlow<String> = publication.hot(settingsManager.proxyBypass, com.newoether.agora.data.SettingsManager.DEFAULT_PROXY_BYPASS)
    val shellConfirmEnabled: StateFlow<Boolean> = publication.hot(settingsManager.shellConfirmEnabled, true)
    val askUserEnabled: StateFlow<Boolean> = publication.hot(settingsManager.askUserEnabled, true)
    val askUserTimeoutMinutes: StateFlow<Int> = publication.hot(settingsManager.askUserTimeoutMinutes, com.newoether.agora.data.DEFAULT_ASK_USER_TIMEOUT_MINUTES)
    val todoEnabled: StateFlow<Boolean> = publication.hot(settingsManager.todoEnabled, true)
    val subagentToolsEnabled: StateFlow<Boolean> = publication.hot(settingsManager.subagentToolsEnabled, true)
    val subagentMaxTotal: StateFlow<Int> = publication.hot(settingsManager.subagentMaxTotal, DEFAULT_SUBAGENT_MAX_TOTAL)
    val subagentMaxConcurrent: StateFlow<Int> = publication.hot(settingsManager.subagentMaxConcurrent, DEFAULT_SUBAGENT_MAX_CONCURRENT)
    suspend fun getSubagentMaxTotal(): Int = settingsManager.subagentMaxTotal.first()
    suspend fun getSubagentMaxConcurrent(): Int = settingsManager.subagentMaxConcurrent.first()
    val subagentCapsuleDisplayMode: StateFlow<String> = publication.hot(settingsManager.subagentCapsuleDisplayMode, com.newoether.agora.data.SubagentCapsuleDisplayModes.DEFAULT)
    val subagentEnabledModels: StateFlow<Set<String>?> = publication.hot(settingsManager.subagentEnabledModels, null)
    val subagentRecommendedModel: StateFlow<String?> = publication.hot(settingsManager.subagentRecommendedModel, null)
    fun addSubagentModels(models: Set<String>) = scope.launch { settingsManager.addSubagentModels(models) }
    fun removeSubagentModel(model: String) = scope.launch { settingsManager.removeSubagentModel(model) }
    fun setSubagentRecommendedModel(model: String?) = scope.launch { settingsManager.saveSubagentRecommendedModel(model) }
    suspend fun getSubagentModelIds(): Set<String> = settingsManager.subagentModelIds.first()
    val shellDevices: StateFlow<List<ShellDeviceConfig>> = publication.hot(settingsManager.shellDevices, emptyList())
    val mcpServers: StateFlow<List<McpServerConfig>> = publication.hot(settingsManager.mcpServers, emptyList())
    val sandboxEnabled: StateFlow<Boolean> = publication.hot(settingsManager.sandboxEnabled, false)
    val sandboxSharedStorageEnabled: StateFlow<Boolean> =
        publication.hot(settingsManager.sandboxSharedStorageEnabled, false)
    val defaultTemperature: StateFlow<Float?> = publication.hot(settingsManager.defaultTemperature, null)
    val defaultMaxTokens: StateFlow<Int?> = publication.hot(settingsManager.defaultMaxTokens, null)
    val defaultTopP: StateFlow<Float?> = publication.hot(settingsManager.defaultTopP, null)
    val defaultFrequencyPenalty: StateFlow<Float?> = publication.hot(settingsManager.defaultFrequencyPenalty, null)
    val defaultPresencePenalty: StateFlow<Float?> = publication.hot(settingsManager.defaultPresencePenalty, null)
    val conversationSettings: StateFlow<Map<String, ConversationSettings>> = publication.conversationSettings(settingsManager, conversationSettingsState)
    val themeMode: StateFlow<String> = publication.hot(settingsManager.themeMode, "FOLLOW_DEVICE")
    val amoledEnabled: StateFlow<Boolean> = publication.hot(settingsManager.amoledEnabled, false)
    val colorScheme: StateFlow<String> = publication.hot(settingsManager.colorScheme, DEFAULT_COLOR_SCHEME)
    val dynamicColor: StateFlow<Boolean> = publication.hot(settingsManager.dynamicColor, DEFAULT_DYNAMIC_COLOR)
    val blurEffectsEnabled: StateFlow<Boolean> = publication.hot(settingsManager.blurEffectsEnabled, true)
    val reduceMotion: StateFlow<Boolean> = publication.hot(settingsManager.reduceMotion, false)
    val stickToBottom: StateFlow<Boolean> = publication.hot(settingsManager.stickToBottom, true)
    val parseInlineDollarMath: StateFlow<Boolean> = publication.hot(settingsManager.parseInlineDollarMath, false)
    val autoWrapCodeBlocks: StateFlow<Boolean> = publication.hot(settingsManager.autoWrapCodeBlocks, true)
    val hapticsEnabled: StateFlow<Boolean> = publication.hot(settingsManager.hapticsEnabled, true)
    val detailedTokenUsage: StateFlow<Boolean> = publication.hot(settingsManager.detailedTokenUsage, false)
    val toolCallDisplayMode: StateFlow<String> = publication.hot(settingsManager.toolCallDisplayMode, ToolCallDisplayModes.DEFAULT)
    val thinkingSegmentDisplayMode: StateFlow<String> = publication.hot(
        settingsManager.thinkingSegmentDisplayMode,
        ThinkingSegmentDisplayModes.DEFAULT,
    )
    val autoExpandActiveGroup: StateFlow<Boolean> =
        publication.hot(settingsManager.autoExpandActiveGroup, true)
    val schemeStyle: StateFlow<String> = publication.hot(settingsManager.schemeStyle, DEFAULT_SCHEME_STYLE)
    val fontPreference: StateFlow<String> = publication.hot(settingsManager.fontPreference, "app_default")
    val customFontPath: StateFlow<String> = publication.hot(settingsManager.customFontPath, "")
    val customFontName: StateFlow<String> = publication.hot(settingsManager.customFontName, "")
    val userBubbleColor: StateFlow<String> = publication.hot(settingsManager.userBubbleColor, "")
    val chatBackground: StateFlow<String> = publication.hot(settingsManager.chatBackground, "")
    val chatBackgroundOpacity: StateFlow<Float> = publication.hot(settingsManager.chatBackgroundOpacity, 0.3f)
    val chatBackgroundVideoMuted: StateFlow<Boolean> = publication.hot(settingsManager.chatBackgroundVideoMuted, true)
    val chatBackgroundVideoLoop: StateFlow<Boolean> = publication.hot(settingsManager.chatBackgroundVideoLoop, true)
    val searchContextWindow: StateFlow<Int> = publication.hot(settingsManager.searchContextWindow, 8)
    val searchMatchLimit: StateFlow<Int> = publication.hot(settingsManager.searchMatchLimit, 10)
    val ragThreshold: StateFlow<Float> = publication.hot(settingsManager.ragThreshold, 0.5f)
    val localChatModels: StateFlow<List<LocalChatModelConfig>> = publication.hot(settingsManager.localChatModels, emptyList())
    val localModelIdleRetentionMinutes: StateFlow<Int> = publication.hot(
        settingsManager.localModelIdleRetentionMinutes,
        DEFAULT_LOCAL_MODEL_IDLE_RETENTION_MINUTES,
    )
    val localLowContextModeEnabled: StateFlow<Boolean> = publication.hot(
        settingsManager.localLowContextModeEnabled,
        DEFAULT_LOCAL_LOW_CONTEXT_MODE_ENABLED,
    )
    val customProviders: StateFlow<List<CustomProviderConfig>> = publication.hot(settingsManager.customProviders, emptyList())
    val lastModelsFetchFingerprint: StateFlow<String> = publication.hot(settingsManager.lastModelsFetchFingerprint, "")
    // ── Auto Backup ───────────────────────────────────────────
    val autoBackupEnabled: StateFlow<Boolean> = publication.hot(settingsManager.autoBackupEnabled, true)
    val autoBackupPeriodHours: StateFlow<Int> = publication.hot(settingsManager.autoBackupPeriodHours, 24)
    val autoBackupCategories: StateFlow<String> = publication.hot(settingsManager.autoBackupCategories, "conversations,memories,system_prompts,settings")
    val autoBackupDirectory: StateFlow<String> = publication.hot(settingsManager.autoBackupDirectory, "Download/Agora/Backup")
    val autoDeleteEnabled: StateFlow<Boolean> = publication.hot(settingsManager.autoDeleteEnabled, true)
    val autoDeletePeriodHours: StateFlow<Int> = publication.hot(settingsManager.autoDeletePeriodHours, 168)
    val lastBackupTimestamp: StateFlow<Long> = publication.hot(settingsManager.lastBackupTimestamp, 0L)

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

    fun updateModelAlias(model: String, alias: String, showProviderName: Boolean? = null) {
        scope.launch {
            settingsManager.updateModelAlias(model, alias, showProviderName)
        }
    }

    fun addCustomModel(provider: String, modelName: String, alias: String = "", showProviderName: Boolean = true) {
        val normalizedProvider = stableProviderReference(provider)
        val normalizedName = modelName.trim()
        if (normalizedProvider.isEmpty() || normalizedName.isEmpty()) return
        val modelId = ModelId(normalizedProvider, normalizedName).prefixed
        scope.launch {
            settingsManager.addCustomModel(modelId, alias, showProviderName)
        }
    }

    suspend fun replaceCustomModel(
        oldModelId: String,
        newModelId: String?,
        alias: String,
        showProviderName: Boolean? = null,
    ) = settingsManager.replaceCustomModel(oldModelId, newModelId, alias, showProviderName)

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
        title: String,
        systemItems: List<PromptTemplateItem>,
        userItems: List<PromptTemplateItem>,
        assistantItems: List<PromptTemplateItem>,
    ) {
        scope.launch {
            addSystemPromptAndAwait(title, systemItems, userItems, assistantItems)
        }
    }

    suspend fun addSystemPromptAndAwait(
        title: String,
        systemItems: List<PromptTemplateItem>,
        userItems: List<PromptTemplateItem>,
        assistantItems: List<PromptTemplateItem>,
        id: String? = null,
    ): String {
        val entry = SystemPromptEntry(
            id = id ?: java.util.UUID.randomUUID().toString(),
            title = title,
            systemItems = systemItems,
            userItems = PredefinedVariables.normalizeMessageTemplate(userItems),
            assistantItems = PredefinedVariables.normalizeMessageTemplate(assistantItems),
        )
        settingsManager.systemPromptStore.update { current ->
            require(current.none { it.id == entry.id }) { "System prompt already exists" }
            current + entry
        }
        systemPrompts.first { prompts -> prompts.any { it.id == entry.id } }
        return entry.id
    }

    fun deleteSystemPrompt(id: String) {
        scope.launch {
            settingsManager.systemPromptStore.update { current -> current.filter { it.id != id } }
        }
    }

    fun updateSystemPrompt(
        id: String,
        title: String,
        systemItems: List<PromptTemplateItem>,
        userItems: List<PromptTemplateItem>,
        assistantItems: List<PromptTemplateItem>,
    ) {
        scope.launch {
            settingsManager.systemPromptStore.update { current ->
                current.map { entry ->
                if (entry.id == id) {
                    entry.copy(
                        title = title,
                        content = "",
                        systemItems = systemItems,
                        userItems = PredefinedVariables.normalizeMessageTemplate(userItems),
                        assistantItems = PredefinedVariables.normalizeMessageTemplate(assistantItems),
                        userPrependItems = emptyList(),
                        userPostpendItems = emptyList(),
                    )
                } else {
                    entry
                }
                }
            }
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
    fun removeShellDevice(deviceId: String) = scope.launch { settingsManager.shellDeviceStore.remove(deviceId) }
    fun removeMcpServer(serverId: String) =
        scope.launch { settingsManager.saveMcpServers(mcpServers.value.filter { it.id != serverId }) }

    fun setConversationSettings(convId: String, settings: ConversationSettings?) {
        persistConversationSettings(conversationSettingsState.set(convId, settings))
    }

    suspend fun setConversationSettingsAndAwait(
        convId: String,
        settings: ConversationSettings?,
    ) {
        persistConversationSettingsWrite(conversationSettingsState.set(convId, settings))
    }

    suspend fun applyConversationSettingsImportAndAwait(
        imported: Map<String, ConversationSettings>,
        replace: Boolean,
    ) {
        conversationSettingsWriteMutex.withLock {
            val updated = conversationSettingsState.applyImport(imported, replace)
            try {
                settingsManager.saveConversationSettingsMap(updated)
                conversationSettingsState.acceptPersisted(updated)
            } catch (cancelled: CancellationException) {
                val persisted = withContext(NonCancellable) {
                    runCatching { settingsManager.conversationSettings.first() }.getOrNull()
                }
                persisted?.let(conversationSettingsState::acceptPersisted)
                throw cancelled
            } catch (error: Exception) {
                runCatching { settingsManager.conversationSettings.first() }
                    .getOrNull()
                    ?.let(conversationSettingsState::acceptPersisted)
                throw error
            }
        }
    }
    fun updateConversationSettings(
        convId: String,
        update: (ConversationSettings) -> ConversationSettings,
    ) {
        persistConversationSettings(conversationSettingsState.update(convId, update))
    }

    private fun persistConversationSettings(write: ConversationSettingsWrite) {
        scope.launch {
            try {
                persistConversationSettingsWrite(write)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The optimistic state is reconciled by persistConversationSettingsWrite.
            }
        }
    }

    private suspend fun persistConversationSettingsWrite(write: ConversationSettingsWrite) {
        conversationSettingsWriteMutex.withLock {
            if (!conversationSettingsState.isLatest(write)) return@withLock
            try {
                val persisted = settingsManager.saveConversationSettings(
                    conversationId = write.conversationId,
                    settings = write.settings,
                )
                touchConversationData(write.conversationId)
                conversationSettingsState.complete(write, persisted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val persisted = runCatching {
                    settingsManager.conversationSettings.first()
                }.getOrNull()
                conversationSettingsState.fail(write, persisted)
                throw error
            }
        }
    }

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
    fun setContextCompactPreserveSystemPrompt(enabled: Boolean) =
        scope.launch { settingsManager.saveContextCompactPreserveSystemPrompt(enabled) }
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
    fun setAccessSkills(enabled: Boolean) = scope.launch { settingsManager.saveAccessSkills(enabled) }
    fun setAccessSkillsModify(enabled: Boolean) = scope.launch { settingsManager.saveAccessSkillsModify(enabled) }
    fun setRagSearchEnabled(enabled: Boolean) = scope.launch { settingsManager.saveRagSearchEnabled(enabled) }
    fun setAutoCacheEnabled(enabled: Boolean) = scope.launch { settingsManager.saveAutoCacheEnabled(enabled) }
    fun setShowUncachedNotification(enabled: Boolean) =
        scope.launch { settingsManager.saveShowUncachedNotification(enabled) }
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
    fun setDebugModelEnabled(enabled: Boolean) =
        scope.launch { settingsManager.saveDebugModelEnabled(enabled) }
    fun setShellEnabled(enabled: Boolean) = scope.launch { settingsManager.saveShellEnabled(enabled) }
    fun setAutomationToolsEnabled(enabled: Boolean) = scope.launch { settingsManager.saveAutomationToolsEnabled(enabled) }
    fun setExactExecutionEnabled(enabled: Boolean) = scope.launch { settingsManager.saveExactExecutionEnabled(enabled) }
    fun setAutomationWakeLockEnabled(enabled: Boolean) =
        scope.launch { settingsManager.saveAutomationWakeLockEnabled(enabled) }
    fun setProxyEnabled(enabled: Boolean) = scope.launch { settingsManager.saveProxyEnabled(enabled) }
    fun setProxyType(type: String) = scope.launch { settingsManager.saveProxyType(type) }
    fun setProxyHost(host: String) = scope.launch { settingsManager.saveProxyHost(host) }
    fun setProxyPort(port: String) = scope.launch { settingsManager.saveProxyPort(port) }
    fun setProxyUsername(user: String) = scope.launch { settingsManager.saveProxyUsername(user) }
    fun setProxyPassword(pass: String) = scope.launch { settingsManager.saveProxyPassword(pass) }
    fun setProxyBypass(bypass: String) = scope.launch { settingsManager.saveProxyBypass(bypass) }
    fun setSandboxEnabled(enabled: Boolean) = scope.launch { settingsManager.saveSandboxEnabled(enabled) }
    fun setSandboxSharedStorageEnabled(enabled: Boolean) = scope.launch { settingsManager.saveSandboxSharedStorageEnabled(enabled) }
    fun setThinkingEnabled(enabled: Boolean) = scope.launch { settingsManager.saveThinkingEnabled(enabled) }
    fun setThinkingLevel(level: String) = scope.launch { settingsManager.saveThinkingLevel(level) }
    fun setThinkingBudgetEnabled(enabled: Boolean) = scope.launch { settingsManager.saveThinkingBudgetEnabled(enabled) }
    fun setThinkingBudgetTokens(tokens: Int) = scope.launch { settingsManager.saveThinkingBudgetTokens(tokens) }
    fun setOpenAiServiceTierEnabled(enabled: Boolean) = scope.launch { settingsManager.saveOpenAiServiceTierEnabled(enabled) }
    fun setOpenAiServiceTier(tier: String) =
        scope.launch { settingsManager.saveOpenAiServiceTier(tier) }
    fun setOpenAiResponsesApiEnabled(enabled: Boolean) =
        scope.launch { settingsManager.saveOpenAiResponsesApiEnabled(enabled) }
    fun setDefaultTemperature(v: Float?) = scope.launch { settingsManager.saveDefaultTemperature(v) }
    fun setDefaultMaxTokens(v: Int?) = scope.launch { settingsManager.saveDefaultMaxTokens(v) }
    fun setDefaultTopP(v: Float?) = scope.launch { settingsManager.saveDefaultTopP(v) }
    fun setDefaultFrequencyPenalty(v: Float?) = scope.launch { settingsManager.saveDefaultFrequencyPenalty(v) }
    fun setDefaultPresencePenalty(v: Float?) = scope.launch { settingsManager.saveDefaultPresencePenalty(v) }
    fun setSearchMatchLimit(n: Int) = scope.launch { settingsManager.saveSearchMatchLimit(n) }
    fun setSearchContextWindow(n: Int) = scope.launch { settingsManager.saveSearchContextWindow(n) }
    fun setRagThreshold(threshold: Float) = scope.launch { settingsManager.saveRagThreshold(threshold) }

    fun setShellConfirmEnabled(enabled: Boolean) = scope.launch { settingsManager.saveShellConfirmEnabled(enabled) }
    fun setAskUserEnabled(enabled: Boolean) = scope.launch { settingsManager.saveAskUserEnabled(enabled) }
    fun setAskUserTimeoutMinutes(minutes: Int) = scope.launch { settingsManager.saveAskUserTimeoutMinutes(minutes) }
    fun setTodoEnabled(enabled: Boolean) = scope.launch { settingsManager.saveTodoEnabled(enabled) }
    fun setSubagentMaxTotal(value: Int) = scope.launch { settingsManager.saveSubagentMaxTotal(value) }
    fun setSubagentMaxConcurrent(value: Int) = scope.launch { settingsManager.saveSubagentMaxConcurrent(value) }
    fun setSubagentToolsEnabled(enabled: Boolean) = scope.launch { settingsManager.saveSubagentToolsEnabled(enabled) }
    fun setSubagentCapsuleDisplayMode(mode: String) = scope.launch { settingsManager.saveSubagentCapsuleDisplayMode(mode) }
    fun addShellDevice(device: ShellDeviceConfig, onResult: (Boolean) -> Unit = {}, onError: (Exception) -> Unit = { onResult(false) }) =
        persistShellDevice(device, creating = true, onResult, onError)
    fun updateShellDevice(device: ShellDeviceConfig, onResult: (Boolean) -> Unit = {}, onError: (Exception) -> Unit = { onResult(false) }) =
        persistShellDevice(device, creating = false, onResult, onError)
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
    suspend fun getAutoCacheEnabled(): Boolean = settingsManager.autoCacheEnabled.first()
    suspend fun getShowUncachedNotification(): Boolean = settingsManager.showUncachedNotification.first()
    suspend fun getLastUpdateCheckTime(): Long = settingsManager.lastUpdateCheckTime.first()
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
    fun setLocalModelIdleRetentionMinutes(minutes: Int) = scope.launch {
        settingsManager.saveLocalModelIdleRetentionMinutes(minutes)
    }
    fun setLocalLowContextModeEnabled(enabled: Boolean) = scope.launch {
        settingsManager.saveLocalLowContextModeEnabled(enabled)
    }
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
