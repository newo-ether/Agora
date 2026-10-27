package com.newoether.agora.viewmodel

import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.model.citationRecords
import com.newoether.agora.tool.ToolExecutionEvent
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.tool.ToolPresentationMetadata
import com.newoether.agora.tool.ToolProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationToolBatchEffectExecutorTest {
    @Test
    fun `malformed citation segments fail closed during overlay restore and append`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String, arguments: String?) = null
            },
            providerName = "provider",
        )
        val answer = MessageSegment(type = "answer", content = "answer")
        val malformed = MessageSegment(type = "citation", content = "{")

        overlay.replaceAll(listOf(answer, malformed))
        overlay.append(malformed)

        assertEquals(listOf(answer), overlay.snapshot())
    }

    @Test
    fun `late thought signature updates the completed thought without adding a segment`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String, arguments: String?) = null
            },
            providerName = "provider",
        )
        overlay.append(MessageSegment(type = "thought", content = "reason"))
        overlay.append(MessageSegment(type = "answer", content = "answer"))

        assertTrue(
            overlay.updateLastThoughtMetadata(
                signature = "signature",
                signatureProvider = "provider",
            )
        )

        val snapshot = overlay.snapshot()
        assertEquals(listOf("answer", "thought", "answer"), snapshot.map { it.type })
        assertEquals("signature", snapshot[1].signature)
        assertEquals("provider", snapshot[1].signatureProvider)
    }

    @Test
    fun `overlay uniquely owns stream indices metadata and terminal presentation`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String, arguments: String?) =
                    ToolPresentationMetadata(displayName = "Display $name", target = "initial")
            },
            providerName = "provider",
        )

        assertTrue(overlay.upsert("stream", null, "tool", "{", "signature"))
        assertEquals(false, overlay.upsert("stream", "call", "", "{}", null))
        overlay.start(call())
        overlay.applyProgress("call", ToolExecutionEvent.TargetResolved("resolved"))
        overlay.applyProgress("call", ToolExecutionEvent.OutputDelta("partial"))
        overlay.applyProgress("call", ToolExecutionEvent.OutputSnapshot("first\n"))
        overlay.applyProgress("call", ToolExecutionEvent.OutputSnapshot("first\nsecond\n"))
        assertEquals(
            "first\nsecond\n",
            overlay.snapshot().single { it.type == "tool" }.toolProgress,
        )
        val completed = overlay.complete(
            call(),
            ToolExecutionResult(text = "done", displayText = "shown"),
        )

        assertEquals("done", completed.data.result)
        assertEquals("Display tool", completed.data.displayName)
        assertEquals("resolved", completed.segment.toolTarget)
        assertEquals("first\nsecond\n", completed.segment.toolProgress)
        assertEquals(ToolExecutionStates.SUCCEEDED, completed.segment.toolState)
        assertEquals("provider", completed.segment.signatureProvider)

        overlay.replaceAll(emptyList())
        assertNull(overlay.snapshot().singleOrNull())
    }

    @Test
    fun `router arguments reach all overlay metadata boundaries and exact execution title persists`() {
        val observed = mutableListOf<Pair<String, String?>>()
        val source = object : GenerationToolPresentationSource {
            override fun presentationMetadata(name: String, arguments: String?): ToolPresentationMetadata? {
                observed += name to arguments
                return null
            }
        }
        val overlay = GenerationToolOverlay(source, "provider")
        val prefix = """{"arguments":{"content":""""
        val full = prefix + "x".repeat(10_000) + """"},"tool_id":"mcp_server_read_ab1234"}"""
        val request = call(arguments = full).copy(name = "mcp_call_tool")
        overlay.upsert("stream", "call", "mcp_call_tool", prefix, null)
        overlay.upsert("stream", "call", "", full, null)
        overlay.start(request)
        assertEquals(listOf("mcp_call_tool" to prefix, "mcp_call_tool" to full,
            "mcp_call_tool" to full), observed)
        overlay.applyProgress("call", ToolExecutionEvent.TargetResolved("server", "filesystem/read_file"))
        overlay.applyProgress("call", ToolExecutionEvent.TargetResolved("exact server"))
        val completed = overlay.complete(request, ToolExecutionResult("done"))
        assertEquals("filesystem/read_file", completed.segment.toolDisplayName)
        assertEquals("filesystem/read_file", completed.data.displayName)
        assertEquals("exact server", completed.data.target)
        assertEquals("mcp_call_tool", completed.data.toolName)
        assertEquals(full, completed.data.arguments)
    }

    @Test
    fun `executor forwards raw arguments to provider metadata`() {
        val observed = mutableListOf<Pair<String, String?>>()
        val provider = object : ToolProvider {
            override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()
            override fun handles(name: String) = true
            override suspend fun execute(name: String, arguments: String, ctx: GenerationContext) = "done"
            override fun presentationMetadata(name: String, arguments: String?): ToolPresentationMetadata? {
                observed += name to arguments
                return ToolPresentationMetadata("remote", "server")
            }
        }
        val executor = GenerationToolExecutor.forTest(listOf(provider))
        val arguments = """{"tool_id":"complete","arguments":{}}"""
        assertEquals(ToolPresentationMetadata("remote", "server"),
            executor.presentationMetadata("mcp_call_tool", arguments))
        assertEquals(listOf("mcp_call_tool" to arguments), observed)
    }

    @Test
    fun `citation overlay preserves first source order and merges repeated terminal updates`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String, arguments: String?) = null
            },
            providerName = "provider",
        )
        val answer = "Alpha Beta"
        fun citation(
            title: String,
            url: String,
            anchor: CitationAnchor? = null,
        ) = requireNotNull(
            CitationPolicy.create(
                provider = "test",
                kind = "web",
                title = title,
                url = url,
                anchors = listOfNotNull(anchor),
                answerText = answer,
            ),
        )
        val firstInitial = citation("First", "https://example.com/first")
        val second = citation(
            "Second",
            "https://example.com/second",
            CitationAnchor(6, 10, "Beta"),
        )
        val firstFinal = citation(
            "First",
            "https://example.com/first",
            CitationAnchor(0, 5, "Alpha"),
        )

        assertTrue(overlay.upsertCitation(firstInitial))
        assertTrue(overlay.upsertCitation(second))
        assertTrue(overlay.upsertCitation(firstFinal))
        assertEquals(false, overlay.upsertCitation(firstFinal))

        val snapshot = overlay.snapshot()
        assertEquals(listOf("answer", "citation", "citation"), snapshot.map { it.type })
        val citations = snapshot.citationRecords(answer)
        assertEquals(listOf("First", "Second"), citations.map { it.title })
        assertEquals(listOf(CitationAnchor(0, 5, "Alpha")), citations[0].anchors)
        assertEquals(listOf(CitationAnchor(6, 10, "Beta")), citations[1].anchors)
    }

    @Test
    fun `provider hosted tool lifecycle becomes one terminal display segment`() {
        val overlay = GenerationToolOverlay(
            presentation = object : GenerationToolPresentationSource {
                override fun presentationMetadata(name: String, arguments: String?) =
                    ToolPresentationMetadata(displayName = "OpenAI Search")
            },
            providerName = "OpenAI",
        )
        val active = StreamEvent.HostedToolCallUpdate(
            streamKey = "ws_1",
            name = "openai_search",
            arguments = "{}",
        )

        assertTrue(overlay.upsertHosted(active))
        assertEquals(ToolExecutionStates.RUNNING, overlay.snapshot().last().toolState)
        assertEquals(
            false,
            overlay.upsertHosted(
                active.copy(
                    arguments = """{"type":"search","query":"latest Agora"}""",
                    result = """{"type":"web_search_call","status":"completed"}""",
                ),
            ),
        )

        val segment = overlay.snapshot().last()
        assertEquals("openai_search", segment.toolName)
        assertEquals("OpenAI Search", segment.toolDisplayName)
        assertEquals(ToolExecutionStates.SUCCEEDED, segment.toolState)
        assertTrue(segment.toolResult?.contains("web_search_call") == true)
    }

    @Test
    fun `snapshot clipping never starts with half of a surrogate pair`() {
        assertEquals("😀b", takeLastWholeCodePoints("a😀b", 3))
        assertEquals("b", takeLastWholeCodePoints("a😀b", 2))
        assertEquals("", takeLastWholeCodePoints("😀", 1))
        assertEquals("😀", takeLastWholeCodePoints("😀", 2))
    }

    @Test
    fun `accepted batch executes in order and returns the same identity without continuation`() = runTest {
        val provider = StreamingToolProvider()
        val tools = GenerationToolExecutor.forTest(listOf(provider))
        var now = 0L
        val executor = GenerationToolBatchEffectExecutor(tools) { now += 100L; now }
        val overlay = GenerationToolOverlay(tools, "provider")
        overlay.upsert("stream", "call", "tool", "{}", null)
        val forces = mutableListOf<Boolean>()
        val publishedAt = mutableListOf<Long>()

        val outcome = executor.execute(
            request = AuthorizedToolBatchRequest(
                effect = RunEffect.ExecuteToolBatch(IDENTITY),
                calls = listOf(call()),
                context = GenerationContext(),
                conversationId = "conversation",
                authorizedToolNames = setOf("tool"),
            ),
            overlay = overlay,
            callbacks = ToolBatchProgressCallbacks(
                publish = { forces += it },
                onPublishedAt = publishedAt::add,
            ),
        )

        assertEquals(IDENTITY, outcome.identity)
        assertEquals(listOf("tool"), provider.executedNames)
        assertEquals(listOf(true, false, false, false), forces)
        assertEquals(4, publishedAt.size)
        assertEquals("done", outcome.calls.single().result)
        assertEquals(ToolExecutionStates.SUCCEEDED, outcome.segments.single().toolState)
    }

    @Test
    fun `oversized running checkpoint fails before tool execution and leaves a writable terminal overlay`() = runTest {
        val provider = StreamingToolProvider()
        val tools = GenerationToolExecutor.forTest(listOf(provider))
        val executor = GenerationToolBatchEffectExecutor(tools)
        val overlay = GenerationToolOverlay(tools, "provider")
        val continuation = Json.parseToJsonElement(
            """{"type":"reasoning","encrypted_content":"${"x".repeat(610_000)}"}""",
        ).jsonObject
        val requestCall = call(responseOutputItems = listOf(continuation))
        overlay.upsert("stream", "call", "tool", "{}", null)

        val failure = runCatching {
            executor.execute(
                request = AuthorizedToolBatchRequest(
                    effect = RunEffect.ExecuteToolBatch(IDENTITY),
                    calls = listOf(requestCall),
                    context = GenerationContext(),
                    conversationId = "conversation",
                    authorizedToolNames = setOf("tool"),
                ),
                overlay = overlay,
                callbacks = ToolBatchProgressCallbacks(
                    publish = { force ->
                        if (force) MessagePersistenceGuard.encodeSegmentsBounded(overlay.snapshot())
                    },
                    onPublishedAt = {},
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(
            failure?.message.orEmpty().contains("continuation state exceeds"),
        )
        assertTrue(provider.executedNames.isEmpty())
        val terminalSegment = overlay.snapshot().single { it.type == "tool" }
        assertEquals(ToolExecutionStates.FAILED, terminalSegment.toolState)
        assertTrue(terminalSegment.responseOutputItems.isEmpty())
        assertNull(terminalSegment.responseOutputItemProvider)
        assertTrue(MessagePersistenceGuard.encodeSegmentsBounded(overlay.snapshot()) != null)
    }

    @Test
    fun `committed response state is released only from the root overlay copy`() {
        val tools = GenerationToolExecutor.forTest(emptyList())
        val overlay = GenerationToolOverlay(tools, "provider")
        val continuation = Json.parseToJsonElement(
            """{"id":"rs_1","type":"reasoning","encrypted_content":"opaque"}""",
        ).jsonObject
        val requestCall = call(responseOutputItems = listOf(continuation))
        overlay.upsert("stream", "call", "tool", "{}", null)
        overlay.start(requestCall)
        val completed = overlay.complete(requestCall, ToolExecutionResult(text = "done"))

        overlay.markCommittedRound(setOf("call"), toolRoundId = "tool_round")

        val rootSegment = overlay.snapshot().single { it.type == "tool" }
        assertEquals("tool_round", rootSegment.toolRoundId)
        assertNull(completed.segment.toolRoundId)
        assertTrue(rootSegment.responseOutputItems.isEmpty())
        assertNull(rootSegment.responseOutputItemProvider)
        assertEquals(listOf(continuation), completed.data.responseOutputItems)
        assertEquals("provider", completed.data.responseOutputItemProvider)
    }

    @Test
    fun `later round with a reused call id cannot reassign committed history`() {
        val overlay = GenerationToolOverlay(GenerationToolExecutor.forTest(emptyList()), "provider")
        overlay.upsert("first", "call", "tool", "{}", null)
        overlay.markCommittedRound(setOf("call"), "tool_first")
        val committed = overlay.snapshot()
        overlay.upsert("second", "call", "tool", "{}", null)

        overlay.markCommittedRound(setOf("call"), "tool_second")

        assertEquals(committed, overlay.snapshot().take(committed.size))
        assertEquals(listOf("tool_first", "tool_second"),
            overlay.snapshot().filter { it.type == "tool" }.map { it.toolRoundId })
    }

    @Test
    fun `declared tool images are transcribed into consecutive thinking segments without polluting the result`() = runTest {
        val provider = ImageResultToolProvider()
        val tools = GenerationToolExecutor.forTest(listOf(provider))
        var now = 0L
        val executor = GenerationToolBatchEffectExecutor(tools) { now += 100L; now }
        val overlay = GenerationToolOverlay(tools, "provider")
        overlay.upsert("stream-a", "call-a", "tool", "{\"path\":\"/private/a.png\"}", null)
        overlay.upsert("stream-b", "call-b", "tool", "{\"path\":\"/private/b.png\"}", null)
        val transcribed = mutableListOf<String>()
        val progress = mutableListOf<String>()

        val outcome = executor.execute(
            request = AuthorizedToolBatchRequest(
                effect = RunEffect.ExecuteToolBatch(IDENTITY),
                calls = listOf(
                    call("call-a", "stream-a", "{\"path\":\"/private/a.png\"}"),
                    call("call-b", "stream-b", "{\"path\":\"/private/b.png\"}"),
                ),
                context = GenerationContext(),
                conversationId = "conversation",
                authorizedToolNames = setOf("tool"),
                toolImageTranscriber = { image, onProgress ->
                    transcribed += image.path
                    val partial = "partial ${image.path}"
                    progress += partial
                    onProgress(partial)
                    "description of ${image.path}"
                },
            ),
            overlay = overlay,
            callbacks = ToolBatchProgressCallbacks(
                publish = {},
                onPublishedAt = {},
            ),
        )

        assertEquals(
            listOf("/private/a.png", "/private/b.png"),
            transcribed,
        )
        assertEquals(
            listOf("partial /private/a.png", "partial /private/b.png"),
            progress,
        )
        val transcriptionSegments = overlay.snapshot().filter { it.type == "transcription" }
        assertEquals(2, transcriptionSegments.size)
        assertEquals(
            listOf(
                "description of /private/a.png",
                "description of /private/b.png",
            ),
            transcriptionSegments.map { it.content },
        )
        // The description stays out of the tool result text — it travels on the result row
        // (ToolCallData.transcription / segment.toolTranscription) and reaches the model via
        // the image-context row (ToolMessagesTest).
        assertEquals(listOf("done", "done"), outcome.calls.map { it.result })
        assertEquals(
            listOf("/private/a.png", "/private/b.png"),
            outcome.calls.map { call -> call.resultImages.single().path },
        )
        assertEquals(
            listOf("/private/a.png", "/private/b.png"),
            outcome.segments.map { segment -> segment.toolImages.single().path },
        )
        assertEquals(
            listOf(
                "description of /private/a.png",
                "description of /private/b.png",
            ),
            outcome.calls.map { it.transcription },
        )
        assertEquals(
            listOf(
                "description of /private/a.png",
                "description of /private/b.png",
            ),
            outcome.segments.map { it.toolTranscription },
        )
    }

    @Test
    fun `declared tool images without a transcriber keep the raw result and add no segment`() = runTest {
        val tools = GenerationToolExecutor.forTest(listOf(ImageResultToolProvider()))
        var now = 0L
        val executor = GenerationToolBatchEffectExecutor(tools) { now += 100L; now }
        val overlay = GenerationToolOverlay(tools, "provider")
        overlay.upsert("stream", "call", "tool", "{\"path\":\"/private/a.png\"}", null)

        val outcome = executor.execute(
            request = AuthorizedToolBatchRequest(
                effect = RunEffect.ExecuteToolBatch(IDENTITY),
                calls = listOf(call(arguments = "{\"path\":\"/private/a.png\"}")),
                context = GenerationContext(),
                conversationId = "conversation",
                authorizedToolNames = setOf("tool"),
            ),
            overlay = overlay,
            callbacks = ToolBatchProgressCallbacks(
                publish = {},
                onPublishedAt = {},
            ),
        )

        assertEquals("done", outcome.calls.single().result)
        assertTrue(overlay.snapshot().none { it.type == "transcription" })
    }

    @Test
    fun `every image is transcribed even when the tool does not opt in`() = runTest {
        val tools = GenerationToolExecutor.forTest(listOf(ImageResultToolProvider(imageCount = 2, transcribe = false)))
        val overlay = GenerationToolOverlay(tools, "provider")
        overlay.upsert("stream", "call", "tool", "{}", null)
        val observed = mutableListOf<String>()
        val outcome = GenerationToolBatchEffectExecutor(tools).execute(
            request = AuthorizedToolBatchRequest(
                effect = RunEffect.ExecuteToolBatch(IDENTITY),
                calls = listOf(call(arguments = "{\"path\":\"/private/image.png\"}")),
                context = GenerationContext(), conversationId = "conversation",
                authorizedToolNames = setOf("tool"),
                toolImageTranscriber = { image, progress ->
                    observed += image.path
                    progress("working")
                    "description ${observed.size}"
                },
            ),
            overlay = overlay,
            callbacks = ToolBatchProgressCallbacks(publish = {}, onPublishedAt = {}),
        )
        assertEquals(listOf("/private/image.png", "/private/image.png.1"), observed)
        assertEquals(2, outcome.calls.single().resultImages.size)
        assertEquals("[Image 1]\ndescription 1\n\n[Image 2]\ndescription 2", outcome.calls.single().transcription)
        assertEquals(outcome.calls.single().transcription, outcome.segments.single().toolTranscription)
        assertEquals(listOf("description 1", "description 2"),
            overlay.snapshot().filter { it.type == "transcription" }.map { it.content })
    }

    @Test
    fun `partial or blank transcription cannot replace the image batch`() = runTest {
        for (failedDescription in listOf(null, "  ")) {
            val tools = GenerationToolExecutor.forTest(listOf(ImageResultToolProvider(imageCount = 2)))
            val overlay = GenerationToolOverlay(tools, "provider")
            overlay.upsert("stream", "call", "tool", "{}", null)
            var described = 0
            val outcome = GenerationToolBatchEffectExecutor(tools).execute(
                request = AuthorizedToolBatchRequest(
                    effect = RunEffect.ExecuteToolBatch(IDENTITY),
                    calls = listOf(call(arguments = "{\"path\":\"/private/image.png\"}")),
                    context = GenerationContext(), conversationId = "conversation",
                    authorizedToolNames = setOf("tool"),
                    toolImageTranscriber = { _, progress ->
                        described++
                        progress("partial $described")
                        if (described == 1) "first image" else failedDescription
                    },
                ),
                overlay = overlay,
                callbacks = ToolBatchProgressCallbacks(publish = {}, onPublishedAt = {}),
            )
            assertEquals(2, described)
            assertNull(outcome.calls.single().transcription)
            assertNull(outcome.segments.single().toolTranscription)
            assertEquals(2, outcome.calls.single().resultImages.size)
            assertEquals(outcome.calls.single().resultImages, outcome.segments.single().toolImages)
        }
    }

    private fun call(
        id: String = "call",
        streamKey: String = "stream",
        arguments: String = "{}",
        responseOutputItems: List<JsonObject> = emptyList(),
    ) = StreamEvent.ToolCallRequest(
        id = id,
        name = "tool",
        arguments = arguments,
        streamKey = streamKey,
        signature = "signature",
        responseOutputItems = responseOutputItems,
    )

    private class ImageResultToolProvider(
        private val imageCount: Int = 1,
        private val transcribe: Boolean = true,
    ) : ToolProvider {
        override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

        override fun handles(name: String): Boolean = name == "tool"

        override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
            error("Streaming adapter expected")

        override fun executeEvents(
            name: String,
            arguments: String,
            ctx: GenerationContext,
        ): Flow<ToolExecutionEvent> {
            val path = kotlinx.serialization.json.Json.parseToJsonElement(arguments)
                .jsonObject["path"]!!.jsonPrimitive.content
            return flowOf(
                ToolExecutionEvent.Completed(
                    ToolExecutionResult(
                        text = "done",
                        images = List(imageCount) { index ->
                            com.newoether.agora.model.ToolImageAttachment(
                                path = if (index == 0) path else "$path.$index",
                                mimeType = "image/png",
                                sizeBytes = 1L,
                                width = 1,
                                height = 1,
                            )
                        },
                        transcribeImages = transcribe,
                    ),
                ),
            )
        }
    }

    private class StreamingToolProvider : ToolProvider {
        val executedNames = mutableListOf<String>()

        override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

        override fun handles(name: String): Boolean = name == "tool"

        override fun presentationMetadata(name: String, arguments: String?) = ToolPresentationMetadata("Display")

        override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
            error("Streaming adapter expected")

        override fun executeEvents(
            name: String,
            arguments: String,
            ctx: GenerationContext,
        ): Flow<ToolExecutionEvent> {
            executedNames += name
            return flowOf(
                ToolExecutionEvent.TargetResolved("target"),
                ToolExecutionEvent.OutputDelta("partial"),
                ToolExecutionEvent.Completed(ToolExecutionResult(text = "done")),
            )
        }
    }

    private companion object {
        val IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 7,
            runId = "run",
            pass = 2,
            effectId = "tool-batch-provider-2-0",
        )
    }
}
