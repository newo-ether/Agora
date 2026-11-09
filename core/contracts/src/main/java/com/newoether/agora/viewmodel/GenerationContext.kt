package com.newoether.agora.viewmodel

import com.newoether.agora.model.RunEffectIdentity

data class GenerationContext(
    val phoneEnabled: Boolean = false,
    val phoneMode: String = "accessibility",
    val phoneAllowAutomation: Boolean = false,
    val phoneReady: Boolean = false,
    val automationCaller: Boolean = false,
    val conversationId: String? = null,
    val subagentToolsEnabled: Boolean = false,
    /** Exposes agent_delete; effective only together with [subagentToolsEnabled]. */
    val subagentDeleteEnabled: Boolean = false,
    val enabledModelIds: List<String> = emptyList(),
    /** Null and empty both allow no new subagents; global enabled IDs are not a fallback. */
    val subagentModelIds: List<String>? = null,
    val subagentRecommendedModel: String? = null,
    val todoEnabled: Boolean = true,
    /** Supplied only by the authorized executor, never by Provider arguments. */
    val toolEffectIdentity: RunEffectIdentity? = null,
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
    val askUserEnabled: Boolean = false,
    val askUserTimeoutMinutes: Int = com.newoether.agora.data.DEFAULT_ASK_USER_TIMEOUT_MINUTES,
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
    val imageTranscriptionPrompt: String = GenerationDefaults.IMAGE_TRANSCRIPTION_USER,
    val transcriptionProviderName: String = "",
    val transcriptionModelId: String = "",
    val transcriptionApiKey: String = "",
    val transcriptionBaseUrl: String? = null,
    val transcriptionAnthropicCacheEnabled: Boolean = true,
    val transcriptionAnthropicCacheTtl: String = "1h",
    /** Wall-clock budget for a single tool execution; downgrades a blocking tool from a
     *  permanent generation hang to a recoverable tool error (#49). */
    val toolTimeoutMs: Long = GenerationDefaults.TOOL_EXECUTION_TIMEOUT_MS
)
