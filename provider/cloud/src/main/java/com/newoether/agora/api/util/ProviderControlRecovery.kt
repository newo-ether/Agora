package com.newoether.agora.api.util

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.util.CloudDebugLog as DebugLog

/**
 * A model can reject tool use or reasoning controls on every attempt. Remember the concrete
 * rejection so this call, and later calls to the same model, go out without that part.
 *
 * A remembered tool rejection expires after [TOOL_REJECTION_TTL_MS] and is never persisted:
 * endpoints gain capabilities over time, so the next process (or the expiry) re-probes with
 * tools instead of degrading the model forever.
 */
internal class ProviderControlRecovery(private val modelId: String) {
    var omitTools = isToolRejectionFresh(modelId)
        private set
    var omitReasoning = false
        private set
    private var toolsRecoveryUsed = false
    private var reasoningRecoveryUsed = false

    /** Drop the parts a concrete endpoint just rejected for this model. */
    fun apply(request: OpenAiChatRequest): OpenAiChatRequest {
        var next = request
        if (omitReasoning) {
            next = next.copy(
                reasoningEffort = null,
                thinking = null,
                enableThinking = null,
                thinkingBudget = null,
                chatTemplateKwargs = null,
                reasoning = null,
                includeReasoning = null,
            )
        }
        if (omitTools) next = next.copy(tools = null)
        return next
    }

    /**
     * True when [statusCode] is a concrete rejection that dropping one control can recover from.
     * Logs the reason; the caller retries the same request once.
     */
    fun recoveredByDropping(statusCode: Int, body: String, providerName: String): Boolean {
        if (statusCode !in 400..499) return false
        if (!omitTools && !toolsRecoveryUsed && ProviderRetryPolicy.rejectsTools(statusCode, body)) {
            omitTools = true
            toolsRecoveryUsed = true
            toolRejections[modelId] = System.currentTimeMillis()
            DebugLog.w(
                "AgoraAPI",
                "[$providerName] HTTP $statusCode: model has no tool-capable endpoint; retrying without tools",
            )
            return true
        }
        if (!omitReasoning && !reasoningRecoveryUsed &&
            ProviderRetryPolicy.rejectsReasoningParameters(body)
        ) {
            omitReasoning = true
            reasoningRecoveryUsed = true
            DebugLog.w(
                "AgoraAPI",
                "[$providerName] HTTP $statusCode: model rejects reasoning controls; retrying without them",
            )
            return true
        }
        return false
    }

    companion object {
        /** How long a tool rejection suppresses re-probing; a process restart always re-probes. */
        internal const val TOOL_REJECTION_TTL_MS = 6 * 60 * 60 * 1000L
        private val toolRejections = java.util.concurrent.ConcurrentHashMap<String, Long>()

        private fun isToolRejectionFresh(modelId: String): Boolean {
            val rejectedAt = toolRejections[modelId] ?: return false
            if (System.currentTimeMillis() - rejectedAt < TOOL_REJECTION_TTL_MS) return true
            toolRejections.remove(modelId, rejectedAt)
            return false
        }
    }
}
