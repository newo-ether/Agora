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
import kotlinx.coroutines.flow.collect
import java.util.UUID

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

    suspend fun generateAndPersist(conversationId: String): Result {
        settings.awaitInitialLoad()
        providers.awaitInitialSync()

        val conversation = conversations.getConversation(conversationId)
            ?: return Result.Failure("Conversation not found")
        val path = ConversationUiState.resolvePath(
            allMessages = conversations.getMessageTopologySnapshot(conversationId)
                .map { message -> message.toUiChatMessageStub() },
            streamingMsg = null,
            selectedChildren = conversations.restoreBranchSelections(conversationId),
        )
        suspend fun firstMatchingMessage(
            participant: Participant,
            predicate: (ChatMessage) -> Boolean,
        ): ChatMessage? {
            for (message in path) {
                if (message.participant != participant) continue
                val entity = conversations.getMessage(message.id) ?: continue
                val projected = projectProviderMessages(
                    entities = listOf(entity),
                    includeStoredTranscriptions = true,
                ).singleOrNull() ?: continue
                if (predicate(projected)) return projected
            }
            return null
        }
        val firstUser = firstMatchingMessage(Participant.USER) { message ->
            titleSourceText(message).isNotBlank()
        } ?: return Result.Failure("Conversation has no user message")
        val firstModel = firstMatchingMessage(Participant.MODEL) { message ->
            message.text.isNotBlank()
        }

        val configuredTitleModel = settings.titleGenerationModel.value
        val prefixedModelId = configuredTitleModel?.takeIf { it.isNotBlank() }
            ?: conversation.modelId?.takeIf { it.isNotBlank() }
            ?: firstModel?.modelName?.takeIf { it.isNotBlank() }
            ?: settings.selectedModel.value
        if (prefixedModelId.isBlank()) return Result.Failure("No title model selected")

        val providerName = providers.providerForModel(prefixedModelId)
        val activeKey = settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() }
            ?: settings.resolveActiveKey(providerName).orEmpty()
        if (!providers.isConfigured(providerName, activeKey)) {
            return Result.Failure("Provider not configured: $providerName")
        }

        val firstUserSource = titleSourceText(firstUser)
        val summary = if (firstModel != null) {
            "User: $firstUserSource\nAssistant: ${firstModel.text.take(500)}"
        } else {
            firstUserSource
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
                        is StreamEvent.Error -> providerError = event.message
                        else -> Unit
                    }
                }
            }
        }

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
