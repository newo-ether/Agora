package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.api.util.MALFORMED_TOOL_CALL_NAME
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffectIdentity
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPassRunnerTest {
    @Test
    fun `citation-only stream forwards metadata and completes without tool authority`() = runTest {
        val event = StreamEvent.CitationUpdate(
            CitationRecord(
                sourceId = "citation_source",
                provider = "test",
                kind = "url",
                title = "Source",
            ),
        )
        val forwarded = mutableListOf<StreamEvent>()

        val outcome = runner(listOf(event)).run(IDENTITY, messages(), CONFIG, forwarded::add)

        assertEquals(ProviderPassOutcome.CompletedText(IDENTITY), outcome)
        assertEquals(listOf(event), forwarded)
    }

    @Test
    fun `closed text stream returns identified CompletedText and forwards events`() = runTest {
        val events = listOf<StreamEvent>(
            StreamEvent.TextChunk("answer"),
            StreamEvent.UsageUpdate(12),
        )
        val forwarded = mutableListOf<StreamEvent>()

        val outcome = runner(events).run(IDENTITY, messages(), CONFIG, forwarded::add)

        assertEquals(ProviderPassOutcome.CompletedText(IDENTITY), outcome)
        assertEquals(events, forwarded)
    }

    @Test
    fun `only a complete unique tool batch becomes authoritative`() = runTest {
        val update = StreamEvent.ToolCallUpdate(
            streamKey = "stream_1",
            id = "call_1",
            name = "file_read",
            arguments = "{}",
        )
        val first = StreamEvent.ToolCallRequest(
            id = "call_1",
            name = "file_read",
            arguments = "{}",
            streamKey = "stream_1",
        )
        val second = StreamEvent.ToolCallRequest(
            id = "call_2",
            name = "file_write",
            arguments = "{\"path\":\"a\"}",
            streamKey = "stream_2",
        )

        val outcome = runner(
            listOf(update, StreamEvent.ToolCallsRequest(listOf(first, second))),
        ).run(IDENTITY, messages(), CONFIG) {}

        assertEquals(
            ProviderPassOutcome.CompletedToolCalls(IDENTITY, listOf(first, second)),
            outcome,
        )
    }

    @Test
    fun `equivalent text and native calls with the same id keep native authority`() = runTest {
        val native = StreamEvent.ToolCallRequest(
            id = "call_1",
            name = "file_read",
            arguments = "{ \"path\": \"a.txt\" }",
            streamKey = "native_stream",
        )
        val forwarded = mutableListOf<StreamEvent>()

        val outcome = runner(
            listOf(
                StreamEvent.TextChunk(
                    "<tool_call>{\"id\":\"call_1\",\"name\":\"file_read\"," +
                        "\"arguments\":{\"path\":\"a.txt\"}}</tool_call>"
                ),
                native,
            )
        ).run(IDENTITY, messages(), CONFIG, forwarded::add)

        assertEquals(ProviderPassOutcome.CompletedToolCalls(IDENTITY, listOf(native)), outcome)
        assertEquals(listOf(native), forwarded.filterIsInstance<StreamEvent.ToolCallRequest>())
        assertTrue(forwarded.none { it is StreamEvent.Error })
    }

    @Test
    fun `malformed duplicate incomplete and empty tool batches stay pairable`() = runTest {
        val valid = StreamEvent.ToolCallRequest("call_1", "file_read", "{}", streamKey = "s1")
        val invalidStreams = listOf(
            listOf<StreamEvent>(
                valid,
                valid.copy(streamKey = "s2"),
            ),
            listOf<StreamEvent>(valid.copy(name = "bad name")),
            listOf<StreamEvent>(valid.copy(id = "bad id")),
            listOf<StreamEvent>(valid.copy(streamKey = "")),
            listOf<StreamEvent>(
                valid,
                valid.copy(id = "call_2"),
            ),
            listOf<StreamEvent>(
                StreamEvent.ToolCallUpdate("open", null, "file_read", "{"),
            ),
            listOf<StreamEvent>(StreamEvent.ToolCallsRequest(emptyList())),
        )

        // Identity damage is answered as malformed calls; a reused or blank stream key is local
        // bookkeeping and only gets a fresh key.
        val expectedMalformed = listOf(1, 1, 1, 0, 0, 1, 1)
        invalidStreams.zip(expectedMalformed).forEach { (streamEvents, malformedCount) ->
            val forwarded = mutableListOf<StreamEvent>()
            val outcome = runner(streamEvents).run(
                IDENTITY,
                messages(),
                CONFIG,
                forwarded::add,
            )

            val calls = (outcome as ProviderPassOutcome.CompletedToolCalls).calls
            assertEquals(malformedCount, calls.count { it.name == MALFORMED_TOOL_CALL_NAME })
            assertEquals(calls.size, calls.map { it.id }.distinct().size)
            assertEquals(calls.size, calls.map { it.streamKey }.distinct().size)
            assertTrue(calls.all { it.streamKey.isNotBlank() })
            assertTrue(forwarded.none { it is StreamEvent.Error })
        }
    }

    @Test
    fun `unparsable arguments stay pairable and are reported by the tool executor`() = runTest {
        val malformed = StreamEvent.ToolCallRequest("call_1", "file_read", "{", streamKey = "s1")
        val forwarded = mutableListOf<StreamEvent>()

        val outcome = runner(listOf(malformed)).run(IDENTITY, messages(), CONFIG, forwarded::add)

        assertEquals(ProviderPassOutcome.CompletedToolCalls(IDENTITY, listOf(malformed)), outcome)
        assertTrue(forwarded.none { it is StreamEvent.Error })
    }

    @Test
    fun `provider error closes as Truncated or Failed without tool authority`() = runTest {
        val truncated = GenerationError.OutputTruncated("provider", "length")
        val apiError = GenerationError.Api("bad", "request", "failure")

        assertEquals(
            ProviderPassOutcome.Truncated(IDENTITY, truncated),
            runner(listOf(StreamEvent.Error(truncated))).run(IDENTITY, messages(), CONFIG) {},
        )
        assertEquals(
            ProviderPassOutcome.Failed(IDENTITY, apiError),
            runner(listOf(StreamEvent.Error(apiError))).run(IDENTITY, messages(), CONFIG) {},
        )
    }

    @Test
    fun `provider exception becomes a forwarded identified failure`() = runTest {
        val failure = IllegalStateException("transport failed")
        val forwarded = mutableListOf<StreamEvent>()
        val provider = fakeProvider(flow {
            throw failure
        })

        val outcome = ProviderPassRunner().run(
            IDENTITY,
            provider,
            messages(),
            CONFIG,
            forwarded::add,
        )

        assertTrue(outcome is ProviderPassOutcome.Failed)
        assertSame(failure, (outcome as ProviderPassOutcome.Failed).error.let {
            (it as GenerationError.Unknown).cause
        })
        assertTrue(forwarded.single() is StreamEvent.Error)
    }

    @Test
    fun `terminal provider error closes the pass and rejects later events`() = runTest {
        val apiError = GenerationError.Api("bad", "request", "failure")
        val forwarded = mutableListOf<StreamEvent>()
        val provider = fakeProvider(flow {
            emit(StreamEvent.Error(apiError))
            emit(StreamEvent.TextChunk("must not be accepted"))
            throw IllegalStateException("must not be reached")
        })

        val outcome = ProviderPassRunner().run(
            IDENTITY,
            provider,
            messages(),
            CONFIG,
            forwarded::add,
        )

        assertEquals(ProviderPassOutcome.Failed(IDENTITY, apiError), outcome)
        assertEquals(listOf(StreamEvent.Error(apiError)), forwarded)
    }

    @Test
    fun `cancellation closes as Cancelled while consumer failures still propagate`() = runTest {
        val cancelledProvider = fakeProvider(flow {
            throw CancellationException("cancel")
        })
        assertEquals(
            ProviderPassOutcome.Cancelled(IDENTITY),
            ProviderPassRunner().run(
                IDENTITY,
                cancelledProvider,
                messages(),
                CONFIG,
            ) {},
        )

        val consumerFailure = IllegalArgumentException("consumer")
        try {
            runner(listOf(StreamEvent.TextChunk("answer"))).run(
                IDENTITY,
                messages(),
                CONFIG,
            ) { throw consumerFailure }
            throw AssertionError("Expected the consumer failure")
        } catch (actual: IllegalArgumentException) {
            assertSame(consumerFailure, actual)
        }
    }

    @Test
    fun `provider pass normalizes answer stranded behind implicit thought close`() = runTest {
        val error = GenerationError.OutputTruncated("provider", "max_tokens")
        val forwarded = mutableListOf<StreamEvent>()

        val outcome = runner(
            listOf(
                StreamEvent.ThoughtChunk(
                    thought = "Let me answer.</thinking>没有理他。",
                    title = "Thinking",
                    signature = "signature",
                ),
                StreamEvent.Error(error),
            )
        ).run(IDENTITY, messages(), CONFIG, forwarded::add)

        assertEquals(ProviderPassOutcome.Truncated(IDENTITY, error), outcome)
        assertEquals(
            listOf(
                StreamEvent.ThoughtChunk("Let me answer.", "Thinking", "signature"),
                StreamEvent.TextChunk("没有理他。"),
                StreamEvent.Error(error),
            ),
            forwarded,
        )
    }

    @Test
    fun `retry drops only failed formatting before the real accumulator and preserves prior tool pass`() = runTest {
        val prefixes = listOf(
            listOf<StreamEvent>(StreamEvent.TextChunk(" \t\n")),
            listOf<StreamEvent>(StreamEvent.ThoughtChunk(" \t\n", title = "Failed attempt")),
            listOf<StreamEvent>(StreamEvent.TextChunk("  "), StreamEvent.ThoughtChunk("\n")),
        )
        listOf(CONFIG.copy(tools = null), CONFIG).forEach { config ->
            prefixes.forEach { prefix ->
                val output = GenerationOutputAccumulator(
                    object : GenerationToolPresentationSource {
                        override fun presentationMetadata(name: String, arguments: String?) = null
                    },
                    "provider",
                )
                val context = mockk<Context>(relaxed = true)
                val provider = fakeProvider(flowOf())
                val gate = StreamingUiUpdateGate()
                var providerAnswerStart = 0
                val retrySnapshots = mutableListOf<String>()
                val consume: suspend (StreamEvent) -> Unit = { event ->
                    output.handleStreamEvent(
                        event, providerAnswerStart, provider, context, emptyMap(), gate,
                        publishStreamUpdate = {},
                        publishRetrySnapshot = { retrySnapshots += output.totalText.toString() },
                    )
                }
                val call = StreamEvent.ToolCallRequest("call_previous", "file_read", "{}", streamKey = "previous")
                val citation = StreamEvent.CitationUpdate(
                    CitationRecord(sourceId = "previous_source", provider = "test", kind = "url", title = "Source"),
                )
                output.beginProviderRequest()
                val priorOutcome = runner(listOf(
                    StreamEvent.ThoughtChunk("Previous thought", signature = "previous_signature"),
                    StreamEvent.TextChunk("Previous answer\n"), citation, call,
                )).run(IDENTITY, messages(), config, consume)
                assertTrue(priorOutcome is ProviderPassOutcome.CompletedToolCalls)
                output.tokenUsageAccumulator.finishRequest()
                output.flushAnswerSegment()
                output.flushThoughtSegment()
                output.currentStatus = MessageStatus.SENDING
                val priorSegments = output.toolOverlay.snapshot()
                providerAnswerStart = output.totalText.length
                output.beginProviderRequest()

                val outcome = runner(
                    prefix + StreamEvent.Retrying(1, 5) + prefix + StreamEvent.Retrying(2, 5) +
                        listOf(StreamEvent.TextChunk("    \n"), StreamEvent.TextChunk("ok")),
                ).run(IDENTITY.copy(pass = 4, effectId = "provider-4-0"), messages(), config, consume)
                output.tokenUsageAccumulator.finishRequest()

                assertTrue(outcome is ProviderPassOutcome.CompletedText)
                assertEquals(listOf("Previous answer\n", "Previous answer\n"), retrySnapshots)
                assertEquals("Previous answer\n    \nok", output.totalText.toString())
                assertEquals("    \nok", output.currentAnswerBuf.toString())
                assertEquals("Previous thought", output.totalThoughts)
                assertEquals("", output.currentThoughtBuf.toString())
                assertEquals(priorSegments, output.toolOverlay.snapshot())
                assertTrue(priorSegments.any { it.signature == "previous_signature" })
                assertTrue(priorSegments.any { it.type == "tool" })
                assertTrue(priorSegments.any { it.type == "citation" })
            }
        }
    }

    @Test
    fun `successful thought prefix signature and whitespace only completion stay exact`() = runTest {
        listOf(CONFIG.copy(tools = null), CONFIG).forEach { config ->
            val thoughts = mutableListOf<StreamEvent>()
            runner(listOf(
                StreamEvent.ThoughtChunk("\n\n", title = "Failed attempt"),
                StreamEvent.Retrying(1, 5),
                StreamEvent.ThoughtChunk(" \t\n"),
                StreamEvent.ThoughtChunk("reason", signature = "signature"),
            )).run(IDENTITY, messages(), config, thoughts::add)
            assertEquals(" \t\nreason", thoughts.filterIsInstance<StreamEvent.ThoughtChunk>().joinToString("") { it.thought })
            assertTrue(thoughts.filterIsInstance<StreamEvent.ThoughtChunk>().any { it.signature == "signature" })

            val blank = mutableListOf<StreamEvent>()
            runner(listOf(StreamEvent.TextChunk(" \t\n"))).run(IDENTITY, messages(), config, blank::add)
            assertEquals(" \t\n", blank.filterIsInstance<StreamEvent.TextChunk>().joinToString("") { it.text })
        }
    }

    @Test
    fun `successful formatting keeps usage and citation order before real accumulation`() = runTest {
        val output = GenerationOutputAccumulator(
            object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String, arguments: String?) = null
            },
            "provider",
        )
        val context = mockk<Context>(relaxed = true)
        val provider = fakeProvider(flowOf())
        val gate = StreamingUiUpdateGate()
        val events = listOf(
            StreamEvent.TextChunk("    "),
            StreamEvent.UsageUpdate(12, thoughtsTokenCount = 3),
            StreamEvent.CitationUpdate(
                CitationRecord(sourceId = "prefix_source", provider = "test", kind = "url", title = "Source"),
            ),
            StreamEvent.TextChunk("ok"),
        )
        val forwarded = mutableListOf<StreamEvent>()

        output.beginProviderRequest()
        val outcome = runner(events).run(IDENTITY, messages(), CONFIG.copy(tools = null)) { event ->
            forwarded += event
            output.handleStreamEvent(
                event, 0, provider, context, emptyMap(), gate,
                publishStreamUpdate = {}, publishRetrySnapshot = {},
            )
        }
        output.tokenUsageAccumulator.finishRequest()

        assertEquals(ProviderPassOutcome.CompletedText(IDENTITY), outcome)
        assertEquals(events, forwarded)
        assertEquals("    ok", output.totalText.toString())
        assertEquals("    ok", output.currentAnswerBuf.toString())
        assertTrue(output.totalTokenCount > 0)
        assertTrue(output.toolOverlay.snapshot().any { it.type == "citation" })
    }

    @Test
    fun `held formatting preserves metadata at retry and terminal boundaries`() = runTest {
        val usage = StreamEvent.UsageUpdate(12)
        val citation = StreamEvent.CitationUpdate(
            CitationRecord(sourceId = "held_source", provider = "test", kind = "url", title = "Source"),
        )
        val prefix = listOf(StreamEvent.TextChunk("  "), usage, citation)
        val retry = StreamEvent.Retrying(1, 5)
        val error = StreamEvent.Error(GenerationError.Api("bad", "request", "failure"))
        val cases = listOf(
            (prefix + retry + StreamEvent.TextChunk("ok")) to
                listOf(usage, citation, retry, StreamEvent.TextChunk("ok")),
            (prefix + error) to (prefix + error),
            prefix to prefix,
        )
        cases.forEach { (events, expected) ->
            val forwarded = mutableListOf<StreamEvent>()
            runner(events).run(IDENTITY, messages(), CONFIG.copy(tools = null), forwarded::add)
            assertEquals(expected, forwarded)
        }
    }

    private fun runner(events: List<StreamEvent>) = ProviderPassRunnerHarness(
        ProviderPassRunner(),
        fakeProvider(flowOf(*events.toTypedArray())),
    )

    private fun fakeProvider(events: Flow<StreamEvent>) = object : LlmProvider {
        override val name: String = "provider"
        override val defaultBaseUrl: String = "https://example.invalid"

        override fun generateResponse(
            messages: List<ChatMessage>,
            config: ProviderConfig,
        ): Flow<StreamEvent> = events

        override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = emptyList()
    }

    private fun messages() = listOf(
        ChatMessage(text = "input", participant = Participant.USER),
    )

    private class ProviderPassRunnerHarness(
        private val runner: ProviderPassRunner,
        private val provider: LlmProvider,
    ) {
        suspend fun run(
            identity: RunEffectIdentity,
            messages: List<ChatMessage>,
            config: ProviderConfig,
            onEvent: suspend (StreamEvent) -> Unit,
        ): ProviderPassOutcome = runner.run(identity, provider, messages, config, onEvent)
    }

    private companion object {
        val IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 7,
            runId = "run",
            pass = 3,
            effectId = "provider-3-0",
        )
        val CONFIG = ProviderConfig(
            apiKey = "key",
            modelId = "model",
            tools = listOf("file_read", "file_write").map { name ->
                ToolDefinition(
                    function = ToolFunction(
                        name = name,
                        description = name,
                        parameters = ToolParameters(properties = emptyMap()),
                    )
                )
            },
        )
    }
}
