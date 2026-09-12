package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.util.DebugLog
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlinx.coroutines.flow.map

internal const val DEFAULT_CONTEXT_COMPACT_ENABLED = true
internal const val DEFAULT_CONTEXT_COMPACT_RETAIN_COUNT = 0
internal const val DEFAULT_CONTEXT_COMPACT_THRESHOLD_PERCENT = 90
internal val CONTEXT_COMPACT_THRESHOLD_PERCENT_RANGE = 50..100
internal const val DEFAULT_LOCAL_MODEL_IDLE_RETENTION_MINUTES = 5
internal const val DEFAULT_LOCAL_LOW_CONTEXT_MODE_ENABLED = false
internal val LOCAL_MODEL_IDLE_RETENTION_PRESETS = intArrayOf(0, 1, 2, 5, 10, 15, 30)

internal fun normalizeLocalModelIdleRetentionMinutes(value: Int?): Int =
    value?.takeIf { it in LOCAL_MODEL_IDLE_RETENTION_PRESETS }
        ?: DEFAULT_LOCAL_MODEL_IDLE_RETENTION_MINUTES

internal fun migrateUnmodifiedBuiltInDefault(
    prompts: List<SystemPromptEntry>,
    locale: Locale,
): List<SystemPromptEntry> {
    if (prompts.isEmpty()) return prompts
    val currentDefault = DefaultSystemPrompt.create(locale)
    return prompts.map { entry ->
        if (DefaultSystemPrompt.isUnmodifiedPreviousVersion(entry)) {
            entry.copy(
                content = "",
                systemItems = currentDefault.systemItems,
                userItems = currentDefault.resolvedUserItems,
                assistantItems = currentDefault.resolvedAssistantItems,
                userPrependItems = emptyList(),
                userPostpendItems = emptyList(),
            )
        } else {
            entry
        }
    }
}

private val Context.dataStore by preferencesDataStore(
    name = "settings", produceMigrations = { listOf(modelProviderNamesMigration) },
)

