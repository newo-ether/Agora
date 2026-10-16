package com.newoether.agora.viewmodel

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.util.ProviderStreamNormalizer
import com.newoether.agora.api.util.malformedToolCallRequest
import com.newoether.agora.api.util.safeWireToolCallId
import com.newoether.agora.api.util.safeWireToolName
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.RunEffectIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import java.util.UUID

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
                is StreamEvent.ImageChunk,
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

        val calls = pairableCalls(completedCalls, openToolStreams, sawEmptyToolBatch)
        return if (calls.isEmpty()) {
            ProviderPassOutcome.CompletedText(identity)
        } else {
            ProviderPassOutcome.CompletedToolCalls(identity, calls)
        }
    }

    /**
     * Makes every completed call pairable with a tool result. A call whose id or name is unusable,
     * and a tool stream that never completed, is replaced by a malformed-call stand-in that the
     * executor answers with an error result, so the model can correct itself instead of the run
     * failing. Unoffered tools and non-object arguments pass through unchanged: the executor
     * already answers those with an error result.
     */
    private fun pairableCalls(
        calls: List<StreamEvent.ToolCallRequest>,
        openToolStreams: Set<String>,
        sawEmptyToolBatch: Boolean,
    ): List<StreamEvent.ToolCallRequest> {
        val seenIds = mutableSetOf<String>()
        val seenStreamKeys = mutableSetOf<String>()
        val repaired = calls.map { call ->
            val cause = when {
                !call.id.matches(safeWireToolCallId) -> "invalid tool call id"
                !seenIds.add(call.id) -> "duplicate tool call id"
                !call.name.matches(safeWireToolName) -> "invalid or incomplete tool name"
                else -> null
            }
            val streamKeyUsable = call.streamKey.isNotBlank() && seenStreamKeys.add(call.streamKey)
            when {
                cause != null -> malformedToolCallRequest(
                    cause = cause,
                    originalName = call.name,
                    originalArguments = call.arguments,
                    streamKey = call.streamKey.takeIf { streamKeyUsable },
                    signature = call.signature,
                )
                streamKeyUsable -> call
                // The stream key is local bookkeeping only; a fresh one keeps the call executable.
                else -> call.copy(streamKey = "call_stream_${UUID.randomUUID()}")
            }
        }
        val unfinished = openToolStreams.map { streamKey ->
            malformedToolCallRequest(
                cause = "the tool call stream ended before the call was complete",
                streamKey = streamKey.takeIf { seenStreamKeys.add(it) },
            )
        }
        val emptyBatch = if (sawEmptyToolBatch && repaired.isEmpty() && unfinished.isEmpty()) {
            listOf(
                malformedToolCallRequest(
                    cause = "a tool call batch was announced but contained no calls",
                ),
            )
        } else {
            emptyList()
        }
        return repaired + unfinished + emptyBatch
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
