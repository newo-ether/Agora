package com.newoether.agora.ui.chat

import com.newoether.agora.ui.common.AgoraHaptics
import com.newoether.agora.viewmodel.ChatViewModel

internal fun updateOpenAiNativeSearch(
    viewModel: ChatViewModel,
    conversationId: String?,
    haptics: AgoraHaptics,
    enabled: Boolean,
) {
    haptics.toggle(enabled)
    viewModel.updateConversationSetting(conversationId) {
        it.copy(openAiWebSearchEnabled = enabled)
    }
}
