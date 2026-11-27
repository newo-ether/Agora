package com.newoether.agora.viewmodel

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Captures the tool definitions and request costs for one client's selected conversation. New Chat
 * callers supply their own workspace overrides, so this never reads the phone's pending workspace.
 */
internal suspend fun captureSessionToolCatalog(
    requestBuilder: GenerationRequestBuilder,
    generationManager: GenerationManager,
    settings: SettingsRepository,
    conversationId: String?,
    modelId: String,
    systemPromptIdOverride: String?,
    conversationSettingsOverride: ConversationSettings?,
): SessionToolCatalog? {
    if (modelId.isBlank()) return null
    val snapshot = try {
        requestBuilder.captureContextProjectionSnapshot(
            conversationId = conversationId ?: SESSION_TOOLS_PREVIEW_CONVERSATION_ID,
            modelId = modelId,
            systemPromptIdOverride = systemPromptIdOverride,
            conversationSettingsOverride = conversationSettingsOverride,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return null
    }
    // Linked groups remain listed while their conversation switch is off, if the global switch
    // still permits their definitions. This lets the same sheet turn them back on.
    val probe = snapshot.context.copy(
        webSearchEnabled = settings.webSearchEnabled.value,
        shellEnabled = settings.shellEnabled.value,
    )
    return try {
        withContext(Dispatchers.Default) {
            generationManager.sessionToolCatalog(snapshot.config, snapshot.context, probe)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
