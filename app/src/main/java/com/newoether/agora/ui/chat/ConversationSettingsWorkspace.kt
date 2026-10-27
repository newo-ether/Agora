package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ChatViewModel

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

@Composable
internal fun effectiveConversationControls(
    viewModel: ChatViewModel,
    isNewChatMode: Boolean,
    currentConversationId: String?,
    selectedModel: String,
    customProviders: List<CustomProviderConfig>,
): EffectiveConversationControls {
    val conversationSettings by viewModel.settings.conversationSettings.collectAsState()
    val pendingSettings by viewModel.pendingConversationSettings.collectAsState()
    val settingsOwnerId = conversationSettingsOwnerId(isNewChatMode, currentConversationId)
    val conversationOverride: ConversationSettings? =
        if (isNewChatMode) pendingSettings else settingsOwnerId?.let(conversationSettings::get)
    val globalCodeExecution by viewModel.settings.codeExecutionEnabled.collectAsState()
    val globalGoogleSearch by viewModel.settings.googleSearchEnabled.collectAsState()
    val globalThinkingEnabled by viewModel.settings.thinkingEnabled.collectAsState()
    val globalThinkingLevel by viewModel.settings.thinkingLevel.collectAsState()
    val globalThinkingBudgetEnabled by viewModel.settings.thinkingBudgetEnabled.collectAsState()
    val globalThinkingBudgetTokens by viewModel.settings.thinkingBudgetTokens.collectAsState()
    val globalLocalLowContextModeEnabled by
        viewModel.settings.localLowContextModeEnabled.collectAsState()
    val openAiResponsesApiEnabled by viewModel.settings.openAiResponsesApiEnabled.collectAsState()
    val globalWebSearch by viewModel.settings.webSearchEnabled.collectAsState()
    val globalShell by viewModel.settings.shellEnabled.collectAsState()
    val maxContextWindow by viewModel.settings.maxContextWindow.collectAsState()
    val selectedProviderName = viewModel.getProviderForModel(selectedModel)
    val globalTierEnabled by viewModel.settings.openAiServiceTierEnabled.collectAsState()
    val globalTier by viewModel.settings.openAiServiceTier.collectAsState()
    return resolveEffectiveConversationControls(
        settingsOwnerId, conversationOverride,
        ConversationSettings(
            contextWindow = maxContextWindow, codeExecutionEnabled = globalCodeExecution,
            googleSearchEnabled = globalGoogleSearch, thinkingEnabled = globalThinkingEnabled,
            thinkingLevel = globalThinkingLevel, thinkingBudgetEnabled = globalThinkingBudgetEnabled,
            thinkingBudgetTokens = globalThinkingBudgetTokens, webSearchEnabled = globalWebSearch,
            shellEnabled = globalShell, lowContextModeEnabled = globalLocalLowContextModeEnabled,
            openAiServiceTierEnabled = globalTierEnabled, openAiServiceTier = globalTier,
        ),
        selectedProviderName, openAiResponsesApiEnabled, customProviders,
    )
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
        showLowContextMode = true,
        lowContextModeEnabled = conversationOverride?.lowContextModeEnabled
            ?: (isEmbeddedLocalModel && requireNotNull(global.lowContextModeEnabled)),
        contextWindow = ContextBudget.normalize(conversationOverride?.contextWindow ?: requireNotNull(global.contextWindow)),
    )
}
