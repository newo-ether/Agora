package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.isContextCompact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Projects Compact UI state and translates current-conversation Compact intents. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ConversationCompactUiCoordinator(
    currentConversationId: StateFlow<String?>,
    registry: ConversationStateRegistry,
    private val scope: CoroutineScope,
    private val configuredModel: () -> String?,
    private val currentModel: () -> String,
    private val configuredPrompt: () -> String,
    private val configuredRetainCount: () -> Int,
    private val configuredPreserveSystemPrompt: () -> Boolean = { false },
    private val compactManual: suspend (CompactRequest) -> CompactResult,
    private val failureMessage: (CompactResult.Failed) -> String,
    private val onFailure: suspend (String) -> Unit,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val isCompacting: StateFlow<Boolean> = currentConversationId
        .flatMapLatest { conversationId ->
            if (conversationId == null) flowOf(false)
            else registry.getOrCreate(conversationId).streamingMessage
                .map { message -> message?.isContextCompact() == true }
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val compactPreview: StateFlow<String> = currentConversationId
        .flatMapLatest { conversationId ->
            if (conversationId == null) flowOf("")
            else registry.getOrCreate(conversationId).streamingMessage
                .map { message ->
                    message?.takeIf(ChatMessage::isContextCompact)?.text.orEmpty()
                }
        }
        .stateIn(scope, SharingStarted.Eagerly, "")

    suspend fun manual(
        model: String,
        prompt: String,
        retainLogicalMessages: Int,
    ): CompactResult = compactManual(
        CompactRequest(
            model,
            prompt,
            retainLogicalMessages,
            preserveSystemPrompt = configuredPreserveSystemPrompt(),
        ),
    )

    fun startManual(
        model: String,
        prompt: String,
        retainLogicalMessages: Int,
    ) {
        start(
            CompactRequest(
                model,
                prompt,
                retainLogicalMessages,
                preserveSystemPrompt = configuredPreserveSystemPrompt(),
            ),
        )
    }

    fun startRecompact(messageId: String) {
        start(
            CompactRequest(
                model = configuredModel()?.takeIf(String::isNotBlank) ?: currentModel(),
                prompt = configuredPrompt(),
                retainLogicalMessages = configuredRetainCount(),
                replaceMessageId = messageId,
                preserveSystemPrompt = configuredPreserveSystemPrompt(),
            ),
        )
    }

    private fun start(request: CompactRequest) {
        // Admission (snapshot capture, topology reads, API-path build) must not run on the UI
        // thread: the confirming dialog dismisses first and the payload follows off main.
        scope.launch(workDispatcher) {
            val result = try {
                compactManual(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CompactResult.Failed(CompactFailureReason.GENERIC)
            }
            if (result is CompactResult.Failed) onFailure(failureMessage(result))
        }
    }
}
