package com.newoether.agora.viewmodel

import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderRequestResolver
import com.newoether.agora.data.PromptTemplateItem
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.model.ProviderPassResult
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.util.Constants

data class GenerationPromptTemplate(
    val systemItems: List<PromptTemplateItem>,
    val userItems: List<PromptTemplateItem>,
    val assistantItems: List<PromptTemplateItem>,
)

data class GenerationConfig(
    val providerName: String,
    val modelId: String,
    val apiKey: String,
    val effectiveSystemPrompt: String?,
    /** Optional API-only USER invocation appended to the initial Provider request. */
    val initialUserPrompt: String? = null,
    val maxContextWindow: Int = ContextBudget.DEFAULT_TOKENS,
    val codeExecutionEnabled: Boolean,
    val googleSearchEnabled: Boolean,
    val thinkingEnabled: Boolean,
    val thinkingLevel: String = "medium",
    val thinkingBudgetEnabled: Boolean = false,
    val thinkingBudgetTokens: Int = 4096,
    /** User correction for the selected model's thinking capability; null keeps the documented one. */
    val thinkingCapabilityOverride: com.newoether.agora.model.ModelThinkingCapabilityOverride? = null,
    val openAiServiceTier: String? = null,
    val responsesApiEnabled: Boolean = false,
    val anthropicCacheEnabled: Boolean = true,
    val anthropicCacheTtl: String = "1h",
    val openAiWebSearchEnabled: Boolean = false,
    val baseUrl: String?,
    val userPrepend: String? = null,
    val userPostpend: String? = null,
    val assistantPrepend: String? = null,
    val assistantPostpend: String? = null,
    /** Frozen template structure with values resolved independently for every dispatch attempt. */
    val promptTemplate: GenerationPromptTemplate? = null,
    val requestResolver: ProviderRequestResolver? = null,
    /** Omits the selected structured prompt and every tool definition for embedded Local requests. */
    val lowContextModeEnabled: Boolean = false,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null
)

data class GenerationContext(
    val conversationId: String? = null,
    val accessSavedMemories: Boolean = true,
    val accessActiveMemory: Boolean = true,
    val skillReadAccess: Boolean = true,
    val skillModifyAccess: Boolean = true,
    val skillCatalog: String = "",
    val accessPastConversations: Boolean = true,
    val modelSearchMethod: String = "keyword",
    val activeEmbeddingConfig: com.newoether.agora.data.EmbeddingModelConfig? = null,
    val embeddingApiKey: String = "",
    val ragThreshold: Float = 0.5f,
    val searchMatchLimit: Int = 10,
    val searchContextWindow: Int = 8,
    val webSearchEnabled: Boolean = false,
    val webSearchApiKeys: Map<String, String> = emptyMap(),
    val webSearchProvider: String = "duckduckgo",
    val webSearchNumResults: Int = 5,
    val webSearchBaseUrl: String = "",
    val imageGenEnabled: Boolean = false,
    val imageGenApiKey: String = "",
    val imageGenBaseUrl: String = "",
    val imageGenModel: String = "gpt-image-1",
    val imageGenSize: String = "1024x1024",
    val automationToolsEnabled: Boolean = false,
    /** Workers use WorkManager's foreground execution instead of starting our service. */
    val foregroundServiceManagedExternally: Boolean = false,
    val shellEnabled: Boolean = false,
    val shellDevices: List<com.newoether.agora.data.ShellDeviceConfig> = emptyList(),
    val sandboxEnabled: Boolean = false,
    val sandboxSharedStorageEnabled: Boolean = false,
    val imageTranscriptionEnabled: Boolean = false,
    val imageTranscriptionModel: String? = null,
    val imageTranscriptionBatchSize: Int = 3,
    val imageTranscriptionPrompt: String = com.newoether.agora.data.BuiltInPrompts.IMAGE_TRANSCRIPTION_USER,
    val transcriptionProviderName: String = "",
    val transcriptionModelId: String = "",
    val transcriptionApiKey: String = "",
    val transcriptionBaseUrl: String? = null,
    val transcriptionAnthropicCacheEnabled: Boolean = true,
    val transcriptionAnthropicCacheTtl: String = "1h",
    /** Wall-clock budget for a single tool execution; downgrades a blocking tool from a
     *  permanent generation hang to a recoverable tool error (#49). */
    val toolTimeoutMs: Long = Constants.TOOL_EXECUTION_TIMEOUT_MS
)

/** Frozen automatic-Compact policy and provider access captured with one generation. */
internal data class AutomaticCompactConfig(
    val enabled: Boolean,
    val thresholdPercent: Int,
    val request: CompactRequest,
    val providerName: String,
    val apiKey: String,
    val baseUrl: String?,
    val responsesApiEnabled: Boolean = false,
    val provider: LlmProvider?,
    val configured: Boolean,
    /** Frozen ordinary generation parameters resolved for the selected Compact model. */
    val generationConfig: GenerationConfig,
    /** Frozen provider registry snapshot used by the shared generation tail. */
    val providerInstances: Map<String, LlmProvider>,
    /** Frozen attachment/transcription policy used to project Compact input exactly once. */
    val generationContext: GenerationContext,
    /** Frozen API-only USER templates included in the exact Auto Compact threshold projection. */
    val userPrepend: String? = null,
    val userPostpend: String? = null,
    /** Filled by the effect owner that has the exact frozen tool-definition set. */
    val fixedTokenCost: Int = 0,
    /** Main request, not Compact-model, replay policy for ordinary assistant reasoning. */
    val includeAssistantReasoning: Boolean = false,
)

