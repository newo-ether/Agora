package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.ContextWindowUsage
import com.newoether.agora.api.util.contextWindowRetainedMessageIds
import com.newoether.agora.api.util.contextWindowUsage
import com.newoether.agora.api.util.tokens.ContextCostModel
import com.newoether.agora.api.util.tokens.ContextCostModels
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.repository.ConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val newChatConversationSettings: () -> ConversationSettings? = { null },
    appContext: android.content.Context? = null,
    private val contextLoader: DurableSelectedContextLoader =
        DurableSelectedContextLoader(conversations, generationErrorFormatter, appContext = appContext),
) {
    private val requestIds = AtomicLong(0L)
    private val _projection = MutableStateFlow(ConversationContextProjection())
    val projection: StateFlow<ConversationContextProjection> = _projection.asStateFlow()
    private val _invalidationRevision = MutableStateFlow(0L)
    val invalidationRevision: StateFlow<Long> = _invalidationRevision.asStateFlow()
    private var latestRequest: Job? = null
    private val projectionTurn = Mutex()

    /**
     * Starts a projection for the latest UI state. Each projection holds the whole selected
     * history in memory, so a newer request cancels the older one, and the mutex lets at most one
     * run at a time: a cancelled projection can still be inside non-suspending work.
     * Call from one thread (the UI's).
     */
    fun request(
        scope: CoroutineScope,
        conversationId: String?,
        selectedBranchesJson: String?,
        selectedModelId: String,
        tokenBudget: Int,
    ) {
        latestRequest?.cancel()
        latestRequest = scope.launch {
            projectionTurn.withLock {
                project(conversationId, selectedBranchesJson, selectedModelId, tokenBudget)
            }
        }
    }

    fun invalidate(conversationId: String?) {
        val previousUsage = _projection.value.usage
        requestIds.incrementAndGet()
        _projection.value = ConversationContextProjection(
            conversationId = conversationId,
            usage = previousUsage,
            loading = true,
        )
        // Room may already have emitted an equal graph. Give the UI a separate request key.
        _invalidationRevision.update { it + 1L }
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
        // Pricing the whole history tokenizes every message; it must never run on the caller's
        // (main) thread.
        val result = try { withContext(Dispatchers.Default) {
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
                        conversationSettingsOverride = if (conversationId == null) {
                            newChatConversationSettings()
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
                    // Stored descriptions remove only their own images before this projection.
                    includeImages = true,
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
            // The indicator must price context exactly like dispatch does, so it uses the selected
            // model's cost model too.
            val costs = snapshot
                ?.let { ContextCostModels.forModel(it.config.modelId) }
                ?: ContextCostModel.Default
            ConversationContextProjection(
                conversationId = conversationId,
                selectedBranchesJson = selectedBranchesJson,
                usage = contextWindowUsage(
                    messages = contextMessages,
                    tokenBudget = tokenBudget,
                    fixedTokenCost = fixedTokenCost,
                    includeAssistantReasoning = includeAssistantReasoning,
                    fixedComposition = fixedComposition,
                    costs = costs,
                ),
                retainedMessageIds = contextWindowRetainedMessageIds(
                    messages = contextMessages,
                    tokenBudget = tokenBudget,
                    fixedTokenCost = fixedTokenCost,
                    includeAssistantReasoning = includeAssistantReasoning,
                    costs = costs,
                ),
                completed = true,
            )
        } } catch (cancelled: CancellationException) {
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