class SettingsManager(val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val modelPreferenceStore = SettingsModelPreferenceStore(context.dataStore, json)

    /** Public access to the underlying DataStore for components that need direct access. */
    val dataStore = context.dataStore

    // SettingsNotifications instance for notification-related settings
    val settingsNotifications: SettingsNotifications by lazy { SettingsNotifications(context, dataStore) }

    companion object {
        const val DEFAULT_PROXY_HOST = "127.0.0.1"
        const val DEFAULT_PROXY_PORT = "7890"
        const val DEFAULT_PROXY_BYPASS =
            "localhost\n127.0.0.1\n10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16\n::1"
    }

    val selectedModel: Flow<String> = modelPreferenceStore.selectedModel
    val providerBaseUrls: Flow<Map<String, String>> = modelPreferenceStore.providerBaseUrls
    val customEndpointResolutions: Flow<Map<String, CustomEndpointResolution>> =
        modelPreferenceStore.customEndpointResolutions
    val availableModels: Flow<Map<String, List<String>>> = modelPreferenceStore.availableModels
    val customModels: Flow<Set<String>> = modelPreferenceStore.customModels
    val enabledModels: Flow<Set<String>> = modelPreferenceStore.enabledModels
    val modelAliases: Flow<Map<String, String>> = modelPreferenceStore.modelAliases
    val modelProviderNames: Flow<Map<String, Boolean>> = modelPreferenceStore.modelProviderNames
    val apiKeys: Flow<List<ApiKeyEntry>> = modelPreferenceStore.apiKeys
    val activeApiKeyIds: Flow<Map<String, String>> = modelPreferenceStore.activeApiKeyIds

    val systemPrompts: Flow<List<SystemPromptEntry>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[SYSTEM_PROMPTS_JSON] ?: "[]"
        try { json.decodeFromString<List<SystemPromptEntry>>(jsonStr) } catch (e: Exception) { emptyList() }
    }
    
    val activeSystemPromptId: Flow<String?> = context.dataStore.data.map { it[ACTIVE_SYSTEM_PROMPT_ID] }

    val maxContextWindow: Flow<Int> = context.dataStore.data.map { preferences ->
        ContextBudget.normalize(
            preferences[CONTEXT_TOKEN_BUDGET]?.toIntOrNull()
                ?: preferences[MAX_CONTEXT_WINDOW]?.toIntOrNull()
        )
    }
    val visualizeContextRollout: Flow<Boolean> = context.dataStore.data.map { it[VISUALIZE_CONTEXT_ROLLOUT] ?: false }
    val contextCompactEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[CONTEXT_COMPACT_ENABLED] ?: DEFAULT_CONTEXT_COMPACT_ENABLED
    }
    val contextCompactModel: Flow<String?> = context.dataStore.data.map { it[CONTEXT_COMPACT_MODEL] }
    val contextCompactPrompt: Flow<String> = context.dataStore.data.map { pref ->
        pref[CONTEXT_COMPACT_PROMPT]?.takeIf { it.isNotBlank() } ?: BuiltInPrompts.CONTEXT_COMPACT_SYSTEM
    }
    val contextCompactRetainCount: Flow<Int> = context.dataStore.data.map {
        it[CONTEXT_COMPACT_RETAIN_COUNT] ?: DEFAULT_CONTEXT_COMPACT_RETAIN_COUNT
    }
    val contextCompactThresholdPercent: Flow<Int> = context.dataStore.data.map {
        it[CONTEXT_COMPACT_THRESHOLD_PERCENT]
            ?.takeIf(CONTEXT_COMPACT_THRESHOLD_PERCENT_RANGE::contains)
            ?: DEFAULT_CONTEXT_COMPACT_THRESHOLD_PERCENT
    }
    val codeExecutionEnabled: Flow<Boolean> = context.dataStore.data.map { it[CODE_EXECUTION_ENABLED] ?: false }
    val googleSearchEnabled: Flow<Boolean> = context.dataStore.data.map { it[GOOGLE_SEARCH_ENABLED] ?: false }
    val thinkingEnabled: Flow<Boolean> = context.dataStore.data.map { it[THINKING_ENABLED] ?: true }
    val thinkingLevel: Flow<String> = context.dataStore.data.map { ThinkingLevels.normalize(it[THINKING_LEVEL]) }
    val thinkingBudgetEnabled: Flow<Boolean> = context.dataStore.data.map { pref ->
        pref[THINKING_BUDGET_ENABLED] ?: (ThinkingLevels.legacyBudgetTokens(pref[THINKING_LEVEL]) != null)
    }
    val thinkingBudgetTokens: Flow<Int> = context.dataStore.data.map { pref ->
        pref[THINKING_BUDGET_TOKENS]
            ?: ThinkingLevels.legacyBudgetTokens(pref[THINKING_LEVEL])
            ?: ThinkingLevels.DefaultBudgetTokens
    }
    val openAiServiceTierEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[OPENAI_SERVICE_TIER_ENABLED] ?: false }
    val openAiServiceTier: Flow<String> = context.dataStore.data.map { pref ->
        OpenAiServiceTiers.normalize(pref[OPENAI_SERVICE_TIER])
    }
    val openAiResponsesApiEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[OPENAI_RESPONSES_API_ENABLED] ?: false }
    val titleGenerationEnabled: Flow<Boolean> = context.dataStore.data.map { it[TITLE_GENERATION_ENABLED] ?: true }
    val titleGenerationModel: Flow<String?> = context.dataStore.data.map { it[TITLE_GENERATION_MODEL] }
    val titleGenerationPrompt: Flow<String> = context.dataStore.data.map { pref ->
        pref[TITLE_GENERATION_PROMPT]?.takeIf { it.isNotBlank() } ?: BuiltInPrompts.TITLE_GENERATION_SYSTEM
    }
    val titleGenerationNotificationsEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[TITLE_GENERATION_NOTIFICATIONS_ENABLED] ?: true
    }
    val imageTranscriptionEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[IMAGE_TRANSCRIPTION_ENABLED] ?: true
    }
    val imageTranscriptionEnabledModels: Flow<Set<String>> = context.dataStore.data.map { it[IMAGE_TRANSCRIPTION_ENABLED_MODELS] ?: emptySet() }
    val imageTranscriptionModel: Flow<String?> = context.dataStore.data.map { it[IMAGE_TRANSCRIPTION_MODEL] }
    val imageTranscriptionBatchSize: Flow<Int> = context.dataStore.data.map { it[IMAGE_TRANSCRIPTION_BATCH_SIZE] ?: 3 }
    val imageTranscriptionPrompt: Flow<String> = context.dataStore.data.map { pref ->
        pref[IMAGE_TRANSCRIPTION_PROMPT]?.takeIf { it.isNotBlank() } ?: BuiltInPrompts.IMAGE_TRANSCRIPTION_USER
    }

    val accessPastConversations: Flow<Boolean> = context.dataStore.data.map { it[ACCESS_PAST_CONVERSATIONS] ?: true }
    val accessSavedMemories: Flow<Boolean> = context.dataStore.data.map { it[ACCESS_SAVED_MEMORIES] ?: true }
    val accessActiveMemory: Flow<Boolean> = context.dataStore.data.map { it[ACCESS_ACTIVE_MEMORY] ?: true }
    val accessSkills: Flow<Boolean> = context.dataStore.data.map { it[ACCESS_SKILLS] ?: true }
    /** Modify access follows the legacy single access_skills value until explicitly set. */
    val accessSkillsModify: Flow<Boolean> = context.dataStore.data.map { pref ->
        pref[ACCESS_SKILLS_MODIFY] ?: pref[ACCESS_SKILLS] ?: true
    }
    val ragSearchEnabled: Flow<Boolean> = context.dataStore.data.map { it[RAG_SEARCH_ENABLED] ?: false }
    val modelSearchMethod: Flow<String> = context.dataStore.data.map { it[MODEL_SEARCH_METHOD] ?: "keyword" }
    val manualSearchMethod: Flow<String> = context.dataStore.data.map { it[MANUAL_SEARCH_METHOD] ?: "keyword" }
    val embeddingModels: Flow<List<EmbeddingModelConfig>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[EMBEDDING_MODELS_JSON] ?: "[]"
        try { json.decodeFromString<List<EmbeddingModelConfig>>(jsonStr) } catch (e: Exception) { emptyList() }
    }
    val activeEmbeddingModelId: Flow<String> = context.dataStore.data.map { it[ACTIVE_EMBEDDING_MODEL_ID] ?: "" }

    val appLanguage: Flow<String> = context.dataStore.data.map { it[APP_LANGUAGE] ?: "system" }
    val webSearchEnabled: Flow<Boolean> = context.dataStore.data.map { it[WEB_SEARCH_ENABLED] ?: true }
    val webSearchProvider: Flow<String> = context.dataStore.data.map {
        normalizeWebSearchProvider(it[WEB_SEARCH_PROVIDER])
    }
    val webSearchApiKeys: Flow<Map<String, String>> = context.dataStore.data.map { preferences ->
        decodeWebSearchApiKeys(preferences, json)
    }
    val webSearchNumResults: Flow<Int> = context.dataStore.data.map { it[WEB_SEARCH_NUM_RESULTS] ?: 5 }
    val webSearchBaseUrl: Flow<String> = context.dataStore.data.map { it[WEB_SEARCH_BASE_URL] ?: "" }

    // ── Image generation ──────────────────────────────────────
    val imageGenEnabled: Flow<Boolean> = context.dataStore.data.map { it[IMAGE_GEN_ENABLED] ?: false }
    // Selected image model "Provider:modelId" (null = none chosen). Creds reused from that provider.
    val imageGenModel: Flow<String?> = context.dataStore.data.map { it[IMAGE_GEN_MODEL] }
    val imageGenSize: Flow<String> = context.dataStore.data.map { it[IMAGE_GEN_SIZE] ?: "1024x1024" }
    val searchContextWindow: Flow<Int> = context.dataStore.data.map { it[SEARCH_CONTEXT_WINDOW] ?: 8 }
    val searchMatchLimit: Flow<Int> = context.dataStore.data.map { it[SEARCH_MATCH_LIMIT] ?: 10 }
    val ragThreshold: Flow<Float> = context.dataStore.data.map { it[RAG_THRESHOLD]?.toFloatOrNull() ?: 0.5f }
    val defaultTemperature: Flow<Float?> = context.dataStore.data.map { it[DEFAULT_TEMPERATURE]?.toFloatOrNull() }
    val defaultMaxTokens: Flow<Int?> = context.dataStore.data.map { it[DEFAULT_MAX_TOKENS] }
    val defaultTopP: Flow<Float?> = context.dataStore.data.map { it[DEFAULT_TOP_P]?.toFloatOrNull() }
    val defaultFrequencyPenalty: Flow<Float?> = context.dataStore.data.map { it[DEFAULT_FREQUENCY_PENALTY]?.toFloatOrNull() }
    val defaultPresencePenalty: Flow<Float?> = context.dataStore.data.map { it[DEFAULT_PRESENCE_PENALTY]?.toFloatOrNull() }
    val conversationSettings: Flow<Map<String, ConversationSettings>> =
        context.dataStore.data.map { preferences -> decodeConversationSettings(preferences, json) }
    val autoCacheEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTO_CACHE_ENABLED] ?: true }
    val showUncachedNotification: Flow<Boolean> =
        context.dataStore.data.map { it[SHOW_UNCACHED_NOTIFICATION] ?: true }
    val autoUpdateCheck: Flow<Boolean> = context.dataStore.data.map { it[AUTO_UPDATE_CHECK] ?: true }
    val lastUpdateCheckTime: Flow<Long> = context.dataStore.data.map { it[LAST_UPDATE_CHECK_TIME] ?: 0L }
    val localChatModels: Flow<List<LocalChatModelConfig>> = modelPreferenceStore.localChatModels
    val localModelIdleRetentionMinutes: Flow<Int> = context.dataStore.data.map {
        normalizeLocalModelIdleRetentionMinutes(it[LOCAL_MODEL_IDLE_RETENTION_MINUTES])
    }
    val localLowContextModeEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[LOCAL_LOW_CONTEXT_MODE_ENABLED] ?: DEFAULT_LOCAL_LOW_CONTEXT_MODE_ENABLED
    }
    val customProviders: Flow<List<CustomProviderConfig>> = modelPreferenceStore.customProviders

    val showDocumentationFab: Flow<Boolean> = context.dataStore.data.map { it[SHOW_DOCUMENTATION_FAB] ?: true }
    /** Release-build feature gate kept local to this installation. */
    val developerOptionsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[DEVELOPER_OPTIONS_ENABLED] ?: false }
    val debugModelEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        (preferences[DEVELOPER_OPTIONS_ENABLED] ?: false) &&
            (preferences[DEBUG_MODEL_ENABLED] ?: false)
    }

    val shellEnabled: Flow<Boolean> = context.dataStore.data.map { it[SHELL_ENABLED] ?: true }
    val automationToolsEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTOMATION_TOOLS_ENABLED] ?: false }
    val exactExecutionEnabled: Flow<Boolean> = context.dataStore.data.map { it[EXACT_EXECUTION_ENABLED] ?: false }
    val automationWakeLockEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[AUTOMATION_WAKE_LOCK_ENABLED] ?: false }
    val proxyEnabled: Flow<Boolean> = context.dataStore.data.map { it[PROXY_ENABLED] ?: false }
    val proxyType: Flow<String> = context.dataStore.data.map { it[PROXY_TYPE] ?: "http" }
    val proxyHost: Flow<String> = context.dataStore.data.map { it[PROXY_HOST] ?: DEFAULT_PROXY_HOST }
    val proxyPort: Flow<String> = context.dataStore.data.map { it[PROXY_PORT] ?: DEFAULT_PROXY_PORT }
    val proxyUsername: Flow<String> = context.dataStore.data.map { it[PROXY_USERNAME] ?: "" }
    val proxyPassword: Flow<String> = context.dataStore.data.map { it[PROXY_PASSWORD] ?: "" }
    val proxyBypass: Flow<String> = context.dataStore.data.map { it[PROXY_BYPASS] ?: DEFAULT_PROXY_BYPASS }
    // Confirm before the model runs state-changing commands on remote shell servers. Default on.
    val shellConfirmEnabled: Flow<Boolean> = context.dataStore.data.map { it[SHELL_CONFIRM_ENABLED] ?: true }
    val shellDevices: Flow<List<ShellDeviceConfig>> =
        context.dataStore.data.map { preferences -> decodeEncryptedShellDevices(preferences, json) }
    val mcpServers: Flow<List<McpServerConfig>> = context.dataStore.data.map { pref ->
        val jsonStr = com.newoether.agora.util.SecretCrypto.decrypt(pref[MCP_SERVERS_JSON] ?: "[]")
        try {
            json.decodeFromString<List<McpServerConfig>>(jsonStr)
        } catch (e: Exception) {
            DebugLog.e("SettingsManager", "Failed to decode MCP servers", e)
            emptyList()
        }
    }
    val sandboxEnabled: Flow<Boolean> = context.dataStore.data.map { it[SANDBOX_ENABLED] ?: false }
    val sandboxSharedStorageEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[SANDBOX_SHARED_STORAGE_ENABLED] ?: false }

    val themeMode: Flow<String> = context.dataStore.data.map { it[THEME_MODE] ?: "FOLLOW_DEVICE" }
    val amoledEnabled: Flow<Boolean> = context.dataStore.data.map { it[AMOLED_ENABLED] ?: false }
    val colorScheme: Flow<String> = context.dataStore.data.map {
        it[COLOR_SCHEME] ?: DEFAULT_COLOR_SCHEME
    }
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map {
        it[DYNAMIC_COLOR] ?: DEFAULT_DYNAMIC_COLOR
    }
    val blurEffectsEnabled: Flow<Boolean> = context.dataStore.data.map { it[BLUR_EFFECTS_ENABLED] ?: true }
    val reduceMotion: Flow<Boolean> = context.dataStore.data.map { it[REDUCE_MOTION] ?: false }
    val stickToBottom: Flow<Boolean> = context.dataStore.data.map { it[STICK_TO_BOTTOM] ?: true }
    val parseInlineDollarMath: Flow<Boolean> =
        context.dataStore.data.map { it[PARSE_INLINE_DOLLAR_MATH] ?: false }
    val hapticsEnabled: Flow<Boolean> = context.dataStore.data.map { it[HAPTICS_ENABLED] ?: true }
    val detailedTokenUsage: Flow<Boolean> =
        context.dataStore.data.map { it[DETAILED_TOKEN_USAGE] ?: false }
    val toolCallDisplayMode: Flow<String> = context.dataStore.data.map { ToolCallDisplayModes.normalize(it[TOOL_CALL_DISPLAY_MODE]) }
    val thinkingSegmentDisplayMode: Flow<String> = context.dataStore.data.map {
        ThinkingSegmentDisplayModes.normalize(it[THINKING_SEGMENT_DISPLAY_MODE])
    }
    val autoExpandActiveGroup: Flow<Boolean> =
        context.dataStore.data.map { it[AUTO_EXPAND_ACTIVE_GROUP] ?: true }
    val schemeStyle: Flow<String> = context.dataStore.data.map {
        it[SCHEME_STYLE] ?: DEFAULT_SCHEME_STYLE
    }
    val fontPreference: Flow<String> = context.dataStore.data.map { it[FONT_PREFERENCE] ?: "app_default" }
    val customFontPath: Flow<String> = context.dataStore.data.map { it[CUSTOM_FONT_PATH] ?: "" }
    val customFontName: Flow<String> = context.dataStore.data.map { it[CUSTOM_FONT_NAME] ?: "" }
    val firstLaunchTime: Flow<Long?> = context.dataStore.data.map { it[FIRST_LAUNCH_TIME] }
    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { it[ONBOARDING_COMPLETED] ?: false }
    val ratingPromptSubmitted: Flow<Boolean> = context.dataStore.data.map { it[RATING_PROMPT_SUBMITTED] ?: false }
    val ratingPromptDismissed: Flow<Boolean> = context.dataStore.data.map { it[RATING_PROMPT_DISMISSED] ?: false }
    val totalMessagesSent: Flow<Int> = context.dataStore.data.map { it[TOTAL_MESSAGES_SENT] ?: 0 }

    // ── Auto Backup ───────────────────────────────────────────
    val autoBackupEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTO_BACKUP_ENABLED] ?: true }
    val autoBackupPeriodHours: Flow<Int> = context.dataStore.data.map { it[AUTO_BACKUP_PERIOD_HOURS] ?: 24 }
    val autoBackupCategories: Flow<String> = context.dataStore.data.map { it[AUTO_BACKUP_CATEGORIES] ?: "conversations,memories,system_prompts,settings" }
    val autoBackupDirectory: Flow<String> = context.dataStore.data.map { it[AUTO_BACKUP_DIRECTORY] ?: "Download/Agora/Backup" }
    val autoDeleteEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTO_DELETE_ENABLED] ?: true }
    val autoDeletePeriodHours: Flow<Int> = context.dataStore.data.map { it[AUTO_DELETE_PERIOD_HOURS] ?: 168 }
    val lastBackupTimestamp: Flow<Long> = context.dataStore.data.map { it[LAST_BACKUP_TIMESTAMP] ?: 0L }
    val lastModelsFetchFingerprint: Flow<String> = modelPreferenceStore.lastModelsFetchFingerprint

    // ── Heartbeat ──────────────────────────────────────────────
    val heartbeatEnabled: Flow<Boolean> = context.dataStore.data.map { it[HEARTBEAT_ENABLED] ?: true }
    val heartbeatIntervalMinutes: Flow<Int> = context.dataStore.data.map { it[HEARTBEAT_INTERVAL_MINUTES] ?: 30 }
    val heartbeatActiveHoursStart: Flow<Int> = context.dataStore.data.map { it[HEARTBEAT_ACTIVE_HOURS_START] ?: 8 }
    val heartbeatActiveHoursEnd: Flow<Int> = context.dataStore.data.map { it[HEARTBEAT_ACTIVE_HOURS_END] ?: 22 }
    val heartbeatLastHeartbeatEpochMs: Flow<Long> = context.dataStore.data.map { it[HEARTBEAT_LAST_HEARTBEAT_EPOCH_MS] ?: 0L }
    val heartbeatInstanceId: Flow<String?> = context.dataStore.data.map { it[HEARTBEAT_INSTANCE_ID] }
    val heartbeatPrompt: Flow<String> = context.dataStore.data.map { it[HEARTBEAT_PROMPT] ?: "" }
    val heartbeatModel: Flow<String?> = context.dataStore.data.map { it[HEARTBEAT_MODEL] }
    val heartbeatConversationId: Flow<String?> = context.dataStore.data.map { it[HEARTBEAT_CONVERSATION_ID] }

    // ── SMS ────────────────────────────────────────────────────
    val smsReadEnabled: Flow<Boolean> = context.dataStore.data.map { it[SMS_READ_ENABLED] ?: false }
    val smsSendEnabled: Flow<Boolean> = context.dataStore.data.map { it[SMS_SEND_ENABLED] ?: false }
    val smsPollIntervalMinutes: Flow<Int> = context.dataStore.data.map { it[SMS_POLL_INTERVAL_MINUTES] ?: 15 }

    // fdroid support gates
    val smsReaderSupported: Flow<Boolean> = settingsNotifications.smsReaderSupported
    val notificationListenerSupported: Flow<Boolean> = settingsNotifications.notificationListenerSupported

    // ── Daemon ─────────────────────────────────────────────────
    val daemonEnabled: Flow<Boolean> = context.dataStore.data.map { it[DAEMON_ENABLED] ?: false }

    suspend fun saveProviderBaseUrl(provider: String, url: String) =
        modelPreferenceStore.saveProviderBaseUrl(provider, url)

    suspend fun saveProviderBaseUrls(urls: Map<String, String>) =
        modelPreferenceStore.saveProviderBaseUrls(urls)

    suspend fun saveCustomEndpointResolution(
        provider: String,
        resolution: CustomEndpointResolution?,
    ) = modelPreferenceStore.saveCustomEndpointResolution(provider, resolution)

    suspend fun renameCustomEndpointResolution(oldName: String, newName: String) =
        modelPreferenceStore.renameCustomEndpointResolution(oldName, newName)

    suspend fun saveSelectedModel(model: String) =
        modelPreferenceStore.saveSelectedModel(model)

    suspend fun saveAvailableModels(provider: String, models: List<String>) =
        modelPreferenceStore.saveAvailableModels(provider, models)

    suspend fun saveCustomModels(models: Set<String>) =
        modelPreferenceStore.saveCustomModels(models)

    suspend fun addCustomModel(modelId: String, alias: String, showProviderName: Boolean = true) =
        modelPreferenceStore.addCustomModel(modelId, alias, showProviderName)

    suspend fun replaceCustomModel(
        oldModelId: String,
        newModelId: String?,
        alias: String,
        showProviderName: Boolean? = null,
    ) = modelPreferenceStore.replaceCustomModel(oldModelId, newModelId, alias, showProviderName)

    suspend fun saveEnabledModels(models: Set<String>) =
        modelPreferenceStore.saveEnabledModels(models)

    suspend fun saveModelAliases(aliases: Map<String, String>) =
        modelPreferenceStore.saveModelAliases(aliases)

    suspend fun updateModelAlias(modelId: String, alias: String, showProviderName: Boolean? = null) =
        modelPreferenceStore.updateModelAlias(modelId, alias, showProviderName)

    suspend fun saveModelProviderNames(values: Map<String, Boolean>, replace: Boolean = true) =
        modelPreferenceStore.saveModelProviderNames(values, replace)

    suspend fun synchronizeLocalModelAliases(aliases: Map<String, String>) =
        modelPreferenceStore.synchronizeLocalModelAliases(aliases)

    suspend fun removeModelAliasesForProvider(providerId: String) =
        modelPreferenceStore.removeModelAliasesForProvider(providerId)

    suspend fun saveApiKeys(keys: List<ApiKeyEntry>) =
        modelPreferenceStore.saveApiKeys(keys)

    suspend fun saveActiveApiKeyIds(ids: Map<String, String>) =
        modelPreferenceStore.saveActiveApiKeyIds(ids)

    suspend fun setActiveApiKeyId(provider: String, id: String?) =
        modelPreferenceStore.setActiveApiKeyId(provider, id)

    suspend fun renameApiKeyProvider(oldProvider: String, newProvider: String) =
        modelPreferenceStore.renameApiKeyProvider(oldProvider, newProvider)

    suspend fun saveSystemPrompts(prompts: List<SystemPromptEntry>) {
        context.dataStore.edit { it[SYSTEM_PROMPTS_JSON] = json.encodeToString(prompts) }
    }
    suspend fun initializeFirstInstallDefaults(
        locale: Locale = Locale.getDefault(),
        now: Long = System.currentTimeMillis()
    ) {
        context.dataStore.edit { prefs ->
            val firstLaunchMissing = prefs[FIRST_LAUNCH_TIME] == null
            val looksLikeFreshInstall = firstLaunchMissing && prefs[ONBOARDING_COMPLETED] != true
            if (firstLaunchMissing) {
                prefs[FIRST_LAUNCH_TIME] = now
            }
            val currentPrompts = try {
                json.decodeFromString<List<SystemPromptEntry>>(prefs[SYSTEM_PROMPTS_JSON] ?: "[]")
            } catch (_: Exception) {
                emptyList()
            }
            val messageTemplatesMigrated = migrateSystemPromptsOnStartup(currentPrompts, locale)
            if (messageTemplatesMigrated != currentPrompts) {
                prefs[SYSTEM_PROMPTS_JSON] = json.encodeToString(messageTemplatesMigrated)
            }
            if (looksLikeFreshInstall) {
                if (messageTemplatesMigrated.isEmpty()) {
                    val defaultPrompt = DefaultSystemPrompt.create(locale)
                    prefs[SYSTEM_PROMPTS_JSON] = json.encodeToString(listOf(defaultPrompt))
                    if (prefs[ACTIVE_SYSTEM_PROMPT_ID] == null) {
                        prefs[ACTIVE_SYSTEM_PROMPT_ID] = defaultPrompt.id
                    }
                }
            }
        }
    }

    suspend fun setActiveSystemPromptId(id: String?) {
        context.dataStore.edit { 
            if (id == null) it.remove(ACTIVE_SYSTEM_PROMPT_ID) else it[ACTIVE_SYSTEM_PROMPT_ID] = id 
        }
    }
    suspend fun saveMaxContextWindow(window: Int) {
        context.dataStore.edit {
            it[CONTEXT_TOKEN_BUDGET] = ContextBudget.normalize(window).toString()
        }
    }
    suspend fun saveVisualizeContextRollout(enabled: Boolean) {
        context.dataStore.edit { it[VISUALIZE_CONTEXT_ROLLOUT] = enabled }
    }
    suspend fun saveCodeExecutionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[CODE_EXECUTION_ENABLED] = enabled }
    }
    suspend fun saveGoogleSearchEnabled(enabled: Boolean) {
        context.dataStore.edit { it[GOOGLE_SEARCH_ENABLED] = enabled }
    }
    suspend fun saveThinkingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[THINKING_ENABLED] = enabled }
    }
    suspend fun saveThinkingLevel(level: String) {
        context.dataStore.edit { it[THINKING_LEVEL] = ThinkingLevels.normalize(level) }
    }
    suspend fun saveThinkingBudgetEnabled(enabled: Boolean) {
        context.dataStore.edit { it[THINKING_BUDGET_ENABLED] = enabled }
    }
    suspend fun saveThinkingBudgetTokens(tokens: Int) {
        context.dataStore.edit { it[THINKING_BUDGET_TOKENS] = tokens.coerceAtLeast(1) }
    }
    suspend fun saveOpenAiServiceTierEnabled(enabled: Boolean) {
        context.dataStore.edit { it[OPENAI_SERVICE_TIER_ENABLED] = enabled }
    }
    suspend fun saveOpenAiServiceTier(tier: String) {
        context.dataStore.edit {
            it[OPENAI_SERVICE_TIER] = OpenAiServiceTiers.normalize(tier)
        }
    }
    suspend fun saveOpenAiResponsesApiEnabled(enabled: Boolean) {
        context.dataStore.edit { it[OPENAI_RESPONSES_API_ENABLED] = enabled }
    }
    suspend fun saveTitleGenerationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[TITLE_GENERATION_ENABLED] = enabled }
    }
    suspend fun saveTitleGenerationNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[TITLE_GENERATION_NOTIFICATIONS_ENABLED] = enabled }
    }
    suspend fun saveAccessPastConversations(enabled: Boolean) {
        context.dataStore.edit { it[ACCESS_PAST_CONVERSATIONS] = enabled }
    }
    suspend fun saveAccessSavedMemories(enabled: Boolean) {
        context.dataStore.edit { it[ACCESS_SAVED_MEMORIES] = enabled }
    }
    suspend fun saveAccessActiveMemory(enabled: Boolean) {
        context.dataStore.edit { it[ACCESS_ACTIVE_MEMORY] = enabled }
    }
    suspend fun saveAccessSkills(enabled: Boolean) {
        context.dataStore.edit { it[ACCESS_SKILLS] = enabled }
    }
    suspend fun saveAccessSkillsModify(enabled: Boolean) {
        context.dataStore.edit { it[ACCESS_SKILLS_MODIFY] = enabled }
    }
    suspend fun saveRagSearchEnabled(enabled: Boolean) {
        context.dataStore.edit { it[RAG_SEARCH_ENABLED] = enabled }
    }
    suspend fun saveAutoCacheEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_CACHE_ENABLED] = enabled }
    }
    suspend fun saveShowUncachedNotification(enabled: Boolean) {
        context.dataStore.edit { it[SHOW_UNCACHED_NOTIFICATION] = enabled }
    }
    suspend fun saveAutoUpdateCheck(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_UPDATE_CHECK] = enabled }
    }
    suspend fun saveLastUpdateCheckTime(time: Long) {
        context.dataStore.edit { it[LAST_UPDATE_CHECK_TIME] = time }
    }
    suspend fun saveModelSearchMethod(method: String) {
        context.dataStore.edit { it[MODEL_SEARCH_METHOD] = method }
    }
    suspend fun saveManualSearchMethod(method: String) {
        context.dataStore.edit { it[MANUAL_SEARCH_METHOD] = method }
    }
    suspend fun saveEmbeddingModels(models: List<EmbeddingModelConfig>) {
        context.dataStore.edit { it[EMBEDDING_MODELS_JSON] = json.encodeToString(models) }
    }
    suspend fun setActiveEmbeddingModelId(id: String) {
        context.dataStore.edit { it[ACTIVE_EMBEDDING_MODEL_ID] = id }
    }
    suspend fun saveAppLanguage(language: String) {
        context.dataStore.edit { it[APP_LANGUAGE] = language }
    }
    suspend fun saveWebSearchEnabled(enabled: Boolean) {
        context.dataStore.edit { it[WEB_SEARCH_ENABLED] = enabled }
    }
    suspend fun saveWebSearchProvider(provider: String) {
        context.dataStore.edit { it[WEB_SEARCH_PROVIDER] = normalizeWebSearchProvider(provider) }
    }
    suspend fun saveWebSearchApiKey(provider: String, apiKey: String) {
        context.dataStore.edit { prefs ->
            val current = com.newoether.agora.util.SecretCrypto.decrypt(prefs[WEB_SEARCH_API_KEYS_JSON] ?: "{}")
            val map = try { json.decodeFromString<MutableMap<String, String>>(current) } catch (e: Exception) { mutableMapOf() }
            if (apiKey.isBlank()) map.remove(provider) else map[provider] = apiKey
            prefs[WEB_SEARCH_API_KEYS_JSON] = com.newoether.agora.util.SecretCrypto.encrypt(json.encodeToString(map))
        }
    }
    suspend fun saveWebSearchApiKeys(keys: Map<String, String>) {
        val nonBlank = keys.filterValues { it.isNotBlank() }
        context.dataStore.edit { prefs ->
            if (nonBlank.isEmpty()) {
                prefs.remove(WEB_SEARCH_API_KEYS_JSON)
            } else {
                prefs[WEB_SEARCH_API_KEYS_JSON] =
                    com.newoether.agora.util.SecretCrypto.encrypt(json.encodeToString(nonBlank))
            }
        }
    }
    suspend fun saveWebSearchNumResults(n: Int) {
        context.dataStore.edit { it[WEB_SEARCH_NUM_RESULTS] = n.coerceIn(1, 10) }
    }
    suspend fun saveWebSearchBaseUrl(url: String) {
        context.dataStore.edit { it[WEB_SEARCH_BASE_URL] = url }
    }
    suspend fun saveImageGenEnabled(enabled: Boolean) {
        context.dataStore.edit { it[IMAGE_GEN_ENABLED] = enabled }
    }
    suspend fun saveImageGenModel(model: String?) {
        context.dataStore.edit {
            if (model == null) it.remove(IMAGE_GEN_MODEL) else it[IMAGE_GEN_MODEL] = model
        }
    }
    suspend fun saveImageGenSize(size: String) {
        context.dataStore.edit { it[IMAGE_GEN_SIZE] = size }
    }
    suspend fun saveSearchMatchLimit(n: Int) {
        context.dataStore.edit { it[SEARCH_MATCH_LIMIT] = n }
    }
    suspend fun saveSearchContextWindow(n: Int) {
        context.dataStore.edit { it[SEARCH_CONTEXT_WINDOW] = n }
    }
    suspend fun saveRagThreshold(threshold: Float) {
        context.dataStore.edit { it[RAG_THRESHOLD] = threshold.toString() }
    }
    suspend fun saveDefaultTemperature(value: Float?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(DEFAULT_TEMPERATURE) else prefs[DEFAULT_TEMPERATURE] = value.toString()
        }
    }
    suspend fun saveDefaultMaxTokens(value: Int?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(DEFAULT_MAX_TOKENS) else prefs[DEFAULT_MAX_TOKENS] = value
        }
    }
    suspend fun saveDefaultTopP(value: Float?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(DEFAULT_TOP_P) else prefs[DEFAULT_TOP_P] = value.toString()
        }
    }
    suspend fun saveDefaultFrequencyPenalty(value: Float?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(DEFAULT_FREQUENCY_PENALTY) else prefs[DEFAULT_FREQUENCY_PENALTY] = value.toString()
        }
    }
    suspend fun saveDefaultPresencePenalty(value: Float?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(DEFAULT_PRESENCE_PENALTY) else prefs[DEFAULT_PRESENCE_PENALTY] = value.toString()
        }
    }
    suspend fun saveConversationSettings(
        conversationId: String,
        settings: ConversationSettings?,
    ): Map<String, ConversationSettings> {
        var updated: Map<String, ConversationSettings> = emptyMap()
        context.dataStore.edit { prefs ->
            val current = prefs[CONVERSATION_SETTINGS_JSON] ?: "{}"
            val map = try { json.decodeFromString<MutableMap<String, ConversationSettings>>(current) } catch (e: Exception) { mutableMapOf() }
            if (settings == null || settings.isAllNull()) map.remove(conversationId)
            else map[conversationId] = settings
            updated = map.toMap()
            prefs[CONVERSATION_SETTINGS_JSON] = json.encodeToString(map)
        }
        return updated
    }
    suspend fun saveConversationSettingsMap(settings: Map<String, ConversationSettings>) {
        val nonEmpty = settings.filterValues { !it.isAllNull() }
        context.dataStore.edit { prefs ->
            if (nonEmpty.isEmpty()) {
                prefs.remove(CONVERSATION_SETTINGS_JSON)
            } else {
                prefs[CONVERSATION_SETTINGS_JSON] = json.encodeToString(nonEmpty)
            }
        }
    }
    suspend fun saveLocalChatModels(models: List<LocalChatModelConfig>) =
        modelPreferenceStore.saveLocalChatModels(models)

    suspend fun saveLocalModelIdleRetentionMinutes(minutes: Int) {
        context.dataStore.edit {
            it[LOCAL_MODEL_IDLE_RETENTION_MINUTES] =
                normalizeLocalModelIdleRetentionMinutes(minutes)
        }
    }

    suspend fun saveLocalLowContextModeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[LOCAL_LOW_CONTEXT_MODE_ENABLED] = enabled }
    }

    suspend fun saveCustomProviders(providers: List<CustomProviderConfig>) =
        modelPreferenceStore.saveCustomProviders(providers)

    internal suspend fun normalizeCustomProviderIdentities(): List<CustomProviderIdentityMigration> =
        modelPreferenceStore.normalizeCustomProviderIdentities()

    internal suspend fun clearLegacyCustomProviderNames(
        completed: List<CustomProviderIdentityMigration>,
    ) = modelPreferenceStore.clearLegacyCustomProviderNames(completed)

    suspend fun saveContextCompactEnabled(enabled: Boolean) {
        context.dataStore.edit { it[CONTEXT_COMPACT_ENABLED] = enabled }
    }
    suspend fun saveContextCompactModel(model: String?) {
        context.dataStore.edit { prefs ->
            if (model == null) prefs.remove(CONTEXT_COMPACT_MODEL) else prefs[CONTEXT_COMPACT_MODEL] = model
        }
    }
    suspend fun saveContextCompactPrompt(prompt: String) {
        context.dataStore.edit { prefs ->
            if (prompt.isBlank()) prefs.remove(CONTEXT_COMPACT_PROMPT) else prefs[CONTEXT_COMPACT_PROMPT] = prompt
        }
    }
    suspend fun saveContextCompactRetainCount(count: Int) {
        require(count >= 0)
        context.dataStore.edit { it[CONTEXT_COMPACT_RETAIN_COUNT] = count }
    }

    suspend fun saveContextCompactThresholdPercent(percent: Int) {
        require(percent in CONTEXT_COMPACT_THRESHOLD_PERCENT_RANGE)
        context.dataStore.edit { it[CONTEXT_COMPACT_THRESHOLD_PERCENT] = percent }
    }

    suspend fun saveTitleGenerationModel(model: String?) {
        context.dataStore.edit {
            if (model == null) it.remove(TITLE_GENERATION_MODEL)
            else it[TITLE_GENERATION_MODEL] = model
        }
    }
    suspend fun saveTitleGenerationPrompt(prompt: String) {
        context.dataStore.edit {
            if (prompt.isBlank()) it.remove(TITLE_GENERATION_PROMPT)
            else it[TITLE_GENERATION_PROMPT] = prompt
        }
    }
    suspend fun saveImageTranscriptionEnabledModels(models: Set<String>) {
        context.dataStore.edit { it[IMAGE_TRANSCRIPTION_ENABLED_MODELS] = models }
    }
    suspend fun saveImageTranscriptionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[IMAGE_TRANSCRIPTION_ENABLED] = enabled }
    }
    suspend fun saveImageTranscriptionModel(model: String?) {
        context.dataStore.edit {
            if (model == null) it.remove(IMAGE_TRANSCRIPTION_MODEL)
            else it[IMAGE_TRANSCRIPTION_MODEL] = model
        }
    }
    suspend fun saveImageTranscriptionBatchSize(size: Int) {
        context.dataStore.edit { it[IMAGE_TRANSCRIPTION_BATCH_SIZE] = size.coerceIn(1, 10) }
    }
    suspend fun saveImageTranscriptionPrompt(prompt: String) {
        context.dataStore.edit {
            if (prompt.isBlank()) it.remove(IMAGE_TRANSCRIPTION_PROMPT)
            else it[IMAGE_TRANSCRIPTION_PROMPT] = prompt
        }
    }
    suspend fun saveShowDocumentationFab(enabled: Boolean) {
        context.dataStore.edit { it[SHOW_DOCUMENTATION_FAB] = enabled }
    }
    suspend fun saveDeveloperOptionsEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[DEVELOPER_OPTIONS_ENABLED] = enabled
            if (!enabled) {
                it[DEBUG_MODEL_ENABLED] = false
            }
        }
    }
    suspend fun saveDebugModelEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[DEBUG_MODEL_ENABLED] =
                enabled && (it[DEVELOPER_OPTIONS_ENABLED] ?: false)
        }
    }
    suspend fun saveShellEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SHELL_ENABLED] = enabled }
    }
    suspend fun saveAutomationToolsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTOMATION_TOOLS_ENABLED] = enabled }
    }
    suspend fun saveExactExecutionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[EXACT_EXECUTION_ENABLED] = enabled }
    }
    suspend fun saveAutomationWakeLockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTOMATION_WAKE_LOCK_ENABLED] = enabled }
    }
    suspend fun saveProxyEnabled(enabled: Boolean) { context.dataStore.edit { it[PROXY_ENABLED] = enabled } }
    suspend fun saveProxyType(type: String) { context.dataStore.edit { it[PROXY_TYPE] = type } }
    suspend fun saveProxyHost(host: String) { context.dataStore.edit { it[PROXY_HOST] = host } }
    suspend fun saveProxyPort(port: String) { context.dataStore.edit { it[PROXY_PORT] = port } }
    suspend fun saveProxyUsername(user: String) { context.dataStore.edit { it[PROXY_USERNAME] = user } }
    suspend fun saveProxyPassword(pass: String) { context.dataStore.edit { it[PROXY_PASSWORD] = pass } }
    suspend fun saveProxyBypass(bypass: String) { context.dataStore.edit { it[PROXY_BYPASS] = bypass } }

    suspend fun saveShellConfirmEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SHELL_CONFIRM_ENABLED] = enabled }
    }
    suspend fun saveShellDevices(devices: List<ShellDeviceConfig>) {
        context.dataStore.edit { it[SHELL_DEVICES_JSON] = com.newoether.agora.util.SecretCrypto.encrypt(json.encodeToString(devices)) }
    }
    suspend fun saveMcpServers(servers: List<McpServerConfig>) {
        context.dataStore.edit {
            it[MCP_SERVERS_JSON] =
                com.newoether.agora.util.SecretCrypto.encrypt(json.encodeToString(servers))
        }
    }
    suspend fun saveSandboxEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SANDBOX_ENABLED] = enabled }
    }
    suspend fun saveSandboxSharedStorageEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SANDBOX_SHARED_STORAGE_ENABLED] = enabled }
    }
    suspend fun saveThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }
    suspend fun saveAmoledEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AMOLED_ENABLED] = enabled }
    }
    suspend fun saveColorScheme(scheme: String) {
        context.dataStore.edit { it[COLOR_SCHEME] = scheme }
    }
    suspend fun saveDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }
    suspend fun saveBlurEffectsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[BLUR_EFFECTS_ENABLED] = enabled }
    }
    suspend fun saveReduceMotion(enabled: Boolean) {
        context.dataStore.edit { it[REDUCE_MOTION] = enabled }
    }
    suspend fun saveStickToBottom(enabled: Boolean) {
        context.dataStore.edit { it[STICK_TO_BOTTOM] = enabled }
    }
    suspend fun saveParseInlineDollarMath(enabled: Boolean) {
        context.dataStore.edit { it[PARSE_INLINE_DOLLAR_MATH] = enabled }
    }
    suspend fun saveHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[HAPTICS_ENABLED] = enabled }
    }
    suspend fun saveDetailedTokenUsage(enabled: Boolean) {
        context.dataStore.edit { it[DETAILED_TOKEN_USAGE] = enabled }
    }
    suspend fun saveToolCallDisplayMode(mode: String) {
        context.dataStore.edit { it[TOOL_CALL_DISPLAY_MODE] = ToolCallDisplayModes.normalize(mode) }
    }
    suspend fun saveThinkingSegmentDisplayMode(mode: String) {
        context.dataStore.edit {
            it[THINKING_SEGMENT_DISPLAY_MODE] = ThinkingSegmentDisplayModes.normalize(mode)
        }
    }
    suspend fun saveAutoExpandActiveGroup(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_EXPAND_ACTIVE_GROUP] = enabled }
    }
    suspend fun saveFontPreference(value: String) {
        context.dataStore.edit { it[FONT_PREFERENCE] = value }
    }
    suspend fun saveCustomFontPath(value: String) {
        context.dataStore.edit { it[CUSTOM_FONT_PATH] = value }
    }
    suspend fun saveCustomFontName(value: String) {
        context.dataStore.edit { it[CUSTOM_FONT_NAME] = value }
    }
    suspend fun saveSchemeStyle(style: String) {
        context.dataStore.edit { it[SCHEME_STYLE] = style }
    }
    suspend fun saveFirstLaunchTime(time: Long) {
        context.dataStore.edit { it[FIRST_LAUNCH_TIME] = time }
    }
    suspend fun saveOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[ONBOARDING_COMPLETED] = completed }
    }
    suspend fun saveRatingPromptSubmitted(submitted: Boolean) {
        context.dataStore.edit { it[RATING_PROMPT_SUBMITTED] = submitted }
    }
    suspend fun saveRatingPromptDismissed(dismissed: Boolean) {
        context.dataStore.edit { it[RATING_PROMPT_DISMISSED] = dismissed }
    }
    suspend fun incrementMessagesSent() {
        context.dataStore.edit { it[TOTAL_MESSAGES_SENT] = (it[TOTAL_MESSAGES_SENT] ?: 0) + 1 }
    }

    // ── Heartbeat ──────────────────────────────────────────────
    suspend fun saveHeartbeatEnabled(enabled: Boolean) = context.dataStore.edit { it[HEARTBEAT_ENABLED] = enabled }
    suspend fun saveHeartbeatIntervalMinutes(minutes: Int) = context.dataStore.edit { it[HEARTBEAT_INTERVAL_MINUTES] = minutes }
    suspend fun saveHeartbeatActiveHoursStart(hour: Int) = context.dataStore.edit { it[HEARTBEAT_ACTIVE_HOURS_START] = hour }
    suspend fun saveHeartbeatActiveHoursEnd(hour: Int) = context.dataStore.edit { it[HEARTBEAT_ACTIVE_HOURS_END] = hour }
    suspend fun saveHeartbeatLastHeartbeatEpochMs(epochMs: Long) = context.dataStore.edit { it[HEARTBEAT_LAST_HEARTBEAT_EPOCH_MS] = epochMs }
    suspend fun saveHeartbeatInstanceId(instanceId: String?) {
        context.dataStore.edit { prefs ->
            if (instanceId == null) prefs.remove(HEARTBEAT_INSTANCE_ID) else prefs[HEARTBEAT_INSTANCE_ID] = instanceId
        }
    }
    suspend fun saveHeartbeatPrompt(prompt: String) {
        context.dataStore.edit { prefs ->
            if (prompt.isBlank()) prefs.remove(HEARTBEAT_PROMPT) else prefs[HEARTBEAT_PROMPT] = prompt
        }
    }
    suspend fun saveHeartbeatModel(model: String?) {
        context.dataStore.edit { prefs ->
            if (model == null) prefs.remove(HEARTBEAT_MODEL) else prefs[HEARTBEAT_MODEL] = model
        }
    }
    suspend fun saveHeartbeatConversationId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(HEARTBEAT_CONVERSATION_ID) else prefs[HEARTBEAT_CONVERSATION_ID] = id
        }
    }
    // ── SMS ────────────────────────────────────────────────────
    suspend fun saveSmsReadEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SMS_READ_ENABLED] = enabled }
    }
    suspend fun saveSmsSendEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SMS_SEND_ENABLED] = enabled }
    }
    suspend fun saveSmsPollIntervalMinutes(minutes: Int) {
        context.dataStore.edit { it[SMS_POLL_INTERVAL_MINUTES] = minutes }
    }
    // ── Daemon ─────────────────────────────────────────────────
    suspend fun saveDaemonEnabled(enabled: Boolean) {
        context.dataStore.edit { it[DAEMON_ENABLED] = enabled }
    }
    // ── Auto Backup ───────────────────────────────────────────
    suspend fun saveAutoBackupEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_BACKUP_ENABLED] = enabled }
    }
    suspend fun saveAutoBackupPeriodHours(hours: Int) {
        context.dataStore.edit { it[AUTO_BACKUP_PERIOD_HOURS] = hours }
    }
    suspend fun saveAutoBackupCategories(categories: String) {
        context.dataStore.edit { it[AUTO_BACKUP_CATEGORIES] = categories }
    }
    suspend fun saveAutoBackupDirectory(path: String) {
        context.dataStore.edit { it[AUTO_BACKUP_DIRECTORY] = path }
    }
    suspend fun saveAutoDeleteEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_DELETE_ENABLED] = enabled }
    }
    suspend fun saveAutoDeletePeriodHours(hours: Int) {
        context.dataStore.edit { it[AUTO_DELETE_PERIOD_HOURS] = hours }
    }
    suspend fun saveLastBackupTimestamp(timestamp: Long) {
        context.dataStore.edit { it[LAST_BACKUP_TIMESTAMP] = timestamp }
    }
    suspend fun saveLastModelsFetchFingerprint(fingerprint: String) =
        modelPreferenceStore.saveLastModelsFetchFingerprint(fingerprint)

    /**
     * Clears only settings that are portable across devices. Secrets, conversation-scoped
     * overrides, local model files, sandbox state, onboarding/rating metadata, and auto-backup
     * configuration are deliberately outside this reset boundary.
     *
     * Composite portable records (remote embedding models, shell devices, MCP servers, and the
     * custom font) are rebuilt separately by the importer so device-local records and credentials
     * can be retained or cleared according to their own import category.
     */
    suspend fun resetPortableSettingsForImport() {
        context.dataStore.edit { prefs ->
            prefs.remove(SELECTED_MODEL)
            prefs.remove(CUSTOM_MODELS)
            prefs.remove(ENABLED_MODELS)
            prefs.remove(ACTIVE_SYSTEM_PROMPT_ID)
            prefs.remove(MODEL_ALIASES_JSON)
            prefs[MODEL_PROVIDER_NAMES_JSON] = "{}"
            prefs.remove(CONTEXT_TOKEN_BUDGET)
            prefs.remove(MAX_CONTEXT_WINDOW)
            prefs.remove(VISUALIZE_CONTEXT_ROLLOUT)
            prefs.remove(CONTEXT_COMPACT_ENABLED)
            prefs.remove(CONTEXT_COMPACT_MODEL)
            prefs.remove(CONTEXT_COMPACT_PROMPT)
            prefs.remove(CONTEXT_COMPACT_RETAIN_COUNT)
            prefs.remove(CONTEXT_COMPACT_THRESHOLD_PERCENT)
            prefs.remove(CODE_EXECUTION_ENABLED)
            prefs.remove(GOOGLE_SEARCH_ENABLED)
            prefs.remove(THINKING_ENABLED)
            prefs.remove(THINKING_LEVEL)
            prefs.remove(THINKING_BUDGET_ENABLED)
            prefs.remove(THINKING_BUDGET_TOKENS)
            prefs.remove(OPENAI_SERVICE_TIER_ENABLED)
            prefs.remove(OPENAI_SERVICE_TIER)
            prefs.remove(OPENAI_RESPONSES_API_ENABLED)
            prefs.remove(PROVIDER_BASE_URLS)
            prefs.remove(TITLE_GENERATION_ENABLED)
            prefs.remove(TITLE_GENERATION_MODEL)
            prefs.remove(TITLE_GENERATION_PROMPT)
            prefs.remove(TITLE_GENERATION_NOTIFICATIONS_ENABLED)
            prefs.remove(IMAGE_TRANSCRIPTION_ENABLED)
            prefs.remove(IMAGE_TRANSCRIPTION_ENABLED_MODELS)
            prefs.remove(IMAGE_TRANSCRIPTION_MODEL)
            prefs.remove(IMAGE_TRANSCRIPTION_BATCH_SIZE)
            prefs.remove(IMAGE_TRANSCRIPTION_PROMPT)
            prefs.remove(ACCESS_PAST_CONVERSATIONS)
            prefs.remove(ACCESS_SAVED_MEMORIES)
            prefs.remove(ACCESS_ACTIVE_MEMORY)
            prefs.remove(ACCESS_SKILLS)
            prefs.remove(ACCESS_SKILLS_MODIFY)
            prefs.remove(RAG_SEARCH_ENABLED)
            prefs.remove(MODEL_SEARCH_METHOD)
            prefs.remove(MANUAL_SEARCH_METHOD)
            prefs.remove(APP_LANGUAGE)
            prefs.remove(WEB_SEARCH_ENABLED)
            prefs.remove(WEB_SEARCH_PROVIDER)
            prefs.remove(WEB_SEARCH_NUM_RESULTS)
            prefs.remove(WEB_SEARCH_BASE_URL)
            prefs.remove(IMAGE_GEN_ENABLED)
            prefs.remove(IMAGE_GEN_MODEL)
            prefs.remove(IMAGE_GEN_SIZE)
            prefs.remove(SEARCH_CONTEXT_WINDOW)
            prefs.remove(SEARCH_MATCH_LIMIT)
            prefs.remove(RAG_THRESHOLD)
            prefs.remove(AUTO_CACHE_ENABLED)
            prefs.remove(SHOW_UNCACHED_NOTIFICATION)
            prefs.remove(AUTO_UPDATE_CHECK)
            prefs.remove(CUSTOM_PROVIDERS_JSON)
            prefs.remove(SHELL_ENABLED)
            prefs.remove(AUTOMATION_TOOLS_ENABLED)
            prefs.remove(EXACT_EXECUTION_ENABLED)
            prefs.remove(AUTOMATION_WAKE_LOCK_ENABLED)
            prefs.remove(DAEMON_ENABLED)
            prefs.remove(HEARTBEAT_ENABLED)
            prefs.remove(HEARTBEAT_INTERVAL_MINUTES)
            prefs.remove(HEARTBEAT_ACTIVE_HOURS_START)
            prefs.remove(HEARTBEAT_ACTIVE_HOURS_END)
            prefs.remove(HEARTBEAT_PROMPT)
            prefs.remove(HEARTBEAT_MODEL)
            prefs.remove(HEARTBEAT_CONVERSATION_ID)
            prefs.remove(SMS_READ_ENABLED)
            prefs.remove(SMS_SEND_ENABLED)
            prefs.remove(SMS_POLL_INTERVAL_MINUTES)
            prefs.remove(PROXY_ENABLED)
            prefs.remove(PROXY_TYPE)
            prefs.remove(PROXY_HOST)
            prefs.remove(PROXY_PORT)
            prefs.remove(PROXY_USERNAME)
            prefs.remove(PROXY_BYPASS)
            prefs.remove(SHELL_CONFIRM_ENABLED)
            prefs.remove(THEME_MODE)
            prefs.remove(AMOLED_ENABLED)
            prefs.remove(COLOR_SCHEME)
            prefs.remove(DYNAMIC_COLOR)
            prefs.remove(BLUR_EFFECTS_ENABLED)
            prefs.remove(REDUCE_MOTION)
            prefs.remove(STICK_TO_BOTTOM)
            prefs.remove(PARSE_INLINE_DOLLAR_MATH)
            prefs.remove(HAPTICS_ENABLED)
            prefs.remove(DETAILED_TOKEN_USAGE)
            prefs.remove(TOOL_CALL_DISPLAY_MODE)
            prefs.remove(THINKING_SEGMENT_DISPLAY_MODE)
            prefs.remove(AUTO_EXPAND_ACTIVE_GROUP)
            prefs.remove(SCHEME_STYLE)
            prefs.remove(FONT_PREFERENCE)
            prefs.remove(SHOW_DOCUMENTATION_FAB)
            prefs.remove(DEFAULT_TEMPERATURE)
            prefs.remove(DEFAULT_MAX_TOKENS)
            prefs.remove(DEFAULT_TOP_P)
            prefs.remove(DEFAULT_FREQUENCY_PENALTY)
            prefs.remove(DEFAULT_PRESENCE_PENALTY)

            // Derived fetch state is never restored. Invalidate it when portable provider/model
            // configuration is replaced so stale results cannot masquerade as imported data.
            prefs.remove(AVAILABLE_MODELS_JSON)
            prefs.remove(CUSTOM_ENDPOINT_RESOLUTIONS_JSON)
            prefs.remove(LAST_MODELS_FETCH_FINGERPRINT)
        }
        settingsNotifications.resetPortableKeys()
    }
    suspend fun invalidatePortableModelCaches() =
        modelPreferenceStore.invalidatePortableModelCaches()
}
