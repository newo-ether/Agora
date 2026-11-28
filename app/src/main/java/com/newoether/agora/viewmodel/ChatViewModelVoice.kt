package com.newoether.agora.viewmodel

import androidx.lifecycle.viewModelScope
import com.newoether.agora.data.repository.persistVoiceExchange
import com.newoether.agora.voice.live.LiveToolBridge
import com.newoether.agora.voice.live.LiveVoiceSession
import kotlinx.coroutines.launch

/** A live-voice tool bridge over the SAME MCP-including executor normal generation uses. */
fun GenerationManager.buildLiveToolBridge(ctx: GenerationContext): LiveToolBridge =
    LiveVoiceToolBridge(toolExecutor, ctx)

/**
 * Build a [LiveToolBridge] for a Live Voice session, exposing
 * the agent tool set (including MCP) with the user's current tool toggles. Keys are resolved
 * so tools like web search / shell can run inside the voice turn.
 */
fun ChatViewModel.buildLiveToolBridge(): LiveToolBridge {
    val ctx = GenerationContext(
        conversationId = "live-voice",
        shellEnabled = settings.shellEnabled.value,
        shellDevices = settings.shellDevices.value,
        sandboxEnabled = settings.sandboxEnabled.value,
        sandboxSharedStorageEnabled = settings.sandboxSharedStorageEnabled.value,
        webSearchEnabled = settings.webSearchEnabled.value,
        webSearchApiKeys = settings.webSearchApiKeys.value,
        webSearchProvider = settings.webSearchProvider.value,
        webSearchNumResults = settings.webSearchNumResults.value,
        webSearchBaseUrl = settings.webSearchBaseUrl.value,
        accessSavedMemories = settings.accessSavedMemories.value,
        accessActiveMemory = settings.accessActiveMemory.value,
        accessPastConversations = settings.accessPastConversations.value,
        todoEnabled = settings.todoEnabled.value,
        askUserEnabled = false,
    )
    return generationManager.buildLiveToolBridge(ctx)
}

/**
 * Wire the Live Voice session's transcript persistence into the CURRENT chat conversation
 * (AIOPE messageSink parity). Each completed spoken exchange is written as a terminal Run of
 * PLAIN-TEXT user/assistant messages via [ConversationRepository.persistVoiceExchange], which
 * no-ops if a live Run already owns the conversation. Plain text keeps the persisted turns valid
 * for the Gemini request builder. Call before starting a session.
 */
fun ChatViewModel.installVoiceMessageSink() {
    LiveVoiceSession.controller.messageSink = { userText, assistantText ->
        val model = currentActiveModel.value.ifBlank { null }
        viewModelScope.launch {
            runCatching {
                // Persist into the open conversation; if none is open (voice started from a fresh
                // chat), create one and open it first so the turns have a home (AIOPE parity).
                var conversationId = currentConversationId.value
                if (conversationId == null) {
                    val title = userText.take(40).ifBlank { "Voice conversation" }
                    conversationId = convRepo.createConversation(title, modelId = model)
                    selectionController.selectConversation(conversationId)
                }
                convRepo.persistVoiceExchange(conversationId, userText, assistantText, model)
            }
        }
    }
}
