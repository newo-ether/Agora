package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.ContextWindowUsage
import com.newoether.agora.api.util.contextWindowRetainedMessageIds
import com.newoether.agora.api.util.contextWindowUsage
import com.newoether.agora.data.repository.ConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

internal data class ConversationContextProjection(
    val conversationId: String? = null,
    val selectedBranchesJson: String? = null,
    val usage: ContextWindowUsage? = null,
    val retainedMessageIds: Set<String>? = null,
    val loading: Boolean = false,
    val completed: Boolean = false,
    val failed: Boolean = false,
)

/** Builds UI context accounting from the same canonical durable projection used by generation. */
internal class ConversationContextProjector(
    private val conversations: ConversationRepository,
    private val requestBuilder: GenerationRequestBuilder,
    private val generationManager: () -> GenerationManager,
    private val generationErrorFormatter: (String) -> String,
    private val newChatSystemPromptId: () -> String? = { null },
    private val contextLoader: DurableSelectedContextLoader =
        DurableSelectedContextLoader(conversations, generationErrorFormatter),
) {
    private val requestIds = AtomicLong(0L)
    private val _projection = MutableStateFlow(ConversationContextProjection())
    val projection: StateFlow<ConversationContextProjection> = _projection.asStateFlow()

    fun invalidate(conversationId: String?) {
        val previousUsage = _projection.value.usage
        requestIds.incrementAndGet()
        _projection.value = ConversationContextProjection(
            conversationId = conversationId,
            usage = previousUsage,
            loading = true,
        )
    }

    suspend fun project(
        conversationId: String?,
        selectedBranchesJson: String?,
        selectedModelId: String,
        tokenBudget: Int,
    ): ConversationContextProjection {
        val previousUsage = _projection.value.usage
        val requestId = requestIds.incrementAndGet()
        _projection.value = ConversationContextProjection(
            conversationId = conversationId,
            selectedBranchesJson = selectedBranchesJson,
            usage = previousUsage,
            loading = true,
        )
        val result = try {
            val effectiveConversationId = conversationId ?: CONTEXT_PREVIEW_CONVERSATION_ID
            val snapshot = selectedModelId.takeIf(String::isNotBlank)?.let { modelId ->
                try {
                    requestBuilder.captureContextProjectionSnapshot(
                        conversationId = effectiveConversationId,
                        modelId = modelId,
                        systemPromptIdOverride = if (conversationId == null) {
                            newChatSystemPromptId()
                        } else {
                            null
                        },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            val durableProviderMessages = conversationId?.let {
                contextLoader.load(
                    DurableSelectedContextRequest(
                        conversationId = it,
                        followSelectedBranch = true,
                        includeStoredTranscriptions =
                            snapshot?.context?.imageTranscriptionEnabled ?: true,
                    ),
                ).messages
            }.orEmpty()
            val contextMessages = snapshot?.let {
                projectGenerationInputMessages(
                    messages = durableProviderMessages,
                    // Transcription-enabled models receive descriptions instead of raw images at
                    // dispatch; the bottom-bar estimate must match.
                    includeImages = !it.context.imageTranscriptionEnabled,
                    userPrepend = it.config.userPrepend,
                    userPostpend = it.config.userPostpend,
                    assistantPrepend = it.config.assistantPrepend,
                    assistantPostpend = it.config.assistantPostpend,
                )
            } ?: durableProviderMessages
            val fixedTokenCost = snapshot?.let {
                generationManager().fixedContextTokenCost(it.config, it.context)
            } ?: 0
            val includeAssistantReasoning = snapshot?.let {
                generationManager().includesAssistantReasoning(it.config, it.context)
            } ?: false
            val fixedComposition = snapshot?.let {
                generationManager().fixedContextComposition(it.config, it.context)
            }
            ConversationContextProjection(
                conversationId = conversationId,
                selectedBranchesJson = selectedBranchesJson,
                usage = contextWindowUsage(
                    messages = contextMessages,
                    tokenBudget = tokenBudget,
                    fixedTokenCost = fixedTokenCost,
                    includeAssistantReasoning = includeAssistantReasoning,
                    fixedComposition = fixedComposition,
                ),
                retainedMessageIds = contextWindowRetainedMessageIds(
                    messages = contextMessages,
                    tokenBudget = tokenBudget,
                    fixedTokenCost = fixedTokenCost,
                    includeAssistantReasoning = includeAssistantReasoning,
                ),
                completed = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ConversationContextProjection(
                conversationId = conversationId,
                selectedBranchesJson = selectedBranchesJson,
                completed = true,
                failed = true,
            )
        }
        if (requestIds.get() == requestId) {
            _projection.value = result
        }
        return result
    }

    private companion object {
        const val CONTEXT_PREVIEW_CONVERSATION_ID = "context-preview-conversation"
    }
}
