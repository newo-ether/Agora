package com.newoether.agora.viewmodel

import com.newoether.agora.api.HttpClient
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** Complete immutable input for launching one already-bound generation execution. */
internal data class BoundRunGenerationRequest(
    val conversationId: String,
    val modelMessageId: String,
    val startTime: Long,
    val snapshot: GenerationAdmissionSnapshot,
    val uiToken: Long,
    val persistId: Long,
    val runId: String,
    val pass: Int,
    val requestKind: String,
    val continuesAfterSuccess: Boolean = false,
    val transformFinalText: (String, MessageStatus) -> String = { text, _ -> text },
) {
    init {
        require(snapshot.conversationId == conversationId)
        require(snapshot.runId == runId)
    }
}

/**
 * Executes the shared generation tail after the caller has durably created and bound the Run.
 *
 * This component owns no Run state, Job, scope, or continuation decision. Provider/tool outcomes
 * still return through the identified callbacks supplied by the conversation runtime host.
 */
internal data class AutomaticCompactContinuationRequest(
    val generationRequest: BoundRunGenerationRequest,
    val parentMessageId: String,
    val config: AutomaticCompactConfig,
)

internal class BoundRunGenerationLauncher(
    private val conversations: ConversationRepository,
    private val generationManagerProvider: () -> GenerationManager,
    private val automaticCompactNeeded: suspend (
        conversationId: String,
        contextLimit: Int,
        config: AutomaticCompactConfig,
    ) -> Boolean,
    private val terminalSettlement: GenerationTerminalSettlementController,
    private val toUiMessage: (MessageEntity) -> ChatMessage,
    private val isConversationVisible: ((String) -> Boolean)? = null,
    private val onAutomaticCompactContinuation: (
        request: AutomaticCompactContinuationRequest,
        state: ConversationGenerationState,
    ) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun launch(
        request: BoundRunGenerationRequest,
        state: ConversationGenerationState,
    ) {
        val requestTrace = HttpClient.RequestTrace(
            requestId = request.modelMessageId,
            origin = request.requestKind,
            diagnosticContext = DeveloperDiagnostics.newRequestContext(
                requestId = request.modelMessageId,
                conversationId = request.conversationId,
                runId = request.runId,
                pass = request.pass,
                provider = request.snapshot.config.providerName,
                model = request.snapshot.selectedModelId,
                requestKind = request.requestKind,
            ),
        )
        requestTrace.mark(
            "prepare_start",
            "acceptedDelayMs=${(clock() - request.startTime).coerceAtLeast(0L)}",
        )
        try {
            requestTrace.mark("request_config_ready")
            val generationManager = generationManagerProvider()
            val fixedTokenCost = generationManager.resolvedFixedContextTokenCost(
                request.snapshot.config,
                request.snapshot.context,
            )
            val automaticCompactConfig = request.snapshot.automaticCompact.copy(
                fixedTokenCost = fixedTokenCost,
                mainModelId = request.snapshot.config.modelId,
                includeAssistantReasoning = generationManager.includesAssistantReasoning(
                    request.snapshot.config,
                    request.snapshot.context,
                ),
            )
            val result = generationManager.generate(
                conversationId = request.conversationId,
                modelMessageId = request.modelMessageId,
                startTime = request.startTime,
                modelName = request.snapshot.selectedModelId,
                runId = request.runId,
                pass = request.pass,
                ownerToken = request.uiToken,
                config = request.snapshot.config,
                ctx = request.snapshot.context,
                providerInstances = request.snapshot.providerInstances,
                generationJob = currentCoroutineContext()[Job],
                callbacks = state.callbacksFor(request.uiToken, request.persistId).copy(
                    isConversationVisible = isConversationVisible?.let { visible ->
                        { visible(request.conversationId) }
                    },
                    transformFinalText = request.transformFinalText,
                    hasPendingContinuation = request.continuesAfterSuccess,
                    onToolRoundPersisted = {
                        if (
                            automaticCompactNeeded(
                                request.conversationId,
                                request.snapshot.config.maxContextWindow,
                                automaticCompactConfig,
                            )
                        ) {
                            ToolRoundBoundaryDecision.CompleteForFollowUp
                        } else {
                            ToolRoundBoundaryDecision.Continue
                        }
                    },
                ),
                streamScope = state.streamScope,
                requestTrace = requestTrace,
            )
            result.followUpParentMessageId?.let { parentMessageId ->
                onAutomaticCompactContinuation(
                    AutomaticCompactContinuationRequest(
                        generationRequest = request,
                        parentMessageId = parentMessageId,
                        config = automaticCompactConfig,
                    ),
                    state,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(
                "AgoraVM",
                "Generation failed in ${request.requestKind} " +
                    "errorType=${e.javaClass.simpleName}",
            )
            // A pre-stream failure would otherwise strand the SENDING placeholder and overlay.
            runCatching {
                val existing = conversations.getMessage(request.modelMessageId)
                if (existing != null && existing.status == MessageStatus.SENDING) {
                    terminalSettlement.finalizeBoundFailure(
                        conversationId = request.conversationId,
                        runId = request.runId,
                        pass = request.pass,
                        uiToken = request.uiToken,
                        state = state,
                        failedMessage = toUiMessage(existing).copy(
                            text = "Error: ${e.localizedMessage ?: "Failed to build the request."}",
                            status = MessageStatus.ERROR,
                        ),
                        effectId = "request-finalize-${request.runId}-${request.pass}",
                    )
                }
            }
        }
    }
}
