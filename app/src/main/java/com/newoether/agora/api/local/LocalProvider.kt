package com.newoether.agora.api.local

import com.newoether.agora.api.*
import com.newoether.agora.api.util.buildToolCallId

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.util.DebugLog
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.newoether.agora.viewmodel.GenerationCancelHandle
import kotlin.coroutines.coroutineContext

private const val CONTEXT_EXCEEDED_PREFIX = "LOCAL_CONTEXT_EXCEEDED:"

internal fun localGenerationFailure(
    event: LlamaGenerationEvent.Failed,
    displayMessage: String,
): GenerationError.LocalModel = localGenerationFailure(
    rawMessage = event.message,
    displayMessage = displayMessage,
)

private fun localGenerationFailure(
    rawMessage: String?,
    displayMessage: String,
): GenerationError.LocalModel = GenerationError.LocalModel(
    message = displayMessage,
    code = if (rawMessage?.startsWith(CONTEXT_EXCEEDED_PREFIX) == true) {
        LOCAL_CONTEXT_CAPACITY_ERROR_CODE
    } else {
        null
    },
)

class LocalProvider(
    private val context: Context,
    private val settings: SettingsRepository
) : LlmProvider {

    companion object {
        private const val TAG = "LocalProvider"
        private val TEMPLATE_JSON = Json {
            encodeDefaults = true
            explicitNulls = false
        }
    }

    override val name: String = Constants.PROVIDER_LOCAL
    override val defaultBaseUrl: String = ""
    override val nativeTextParsingAuthoritative: Boolean = true

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val chatModels = settings.localChatModels.first()
        val modelConfig = chatModels.find { it.modelId == config.modelId }
        if (modelConfig == null) {
            emit(StreamEvent.Error(GenerationError.LocalModel("Local model not found: ${config.modelId}")))
            return@flow
        }

        // The process runtime owns strict FIFO admission and the single Chat-or-Embedding resident.
        // This block covers model/context mutation, template rendering, and complete generation.
        val executed = LocalModelRuntime.runChat(
            modelPath = modelConfig.localFilePath,
            nCtx = modelConfig.nCtx,
        ) { engine ->

        // Build template messages, collecting images per-message with <__media__> markers
        val imagePaths = mutableListOf<String>()
        val localContextWindow = minOf(config.maxContextWindow, modelConfig.nCtx).coerceAtLeast(1)
        val resolvedRequest = config.copy(maxContextWindow = localContextWindow).resolveRequest(messages)
        val templateMessages = buildTemplateMessages(
            resolvedRequest.messages,
            resolvedRequest.systemPrompt,
            imagePaths,
        )
        val hasImages = imagePaths.isNotEmpty()

        if (hasImages) {
            if (modelConfig.mmprojPath.isBlank()) {
                emit(StreamEvent.Error(GenerationError.LocalModel(
                    "This local model has no vision projector configured."
                )))
                return@runChat
            }
            if (!engine.loadMmproj(modelConfig.mmprojPath)) {
                emit(StreamEvent.Error(GenerationError.LocalModel(
                    "Failed to load the configured vision projector."
                )))
                return@runChat
            }
        } else if (modelConfig.mmprojPath.isBlank()) {
            engine.unloadMmproj()
        }

        // Template ownership stays with the model. A generic fallback can silently apply the
        // wrong role/control-token protocol, so an incompatible model fails closed.
        val templateTools = config.tools.orEmpty().map { tool ->
            ChatTemplateTool(
                name = tool.function.name,
                description = tool.function.description,
                parameters = TEMPLATE_JSON.encodeToString(tool.function.parameters),
            )
        }
        val requiresToolCapableTemplate = templateTools.isNotEmpty() || templateMessages.any { message ->
            message.toolCalls.isNotEmpty() || message.role == "tool"
        }
        val template = engine.applyTemplate(
            messages = templateMessages,
            tools = templateTools,
            addAss = true,
            enableThinking = config.thinkingEnabled,
        )
        if (template == null) {
            emit(StreamEvent.Error(GenerationError.LocalModel(
                "The local model does not provide a compatible chat template."
            )))
            return@runChat
        }
        if (requiresToolCapableTemplate && !template.supportsTools) {
            emit(StreamEvent.Error(GenerationError.LocalModel(
                "The local model chat template does not support tool calling."
            )))
            return@runChat
        }
        val promptLength = template.prompt.length
        val imageCount = imagePaths.size
        if (hasImages) {
            DebugLog.d(TAG, "Generated multimodal prompt ($promptLength chars, $imageCount images)")
        } else {
            DebugLog.d(TAG, "Generated prompt ($promptLength chars)")
        }

        // Native template parsing produces typed thought and tool events. Shared stream normalization
        // still recovers reasoning delimiters that the model emits as ordinary text.
        var inputTokenCount = 0
        var outputTokenCount = 0
        var terminalError: GenerationError? = null
        try {
            val tokenFlow = if (hasImages) {
                engine.generateWithImages(
                    template = template,
                    imagePaths = imagePaths,
                    temperature = config.temperature ?: modelConfig.temperature,
                    topP = config.topP ?: modelConfig.topP,
                    frequencyPenalty = config.frequencyPenalty ?: 0f,
                    presencePenalty = config.presencePenalty ?: 0f,
                    maxTokens = config.maxTokens ?: modelConfig.maxTokens,
                )
            } else {
                engine.generate(
                    template = template,
                    temperature = config.temperature ?: modelConfig.temperature,
                    topP = config.topP ?: modelConfig.topP,
                    frequencyPenalty = config.frequencyPenalty ?: 0f,
                    presencePenalty = config.presencePenalty ?: 0f,
                    maxTokens = config.maxTokens ?: modelConfig.maxTokens,
                )
            }
            // Register while still holding the process-wide runtime task. The handle is removed
            // before the next FIFO waiter may begin native work on the resident engine.
            val streamScope = HttpClient.boundStreamScope()
            // Tool call indexes restart at 0 in every provider pass; without a per-pass key the
            // next pass's first call would update (and hide) the previous pass's card.
            val passKey = java.util.UUID.randomUUID().toString().take(8)
            val nativeCancel = GenerationCancelHandle { engine.cancel() }
            streamScope?.register(nativeCancel)
            try {
                tokenFlow.collect { event ->
                    if (!coroutineContext.isActive) {
                        engine.cancel()
                        return@collect
                    }
                    when (event) {
                        is LlamaGenerationEvent.Text -> {
                            if (event.value.isNotEmpty()) emit(StreamEvent.TextChunk(event.value))
                        }
                        is LlamaGenerationEvent.Thought -> {
                            if (event.value.isNotEmpty()) emit(StreamEvent.ThoughtChunk(event.value))
                        }
                        is LlamaGenerationEvent.ToolCallUpdate -> {
                            val call = event.call
                            emit(
                                StreamEvent.ToolCallUpdate(
                                    streamKey = "local_tool_${passKey}_${call.index}",
                                    id = call.id,
                                    name = call.name,
                                    arguments = call.arguments,
                                )
                            )
                        }
                        is LlamaGenerationEvent.ToolCallsCompleted -> {
                            val calls = event.calls.map { call ->
                                val arguments = call.arguments.ifBlank { "{}" }
                                StreamEvent.ToolCallRequest(
                                    id = call.id?.takeIf(String::isNotBlank)
                                        ?: buildToolCallId(
                                            "${call.name}:$passKey:${call.index}",
                                            arguments,
                                        ),
                                    name = call.name,
                                    arguments = arguments,
                                    streamKey = "local_tool_${passKey}_${call.index}",
                                )
                            }
                            if (calls.size == 1) {
                                emit(calls.single())
                            } else if (calls.isNotEmpty()) {
                                emit(StreamEvent.ToolCallsRequest(calls))
                            }
                        }
                        is LlamaGenerationEvent.Completed -> {
                            inputTokenCount = event.inputTokenCount
                            outputTokenCount = event.outputTokenCount
                            terminalError = when (event.reason) {
                                LlamaGenerationStopReason.EOG -> null
                                LlamaGenerationStopReason.MAX_TOKENS ->
                                    GenerationError.OutputTruncated(name, "max_tokens")
                                LlamaGenerationStopReason.CONTEXT_FULL -> GenerationError.LocalModel(
                                    message = "Local context window was exhausted before generation completed.",
                                    code = LOCAL_CONTEXT_CAPACITY_ERROR_CODE,
                                )
                                LlamaGenerationStopReason.CANCELLED -> GenerationError.Cancelled
                            }
                        }
                        is LlamaGenerationEvent.Failed -> {
                            inputTokenCount = event.inputTokenCount
                            outputTokenCount = event.outputTokenCount
                            terminalError = localGenerationFailure(
                                event = event,
                                displayMessage = formatGenerationError(
                                    IllegalStateException(event.message),
                                    modelConfig,
                                ),
                            )
                        }
                    }
                }
            } finally {
                streamScope?.unregister(nativeCancel)
            }
            if (terminalError === GenerationError.Cancelled) {
                throw kotlinx.coroutines.CancellationException("Native generation cancelled")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            engine.cancel()
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "Generation failed", e)
            emit(
                StreamEvent.Error(
                    localGenerationFailure(
                        rawMessage = e.message,
                        displayMessage = formatGenerationError(e, modelConfig),
                    )
                )
            )
            return@runChat
        }

        emit(
            StreamEvent.UsageUpdate(
                TokenUsage(
                    totalTokenCount = (inputTokenCount + outputTokenCount).coerceAtLeast(0),
                    inputTokenCount = inputTokenCount.coerceAtLeast(0),
                    outputTokenCount = outputTokenCount.coerceAtLeast(0),
                )
            )
        )
        terminalError?.let { emit(StreamEvent.Error(it)) }
        }
        if (!executed) {
            emit(StreamEvent.Error(GenerationError.LocalModel(
                "Failed to load model: ${modelConfig.alias}"
            )))
        }
    }.flowOn(Dispatchers.IO)

    private fun formatGenerationError(
        error: Exception,
        model: com.newoether.agora.data.LocalChatModelConfig
    ): String {
        val message = error.message ?: "Unknown error"
        if (message.startsWith(CONTEXT_EXCEEDED_PREFIX)) {
            val parts = message.removePrefix(CONTEXT_EXCEEDED_PREFIX).split(":")
            val promptTokens = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val contextTokens = parts.getOrNull(1)?.toIntOrNull() ?: model.nCtx
            return context.getString(R.string.local_context_exceeded, promptTokens, contextTokens)
        }
        return "Generation failed: $message"
    }

    private fun buildTemplateMessages(
        messages: List<ChatMessage>,
        systemPrompt: String?,
        imagePathsOut: MutableList<String>? = null
    ): List<ChatTemplateMessage> {
        val result = mutableListOf<ChatTemplateMessage>()

        if (!systemPrompt.isNullOrBlank()) {
            result.add(ChatTemplateMessage(role = "system", content = systemPrompt))
        }

        for (msg in messages) {
            if (msg.participant == Participant.ERROR) continue

            // Tool call messages are one assistant turn, including parallel calls.
            if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                val toolCalls = if (!toolSegs.isNullOrEmpty()) {
                    toolSegs.map { seg ->
                        val name = seg.toolName.orEmpty()
                        val arguments = seg.toolArgs ?: "{}"
                        ChatTemplateToolCall(
                            id = seg.toolCallId?.takeIf(String::isNotBlank)
                                ?: buildToolCallId(name, arguments),
                            name = name,
                            arguments = arguments,
                        )
                    }
                } else {
                    msg.toolCall?.let { toolCall ->
                        listOf(
                            ChatTemplateToolCall(
                                id = toolCall.toolCallId?.takeIf(String::isNotBlank)
                                    ?: buildToolCallId(toolCall.toolName, toolCall.arguments),
                                name = toolCall.toolName,
                                arguments = toolCall.arguments,
                            )
                        )
                    }.orEmpty()
                }
                if (toolCalls.isNotEmpty()) {
                    result.add(
                        ChatTemplateMessage(
                            role = "assistant",
                            content = "",
                            toolCalls = toolCalls.toTypedArray(),
                        )
                    )
                }
                continue
            }

            // Tool result messages preserve the call identity expected by native templates.
            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    for (seg in toolSegs) {
                        val name = seg.toolName.orEmpty()
                        val arguments = seg.toolArgs ?: "{}"
                        result.add(
                            ChatTemplateMessage(
                                role = "tool",
                                content = seg.toolResult.orEmpty(),
                                toolName = name,
                                toolCallId = seg.toolCallId?.takeIf(String::isNotBlank)
                                    ?: buildToolCallId(name, arguments),
                            )
                        )
                    }
                } else {
                    msg.toolCall?.let { toolCall ->
                        result.add(
                            ChatTemplateMessage(
                                role = "tool",
                                content = toolCall.result,
                                toolName = toolCall.toolName,
                                toolCallId = toolCall.toolCallId?.takeIf(String::isNotBlank)
                                    ?: buildToolCallId(toolCall.toolName, toolCall.arguments),
                            )
                        )
                    }
                }
                continue
            }

            // Normal messages
            val role = when (msg.participant) {
                Participant.USER -> "user"
                Participant.MODEL -> "assistant"
                Participant.ERROR -> "user"
            }

            val images = msg.images.filter { it.isNotBlank() }
            val content = if (role == "user" && images.isNotEmpty() && imagePathsOut != null) {
                imagePathsOut.addAll(images)
                images.joinToString("\n") { "<__media__>" } + "\n" + msg.text
            } else {
                msg.text
            }

            result.add(ChatTemplateMessage(role = role, content = content))
        }

        return result
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> {
        return settings.localChatModels.first().map { it.modelId }
    }

}