/** Request-shape snapshot used only for exact context accounting; no Provider access is required. */
internal data class GenerationContextProjectionSnapshot(
    val config: GenerationConfig,
    val context: GenerationContext,
)

/**
 * Complete immutable configuration accepted for one durable Run.
 *
 * This value is constructed before the Run/message graph commits. The same instance is then used
 * by automatic Compact, the first Provider pass, and every tool continuation. Mutable settings
 * must never be consulted again for generation-owned behavior after this boundary.
 */
internal data class GenerationAdmissionSnapshot(
    val conversationId: String,
    val runId: String,
    /** Stable prefixed model identity persisted on the conversation and visible MODEL row. */
    val selectedModelId: String,
    val config: GenerationConfig,
    val context: GenerationContext,
    /** Copy of the registry keys/instances at admission; custom-provider rename cannot revoke it. */
    val providerInstances: Map<String, LlmProvider>,
    val automaticCompact: AutomaticCompactConfig,
    val titleGenerationEnabled: Boolean,
) {
    init {
        require(conversationId.isNotBlank())
        require(runId.isNotBlank())
        require(selectedModelId.isNotBlank())
        require(context.conversationId == conversationId)
        require(config.providerName in providerInstances)
    }
}

/**
 * The token-gated UI callbacks a single generation drives. Built once per call by
 * [ConversationGenerationState.callbacksFor], so each generation entry point
 * ([MessageGenerationController]'s send / regenerate / edit) wires the per-conversation
 * ownership tokens in exactly one place instead of re-threading lambdas by hand.
 *
 * Note: the generation-slot lifecycle (generating flag / active-conversation set) is owned by
 * [ConversationGenerationState]. These callbacks project token-gated UI state and carry identified
 * tool effects/results; GenerationManager does not directly mutate the process Run state.
 */
internal data class GenerationCallbacks(
    val onStreamUpdate: (com.newoether.agora.model.ChatMessage) -> Unit,
    val onLoadingChange: (Boolean) -> Unit,
    val onStreamClear: () -> Unit,
    val isLatestPersist: () -> Boolean,
    /** The conversation mailbox must authorize every Provider pass before network execution. */
    val onProviderPassRequested: suspend (RunEffectIdentity) -> RunEffect.StartProviderPass?,
    /** The mailbox accepts the closed outcome before any text/tool continuation is consumed. */
    val onProviderPassCompleted: suspend (
        RunEffectIdentity,
        ProviderPassResult,
    ) -> RunEffect.ProviderPassAccepted?,
    /** The mailbox chooses the one normal terminal effect that may mutate Room. */
    val onRunFinalizationRequested: suspend (
        RunEffectIdentity,
        RunStatus,
        RunEndReason,
        Boolean,
    ) -> RunEffect.FinalizeRun?,
    /** Echo the exact Room result; true means the current mailbox state accepted it. */
    val onRunFinalizationCompleted: suspend (RunEffectIdentity, Boolean) -> Boolean,
    /** True when the user queued a send behind this generation. The tool loop checks it at
     *  each round boundary and ends the generation there so the queue can flush immediately
     *  (steering) instead of waiting out the entire loop. Headless runs keep the default. */
    val hasQueuedSends: () -> Boolean = { false },
    /** Null for headless automation, which retains its existing foreground/background policy. */
    val isConversationVisible: (() -> Boolean)? = null,
    /** A validated Provider outcome is necessary but not sufficient: runtime identity must accept
     * the exact batch before any tool can execute. Defaults preserve the isolated headless test
     * adapter until Task ownership migrates fully in Phase 7. */
    val onToolBatchRequested: suspend (RunEffectIdentity) -> RunEffect.ExecuteToolBatch? = {
        RunEffect.ExecuteToolBatch(it.copy(effectId = "tool-batch-${it.effectId}"))
    },
    /** Authoritative results exist only after every call in the accepted batch completed. */
    val onToolBatchCompleted: suspend (RunEffectIdentity) -> RunEffect.CommitToolRound? = {
        RunEffect.CommitToolRound(it.copy(effectId = "tool-round-${it.effectId}"))
    },
    /** Generic output text projection applied before streaming UI, checkpoints, and terminal Room writes. */
    val transformFinalText: (String, com.newoether.agora.model.MessageStatus) -> String =
        { text, _ -> text },
    /** Only an accepted durable success result authorizes the next Provider pass. */
    val onToolRoundCommitted: suspend (RunEffectIdentity, Boolean) -> RunEffect? =
        { identity, success ->
            if (success) RunEffect.ContinueProviderPass(identity)
            else RunEffect.ToolRoundCommitFailed(identity)
        },
    /**
     * Chooses the boundary after a durable tool round: continue the current provider loop, or
     * terminalize this Assistant so a follow-up can start as a fresh standard generation.
     */
    val onToolRoundPersisted: suspend () -> ToolRoundBoundaryDecision = {
        ToolRoundBoundaryDecision.Continue
    },
)

internal sealed interface ToolRoundBoundaryDecision {
    data object Continue : ToolRoundBoundaryDecision
    data object CompleteForFollowUp : ToolRoundBoundaryDecision
}

internal data class GenerationExecutionResult(
    val followUpParentMessageId: String? = null,
)
