package com.newoether.agora.viewmodel

import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.isAnthropicCacheEnabledForProvider
import com.newoether.agora.data.anthropicCacheTtlForProvider
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val TITLE_WHITESPACE = Regex("\\s+")

private const val INITIAL_CONVERSATION_TITLE_MAX_CODE_POINTS = 32

internal fun initialConversationTitle(prompt: String, fallback: String): String {
    val normalized = prompt.replace(TITLE_WHITESPACE, " ").trim()
    if (normalized.isEmpty()) return fallback
    val codePointCount = normalized.codePointCount(0, normalized.length)
    if (codePointCount <= INITIAL_CONVERSATION_TITLE_MAX_CODE_POINTS) return normalized
    val prefixCodePoints = INITIAL_CONVERSATION_TITLE_MAX_CODE_POINTS - 1
    val prefixEnd = normalized.offsetByCodePoints(0, prefixCodePoints)
    return normalized.substring(0, prefixEnd).trimEnd() + "…"
}

internal fun fallbackConversationTitle(response: String): String =
    response.replace(TITLE_WHITESPACE, " ").trim().take(60)

internal fun titleSourceText(message: ChatMessage): String {
    if (message.text.isNotBlank()) return message.text.trim()
    val attachmentText = message.attachmentMeta?.items.orEmpty().joinToString("\n\n") { item ->
        val label = item.fileName ?: item.type
        when {
            !item.textContent.isNullOrBlank() -> "--- File: $label ---\n${item.textContent}"
            !item.transcription.isNullOrBlank() ->
                "--- Image Transcription: $label ---\n${item.transcription}"
            else -> buildString {
                append("--- Attachment: ")
                append(label)
                append(" ---")
                append("\nType: ")
                append(item.mimeType ?: item.type)
            }
        }
    }
    return attachmentText.trim()
}

/**
 * UI-independent conversation title generation shared by foreground chats and headless Tasks.
 * It owns provider/key resolution and persistence so both paths obey the same cold-start,
 * custom-provider, local-model serialization, and title-cleanup rules.
 */
