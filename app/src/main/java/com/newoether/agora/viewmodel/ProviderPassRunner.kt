package com.newoether.agora.viewmodel

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.util.ProviderStreamNormalizer
import com.newoether.agora.api.util.safeWireToolCallId
import com.newoether.agora.api.util.safeWireToolName
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.RunEffectIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json

internal sealed interface ProviderPassOutcome {
    val identity: RunEffectIdentity

    data class CompletedText(
        override val identity: RunEffectIdentity,
    ) : ProviderPassOutcome

    data class CompletedToolCalls(
        override val identity: RunEffectIdentity,
        val calls: List<StreamEvent.ToolCallRequest>,
    ) : ProviderPassOutcome {
        init {
            require(calls.isNotEmpty())
        }
    }

    data class Truncated(
        override val identity: RunEffectIdentity,
        val error: GenerationError.OutputTruncated,
    ) : ProviderPassOutcome

    data class Failed(
        override val identity: RunEffectIdentity,
        val error: GenerationError,
    ) : ProviderPassOutcome

    data class Cancelled(
        override val identity: RunEffectIdentity,
    ) : ProviderPassOutcome
}

/**
 * Executes exactly one Provider request and closes it into an identity-bearing outcome.
 *
 * Providers remain responsible for protocol-specific semantic termination validation and retry.
 * This boundary adds consumer-side fail-closed validation: live tool progress may reach the UI,
 * but no call becomes authoritative unless the completed batch has unique, complete metadata.
 */
internal class ProviderPassRunner(
    private val json: Json = Json,
) {
    suspend fun run(
        identity: RunEffectIdentity,
        provider: LlmProvider,
        messages: List<ChatMessage>,
        config: ProviderConfig,
        onEvent: suspend (StreamEvent) -> Unit,
    ): ProviderPassOutcome {
        val completedCalls = mutableListOf<StreamEvent.ToolCallRequest>()
        val openToolStreams = linkedSetOf<String>()
        var providerError: GenerationError? = null
        var sawEmptyToolBatch = false
        val streamNormalizer = ProviderStreamNormalizer(
            tools = config.tools,
            json = json,
            nativeTextParsingAuthoritative = provider.nativeTextParsingAuthoritative,
        )

        suspend fun acceptEvent(event: StreamEvent) {
            when (event) {
                is StreamEvent.ToolCallUpdate -> openToolStreams += event.streamKey
                is StreamEvent.ToolCallRequest -> {
                    completedCalls += event
                    openToolStreams -= event.streamKey
                }
                is StreamEvent.ToolCallsRequest -> {
                    if (event.calls.isEmpty()) sawEmptyToolBatch = true
                    event.calls.forEach { call ->
                        completedCalls += call
                        openToolStreams -= call.streamKey
                    }
                }
                is StreamEvent.Error -> if (providerError == null) {
                    providerError = event.error
                }
                is StreamEvent.TextChunk,
                is StreamEvent.CitationUpdate,
                is StreamEvent.ThoughtChunk,
                is StreamEvent.HostedToolCallUpdate,
                is StreamEvent.UsageUpdate,
                is StreamEvent.Retrying,
                -> Unit
            }
            try {
                onEvent(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw EventConsumerException(error)
            }
            if (event is StreamEvent.Error) {
                throw ProviderPassClosedException()
            }
        }

        try {
            provider.generateResponse(messages, config).collect { event ->
                streamNormalizer.emit(event, ::acceptEvent)
            }
            streamNormalizer.finish(::acceptEvent)
        } catch (cancelled: CancellationException) {
            return ProviderPassOutcome.Cancelled(identity)
        } catch (consumerFailure: EventConsumerException) {
            throw consumerFailure.original
        } catch (_: ProviderPassClosedException) {
            return errorOutcome(identity, checkNotNull(providerError))
        } catch (providerFailure: Exception) {
            providerError?.let { error ->
                return errorOutcome(identity, error)
            }
            try {
                streamNormalizer.finish(::acceptEvent, releaseTextTools = false)
            } catch (cancelled: CancellationException) {
                return ProviderPassOutcome.Cancelled(identity)
            } catch (consumerFailure: EventConsumerException) {
                throw consumerFailure.original
            }
            val error = GenerationError.Unknown(providerFailure)
            onEvent(StreamEvent.Error(error))
            return ProviderPassOutcome.Failed(identity, error)
        }

        providerError?.let { error ->
            return errorOutcome(identity, error)
        }

        validateCompletedTools(
            completedCalls,
            openToolStreams,
            sawEmptyToolBatch,
        )?.let { error ->
            onEvent(StreamEvent.Error(error))
            return ProviderPassOutcome.Failed(identity, error)
        }

        return if (completedCalls.isEmpty()) {
            ProviderPassOutcome.CompletedText(identity)
        } else {
            ProviderPassOutcome.CompletedToolCalls(identity, completedCalls.toList())
        }
    }

    private fun validateCompletedTools(
        calls: List<StreamEvent.ToolCallRequest>,
        openToolStreams: Set<String>,
        sawEmptyToolBatch: Boolean,

    ): GenerationError? {
        val invalidCause = when {
            sawEmptyToolBatch -> "Provider returned an empty tool call batch"
            openToolStreams.isNotEmpty() -> "Provider ended with incomplete tool metadata"
            calls.map { it.id }.distinct().size != calls.size ->
                "Provider returned duplicate tool call ids"
            calls.map { it.streamKey }.distinct().size != calls.size ->
                "Provider returned duplicate tool stream identities"
            calls.any { it.streamKey.isBlank() } ->
                "Provider returned an invalid tool stream identity"
            calls.any { !it.id.matches(safeWireToolCallId) } ->
                "Provider returned an invalid tool call id"
            calls.any { !it.name.matches(safeWireToolName) } ->
                "Provider returned an invalid or incomplete tool name"
            // A tool the request did not offer and arguments that are not a JSON object are model
            // mistakes, not protocol damage: the tool executor already answers each of them with an
            // error tool result, so the model sees the failure and can correct itself. Only
            // conditions that make a tool result impossible to pair remain fatal here.
            else -> null
        } ?: return null
        return GenerationError.SseParse(rawLine = "tool_calls", cause = invalidCause)
    }

    private fun errorOutcome(
        identity: RunEffectIdentity,
        error: GenerationError,
    ): ProviderPassOutcome = when (error) {
        is GenerationError.OutputTruncated -> ProviderPassOutcome.Truncated(identity, error)
        else -> ProviderPassOutcome.Failed(identity, error)
    }

    private class EventConsumerException(val original: Exception) : RuntimeException(original)
    private class ProviderPassClosedException : RuntimeException()
}
