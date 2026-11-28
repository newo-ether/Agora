package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.presentation.EffectiveConversationControls
import com.newoether.agora.presentation.conversationSettingsOwnerId
import com.newoether.agora.presentation.resolveEffectiveConversationControls
import com.newoether.agora.viewmodel.ChatViewModel

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
