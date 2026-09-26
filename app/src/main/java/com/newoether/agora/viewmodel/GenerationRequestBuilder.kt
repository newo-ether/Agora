package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.api.ProviderRequestInput
import com.newoether.agora.api.ProviderRequestResolver
import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.api.util.prepareMessages
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.PredefinedVariables
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.data.isResponsesApiEnabledForProvider
import com.newoether.agora.data.isAnthropicCacheEnabledForProvider
import com.newoether.agora.data.anthropicCacheTtlForProvider
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.NewChatPersistEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.ModelThinkingCapabilities
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.model.apiModelName
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun buildPromptRuntimeValues(
    now: java.util.Date,
    modelId: String,
    activeMemory: String,
    skillCatalog: String,
): Map<String, String> {
    val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    val sentDateFormat = java.text.SimpleDateFormat(
        PredefinedVariables.SENT_DATE_PATTERN,
        java.util.Locale.US,
    )
    return mapOf(
        PredefinedVariables.TIME to timeFormat.format(now),
        PredefinedVariables.DATE to dateFormat.format(now),
        PredefinedVariables.SENT_TIME to timeFormat.format(now),
        PredefinedVariables.SENT_DATE to sentDateFormat.format(now),
        PredefinedVariables.CURRENT_MODEL_ID to modelId,
        PredefinedVariables.MESSAGE_MODEL_ID to "",
        PredefinedVariables.MODEL_ID to modelId,
        PredefinedVariables.ACTIVE_MEMORY to activeMemory,
        PredefinedVariables.SKILL_CATALOG to skillCatalog,
    )
}

/**
 * Stateless builder for the LLM generation request. Extracted from ChatViewModel.
 * Reads configuration singletons only; holds NO mutable UI state.
 */
