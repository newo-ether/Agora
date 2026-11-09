package com.newoether.agora.presentation

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.isResponsesApiEnabledForProvider
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.util.Constants

internal data class EffectiveConversationControls(
    val settingsOwnerId: String?,
    val codeExecutionEnabled: Boolean,
    val googleSearchEnabled: Boolean,
    val thinkingEnabled: Boolean,
    val thinkingLevel: String,
    val thinkingBudgetEnabled: Boolean,
    val thinkingBudgetTokens: Int,
    val openAiWebSearchAvailable: Boolean,
    val openAiWebSearchEnabled: Boolean,
    val openAiServiceTierState: OpenAiConversationServiceTierState,
    val webSearchAvailable: Boolean,
    val webSearchEnabled: Boolean,
    val shellAvailable: Boolean,
    val shellEnabled: Boolean,
    val showLowContextMode: Boolean,
    val lowContextModeEnabled: Boolean,
    val contextWindow: Int,
)

/** New Chat owns its settings even while the previous conversation id remains under transition. */
internal fun conversationSettingsOwnerId(
    isNewChatMode: Boolean,
    currentConversationId: String?,
): String? = currentConversationId.takeUnless { isNewChatMode }

internal val advancedMaxTokensPresets = intArrayOf(256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072)

/** Applies only the editor's six fields to the current settings, not its opening tool snapshot. */
internal fun ConversationSettings.withGenerationParameters(draft: ConversationSettings) = copy(
    contextWindow = draft.contextWindow?.let(ContextBudget::normalize),
    temperature = draft.temperature,
    maxTokens = draft.maxTokens,
    topP = draft.topP,
    frequencyPenalty = draft.frequencyPenalty,
    presencePenalty = draft.presencePenalty,
)

internal fun validGenerationParameters(draft: ConversationSettings): Boolean =
    (draft.contextWindow == null || draft.contextWindow in ContextBudget.MIN_TOKENS..ContextBudget.MAX_TOKENS) &&
    (draft.maxTokens == null || draft.maxTokens > 0) &&
    listOf(draft.temperature to 0f..2f, draft.topP to 0f..1f,
        draft.frequencyPenalty to -2f..2f, draft.presencePenalty to -2f..2f).all { (value, range) ->
        value == null || (value.isFinite() && value in range)
    }

/** Pure projection shared by Compose and a connection-local WebUI composer. */
internal fun resolveEffectiveConversationControls(
    settingsOwnerId: String?,
    conversationOverride: ConversationSettings?,
    global: ConversationSettings,
    selectedProviderName: String,
    openAiResponsesApiEnabled: Boolean,
    customProviders: List<CustomProviderConfig>,
): EffectiveConversationControls {
    val isEmbeddedLocalModel = selectedProviderName == Constants.PROVIDER_LOCAL

    return EffectiveConversationControls(
        settingsOwnerId = settingsOwnerId,
        codeExecutionEnabled = conversationOverride?.codeExecutionEnabled ?: requireNotNull(global.codeExecutionEnabled),
        googleSearchEnabled = conversationOverride?.googleSearchEnabled ?: requireNotNull(global.googleSearchEnabled),
        thinkingEnabled = conversationOverride?.thinkingEnabled ?: requireNotNull(global.thinkingEnabled),
        thinkingLevel = conversationOverride?.thinkingLevel ?: requireNotNull(global.thinkingLevel),
        thinkingBudgetEnabled =
            conversationOverride?.thinkingBudgetEnabled ?: requireNotNull(global.thinkingBudgetEnabled),
        thinkingBudgetTokens =
            conversationOverride?.thinkingBudgetTokens ?: requireNotNull(global.thinkingBudgetTokens),
        openAiWebSearchAvailable = resolveOpenAiNativeSearchAvailability(
            selectedProviderName,
            openAiResponsesApiEnabled,
            customProviders,
        ),
        openAiWebSearchEnabled = conversationOverride?.openAiWebSearchEnabled ?: true,
        openAiServiceTierState = resolveOpenAiConversationServiceTier(
            requireNotNull(global.openAiServiceTierEnabled),
            requireNotNull(global.openAiServiceTier),
            conversationOverride,
            selectedProviderName,
            openAiResponsesApiEnabled,
            customProviders,
        ),
        webSearchAvailable = requireNotNull(global.webSearchEnabled),
        webSearchEnabled = requireNotNull(global.webSearchEnabled) && (conversationOverride?.webSearchEnabled ?: true),
        shellAvailable = requireNotNull(global.shellEnabled),
        shellEnabled = requireNotNull(global.shellEnabled) && (conversationOverride?.shellEnabled ?: true),
        showLowContextMode = isEmbeddedLocalModel,
        lowContextModeEnabled = isEmbeddedLocalModel &&
            (conversationOverride?.lowContextModeEnabled ?: requireNotNull(global.lowContextModeEnabled)),
        contextWindow = ContextBudget.normalize(conversationOverride?.contextWindow ?: requireNotNull(global.contextWindow)),
    )
}

internal data class OpenAiConversationServiceTierState(
    val available: Boolean,
    val enabled: Boolean,
    val tier: String,
)

internal fun resolveOpenAiConversationServiceTier(
    globalEnabled: Boolean,
    globalTier: String,
    conversationOverride: ConversationSettings?,
    providerName: String,
    builtInOpenAiResponsesEnabled: Boolean,
    customProviders: List<CustomProviderConfig>,
): OpenAiConversationServiceTierState = OpenAiConversationServiceTierState(
    available = isResponsesApiEnabledForProvider(
        providerName = providerName,
        builtInOpenAiEnabled = builtInOpenAiResponsesEnabled,
        customProviders = customProviders,
    ),
    enabled = conversationOverride?.openAiServiceTierEnabled ?: globalEnabled,
    tier = OpenAiServiceTiers.normalize(
        conversationOverride?.openAiServiceTier ?: globalTier,
    ),
)

internal fun resolveOpenAiNativeSearchAvailability(
    providerName: String,
    builtInOpenAiEnabled: Boolean,
    customProviders: List<CustomProviderConfig>,
): Boolean = isResponsesApiEnabledForProvider(
    providerName = providerName,
    builtInOpenAiEnabled = builtInOpenAiEnabled,
    customProviders = customProviders,
)
