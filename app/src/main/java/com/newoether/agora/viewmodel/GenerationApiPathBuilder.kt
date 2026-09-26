package com.newoether.agora.viewmodel

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.api.util.projectGenerationStatusesForApi
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class GenerationApiPathRequest(
    val parentId: String?,
    val conversationId: String,
    val config: GenerationConfig,
    val context: GenerationContext,
    val loadedMessages: List<MessageEntity>? = null,
)

internal data class GenerationApiPath(
    val messages: List<ChatMessage>,
    val providerConfig: ProviderConfig,
)

internal fun interface GenerationToolDefinitionSource {
    fun definitions(context: GenerationContext): List<ToolDefinition>
}

/**
 * Builds one immutable Provider request path from durable Room state.
 *
 * [loadedMessages] remains an explicit test/import seam. Production requests load only the
 * canonical selected path and never materialize off-path message payloads.
 */
internal class GenerationApiPathBuilder(
    private val conversations: ConversationRepository,
    private val generationErrorFormatter: (String) -> String,
    private val contextLoader: DurableSelectedContextLoader =
        DurableSelectedContextLoader(conversations, generationErrorFormatter),
    private val toolDefinitions: GenerationToolDefinitionSource,
) {
    suspend fun build(request: GenerationApiPathRequest): GenerationApiPath =
        withContext(Dispatchers.Default) {
            val config = request.config
            val definitions = if (config.lowContextModeEnabled) {
                emptyList()
            } else {
                toolDefinitions.definitions(request.context)
            }
            val includeAssistantReasoning =
                !config.responsesApiEnabled &&
                    config.thinkingEnabled &&
                    (
                        config.providerName == Constants.PROVIDER_DEEPSEEK ||
                            com.newoether.agora.api.openai.isDeepSeekModel(config.modelId)
                        ) &&
                    definitions.isNotEmpty()
            val fixedTokenCost = if (config.requestResolver == null) {
                ContextTokenEstimator.estimateFixed(
                    systemPrompt = config.effectiveSystemPrompt,
                    tools = definitions,
                    initialUserPrompt = config.initialUserPrompt,
                    codeExecutionEnabled = config.codeExecutionEnabled,
                    googleSearchEnabled = config.googleSearchEnabled,
                    openAiWebSearchEnabled = config.openAiWebSearchEnabled,
                )
            } else {
                0
            }
            val providerTokenBudget = if (config.requestResolver == null) {
                (config.maxContextWindow - fixedTokenCost).coerceAtLeast(1)
            } else {
                config.maxContextWindow
            }
            val currentPath = request.loadedMessages?.let { loaded ->
                projectLoadedSnapshot(
                    parentId = request.parentId,
                    loadedMessages = loaded,
                    includeStoredTranscriptions =
                        request.context.imageTranscriptionEnabled,
                )
            } ?: contextLoader.load(
                DurableSelectedContextRequest(
                    conversationId = request.conversationId,
                    anchorMessageId = request.parentId,
                    includeStoredTranscriptions =
                        request.context.imageTranscriptionEnabled,
                ),
            ).messages

            GenerationApiPath(
                messages = currentPath,
                providerConfig = ProviderConfig(
                    apiKey = config.apiKey,
                    modelId = config.modelId,
                    // Transcription-enabled models receive image descriptions instead of raw
                    // images. Sending image_url parts to a non-vision model is a hard provider 400.
                    includeImages = !request.context.imageTranscriptionEnabled,
                    includeAssistantReasoning = includeAssistantReasoning,
                    systemPrompt = config.effectiveSystemPrompt,
                    maxContextWindow = providerTokenBudget,
                    codeExecutionEnabled = config.codeExecutionEnabled,
                    googleSearchEnabled = config.googleSearchEnabled,
                    thinkingEnabled = config.thinkingEnabled,
                    thinkingLevel = config.thinkingLevel,
                    thinkingBudgetEnabled = config.thinkingBudgetEnabled,
                    thinkingBudgetTokens = config.thinkingBudgetTokens,
                    openAiServiceTier = config.openAiServiceTier,
                    responsesApiEnabled = config.responsesApiEnabled,
                    anthropicCacheEnabled = config.anthropicCacheEnabled,
                    anthropicCacheTtl = config.anthropicCacheTtl,
                    openAiWebSearchEnabled = config.openAiWebSearchEnabled,
                    baseUrl = config.baseUrl,
                    tools = definitions,
                    userPrepend = config.userPrepend,
                    userPostpend = config.userPostpend,
                    temperature = config.temperature,
                    maxTokens = config.maxTokens,
                    topP = config.topP,
                    frequencyPenalty = config.frequencyPenalty,
                    presencePenalty = config.presencePenalty,
                    promptCacheKey = request.conversationId.takeIf {
                        config.providerName == Constants.PROVIDER_OPENAI
                    },
                    sessionId = request.conversationId.takeIf {
                        config.providerName == Constants.PROVIDER_OPENCODE_GO
                    },
                    requestResolver = config.requestResolver,
                ),
            )
        }

    private fun projectLoadedSnapshot(
        parentId: String?,
        loadedMessages: List<MessageEntity>,
        includeStoredTranscriptions: Boolean,
    ): List<ChatMessage> {
        val messagesById = loadedMessages.associateBy(MessageEntity::id)
        val pathEntities = mutableListOf<MessageEntity>()
        val visited = mutableSetOf<String>()
        var currentId = parentId
        while (currentId != null) {
            check(visited.add(currentId)) { "Provider context ancestry contains a cycle" }
            val message = messagesById[currentId] ?: break
            pathEntities.add(0, message)
            if (
                message.id.startsWith(Constants.COMPACT_MSG_PREFIX) &&
                message.status == MessageStatus.SUCCESS
            ) break
            currentId = message.parentId
        }
        return projectProviderMessages(
            entities = ApiPathAssembler.assemble(pathEntities, loadedMessages),
            includeStoredTranscriptions = includeStoredTranscriptions,
        ).let { messages ->
            projectGenerationStatusesForApi(messages, generationErrorFormatter)
        }
    }
}
