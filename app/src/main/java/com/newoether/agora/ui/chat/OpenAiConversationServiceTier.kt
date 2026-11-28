package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.presentation.OpenAiConversationServiceTierState
import com.newoether.agora.presentation.resolveOpenAiConversationServiceTier
import com.newoether.agora.ui.common.AgoraHaptics
import com.newoether.agora.viewmodel.ChatViewModel

@Composable
internal fun openAiConversationServiceTierState(
    viewModel: ChatViewModel,
    conversationOverride: ConversationSettings?,
    providerName: String,
    builtInOpenAiResponsesEnabled: Boolean,
    customProviders: List<CustomProviderConfig>,
): OpenAiConversationServiceTierState {
    val globalEnabled by viewModel.settings.openAiServiceTierEnabled.collectAsState()
    val globalTier by viewModel.settings.openAiServiceTier.collectAsState()
    return resolveOpenAiConversationServiceTier(
        globalEnabled = globalEnabled,
        globalTier = globalTier,
        conversationOverride = conversationOverride,
        providerName = providerName,
        builtInOpenAiResponsesEnabled = builtInOpenAiResponsesEnabled,
        customProviders = customProviders,
    )
}

internal fun updateOpenAiConversationServiceTierEnabled(
    viewModel: ChatViewModel,
    conversationId: String?,
    haptics: AgoraHaptics,
    enabled: Boolean,
) {
    haptics.toggle(enabled)
    viewModel.updateConversationSetting(conversationId) {
        it.copy(openAiServiceTierEnabled = enabled)
    }
}

internal fun updateOpenAiConversationServiceTier(
    viewModel: ChatViewModel,
    conversationId: String?,
    haptics: AgoraHaptics,
    tier: String,
) {
    haptics.selection()
    viewModel.updateConversationSetting(conversationId) {
        it.copy(openAiServiceTier = OpenAiServiceTiers.normalize(tier))
    }
}