class ConversationTitleGenerator(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val providers: ProviderRegistry,
) {
    sealed interface Result {
        data class Success(val title: String) : Result
        data class Failure(val reason: String) : Result
    }

    companion object {
        // Foreground and headless owners share the repository, so admission is process-wide.
        private val activeRequests = ConcurrentHashMap<
            Pair<ConversationRepository, String>, CompletableDeferred<Result>
        >()
    }

    /** Task fallback belongs beside the shared title generation and persistence rules. */
    internal suspend fun updateTaskTitle(conversationId: String, response: String) {
        settings.awaitInitialLoad()
        providers.awaitInitialSync()
        if (settings.titleGenerationEnabled.value) {
            when (generateAndPersist(conversationId)) {
                is Result.Success -> return
                is Result.Failure -> DebugLog.w("ConversationTitleGenerator", "Using task response title fallback")
            }
        }
        val fallback = fallbackConversationTitle(response)
        if (fallback.isBlank()) return
        conversations.getConversation(conversationId)?.let { conversation ->
            conversations.updateConversationTitleIfUnchanged(conversationId, conversation.title, fallback)
        }
    }
    suspend fun generateAndPersist(
        conversationId: String,
        runtimeSnapshot: ConversationGenerationSnapshot? = null,
        onStarted: suspend () -> Unit = {},
    ): Result {
        val key = conversations to conversationId
        val completion = CompletableDeferred<Result>()
        activeRequests.putIfAbsent(key, completion)?.let { return it.await() }
        try {
            val result = generate(conversationId, runtimeSnapshot, onStarted)
            completion.complete(result)
            return result
        } catch (e: CancellationException) {
            completion.completeExceptionally(e)
            throw e
        } catch (e: Exception) {
            DebugLog.e("ConversationTitleGenerator", "Title generation failed", e)
            val result = Result.Failure(e.localizedMessage ?: "Title generation failed")
            completion.complete(result)
            return result
        } finally {
            activeRequests.remove(key, completion)
        }
    }

    private suspend fun generate(
        conversationId: String,
        runtimeSnapshot: ConversationGenerationSnapshot?,
        onStarted: suspend () -> Unit,
    ): Result {
        var sourceEntity: com.newoether.agora.data.local.MessageEntity? = null
        var sourceModel: String? = null
        // Freeze the selected path and its first user payload in one Room read.
        // Settings, notifications and local-model admission must not delay this source capture.
        val conversation = conversations.withReadSnapshot { reader ->
            val target = reader.getConversation(conversationId)
                ?: return@withReadSnapshot null
            val topology = reader.getMessageContextTopology(conversationId)
            val streaming = runtimeSnapshot
                ?.takeIf { it.conversationId == conversationId && it.isGenerating }
                ?.streamingMessage
                ?.takeIf { live ->
                    !live.runId.isNullOrBlank() && topology.any { row ->
                        row.conversationId == conversationId && row.id == live.id &&
                            row.parentId == live.parentId && row.runId == live.runId &&
                            row.runSequence == live.runSequence && row.participant == live.participant
                    }
                }
            val path = ConversationUiState.resolvePath(
                allMessages = topology.map { it.toUiChatMessageStub() },
                streamingMsg = streaming,
                selectedChildren = com.newoether.agora.data.repository.ConversationBranchSelections(reader)
                    .restoreBranchSelections(conversationId),
            )
            sourceModel = path.firstOrNull { it.participant == Participant.MODEL }?.modelName
            val firstUser = path.firstOrNull { it.participant == Participant.USER }
            if (firstUser != null) {
                sourceEntity = reader.getMessage(firstUser.id)?.takeIf { it.conversationId == conversationId }
            }
            target
        } ?: return Result.Failure("Conversation not found")
        val summary = sourceEntity?.let { entity ->
            projectProviderMessages(listOf(entity), includeStoredTranscriptions = true)
                .singleOrNull()?.let(::titleSourceText)
        }.orEmpty()
        if (summary.isBlank()) return Result.Failure("Conversation has no usable text")

        settings.awaitInitialLoad()
        providers.awaitInitialSync()
        val configuredTitleModel = settings.titleGenerationModel.value
        val prefixedModelId = configuredTitleModel?.takeIf { it.isNotBlank() }
            ?: conversation.modelId?.takeIf { it.isNotBlank() }
            ?: sourceModel?.takeIf { it.isNotBlank() }
            ?: settings.selectedModel.value
        if (prefixedModelId.isBlank()) return Result.Failure("No title model selected")

        val providerName = providers.providerForModel(prefixedModelId)
        val activeKey = settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() }
            ?: settings.resolveActiveKey(providerName).orEmpty()
        if (!providers.isConfigured(providerName, activeKey)) {
            return Result.Failure("Provider not configured: $providerName")
        }

        val titlePrompt = listOf(
            ChatMessage(
                text = "Generate a short title (5 words maximum) for this conversation:\n\n" +
                    "$summary\n\nRespond with ONLY the title text, no quotes, no punctuation, " +
                    "no explanation.",
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
            )
        )
        val modelId = ModelId.parse(providers.canonicalModelId(prefixedModelId)).modelName
        val provider = providers.getInstanceOrNull(providerName)
            ?: return Result.Failure("Provider not registered: $providerName")
        val config = ProviderConfig(
            anthropicCacheEnabled = isAnthropicCacheEnabledForProvider(
                providerName, settings.anthropicCacheEnabled.value, settings.customProviders.value,
            ),
            anthropicCacheTtl = anthropicCacheTtlForProvider(
                providerName, settings.anthropicCacheTtl.value, settings.customProviders.value,
            ),
            apiKey = activeKey,
            modelId = modelId,
            systemPrompt = settings.titleGenerationPrompt.value.ifBlank {
                BuiltInPrompts.TITLE_GENERATION_SYSTEM
            },
            maxContextWindow = com.newoether.agora.model.ContextBudget.MIN_TOKENS,
            thinkingEnabled = false,
            baseUrl = providers.getEffectiveBaseUrl(providerName),
            sessionId = conversationId.takeIf { providerName == Constants.PROVIDER_OPENCODE_GO },
        )

        val requestId = UUID.randomUUID().toString()
        val requestTrace = HttpClient.RequestTrace(
            requestId = requestId,
            origin = "title",
            diagnosticContext = DeveloperDiagnostics.newRequestContext(
                requestId = requestId,
                conversationId = conversationId,
                runId = null,
                pass = null,
                provider = providerName,
                model = prefixedModelId,
                requestKind = "title",
            ),
        )
        var title = ""
        var providerError: String? = null
        suspend fun collectTitle() {
            HttpClient.withStreamScope(scope = null, requestTrace = requestTrace) {
                provider.generateResponse(titlePrompt, config).collect { event ->
                    requestTrace.recordParsedEvent(event)
                    when (event) {
                        is StreamEvent.TextChunk -> title += event.text
                        is StreamEvent.Retrying -> if (event.resetCurrentAttempt) {
                            title = ""
                            providerError = null
                        }
                        is StreamEvent.Error -> providerError = event.message
                        else -> Unit
                    }
                }
            }
        }

        onStarted()
        try {
            // LocalProvider owns process-wide local-model serialization. Acquiring that mutex
            // here as well would re-enter the same non-reentrant lock while collecting its Flow.
            collectTitle()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(
                "ConversationTitleGenerator",
                "Title generation failed for provider=$providerName model=$modelId",
                e,
            )
            return Result.Failure(e.localizedMessage ?: "Title generation failed")
        }

        providerError?.let { error ->
            DebugLog.e("ConversationTitleGenerator", "Title generation error: $error")
            return Result.Failure(error)
        }
        val cleaned = fallbackConversationTitle(title)
        if (cleaned.isBlank()) return Result.Failure("Provider returned an empty title")

        if (
            conversations.updateConversationTitleIfUnchanged(
                id = conversationId,
                expectedTitle = conversation.title,
                newTitle = cleaned,
            )
        ) {
            return Result.Success(cleaned)
        }
        val current = conversations.getConversation(conversationId)
            ?: return Result.Failure("Conversation was deleted")
        // A manual rename (or another title generation) won the race. Preserve the newer title
        // and report success so headless task fallback cannot overwrite it immediately afterward.
        return Result.Success(current.title)
    }
}
