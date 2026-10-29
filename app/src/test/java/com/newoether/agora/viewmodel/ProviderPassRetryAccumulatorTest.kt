package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.model.toCitationRecord
import com.newoether.agora.tool.ToolExecutionResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPassRetryAccumulatorTest {
    @Test
    fun `reset snapshot removes partial output and preserves a committed tool round`() = runTest {
        val harness = AccumulatorHarness()
        val previousCall = StreamEvent.ToolCallRequest(
            id = "call_previous",
            name = "file_read",
            arguments = "{}",
            signature = "previous_tool_signature",
            streamKey = "previous_stream",
        )
        val previousThought = StreamEvent.ThoughtChunk(
            "Previous thought",
            title = "Previous title",
            signature = "previous_signature",
        )
        assertEquals(
            ProviderPassOutcome.CompletedToolCalls(IDENTITY, listOf(previousCall)),
            harness.run(listOf(
                previousThought,
                StreamEvent.TextChunk("Previous answer\n"),
                citation("previous_source", "Previous answer\n"),
                StreamEvent.UsageUpdate(PRIOR_USAGE),
                previousCall,
            )),
        )
        val output = harness.output
        output.flushAnswerSegment()
        output.flushThoughtSegment()
        output.toolOverlay.start(previousCall)
        output.toolOverlay.complete(previousCall, ToolExecutionResult("Committed result"))
        output.toolOverlay.markCommittedRound(setOf(previousCall.id), "committed_round")
        output.currentStatus = MessageStatus.SENDING
        val baselineSegments = output.toolOverlay.snapshot()
        val baselineTiming = output.thoughtTiming.totalDurationMs
        val baselineSequence = output.nextAnswerDeltaSequence
        val baselineUsage = output.totalTokenUsage
        var resetSnapshots = 0
        var partialWasPublished = false
        harness.onStreamUpdate = {
            if (output.toolOverlay.hasStream("failed_hosted")) partialWasPublished = true
        }
        harness.onRetry = {
            resetSnapshots += 1
            assertTrue(partialWasPublished)
            assertEquals("Previous answer\n", output.totalText.toString())
            assertEquals("Previous thought", output.totalThoughts)
            assertEquals("Previous title", output.totalThoughtTitle)
            assertEquals(MessageStatus.SENDING, output.currentStatus)
            assertEquals("", output.currentAnswerBuf.toString())
            assertEquals("", output.currentThoughtBuf.toString())
            assertTrue(output.currentAnswerDeltas.isEmpty())
            assertEquals(baselineSequence, output.nextAnswerDeltaSequence)
            assertNull(output.currentThoughtSignature)
            assertNull(output.currentThoughtSignatureProvider)
            assertNull(output.generationErrorMessage)
            assertNull(output.generationErrorCode)
            assertEquals(baselineTiming, output.thoughtTiming.totalDurationMs)
            assertEquals(0L, output.thoughtTiming.currentDurationMs)
            assertNull(output.thoughtTiming.liveDurationMs())
            assertEquals(baselineUsage, output.totalTokenUsage)
            assertEquals(PRIOR_USAGE.totalTokenCount, output.totalTokenCount)
            assertEquals(baselineSegments, output.toolOverlay.snapshot())
            assertTrue(output.toolOverlay.hasStream(previousCall.streamKey))
            assertFalse(output.toolOverlay.hasStream("failed_stream"))
            assertFalse(output.toolOverlay.hasStream("failed_hosted"))
            assertEquals("Retrying", output.retryText)
        }

        val nextIdentity = IDENTITY.copy(pass = 4, effectId = "provider-4-0")
        val outcome = harness.run(
            listOf(
                StreamEvent.ThoughtChunk("", "Failed title", "failed_late_signature"),
                StreamEvent.ThoughtChunk("Failed thought", "Failed title", "failed_signature"),
                StreamEvent.TextChunk("Failed answer"),
                citation("previous_source", "Failed answer"),
                citation("failed_source", "Failed answer"),
                StreamEvent.UsageUpdate(FAILED_USAGE),
                StreamEvent.ToolCallUpdate("failed_stream", "call_failed", "file_write", "{"),
                StreamEvent.HostedToolCallUpdate("failed_hosted", "openai_search", "{}", "Failed result"),
                StreamEvent.Retrying(1, 5, resetCurrentAttempt = true),
                StreamEvent.TextChunk("Replacement"),
                StreamEvent.UsageUpdate(REPLACEMENT_USAGE),
            ),
            identity = nextIdentity,
        )

        assertEquals(ProviderPassOutcome.CompletedText(nextIdentity), outcome)
        assertEquals(1, resetSnapshots)
        assertEquals("Previous answer\nReplacement", output.totalText.toString())
        assertEquals("Replacement", output.currentAnswerBuf.toString())
        assertEquals(listOf(baselineSequence), output.currentAnswerDeltas.map { it.sequence })
        assertEquals(listOf(11), output.currentAnswerDeltas.map { it.codePointCount })
        assertEquals(baselineSequence + 1, output.nextAnswerDeltaSequence)
        assertEquals(baselineSegments, output.toolOverlay.snapshot())
        assertEquals(baselineUsage?.plusRequest(REPLACEMENT_USAGE), output.totalTokenUsage)
        assertEquals(120, output.totalTokenCount)
        val committed = baselineSegments.single { it.type == "tool" }
        assertEquals("committed_round", committed.toolRoundId)
        assertEquals("Committed result", committed.toolResult)
        assertEquals(ToolExecutionStates.SUCCEEDED, committed.toolState)
        assertTrue(baselineSegments.any { it.signature == "previous_signature" })
        val source = baselineSegments.mapNotNull { it.toCitationRecord() }.single()
        assertEquals("previous_source", source.providerSourceId)
        assertEquals(listOf("Previous answer\n"), source.anchors.map { it.citedText })
        assertNull(output.retryText)
    }

    @Test
    fun `replacement native call can reuse failed id and stream without stale authority`() = runTest {
        val failedCall = StreamEvent.ToolCallRequest(
            id = "call_reused",
            name = "file_write",
            arguments = "{\"path\":\"failed\"}",
            signature = "failed_signature",
            streamKey = "reused_stream",
        )
        val replacement = failedCall.copy(
            name = "file_read",
            arguments = "{\"path\":\"replacement\"}",
            signature = "replacement_signature",
        )
        val failedBatches = listOf(
            listOf<StreamEvent>(
                failedCall,
                StreamEvent.ToolCallUpdate("failed_open", "call_open", "file_write", "{"),
            ),
            listOf<StreamEvent>(StreamEvent.ToolCallsRequest(emptyList())),
        )
        failedBatches.forEach { failedBatch ->
            val harness = AccumulatorHarness()
            harness.onRetry = {
                assertTrue(harness.output.toolOverlay.snapshot().none { it.type == "tool" })
            }

            val outcome = harness.run(
                failedBatch + StreamEvent.Retrying(1, 5, resetCurrentAttempt = true) +
                    listOf(
                        StreamEvent.ToolCallUpdate(
                            replacement.streamKey, replacement.id, replacement.name,
                            replacement.arguments, replacement.signature,
                        ),
                        replacement,
                    ),
            )

            assertEquals(ProviderPassOutcome.CompletedToolCalls(IDENTITY, listOf(replacement)), outcome)
            val calls = (outcome as ProviderPassOutcome.CompletedToolCalls).calls
            calls.forEach { call ->
                harness.output.toolOverlay.start(call)
                harness.output.toolOverlay.complete(call, ToolExecutionResult("Replacement result"))
            }
            val tool = harness.output.toolOverlay.snapshot().single { it.type == "tool" }
            assertEquals(replacement.id, tool.toolCallId)
            assertEquals(replacement.name, tool.toolName)
            assertEquals(replacement.arguments, tool.toolArgs)
            assertEquals(replacement.signature, tool.signature)
            assertEquals("Replacement result", tool.toolResult)
            assertEquals(ToolExecutionStates.SUCCEEDED, tool.toolState)
            assertFalse(harness.output.toolOverlay.hasStream("failed_open"))
            assertTrue(harness.forwarded.none { it is StreamEvent.Error })
        }
    }

    @Test
    fun `reset discards buffered text tool candidates and incomplete delimiters`() = runTest {
        val failedPrefixes = listOf(
            "<tool_call>{\"id\":\"failed_text\",\"name\":\"file_write\",\"arguments\":{}}</tool_call>",
            "<tool_call>{\"id\":\"failed_text\",\"name\":\"file_write\",\"arguments\":{",
            "{\"name\":\"file_write\",\"arguments\":{",
            "<tool_",
            "<think>Failed thought</thi",
            "<think>Failed thought",
        )
        failedPrefixes.forEach { failedPrefix ->
            val harness = AccumulatorHarness()
            harness.onRetry = {
                assertEquals(failedPrefix, "", harness.output.totalText.toString())
                assertEquals(failedPrefix, "", harness.output.totalThoughts)
                assertTrue(harness.output.toolOverlay.snapshot().all { it.type == "answer" })
            }

            val outcome = harness.run(listOf(
                StreamEvent.TextChunk(failedPrefix),
                StreamEvent.Retrying(1, 5, resetCurrentAttempt = true),
                StreamEvent.TextChunk("Replacement answer"),
            ))

            assertEquals(failedPrefix, ProviderPassOutcome.CompletedText(IDENTITY), outcome)
            assertEquals(failedPrefix, "Replacement answer", harness.output.totalText.toString())
            assertEquals(failedPrefix, "", harness.output.totalThoughts)
            assertTrue(harness.output.toolOverlay.snapshot().none { it.type == "tool" })
            assertTrue(harness.forwarded.none {
                it is StreamEvent.ToolCallRequest || it is StreamEvent.ToolCallsRequest || it is StreamEvent.Error
            })
        }
    }

    @Test
    fun `reset restores thought classification after a failed implicit close`() = runTest {
        val harness = AccumulatorHarness()
        harness.onRetry = {
            assertEquals("", harness.output.totalText.toString())
            assertEquals("", harness.output.totalThoughts)
            assertNull(harness.output.totalThoughtTitle)
            assertNull(harness.output.currentThoughtSignature)
            assertEquals(MessageStatus.SENDING, harness.output.currentStatus)
        }

        val outcome = harness.run(listOf(
            StreamEvent.ThoughtChunk("Failed thought</thinking>Failed answer", "Failed", "failed_signature"),
            StreamEvent.Retrying(1, 5, resetCurrentAttempt = true),
            StreamEvent.ThoughtChunk("Replacement thought", "Replacement", "replacement_signature"),
        ))

        assertEquals(ProviderPassOutcome.CompletedText(IDENTITY), outcome)
        assertEquals("", harness.output.totalText.toString())
        assertEquals("Replacement thought", harness.output.totalThoughts)
        assertEquals("Replacement thought", harness.output.currentThoughtBuf.toString())
        assertEquals("Replacement", harness.output.totalThoughtTitle)
        assertEquals("replacement_signature", harness.output.currentThoughtSignature)
        assertEquals("provider", harness.output.currentThoughtSignatureProvider)
        assertEquals(MessageStatus.THINKING, harness.output.currentStatus)
    }

    @Test
    fun `reset restores unflushed request baseline buffers deltas and signature`() = runTest {
        val harness = AccumulatorHarness()
        harness.run(listOf(
            StreamEvent.TextChunk("Baseline answer"),
            StreamEvent.ThoughtChunk("Baseline thought", "Baseline title", "baseline_signature"),
        ))
        val output = harness.output
        val baselineSegments = output.toolOverlay.snapshot()
        val baselineThought = output.currentThoughtBuf.toString()
        val baselineSequence = output.nextAnswerDeltaSequence
        harness.onRetry = {
            assertEquals("Baseline answer", output.totalText.toString())
            assertEquals("Baseline thought", output.totalThoughts)
            assertEquals(baselineThought, output.currentThoughtBuf.toString())
            assertEquals("baseline_signature", output.currentThoughtSignature)
            assertEquals("provider", output.currentThoughtSignatureProvider)
            assertEquals(MessageStatus.THINKING, output.currentStatus)
            assertEquals(baselineSegments, output.toolOverlay.snapshot())
            assertEquals(baselineSequence, output.nextAnswerDeltaSequence)
        }
        assertEquals(
            ProviderPassOutcome.CompletedText(IDENTITY),
            harness.run(listOf(
                StreamEvent.TextChunk("Failed answer"),
                StreamEvent.Retrying(1, 5, resetCurrentAttempt = true),
            )),
        )
        output.flushThoughtSegment()
        output.currentStatus = MessageStatus.SENDING
        harness.onRetry = {}
        harness.run(listOf(StreamEvent.TextChunk(" pending")))
        val baselineAnswer = output.currentAnswerBuf.toString()
        val baselineDeltas = output.currentAnswerDeltas.toList()
        val pendingSequence = output.nextAnswerDeltaSequence
        val pendingSegments = output.toolOverlay.snapshot()
        harness.onRetry = {
            assertEquals("Baseline answer pending", output.totalText.toString())
            assertEquals(baselineAnswer, output.currentAnswerBuf.toString())
            assertEquals(baselineDeltas, output.currentAnswerDeltas)
            assertEquals(pendingSequence, output.nextAnswerDeltaSequence)
            assertEquals(pendingSegments, output.toolOverlay.snapshot())
            assertEquals("", output.currentThoughtBuf.toString())
            assertNull(output.currentThoughtSignature)
            assertEquals(MessageStatus.SENDING, output.currentStatus)
        }

        harness.run(listOf(
            StreamEvent.ThoughtChunk("Failed thought", signature = "failed_signature"),
            StreamEvent.Retrying(1, 5, resetCurrentAttempt = true),
            StreamEvent.TextChunk(" replacement"),
        ))

        assertEquals(" pending replacement", output.currentAnswerBuf.toString())
        assertEquals(baselineDeltas, output.currentAnswerDeltas.dropLast(1))
        assertEquals(pendingSequence, output.currentAnswerDeltas.last().sequence)
        assertEquals("Baseline thought", output.totalThoughts)
    }

    @Test
    fun `metadata only response read reset removes citation and reasoning usage state`() = runTest {
        val harness = AccumulatorHarness()
        val baselineSegments = harness.output.toolOverlay.snapshot()
        harness.onRetry = {
            assertEquals("", harness.output.totalText.toString())
            assertEquals("", harness.output.totalThoughts)
            assertNull(harness.output.totalThoughtTitle)
            assertEquals(MessageStatus.SENDING, harness.output.currentStatus)
            assertEquals(baselineSegments, harness.output.toolOverlay.snapshot())
            assertNull(harness.output.totalTokenUsage)
            assertEquals(0, harness.output.totalTokenCount)
            assertNull(harness.output.thoughtTiming.liveDurationMs())
        }

        val outcome = harness.run(listOf(
            citation("failed_metadata", ""),
            StreamEvent.UsageUpdate(FAILED_USAGE),
            StreamEvent.Retrying(1, 5, resetCurrentAttempt = true),
            StreamEvent.TextChunk("Replacement"),
        ))

        assertEquals(ProviderPassOutcome.CompletedText(IDENTITY), outcome)
        assertEquals("Replacement", harness.output.totalText.toString())
        assertEquals(baselineSegments, harness.output.toolOverlay.snapshot())
        assertNull(harness.output.totalTokenUsage)
        assertTrue(harness.forwarded.none { it is StreamEvent.Error })
    }

    @Test
    fun `ordinary no output retry drops failed usage and retains completed request usage`() = runTest {
        listOf(false, true).forEach { withPreviousRequest ->
            val harness = AccumulatorHarness()
            if (withPreviousRequest) {
                harness.run(listOf(StreamEvent.TextChunk("Previous answer"), StreamEvent.UsageUpdate(PRIOR_USAGE)))
                harness.output.flushAnswerSegment()
            }
            val expectedUsage = if (withPreviousRequest) PRIOR_USAGE else null
            val previousText = harness.output.totalText.toString()
            val retry = StreamEvent.Retrying(1, 5)
            harness.onRetry = {
                assertEquals(
                    expectedUsage?.withoutWallClockTiming(),
                    harness.output.totalTokenUsage?.withoutWallClockTiming(),
                )
                assertEquals(expectedUsage?.totalTokenCount ?: 0, harness.output.totalTokenCount)
                assertEquals(previousText, harness.output.totalText.toString())
            }

            val outcome = harness.run(listOf(
                StreamEvent.UsageUpdate(FAILED_USAGE.copy(reasoningTokenCount = 0)),
                retry,
                StreamEvent.TextChunk("Replacement"),
            ))

            assertFalse(retry.resetCurrentAttempt)
            assertEquals(ProviderPassOutcome.CompletedText(IDENTITY), outcome)
            assertEquals(
                expectedUsage?.withoutWallClockTiming(),
                harness.output.totalTokenUsage?.withoutWallClockTiming(),
            )
            assertEquals(expectedUsage?.totalTokenCount ?: 0, harness.output.totalTokenCount)
            assertEquals(previousText + "Replacement", harness.output.totalText.toString())
        }
    }

    @Test
    fun `exhausted response read failure keeps only the final attempt partial output`() = runTest {
        val errors = listOf(
            GenerationError.SseParse("data: invalid", "Malformed event"),
            GenerationError.Api(null, null, "The server response could not be read."),
        )
        errors.forEach { error ->
            val harness = AccumulatorHarness()
            var retries = 0
            harness.onRetry = {
                retries += 1
                assertEquals("", harness.output.totalText.toString())
                assertEquals("", harness.output.totalThoughts)
                assertNull(harness.output.totalTokenUsage)
                assertTrue(harness.output.toolOverlay.snapshot().none { it.type == "tool" })
                assertTrue(harness.output.toolOverlay.snapshot().none { it.type == "citation" })
            }
            val finalCall = StreamEvent.ToolCallRequest("call_final", "file_write", "{}")
            val events = buildList {
                repeat(6) { attempt ->
                    add(StreamEvent.ThoughtChunk("Thought $attempt", signature = "signature_$attempt"))
                    add(StreamEvent.TextChunk("Partial $attempt"))
                    add(citation("source_$attempt", "Partial $attempt"))
                    add(StreamEvent.UsageUpdate(REPLACEMENT_USAGE.copy(totalTokenCount = 20 + attempt)))
                    if (attempt < 5) {
                        add(StreamEvent.Retrying(attempt + 1, 5, resetCurrentAttempt = true))
                    } else {
                        add(finalCall)
                        add(StreamEvent.Error(error))
                        add(StreamEvent.TextChunk("Must not be accepted"))
                    }
                }
            }

            val outcome = harness.run(events)

            assertEquals(ProviderPassOutcome.Failed(IDENTITY, error), outcome)
            assertEquals(5, retries)
            assertEquals(1, harness.forwarded.count { it is StreamEvent.Error })
            assertEquals("Partial 5", harness.output.totalText.toString())
            assertEquals("Thought 5", harness.output.totalThoughts)
            assertEquals(25, harness.output.totalTokenCount)
            assertEquals(MessageStatus.ERROR, harness.output.currentStatus)
            assertEquals("The server response could not be read.", harness.output.generationErrorMessage)
            val segments = harness.output.toolOverlay.snapshot()
            assertEquals(listOf("Partial 5"), segments.filter { it.type == "answer" && it.content.isNotEmpty() }.map { it.content })
            assertEquals(listOf("signature_5"), segments.filter { it.type == "thought" }.map { it.signature })
            assertEquals(listOf("source_5"), segments.mapNotNull { it.toCitationRecord() }.map { it.providerSourceId })
            assertEquals(ToolExecutionStates.FAILED, segments.single { it.type == "tool" }.toolState)
        }
    }

    /** Wall-clock figures are measured in real time, so constant fixtures cannot pin them. */
    private fun TokenUsage.withoutWallClockTiming(): TokenUsage =
        copy(timeToFirstTokenMs = null, totalGenerationMs = null)

    private class AccumulatorHarness {
        val output = GenerationOutputAccumulator(
            object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String, arguments: String?) = null
            },
            "provider",
        )
        val forwarded = mutableListOf<StreamEvent>()
        var onRetry: () -> Unit = {}
        var onStreamUpdate: () -> Unit = {}
        private val gate = StreamingUiUpdateGate()
        private val context = mockk<Context>(relaxed = true) {
            every { getString(R.string.generation_retry_attempt, any(), any()) } returns "Retrying"
            every { getString(R.string.generation_error_sse_parse) } returns "The server response could not be read."
        }

        suspend fun run(
            events: List<StreamEvent>,
            identity: RunEffectIdentity = IDENTITY,
        ): ProviderPassOutcome {
            val provider = object : LlmProvider {
                override val name: String = "provider"
                override val defaultBaseUrl: String = "https://example.invalid"
                override fun generateResponse(
                    messages: List<ChatMessage>,
                    config: ProviderConfig,
                ): Flow<StreamEvent> = flowOf(*events.toTypedArray())
                override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = emptyList()
            }
            val answerStart = output.totalText.length
            output.beginProviderRequest()
            try {
                return ProviderPassRunner().run(
                    identity,
                    provider,
                    listOf(ChatMessage(text = "input", participant = Participant.USER)),
                    CONFIG,
                ) { event ->
                    forwarded += event
                    output.handleStreamEvent(
                        event, answerStart, provider, context, emptyMap(), gate,
                        publishStreamUpdate = { onStreamUpdate() },
                        publishRetrySnapshot = { onRetry() },
                    )
                }
            } finally {
                output.tokenUsageAccumulator.finishRequest()
                output.totalTokenUsage = output.tokenUsageAccumulator.snapshot()
                output.totalTokenCount = output.totalTokenUsage?.totalTokenCount ?: 0
            }
        }
    }

    private companion object {
        val IDENTITY = RunEffectIdentity("conversation", 7, "run", 3, "provider-3-0")
        val CONFIG = ProviderConfig(
            apiKey = "key",
            modelId = "model",
            tools = listOf("file_read", "file_write").map { name ->
                ToolDefinition(function = ToolFunction(
                    name = name,
                    description = name,
                    parameters = ToolParameters(properties = emptyMap()),
                ))
            },
        )
        val PRIOR_USAGE = TokenUsage(
            totalTokenCount = 100,
            inputTokenCount = 80,
            cachedInputTokenCount = 20,
            cacheWriteInputTokenCount = 5,
            uncachedInputTokenCount = 60,
            outputTokenCount = 20,
            reasoningTokenCount = 4,
            generationDurationMs = 40L,
        )
        val FAILED_USAGE = PRIOR_USAGE.copy(totalTokenCount = 999, generationDurationMs = 999L)
        val REPLACEMENT_USAGE = TokenUsage(
            totalTokenCount = 20,
            inputTokenCount = 12,
            cachedInputTokenCount = 2,
            cacheWriteInputTokenCount = 0,
            uncachedInputTokenCount = 10,
            outputTokenCount = 8,
            reasoningTokenCount = 1,
            generationDurationMs = 10L,
        )

        fun citation(sourceId: String, text: String) = StreamEvent.CitationUpdate(
            CitationRecord(
                sourceId = sourceId,
                provider = "test",
                kind = "url",
                title = "Source $sourceId",
                providerSourceId = sourceId,
                anchors = listOf(CitationAnchor(0, text.length, text)),
            ),
        )
    }
}
