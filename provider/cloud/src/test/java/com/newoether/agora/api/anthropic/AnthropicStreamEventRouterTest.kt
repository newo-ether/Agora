package com.newoether.agora.api.anthropic

import com.newoether.agora.api.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicStreamEventRouterTest {
    @Test
    fun toolBlock_createsSegmentAtStartAndOwnsEveryFollowingDelta() {
        val router = AnthropicStreamEventRouter()

        val started = router.route(
            AnthropicStreamEvent(
                type = "content_block_start",
                index = 3,
                contentBlock = AnthropicContentBlock(
                    type = "tool_use",
                    id = "call-3",
                    name = "any_tool",
                ),
            )
        )
        val initial = started.single() as StreamEvent.ToolCallUpdate
        assertEquals("call-3", initial.streamKey)
        assertEquals("any_tool", initial.name)
        assertEquals("", initial.arguments)

        val mirroredText = router.route(
            AnthropicStreamEvent(
                type = "content_block_delta",
                index = 3,
                delta = AnthropicDelta(
                    type = "text_delta",
                    text = "transport-owned display data",
                ),
            )
        )
        assertTrue(mirroredText.isEmpty())

        val argumentDelta = router.route(
            AnthropicStreamEvent(
                type = "content_block_delta",
                index = 3,
                delta = AnthropicDelta(
                    type = "input_json_delta",
                    partialJson = """{"value":"partial"}""",
                ),
            )
        ).single() as StreamEvent.ToolCallUpdate
        assertEquals("""{"value":"partial"}""", argumentDelta.arguments)

        val completed = router.route(
            AnthropicStreamEvent(
                type = "content_block_stop",
                index = 3,
            )
        ).single() as StreamEvent.ToolCallRequest
        assertEquals(initial.streamKey, completed.streamKey)
        assertEquals(argumentDelta.arguments, completed.arguments)
    }

    @Test
    fun multipleToolBlocks_areTrackedAndCompletedByIndex() {
        val router = AnthropicStreamEventRouter()
        router.route(toolStart(index = 1, id = "call-1", name = "first"))
        router.route(toolStart(index = 2, id = "call-2", name = "second"))
        router.route(toolArguments(index = 1, delta = """{"a":1}"""))
        router.route(toolArguments(index = 2, delta = """{"b":2}"""))

        val second = router.route(toolStop(index = 2)).single() as StreamEvent.ToolCallRequest
        val first = router.route(toolStop(index = 1)).single() as StreamEvent.ToolCallRequest

        assertEquals("call-2", second.id)
        assertEquals("""{"b":2}""", second.arguments)
        assertEquals("call-1", first.id)
        assertEquals("""{"a":1}""", first.arguments)
        assertTrue(router.reportIncompleteBlocks().isEmpty())
    }

    @Test
    fun textAndThinkingBlocksRemainIndependent() {
        val router = AnthropicStreamEventRouter()
        router.route(
            AnthropicStreamEvent(
                type = "content_block_start",
                index = 0,
                contentBlock = AnthropicContentBlock(type = "text"),
            )
        )
        val text = router.route(
            AnthropicStreamEvent(
                type = "content_block_delta",
                index = 0,
                delta = AnthropicDelta(type = "text_delta", text = "answer"),
            )
        ).single()
        assertEquals(StreamEvent.TextChunk("answer"), text)

        router.route(
            AnthropicStreamEvent(
                type = "content_block_start",
                index = 1,
                contentBlock = AnthropicContentBlock(type = "thinking"),
            )
        )
        val thought = router.route(
            AnthropicStreamEvent(
                type = "content_block_delta",
                index = 1,
                delta = AnthropicDelta(type = "thinking_delta", thinking = "reasoning"),
            )
        ).single()
        assertEquals(StreamEvent.ThoughtChunk("reasoning"), thought)
    }

    @Test
    fun whitespaceThinkingIsPreservedWhileBlankSignatureIsIgnored() {
        val router = AnthropicStreamEventRouter()
        val started = router.route(
            AnthropicStreamEvent(
                type = "content_block_start",
                index = 1,
                contentBlock = AnthropicContentBlock(
                    type = "thinking",
                    thinking = " ",
                    signature = "signature-1",
                ),
            )
        )
        val blankSignature = router.route(
            AnthropicStreamEvent(
                type = "content_block_delta",
                index = 1,
                delta = AnthropicDelta(type = "signature_delta", signature = " "),
            )
        )
        val blankThought = router.route(
            AnthropicStreamEvent(
                type = "content_block_delta",
                index = 1,
                delta = AnthropicDelta(type = "thinking_delta", thinking = " "),
            )
        )
        val thought = router.route(
            AnthropicStreamEvent(
                type = "content_block_delta",
                index = 1,
                delta = AnthropicDelta(type = "thinking_delta", thinking = "reasoning"),
            )
        ).single() as StreamEvent.ThoughtChunk

        // Whitespace-only thinking carries real formatting (newlines, indentation, fence
        // boundaries), so it must reach the UI verbatim. Only the signature keeps the
        // blank-rejecting contract, because a blank signature is never a valid credential.
        val startedThought = started.single() as StreamEvent.ThoughtChunk
        assertEquals(" ", startedThought.thought)
        assertEquals("signature-1", startedThought.signature)
        assertTrue(blankSignature.isEmpty())
        val blankThoughtChunk = blankThought.single() as StreamEvent.ThoughtChunk
        assertEquals(" ", blankThoughtChunk.thought)
        assertEquals("signature-1", blankThoughtChunk.signature)
        assertEquals("reasoning", thought.thought)
        assertEquals("signature-1", thought.signature)
    }

    @Test
    fun usageSeparatesCacheReadFromUncachedInput() {
        val router = AnthropicStreamEventRouter()
        router.route(
            AnthropicStreamEvent(
                type = "message_start",
                message = AnthropicMessageInfo(
                    usage = AnthropicUsage(
                        inputTokens = 100,
                        cacheCreationInputTokens = 20,
                        cacheReadInputTokens = 30,
                    )
                ),
            )
        )

        val event = router.route(
            AnthropicStreamEvent(
                type = "message_delta",
                usage = AnthropicUsage(outputTokens = 40),
            )
        ).single() as StreamEvent.UsageUpdate

        assertEquals(190, event.usage.totalTokenCount)
        assertEquals(150, event.usage.inputTokenCount)
        assertEquals(30, event.usage.cachedInputTokenCount)
        assertEquals(20, event.usage.cacheWriteInputTokenCount)
        assertEquals(120, event.usage.uncachedInputTokenCount)
        assertEquals(40, event.usage.outputTokenCount)
    }

    @Test
    fun usageFromTerminalDeltaWhenStartCarriesPlaceholderZeros() {
        // Relay endpoints report all-zero placeholders in message_start and the real input/
        // cache counts only in the terminal message_delta. The router must adopt the delta's
        // values so a cache hit is never silently reported as zero.
        val router = AnthropicStreamEventRouter()
        router.route(
            AnthropicStreamEvent(
                type = "message_start",
                message = AnthropicMessageInfo(
                    usage = AnthropicUsage(
                        inputTokens = 2,
                        cacheCreationInputTokens = 0,
                        cacheReadInputTokens = 0,
                        outputTokens = 1,
                    )
                ),
            )
        )

        val event = router.route(
            AnthropicStreamEvent(
                type = "message_delta",
                usage = AnthropicUsage(
                    inputTokens = 1,
                    cacheCreationInputTokens = 370,
                    cacheReadInputTokens = 3740,
                    outputTokens = 8,
                ),
            )
        ).single() as StreamEvent.UsageUpdate

        assertEquals(3740, event.usage.cachedInputTokenCount)
        assertEquals(370, event.usage.cacheWriteInputTokenCount)
        assertEquals(371, event.usage.uncachedInputTokenCount)
        assertEquals(4111, event.usage.inputTokenCount)
        assertEquals(8, event.usage.outputTokenCount)
        assertEquals(4119, event.usage.totalTokenCount)
    }

    private fun toolStart(index: Int, id: String, name: String) = AnthropicStreamEvent(
        type = "content_block_start",
        index = index,
        contentBlock = AnthropicContentBlock(type = "tool_use", id = id, name = name),
    )

    private fun toolArguments(index: Int, delta: String) = AnthropicStreamEvent(
        type = "content_block_delta",
        index = index,
        delta = AnthropicDelta(type = "input_json_delta", partialJson = delta),
    )

    private fun toolStop(index: Int) = AnthropicStreamEvent(
        type = "content_block_stop",
        index = index,
    )
}