class GenerationRequestBuilder(
    private val settings: SettingsRepository,
    private val convRepo: ConversationRepository,
    private val memoryManager: MemoryManager,
    private val skillManager: SkillManager,
    private val providerRegistry: ProviderRegistry,
    private val ragManager: RagManager,
    private val appContext: Context,
    // This remains a StateFlow because buildEffectiveConversationSettings reads its current value.
    private val pendingConversationSettings: StateFlow<ConversationSettings?>,
    // resolveProviderKey uses this callback to emit snackbar messages.
    private val onSnackbar: (String) -> Unit,
) {
    data class ProviderKey(val providerName: String, val apiKey: String)

    /** Resolves the active provider+key for [modelId] and verifies configuration.
     *  Emits a snackbar and returns null when the provider is not configured. */
    internal fun resolveProviderKey(modelId: String): ProviderKey? {
        val providerName = providerRegistry.providerForModel(modelId)
        val activeKey = settings.resolveActiveKey(providerName) ?: ""
        if (!providerRegistry.isConfigured(providerName, activeKey)) {
            val displayProviderName = providerDisplayName(
                providerName,
                settings.customProviders.value,
            )
            onSnackbar(
                appContext.getString(
                    R.string.no_api_key_for_provider,
                    displayProviderName,
                )
            )
            return null
        }
        return ProviderKey(providerName, activeKey)
    }

    internal suspend fun prepareForegroundSend(
        target: ForegroundSendTarget,
        composer: ConversationComposerSnapshot,
        validationContext: Context,
    ): ForegroundSendAdmission? {
        val startedNs = System.nanoTime()
        fun markStage(name: String) {
            com.newoether.agora.util.DebugLog.sendStage(
                runId = target.runId,
                component = "prepare",
                stage = name,
                elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L,
            )
        }
        markStage("await-settings")
        settings.awaitInitialLoad()
        if (target.modelId.isBlank()) {
            onSnackbar(validationContext.getString(R.string.no_model_selected))
            return null
        }
        markStage("await-provider")
        val selectedProvider = awaitProviderKey(target.modelId) ?: return null
        markStage("validate-provider")
        if (selectedProvider.providerName == Constants.PROVIDER_LOCAL) {
            val localModelId = target.modelId.substringAfter("${Constants.PROVIDER_LOCAL}:")
            val localConfig = settings.localChatModels.value.find { it.modelId == localModelId }
            if (localConfig == null || !java.io.File(localConfig.localFilePath).exists()) {
                onSnackbar(validationContext.getString(R.string.local_model_not_found))
                return null
            }
        }
        markStage("await-workspace")
        val workspace = target.newChatWorkspace?.awaitCaptured()
        markStage(if (target.wasNewChat) "new-conversation-snapshot" else "read-conversation")
        val conversationSnapshot = if (target.wasNewChat) {
            ChatEntity(
                id = target.conversationId,
                title = initialConversationTitle(
                    prompt = composer.text,
                    fallback = appContext.getString(R.string.new_chat),
                ),
                modelId = target.modelId,
                systemPromptId = workspace?.systemPromptId,
            )
        } else {
            convRepo.getConversation(target.conversationId) ?: return null
        }
        val settingsOverride = if (target.wasNewChat) {
            workspace?.conversationSettings
        } else {
            settings.conversationSettings.value[target.ownerId]
        }
        markStage("capture-generation-snapshot")
        val generationSnapshot = captureAdmissionSnapshot(
            conversationId = target.conversationId,
            runId = target.runId,
            modelId = target.modelId,
            conversationOverride = conversationSnapshot,
            conversationSettingsOverride = settingsOverride,
        )
        markStage("serialize-admission")
        return ForegroundSendAdmission(
            target = target,
            generationSnapshot = generationSnapshot,
            newConversation = conversationSnapshot.takeIf { target.wasNewChat },
            newConversationSettings = workspace?.conversationSettings,
            newChatPersistSnapshot = if (target.wasNewChat) {
                (workspace?.persisted ?: NewChatPersistEntity()).copy(
                    draftText = composer.text,
                    draftAttachments = composer.attachments
                        .takeIf(List<*>::isNotEmpty)
                        ?.let(Json::encodeToString),
                )
            } else {
                null
            },
        )
    }

    internal suspend fun awaitProviderKey(modelId: String): ProviderKey? {
        providerRegistry.awaitInitialSync()
        return resolveProviderKey(modelId)
    }

    private fun resolveTranscriptionProviderName(model: String?): String =
        model?.let { providerRegistry.providerForModel(it) } ?: ""

    private fun resolveTranscriptionModelId(model: String?): String =
        model?.let {
            ModelId.parse(providerRegistry.canonicalModelId(it)).modelName
        } ?: ""

    private fun resolveTranscriptionApiKey(model: String?): String {
        model ?: return ""
        val providerName = providerRegistry.providerForModel(model)
        if (providerName == Constants.PROVIDER_LOCAL) return ""
        return settings.resolveActiveKey(providerName) ?: ""
    }

    private fun resolveTranscriptionBaseUrl(model: String?): String? {
        model ?: return null
        return providerRegistry.getEffectiveBaseUrl(providerRegistry.providerForModel(model))
    }

    // Image generation reuses the selected model's provider credentials (mirrors transcription).
    private fun resolveImageGenModelId(model: String?): String =
        model?.let {
            ModelId.parse(providerRegistry.canonicalModelId(it)).apiModelName
        } ?: ""

    private fun resolveImageGenApiKey(model: String?): String {
        model ?: return ""
        val providerName = providerRegistry.providerForModel(model)
        if (providerName == Constants.PROVIDER_LOCAL) return ""
        return settings.resolveActiveKey(providerName) ?: ""
    }

    private fun resolveImageGenBaseUrl(model: String?): String {
        model ?: return ""
        return providerRegistry.getEffectiveBaseUrl(providerRegistry.providerForModel(model)) ?: ""
    }

    fun buildEffectiveConversationSettings(conversationId: String): ConversationSettings {
        val overrides = settings.conversationSettings.value[conversationId]
            ?: pendingConversationSettings.value  // new chat: may not be saved to map yet
            ?: ConversationSettings()
        return resolveEffectiveConversationSettings(overrides)
    }
    private fun resolveEffectiveConversationSettings(
        overrides: ConversationSettings,
    ): ConversationSettings {
        return ConversationSettings(
            contextWindow = ContextBudget.normalize(
                overrides.contextWindow ?: settings.maxContextWindow.value
            ),
            temperature = overrides.temperature ?: settings.defaultTemperature.value,
            maxTokens = overrides.maxTokens ?: settings.defaultMaxTokens.value,
            topP = overrides.topP ?: settings.defaultTopP.value,
            frequencyPenalty = overrides.frequencyPenalty ?: settings.defaultFrequencyPenalty.value,
            presencePenalty = overrides.presencePenalty ?: settings.defaultPresencePenalty.value,
            codeExecutionEnabled = overrides.codeExecutionEnabled ?: settings.codeExecutionEnabled.value,
            googleSearchEnabled = overrides.googleSearchEnabled ?: settings.googleSearchEnabled.value,
            openAiWebSearchEnabled = overrides.openAiWebSearchEnabled ?: true,
            thinkingEnabled = overrides.thinkingEnabled ?: settings.thinkingEnabled.value,
            thinkingLevel = overrides.thinkingLevel ?: settings.thinkingLevel.value,
            thinkingBudgetEnabled = overrides.thinkingBudgetEnabled ?: settings.thinkingBudgetEnabled.value,
            thinkingBudgetTokens = overrides.thinkingBudgetTokens ?: settings.thinkingBudgetTokens.value,
            openAiServiceTierEnabled =
                overrides.openAiServiceTierEnabled ?: settings.openAiServiceTierEnabled.value,
            openAiServiceTier = OpenAiServiceTiers.normalize(
                overrides.openAiServiceTier ?: settings.openAiServiceTier.value,
            ),
            webSearchEnabled = if (settings.webSearchEnabled.value) (overrides.webSearchEnabled ?: true) else false,
            shellEnabled = if (settings.shellEnabled.value) (overrides.shellEnabled ?: true) else false,
            lowContextModeEnabled =
                overrides.lowContextModeEnabled ?: settings.localLowContextModeEnabled.value,
        )
    }

    /**
     * Captures every setting owned by one generation before its Room graph is admitted.
     *
     * The returned value contains only immutable/copy-on-capture data. Later settings edits can
     * affect the next Run, but not Compact preflight, Provider passes, or tool continuation for
     * this Run.
     */
    internal suspend fun captureAdmissionSnapshot(
        conversationId: String,
        runId: String,
        modelId: String,
        conversationOverride: ChatEntity? = null,
        resolvedPromptOverride: ResolvedPrompt? = null,
        conversationSettingsOverride: ConversationSettings? = null,
    ): GenerationAdmissionSnapshot {
        providerRegistry.awaitInitialSync()
        val selectedModelId = providerRegistry.canonicalModelId(modelId)
        val providerName = providerRegistry.providerForModel(selectedModelId)
        val effectiveSettings = if (conversationOverride != null) {
            resolveEffectiveConversationSettings(
                conversationSettingsOverride ?: ConversationSettings(),
            )
        } else {
            buildEffectiveConversationSettings(conversationId)
        }
        val frozenKey = settings.awaitActiveKey(providerName).orEmpty()
        check(providerRegistry.isConfigured(providerName, frozenKey)) {
            "Provider is no longer configured: $providerName"
        }
        val (baseConfig, context) = buildGenerationPair(
            providerName = providerName,
            modelId = selectedModelId,
            activeKey = frozenKey,
            resolvedSystemPrompt = null,
            resolvedUserPrepend = null,
            resolvedUserPostpend = null,
            effectiveSettings = effectiveSettings,
            currentId = conversationId,
            applyLowContextMode = true,
            includeSkillCatalog =
                settings.accessSkills.value &&
                    !(
                        providerName == Constants.PROVIDER_LOCAL &&
                            effectiveSettings.lowContextModeEnabled == true
                        ),
        )
        val compactModel = settings.contextCompactModel.value
            ?.takeIf(String::isNotBlank)
            ?.let(providerRegistry::canonicalModelId)
            ?: selectedModelId
        val compactProviderName = providerRegistry.providerForModel(compactModel)
        val providerInstances = providerRegistry.generationSnapshot()
        val compactKey = if (compactProviderName == providerName) {
            frozenKey
        } else {
            settings.resolveActiveKey(compactProviderName).orEmpty()
        }
        val compactPreserveSystemPrompt = settings.contextCompactPreserveSystemPrompt.value
        val compactSystemPrompt = if (compactPreserveSystemPrompt) {
            resolveStandardSystemPromptForCompact(
                conversationId = conversationId,
                conversationOverride = conversationOverride,
                modelId = compactModel,
            )
        } else {
            settings.contextCompactPrompt.value
        }
        val (compactGenerationConfig, compactGenerationContext) = buildGenerationPair(
            providerName = compactProviderName,
            modelId = compactModel,
            activeKey = compactKey,
            resolvedSystemPrompt = compactSystemPrompt,
            resolvedUserPrepend = null,
            resolvedUserPostpend = null,
            effectiveSettings = effectiveSettings,
            currentId = conversationId,
            applyLowContextMode = false,
            includeSkillCatalog = false,
        )
        val automaticCompact = AutomaticCompactConfig(
            enabled = settings.contextCompactEnabled.value,
            thresholdPercent = settings.contextCompactThresholdPercent.value,
            request = CompactRequest(
                model = compactModel,
                prompt = settings.contextCompactPrompt.value,
                retainLogicalMessages = settings.contextCompactRetainCount.value,
                preserveSystemPrompt = compactPreserveSystemPrompt,
            ),
            providerName = compactProviderName,
            apiKey = compactKey,
            baseUrl = providerRegistry.getEffectiveBaseUrl(compactProviderName),
            responsesApiEnabled = isResponsesApiEnabledForProvider(
                providerName = compactProviderName,
                builtInOpenAiEnabled = settings.openAiResponsesApiEnabled.value,
                customProviders = settings.customProviders.value,
            ),
            provider = providerInstances[compactProviderName],
            configured = providerRegistry.isConfigured(compactProviderName, compactKey),
            generationConfig = compactGenerationConfig,
            providerInstances = providerInstances,
            generationContext = compactGenerationContext.copy(
                webSearchApiKeys = context.webSearchApiKeys.toMap(),
                shellDevices = context.shellDevices.toList(),
            ),
        )
        val titleGenerationEnabled = settings.titleGenerationEnabled.value
        val ordinaryConfig = if (baseConfig.lowContextModeEnabled) {
            baseConfig.copy(
                effectiveSystemPrompt = null,
                userPrepend = null,
                userPostpend = null,
                assistantPrepend = null,
                assistantPostpend = null,
                promptTemplate = null,
                requestResolver = null,
            )
        } else resolvedPromptOverride?.let { override ->
            baseConfig.copy(
                effectiveSystemPrompt = override.systemPrompt,
                userPrepend = override.userPrepend,
                userPostpend = override.userPostpend,
                assistantPrepend = override.assistantPrepend,
                assistantPostpend = override.assistantPostpend,
            )
        } ?: run {
            val promptTemplate = capturePromptTemplate(
                currentId = conversationId,
                conversationOverride = conversationOverride,
                promptSettings = capturePromptSettings(),
            )
            baseConfig.copy(
                effectiveSystemPrompt = null,
                userPrepend = null,
                userPostpend = null,
                assistantPrepend = null,
                assistantPostpend = null,
                promptTemplate = promptTemplate,
                requestResolver = createRequestResolver(promptTemplate, selectedModelId),
            )
        }
        return GenerationAdmissionSnapshot(
            conversationId = conversationId,
            runId = runId,
            selectedModelId = selectedModelId,
            config = ordinaryConfig,
            context = context.copy(
                webSearchApiKeys = context.webSearchApiKeys.toMap(),
                shellDevices = context.shellDevices.toList(),
            ),
            providerInstances = providerInstances,
            automaticCompact = automaticCompact,
            titleGenerationEnabled = titleGenerationEnabled,
        )
    }

    /**
     * Captures only the system-prompt and tool-definition inputs needed by the context indicator.
     * Unlike Run admission this must work before a Provider has a usable key or endpoint.
     */
    internal suspend fun captureContextProjectionSnapshot(
        conversationId: String,
        modelId: String,
        systemPromptIdOverride: String? = null,
    ): GenerationContextProjectionSnapshot {
        val selectedModelId = providerRegistry.canonicalModelId(modelId)
        val providerName = providerRegistry.providerForModel(selectedModelId)
        val effectiveSettings = buildEffectiveConversationSettings(conversationId)
        val (baseConfig, context) = buildGenerationPair(
            providerName = providerName,
            modelId = selectedModelId,
            activeKey = "",
            resolvedSystemPrompt = null,
            resolvedUserPrepend = null,
            resolvedUserPostpend = null,
            effectiveSettings = effectiveSettings,
            currentId = conversationId,
            applyLowContextMode = true,
            includeSkillCatalog =
                settings.accessSkills.value &&
                    !(
                        providerName == Constants.PROVIDER_LOCAL &&
                            effectiveSettings.lowContextModeEnabled == true
                        ),
        )
        if (baseConfig.lowContextModeEnabled) {
            return GenerationContextProjectionSnapshot(
                config = baseConfig,
                context = context.copy(
                    webSearchApiKeys = context.webSearchApiKeys.toMap(),
                    shellDevices = context.shellDevices.toList(),
                ),
            )
        }
        val promptTemplate = capturePromptTemplate(
            currentId = conversationId,
            conversationOverride = null,
            promptSettings = capturePromptSettings(),
            systemPromptIdOverride = systemPromptIdOverride,
        )
        val resolved = resolvePromptTemplate(promptTemplate, selectedModelId)
        return GenerationContextProjectionSnapshot(
            config = baseConfig.copy(
                effectiveSystemPrompt = resolved.systemPrompt,
                userPrepend = resolved.userPrepend,
                userPostpend = resolved.userPostpend,
                assistantPrepend = resolved.assistantPrepend,
                assistantPostpend = resolved.assistantPostpend,
                promptTemplate = promptTemplate,
            ),
            context = context.copy(
                webSearchApiKeys = context.webSearchApiKeys.toMap(),
                shellDevices = context.shellDevices.toList(),
            ),
        )
    }

    private fun buildGenerationPair(
        providerName: String,
        modelId: String,
        activeKey: String,
        resolvedSystemPrompt: String?,
        resolvedUserPrepend: String?,
        resolvedUserPostpend: String?,
        effectiveSettings: ConversationSettings,
        currentId: String,
        applyLowContextMode: Boolean,
        includeSkillCatalog: Boolean,
    ): Pair<GenerationConfig, GenerationContext> {
        val lowContextModeEnabled =
            applyLowContextMode &&
                providerName == Constants.PROVIDER_LOCAL &&
                effectiveSettings.lowContextModeEnabled == true
        val imageGenModel = settings.imageGenModel.value
        val transcriptionModel = settings.imageTranscriptionModel.value
        val cacheProviders = settings.customProviders.value
        val cacheEnabled = settings.anthropicCacheEnabled.value
        val cacheTtl = settings.anthropicCacheTtl.value
        val transcriptionProviderName = resolveTranscriptionProviderName(transcriptionModel)
        val configuredSkillReadAccess = settings.accessSkills.value
        val skillReadAccess = configuredSkillReadAccess && includeSkillCatalog
        val skillModifyAccess = skillReadAccess && settings.accessSkillsModify.value
        val skillCatalog = if (skillReadAccess) skillManager.catalog() else ""
        val responsesApiEnabled = isResponsesApiEnabledForProvider(
            providerName = providerName,
            builtInOpenAiEnabled = settings.openAiResponsesApiEnabled.value,
            customProviders = settings.customProviders.value,
        )
        val config = GenerationConfig(
            anthropicCacheEnabled = isAnthropicCacheEnabledForProvider(providerName, cacheEnabled, cacheProviders),
            anthropicCacheTtl = anthropicCacheTtlForProvider(providerName, cacheTtl, cacheProviders),
            providerName = providerName,
            modelId = ModelId.parse(providerRegistry.canonicalModelId(modelId)).modelName,
            // The user's correction for THIS model, so a relay that rejects a documented field can
            // be fixed without changing the built-in capability table.
            thinkingCapabilityOverride = settings.thinkingCapabilityOverrides.value[
                ModelThinkingCapabilities.overrideKey(
                    providerName,
                    ModelId.parse(providerRegistry.canonicalModelId(modelId)).modelName,
                ),
            ],
            apiKey = activeKey,
            effectiveSystemPrompt = resolvedSystemPrompt,
            maxContextWindow = ContextBudget.normalize(
                effectiveSettings.contextWindow ?: settings.maxContextWindow.value
            ),
            codeExecutionEnabled = if (lowContextModeEnabled) false
            else effectiveSettings.codeExecutionEnabled ?: settings.codeExecutionEnabled.value,
            googleSearchEnabled = if (lowContextModeEnabled) false
            else effectiveSettings.googleSearchEnabled ?: settings.googleSearchEnabled.value,
            thinkingEnabled = effectiveSettings.thinkingEnabled ?: settings.thinkingEnabled.value,
            thinkingLevel = effectiveSettings.thinkingLevel ?: settings.thinkingLevel.value,
            thinkingBudgetEnabled = effectiveSettings.thinkingBudgetEnabled ?: settings.thinkingBudgetEnabled.value,
            thinkingBudgetTokens = effectiveSettings.thinkingBudgetTokens ?: settings.thinkingBudgetTokens.value,
            openAiServiceTier = OpenAiServiceTiers.requestValue(
                enabled = effectiveSettings.openAiServiceTierEnabled == true,
                value = effectiveSettings.openAiServiceTier,
                responsesApiEnabled = responsesApiEnabled,
            ),
            responsesApiEnabled = responsesApiEnabled,
            openAiWebSearchEnabled =
                !lowContextModeEnabled &&
                    effectiveSettings.openAiWebSearchEnabled == true && responsesApiEnabled,
            baseUrl = providerRegistry.getEffectiveBaseUrl(providerName),
            userPrepend = resolvedUserPrepend,
            userPostpend = resolvedUserPostpend,
            lowContextModeEnabled = lowContextModeEnabled,
            temperature = effectiveSettings.temperature,
            maxTokens = effectiveSettings.maxTokens,
            topP = effectiveSettings.topP,
            frequencyPenalty = effectiveSettings.frequencyPenalty,
            presencePenalty = effectiveSettings.presencePenalty
        )
        val genCtx = GenerationContext(
            conversationId = currentId,
            accessSavedMemories = settings.accessSavedMemories.value,
            accessActiveMemory = settings.accessActiveMemory.value,
            skillReadAccess = skillReadAccess,
            skillModifyAccess = skillModifyAccess,
            skillCatalog = skillCatalog,
            accessPastConversations = settings.accessPastConversations.value,
            modelSearchMethod = settings.modelSearchMethod.value,
            activeEmbeddingConfig = ragManager.activeEmbeddingModel.value,
            embeddingApiKey = ragManager.resolveEmbeddingApiKey() ?: "",
            ragThreshold = settings.ragThreshold.value,
            searchMatchLimit = settings.searchMatchLimit.value,
            searchContextWindow = settings.searchContextWindow.value,
            webSearchEnabled = effectiveSettings.webSearchEnabled ?: settings.webSearchEnabled.value,
            webSearchApiKeys = settings.webSearchApiKeys.value,
            webSearchProvider = settings.webSearchProvider.value,
            webSearchNumResults = settings.webSearchNumResults.value,
            webSearchBaseUrl = settings.webSearchBaseUrl.value,
            imageGenEnabled = settings.imageGenEnabled.value && imageGenModel?.contains(":") == true,
            imageGenApiKey = resolveImageGenApiKey(imageGenModel),
            imageGenBaseUrl = resolveImageGenBaseUrl(imageGenModel),
            imageGenModel = resolveImageGenModelId(imageGenModel),
            imageGenSize = settings.imageGenSize.value,
            automationToolsEnabled = settings.automationToolsEnabled.value,
            shellEnabled = effectiveSettings.shellEnabled ?: settings.shellEnabled.value,
            shellDevices = settings.shellDevices.value,
            sandboxEnabled = settings.sandboxEnabled.value,
            sandboxSharedStorageEnabled = settings.sandboxSharedStorageEnabled.value,
            // Keyed on THIS generation's model, not the UI's currently-selected one — a queued
            // or parallel-conversation generation must not inherit another conversation's model.
            imageTranscriptionEnabled =
                settings.imageTranscriptionEnabled.value &&
                    settings.imageTranscriptionEnabledModels.value.contains(modelId),
            imageTranscriptionModel = transcriptionModel,
            imageTranscriptionBatchSize = settings.imageTranscriptionBatchSize.value,
            imageTranscriptionPrompt = settings.imageTranscriptionPrompt.value,
            transcriptionProviderName = transcriptionProviderName,
            transcriptionAnthropicCacheEnabled = isAnthropicCacheEnabledForProvider(
                transcriptionProviderName, cacheEnabled, cacheProviders,
            ),
            transcriptionAnthropicCacheTtl = anthropicCacheTtlForProvider(
                transcriptionProviderName, cacheTtl, cacheProviders,
            ),
            transcriptionModelId = resolveTranscriptionModelId(transcriptionModel),
            transcriptionApiKey = resolveTranscriptionApiKey(transcriptionModel),
            transcriptionBaseUrl = resolveTranscriptionBaseUrl(transcriptionModel)
        )
        return Pair(config, genCtx)
    }

    private data class PromptSettingsSnapshot(
        val activeSystemPromptId: String?,
        val systemPrompts: List<SystemPromptEntry>,
    )

    private fun capturePromptSettings() = PromptSettingsSnapshot(
        activeSystemPromptId = settings.activeSystemPromptId.value,
        systemPrompts = settings.systemPrompts.value.toList(),
    )

    /**
     * Preserved-Compact path: resolve the conversation's ordinary system prompt so the
     * compaction request carries exactly what a normal generation would send for the compact
     * model. Prompt variables are evaluated at admission; compaction is single-pass, so no
     * per-provider-pass re-resolution is required.
     */
    private suspend fun resolveStandardSystemPromptForCompact(
        conversationId: String,
        conversationOverride: ChatEntity?,
        modelId: String,
    ): String? {
        val promptTemplate = capturePromptTemplate(
            currentId = conversationId,
            conversationOverride = conversationOverride,
            promptSettings = capturePromptSettings(),
        )
        return resolvePromptTemplate(promptTemplate, modelId).systemPrompt
    }

    data class ResolvedPrompt(
        val systemPrompt: String?,
        val userPrepend: String?,
        val userPostpend: String?,
        val assistantPrepend: String? = null,
        val assistantPostpend: String? = null,
    )

    private suspend fun capturePromptTemplate(
        currentId: String,
        conversationOverride: ChatEntity?,
        promptSettings: PromptSettingsSnapshot,
        systemPromptIdOverride: String? = null,
    ): GenerationPromptTemplate = withContext(Dispatchers.Default) {
        val conversation = conversationOverride ?: convRepo.getConversation(currentId)
        val targetPromptId = systemPromptIdOverride
            ?: conversation?.systemPromptId
            ?: promptSettings.activeSystemPromptId
        val entry = promptSettings.systemPrompts.find { it.id == targetPromptId }
        GenerationPromptTemplate(
            systemItems = entry?.resolvedSystemItems?.toList().orEmpty(),
            userItems = entry?.resolvedUserItems?.toList()
                ?: PredefinedVariables.normalizeMessageTemplate(emptyList()),
            assistantItems = entry?.resolvedAssistantItems?.toList()
                ?: PredefinedVariables.normalizeMessageTemplate(emptyList()),
        )
    }

    private fun createRequestResolver(
        promptTemplate: GenerationPromptTemplate,
        activeModel: String,
    ): ProviderRequestResolver = ProviderRequestResolver { messages, providerConfig ->
        val resolved = resolvePromptTemplate(promptTemplate, activeModel)
        val fixedTokenCost = ContextTokenEstimator.estimateFixed(
            systemPrompt = resolved.systemPrompt,
            tools = providerConfig.tools.orEmpty(),
            initialUserPrompt = null,
            codeExecutionEnabled = providerConfig.codeExecutionEnabled,
            googleSearchEnabled = providerConfig.googleSearchEnabled,
            openAiWebSearchEnabled = providerConfig.openAiWebSearchEnabled,
        )
        val providerTokenBudget =
            (providerConfig.maxContextWindow - fixedTokenCost).coerceAtLeast(1)
        val projectedMessages = projectGenerationInputMessages(
            messages = messages,
            includeImages = providerConfig.includeImages,
            userPrepend = resolved.userPrepend,
            userPostpend = resolved.userPostpend,
            assistantPrepend = resolved.assistantPrepend,
            assistantPostpend = resolved.assistantPostpend,
        )
        ProviderRequestInput(
            messages = prepareMessages(projectedMessages, providerTokenBudget),
            systemPrompt = resolved.systemPrompt,
        )
    }

    private suspend fun resolvePromptTemplate(
        promptTemplate: GenerationPromptTemplate,
        activeModel: String,
    ): ResolvedPrompt = withContext(Dispatchers.Default) {
        coroutineScope {
            val includeActiveMemory = settings.accessActiveMemory.value
            val includeSkillCatalog = settings.accessSkills.value
            val activeMemoryDeferred = async(Dispatchers.IO) {
                if (includeActiveMemory) memoryManager.getActiveMemory() else ""
            }
            val skillCatalogDeferred = async {
                if (includeSkillCatalog) skillManager.catalog() else ""
            }
            val modelId = ModelId.parse(
                providerRegistry.canonicalModelId(activeModel),
            ).modelName
            val runtimeValues = buildPromptRuntimeValues(
                now = java.util.Date(),
                modelId = modelId,
                activeMemory = activeMemoryDeferred.await()
                    .takeIf { includeActiveMemory }
                    .orEmpty(),
                skillCatalog = skillCatalogDeferred.await()
                    .takeIf { includeSkillCatalog }
                    .orEmpty(),
            )
            val perMessageValues = runtimeValues.filterKeys {
                it !in PredefinedVariables.PER_MESSAGE_VARS
            }

            fun resolveMessageTemplate(items: List<com.newoether.agora.data.PromptTemplateItem>): Pair<String?, String?> {
                val parts = PredefinedVariables.splitMessageTemplate(items)
                val before = PredefinedVariables.compile(
                    parts.beforePrompt,
                    perMessageValues,
                    emptyMap(),
                ).takeIf(String::isNotEmpty)
                val after = PredefinedVariables.compile(
                    parts.afterPrompt,
                    perMessageValues,
                    emptyMap(),
                ).takeIf(String::isNotEmpty)
                return before to after
            }

            val userTemplate = resolveMessageTemplate(promptTemplate.userItems)
            val assistantTemplate = resolveMessageTemplate(promptTemplate.assistantItems)
            ResolvedPrompt(
                systemPrompt = PredefinedVariables.compile(
                    promptTemplate.systemItems,
                    runtimeValues,
                    emptyMap(),
                ).takeIf(String::isNotEmpty),
                userPrepend = userTemplate.first,
                userPostpend = userTemplate.second,
                assistantPrepend = assistantTemplate.first,
                assistantPostpend = assistantTemplate.second,
            )
        }
    }
}
