package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.splitContextForCompactRetention
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.model.isSuccessfulContextCompact
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.UUID

internal data class StandardCompactLaunch(
    val messageId: String,
    val job: Job,
)

/**
 * Prepares Compact-specific presentation and generation parameters, then delegates the complete
 * durable Run, stream, Stop, finalization, queue, and recovery lifecycle to ordinary generation.
 */
internal class ConversationCompactController(
    private val conversations: ConversationRepository,
    private val operation: ContextCompactOperation,
    private val requestBuilder: GenerationRequestBuilder?,
    private val generationManagerProvider: () -> GenerationManager,
    private val continuationLauncher: () -> StandardGenerationContinuationLauncher,
    private val onCompactStarted: (conversationId: String, messageId: String) -> Unit = { _, _ -> },
) {
    suspend fun automaticNeeded(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
    ): Boolean = operation.automaticNeeded(conversationId, contextLimit, config)

    suspend fun startAutomaticBeforeSend(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
        state: ConversationGenerationState,
    ): CompactResult {
        if (!operation.automaticNeeded(conversationId, contextLimit, config)) {
            return CompactResult.NotNeeded
        }
        val snapshot = automaticSnapshot(conversationId, config)
        return launch(
            conversationId = conversationId,
            request = config.request,
            snapshot = snapshot,
            state = state,
            awaitCompletion = false,
            touchConversationOnAdmission = false,
        ).second
    }

    suspend fun startAutomaticStandard(
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
        state: ConversationGenerationState,
    ): StandardCompactLaunch? {
        if (!operation.automaticNeeded(conversationId, contextLimit, config)) return null
        val snapshot = automaticSnapshot(conversationId, config)
        return launch(
            conversationId = conversationId,
            request = config.request,
            snapshot = snapshot,
            state = state,
            awaitCompletion = false,
            touchConversationOnAdmission = false,
        ).first
    }

    suspend fun manual(
        conversationId: String,
        request: CompactRequest,
        state: ConversationGenerationState,
    ): CompactResult {
        if (request.model.isBlank()) return CompactResult.Failed(CompactFailureReason.SELECT_MODEL)
        if (request.prompt.isBlank()) return CompactResult.Failed(CompactFailureReason.EMPTY_PROMPT)
        if (request.retainLogicalMessages < 0) {
            return CompactResult.Failed(CompactFailureReason.INVALID_RETAIN_COUNT)
        }
        val snapshotRunId = "compact_preflight_${UUID.randomUUID()}"
        val snapshot = try {
            val builder = requestBuilder
                ?: return CompactResult.Failed(CompactFailureReason.SETUP_UNAVAILABLE)
            builder.captureAdmissionSnapshot(
                conversationId = conversationId,
                runId = snapshotRunId,
                modelId = request.model,
            ).forCompact(request)
        } catch (_: Exception) {
            return CompactResult.Failed(
                CompactFailureReason.SETUP_FAILED,
            )
        }
        return launch(
            conversationId = conversationId,
            request = request,
            snapshot = snapshot,
            state = state,
            awaitCompletion = true,
            touchConversationOnAdmission = true,
        ).second
    }

    private suspend fun launch(
        conversationId: String,
        request: CompactRequest,
        snapshot: GenerationAdmissionSnapshot,
        state: ConversationGenerationState,
        awaitCompletion: Boolean,
        touchConversationOnAdmission: Boolean,
    ): Pair<StandardCompactLaunch?, CompactResult> {
        val topology = conversations.getProviderContextTopologySnapshot(conversationId)
            ?: return null to CompactResult.NotNeeded
        val selectedPathIds = selectedVisibleContextMessageIds(topology)
        val target = request.replaceMessageId?.let { targetId ->
            targetId.takeIf { it in selectedPathIds }
                ?.let { conversations.getMessage(it) }
                ?.takeIf { it.id.startsWith(Constants.COMPACT_MSG_PREFIX) }
                ?.takeIf {
                    it.status in setOf(
                        MessageStatus.SUCCESS,
                        MessageStatus.ERROR,
                        MessageStatus.STOPPED,
                    )
                }
                ?: return null to CompactResult.Failed(
                    CompactFailureReason.NOT_READY_TO_RECOMPACT,
                )
        }
        val parentId = target?.parentId ?: selectedPathIds.lastOrNull()
        val parent = parentId?.let { conversations.getMessage(it) }
            ?: return null to CompactResult.NotNeeded
        if (target != null && target.parentId != parent.id) {
            return null to CompactResult.Failed(CompactFailureReason.BOUNDARY_DISAPPEARED)
        }

        val generationSnapshot = snapshot.forCompact(request)
        val providerPath = generationManagerProvider().buildApiPath(
            GenerationApiPathRequest(
                parentId = parent.id,
                conversationId = conversationId,
                config = generationSnapshot.config,
                context = generationSnapshot.context,
            ),
        )
        val transform = retainedTextTransform(
            path = providerPath.messages,
            retainLogicalMessages = request.retainLogicalMessages,
        )
        val messageId = target?.id ?: Constants.COMPACT_MSG_PREFIX + UUID.randomUUID()
        val launched = continuationLauncher().launch(
            StandardGenerationContinuationRequest(
                conversationId = conversationId,
                parentMessageId = parent.id,
                snapshot = generationSnapshot,
                modelMessageId = messageId,
                replacementMessageId = target?.id,
                requestKind = "compact",
                touchConversationOnAdmission = touchConversationOnAdmission,
                queueDrainRequiresSuccess = true,
                transformFinalText = transform,
            ),
            state,
        ) ?: return null to CompactResult.Failed(CompactFailureReason.GENERATION_BUSY)

        if (!launched.started.await()) {
            launched.job.join()
            return null to CompactResult.Failed(CompactFailureReason.GENERATION_NOT_STARTED)
        }
        onCompactStarted(conversationId, messageId)
        val compactLaunch = StandardCompactLaunch(messageId, launched.job)
        if (!awaitCompletion) return compactLaunch to CompactResult.Created(messageId)

        try {
            launched.job.join()
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        val settled = conversations.getMessage(messageId)
            ?: return compactLaunch to CompactResult.Failed(CompactFailureReason.MESSAGE_DISAPPEARED)
        val result = when (settled.status) {
            MessageStatus.SUCCESS -> CompactResult.Created(messageId)
            MessageStatus.STOPPED -> CompactResult.Stopped(messageId)
            MessageStatus.ERROR -> CompactResult.Failed(
                reason = CompactFailureReason.GENERIC,
                externalDetail = settled.toUiChatMessage { text -> text }
                    .segments
                    .orEmpty()
                    .lastOrNull { segment ->
                        segment.type == "error" && segment.content.isNotBlank()
                    }
                    ?.content,
                messageId = messageId,
            )
            else -> CompactResult.Failed(
                reason = CompactFailureReason.GENERIC,
                messageId = messageId,
            )
        }
        return compactLaunch to result
    }

    private fun automaticSnapshot(
        conversationId: String,
        config: AutomaticCompactConfig,
    ): GenerationAdmissionSnapshot = GenerationAdmissionSnapshot(
        conversationId = conversationId,
        runId = "compact_preflight_${UUID.randomUUID()}",
        selectedModelId = config.request.model,
        config = config.generationConfig,
        context = config.generationContext,
        providerInstances = config.providerInstances,
        automaticCompact = config.copy(enabled = false),
        titleGenerationEnabled = false,
    ).forCompact(config.request)

    private fun GenerationAdmissionSnapshot.forCompact(
        request: CompactRequest,
    ): GenerationAdmissionSnapshot = copy(
        config = config.copy(
            // Preserved mode keeps the system prompt captured for the compact request (the
            // conversation's ordinary one) and moves the Compact Prompt to the head of the
            // user message; legacy mode replaces the system prompt with the Compact Prompt.
            effectiveSystemPrompt = if (request.preserveSystemPrompt) {
                config.effectiveSystemPrompt
            } else {
                request.prompt
            },
            initialUserPrompt = if (request.preserveSystemPrompt) {
                request.prompt + "\n\n" + BuiltInPrompts.CONTEXT_COMPACT_USER
            } else {
                BuiltInPrompts.CONTEXT_COMPACT_USER
            },
            userPrepend = null,
            userPostpend = null,
            assistantPrepend = null,
            assistantPostpend = null,
            promptTemplate = null,
            requestResolver = null,
            codeExecutionEnabled = false,
            googleSearchEnabled = false,
            openAiWebSearchEnabled = false,
            thinkingEnabled = false,
        ),
        context = context.copy(
            accessSavedMemories = false,
            accessActiveMemory = false,
            skillReadAccess = false,
            skillModifyAccess = false,
            accessPastConversations = false,
            webSearchEnabled = false,
            imageGenEnabled = false,
            askUserEnabled = false,
            automationToolsEnabled = false,
            shellEnabled = false,
            sandboxEnabled = false,
            sandboxSharedStorageEnabled = false,
        ),
        automaticCompact = automaticCompact.copy(enabled = false),
        titleGenerationEnabled = false,
    )

    private fun retainedTextTransform(
        path: List<ChatMessage>,
        retainLogicalMessages: Int,
    ): (String, MessageStatus) -> String {
        val semanticPath = path.filterNot {
            it.isContextCompact() && !it.isSuccessfulContextCompact()
        }
        val nearestCompact = semanticPath.indexOfLast(ChatMessage::isSuccessfulContextCompact)
        val activePath = semanticPath.drop(nearestCompact.coerceAtLeast(-1) + 1)
        val retained = splitContextForCompactRetention(
            compactSplitMessages(activePath),
            retainLogicalMessages,
        ).retained
        return { generatedText, status ->
            val normalizedText = normalizeContextCompactOutput(generatedText)
            if (status == MessageStatus.SUCCESS && normalizedText.isNotBlank()) {
                buildPersistedCompactText(normalizedText, retained)
            } else {
                normalizedText
            }
        }
    }
}
