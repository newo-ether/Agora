package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.MALFORMED_TOOL_CALL_NAME
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.util.ProviderStreamNormalizer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderThoughtBoundaryNormalizerTest {
    @Test
    fun `untagged mixed language thought stays thought before a native tool`() = runTest {
        val thought = "I should inspect the browser process tree.\n\n" +
            "42 个 chrome.exe 中，只有这一组是桌面浏览器。我现在只结束这一组浏览器进程。\n\n"
        val tool = StreamEvent.ToolCallRequest(
            id = "call_browser",
            name = "file_read",
            arguments = "{}",
            streamKey = "browser_stream",
        )
        val events = normalize(
            thought.map { StreamEvent.ThoughtChunk(it.toString()) } + tool,
            TOOLS,
        )

        assertEquals(thought, events.filterIsInstance<StreamEvent.ThoughtChunk>()
            .joinToString("") { it.thought })
        assertTrue(events.none { it is StreamEvent.TextChunk })
        assertEquals(tool, events.last())
    }

    @Test
    fun `both close variants recover answer at every transport boundary`() = runTest {
        for (close in listOf("</think>", "</thinking>")) {
            val source = "English reasoning\n\n${close}中文正文"
            for (split in 0..source.length) {
                val events = normalize(
                    listOf(
                        StreamEvent.ThoughtChunk(source.substring(0, split)),
                        StreamEvent.ThoughtChunk(source.substring(split)),
                        StreamEvent.ThoughtChunk("，后续正文"),
                    )
                )

                assertEquals("$close split=$split", "English reasoning\n\n",
                    events.filterIsInstance<StreamEvent.ThoughtChunk>()
                        .joinToString("") { it.thought })
                assertEquals("$close split=$split", "中文正文，后续正文",
                    events.filterIsInstance<StreamEvent.TextChunk>()
                        .joinToString("") { it.text })
            }
        }
    }

    @Test
    fun `body thinking tags recover both variants across every transport boundary`() = runTest {
        for (tag in listOf("think", "thinking")) {
            val source = "正文前缀<$tag>reasoning</$tag>正文后缀"
            for (split in 0..source.length) {
                val events = normalize(
                    listOf(
                        StreamEvent.TextChunk(source.substring(0, split)),
                        StreamEvent.TextChunk(source.substring(split)),
                    )
                )

                assertEquals("$tag split=$split", "reasoning",
                    events.filterIsInstance<StreamEvent.ThoughtChunk>()
                        .joinToString("") { it.thought })
                assertEquals("$tag split=$split", "正文前缀正文后缀",
                    events.filterIsInstance<StreamEvent.TextChunk>()
                        .joinToString("") { it.text })
            }
        }
    }

    @Test
    fun `implicit close splits thought and answer while preserving metadata`() = runTest {
        val events = normalize(
            listOf(
                StreamEvent.ThoughtChunk(
                    thought = "reason</thinking>answer",
                    title = "Thinking",
                    signature = "signature",
                )
            )
        )

        assertEquals(
            listOf(
                StreamEvent.ThoughtChunk("reason", "Thinking", "signature"),
                StreamEvent.TextChunk("answer"),
            ),
            events,
        )
    }

    @Test
    fun `every chunk boundary and mixed case close produces the same content`() = runTest {
        val source = "reason</ThInKiNg>answer"
        for (split in 0..source.length) {
            val events = normalize(
                listOf(
                    StreamEvent.ThoughtChunk(source.substring(0, split)),
                    StreamEvent.ThoughtChunk(source.substring(split)),
                )
            )

            assertEquals(
                "split=$split",
                "reason",
                events.filterIsInstance<StreamEvent.ThoughtChunk>()
                    .joinToString("") { it.thought },
            )
            assertEquals(
                "split=$split",
                "answer",
                events.filterIsInstance<StreamEvent.TextChunk>()
                    .joinToString("") { it.text },
            )
        }
    }

    @Test
    fun `metadata arriving with a split close is applied before answer text`() = runTest {
        val events = normalize(
            listOf(
                StreamEvent.ThoughtChunk("reason</thin"),
                StreamEvent.ThoughtChunk(
                    thought = "king>answer",
                    title = "Thinking",
                    signature = "signature",
                ),
            )
        )

        assertEquals(
            listOf(
                StreamEvent.ThoughtChunk("reason"),
                StreamEvent.ThoughtChunk("", "Thinking", "signature"),
                StreamEvent.TextChunk("answer"),
            ),
            events,
        )
    }

    @Test
    fun `late signature metadata remains available after recovered answer text`() = runTest {
        val events = normalize(
            listOf(
                StreamEvent.ThoughtChunk("reason</thinking>answer"),
                StreamEvent.ThoughtChunk("", "Thinking", "signature"),
            )
        )

        assertEquals(
            listOf(
                StreamEvent.ThoughtChunk("reason"),
                StreamEvent.TextChunk("answer"),
                StreamEvent.ThoughtChunk("", "Thinking", "signature"),
            ),
            events,
        )
    }

    @Test
    fun `after implicit close later thought chunks remain answer text`() = runTest {
        val events = normalize(
            listOf(
                StreamEvent.ThoughtChunk("reason</thinking>first"),
                StreamEvent.ThoughtChunk(" second"),
            )
        )

        assertEquals(
            "reason",
            events.filterIsInstance<StreamEvent.ThoughtChunk>()
                .joinToString("") { it.thought },
        )
        assertEquals(
            "first second",
            events.filterIsInstance<StreamEvent.TextChunk>()
                .joinToString("") { it.text },
        )
    }

    @Test
    fun `close markers in markdown code stay in thought`() = runTest {
        val code = "`</thinking>`\n```\n</thinking>\n```\n"
        val events = normalize(
            (code + "reason</thinking>answer")
                .map { character -> StreamEvent.ThoughtChunk(character.toString()) }
        )

        assertEquals(
            code + "reason",
            events.filterIsInstance<StreamEvent.ThoughtChunk>()
                .joinToString("") { it.thought },
        )
        assertEquals(
            "answer",
            events.filterIsInstance<StreamEvent.TextChunk>()
                .joinToString("") { it.text },
        )
    }

    @Test
    fun `tool and terminal error ordering survives normalization`() = runTest {
        val tool = StreamEvent.ToolCallUpdate(
            streamKey = "stream",
            id = "call",
            name = "shell",
            arguments = "{}",
        )
        val error = StreamEvent.Error(
            GenerationError.Api("bad", "request", "failure")
        )
        val events = normalize(
            listOf(
                StreamEvent.ThoughtChunk("before"),
                tool,
                StreamEvent.ThoughtChunk("after</thinking>answer"),
                error,
            )
        )

        assertEquals(
            listOf(
                StreamEvent.ThoughtChunk("before"),
                tool,
                StreamEvent.ThoughtChunk("after"),
                StreamEvent.TextChunk("answer"),
                error,
            ),
            events,
        )
        assertTrue(events.indexOf(tool) < events.indexOf(error))
        assertFalse(events.any { it is StreamEvent.ThoughtChunk && it.thought.contains("</thinking>") })
    }

    @Test
    fun `native parser authority keeps tool authority while recovering inline thinking`() = runTest {
        val content = "<think>reason</think><tool_call>{\"name\":\"file_read\",\"arguments\":{}}</tool_call>"
        val native = StreamEvent.ToolCallRequest(
            id = "call_native",
            name = "file_read",
            arguments = "{}",
            streamKey = "native_stream",
        )
        val events = mutableListOf<StreamEvent>()
        val normalizer = ProviderStreamNormalizer(
            tools = TOOLS,
            nativeTextParsingAuthoritative = true,
        )

        normalizer.emit(StreamEvent.TextChunk(content), events::add)
        normalizer.emit(native, events::add)
        normalizer.finish(events::add)

        assertEquals("reason", events.filterIsInstance<StreamEvent.ThoughtChunk>()
            .joinToString("") { it.thought })
        assertEquals(
            "<tool_call>{\"name\":\"file_read\",\"arguments\":{}}</tool_call>",
            events.filterIsInstance<StreamEvent.TextChunk>().joinToString("") { it.text },
        )
        assertEquals(listOf(native), events.filterIsInstance<StreamEvent.ToolCallRequest>())
    }

    @Test
    fun `native parser authority recovers device channel thinking across every split`() = runTest {
        val source = "<|channel>thought Thinking Process:reason<channel|>final answer"
        for (split in 0..source.length) {
            val events = mutableListOf<StreamEvent>()
            val normalizer = ProviderStreamNormalizer(
                tools = TOOLS,
                nativeTextParsingAuthoritative = true,
            )

            normalizer.emit(StreamEvent.TextChunk(source.substring(0, split)), events::add)
            normalizer.emit(StreamEvent.TextChunk(source.substring(split)), events::add)
            normalizer.finish(events::add)

            assertEquals(
                "split=$split",
                "Thinking Process:reason",
                events.filterIsInstance<StreamEvent.ThoughtChunk>()
                    .joinToString("") { it.thought },
            )
            assertEquals(
                "split=$split",
                "final answer",
                events.filterIsInstance<StreamEvent.TextChunk>()
                    .joinToString("") { it.text },
            )
            assertTrue(
                "split=$split",
                events.none { event ->
                    event is StreamEvent.TextChunk &&
                        (event.text.contains("<|channel>") || event.text.contains("<channel|>"))
                },
            )
            assertTrue(events.none { it is StreamEvent.ToolCallRequest })
        }
    }

    @Test
    fun `native parser authority keeps device markers literal inside markdown code`() = runTest {
        val content = "`<|channel>thought Thinking Process:inline<channel|>`\n```\n" +
            "<|channel>thought Thinking Process:fenced<channel|>\n```"
        val events = mutableListOf<StreamEvent>()
        val normalizer = ProviderStreamNormalizer(
            tools = TOOLS,
            nativeTextParsingAuthoritative = true,
        )

        content.map(Char::toString).forEach { chunk ->
            normalizer.emit(StreamEvent.TextChunk(chunk), events::add)
        }
        normalizer.finish(events::add)

        assertEquals(content, events.filterIsInstance<StreamEvent.TextChunk>()
            .joinToString("") { it.text })
        assertTrue(events.none { it is StreamEvent.ThoughtChunk })
    }

    @Test
    fun `text tool candidates stay private until stream completion`() = runTest {
        val events = mutableListOf<StreamEvent>()
        val normalizer = ProviderStreamNormalizer(TOOLS)

        normalizer.emit(StreamEvent.TextChunk("<tool_call>{\"id\":\"call_1\","), events::add)
        normalizer.emit(
            StreamEvent.TextChunk("\"name\":\"file_read\",\"arguments\":{}}</tool_call>"),
            events::add,
        )
        assertTrue(events.none { it is StreamEvent.ToolCallUpdate || it is StreamEvent.ToolCallRequest })

        normalizer.finish(events::add)

        val call = events.filterIsInstance<StreamEvent.ToolCallRequest>().single()
        assertEquals("call_1", call.id)
        assertEquals("file_read", call.name)
        assertEquals("{}", call.arguments)
        assertTrue(events.none { it is StreamEvent.ToolCallUpdate })
    }

    @Test
    fun `native tool call suppresses equivalent buffered text call`() = runTest {
        val events = mutableListOf<StreamEvent>()
        val normalizer = ProviderStreamNormalizer(TOOLS)
        val native = StreamEvent.ToolCallRequest(
            id = "call_native",
            name = "file_read",
            arguments = "{ \"path\": \"a.txt\" }",
            streamKey = "native_stream",
        )

        normalizer.emit(
            StreamEvent.TextChunk(
                "<tool_call>{\"name\":\"file_read\",\"arguments\":{\"path\":\"a.txt\"}}</tool_call>"
            ),
            events::add,
        )
        normalizer.emit(native, events::add)
        normalizer.finish(events::add)

        assertEquals(listOf(native), events.filterIsInstance<StreamEvent.ToolCallRequest>())
        assertTrue(events.none { it is StreamEvent.ToolCallUpdate })
    }

    @Test
    fun `prose and markdown tool examples remain literal text`() = runTest {
        val content = "Example: <invoke name=\"file_read\"></invoke>\n`<tool_call>{}</tool_call>`"
        val events = normalize(listOf(StreamEvent.TextChunk(content)), TOOLS)

        assertEquals(content, events.filterIsInstance<StreamEvent.TextChunk>().joinToString("") { it.text })
        assertTrue(events.none { it is StreamEvent.ToolCallRequest || it is StreamEvent.Error })
    }

    @Test
    fun `malformed syntax is answered as a malformed call`() = runTest {
        val malformed = normalize(
            listOf(StreamEvent.TextChunk("<tool_call>{\"name\":\"file_read\"}")),
            TOOLS,
        )

        // Nothing is executed, and the run continues with an error result the model can act on.
        assertTrue(malformed.none { it is StreamEvent.Error })
        assertEquals(
            MALFORMED_TOOL_CALL_NAME,
            malformed.filterIsInstance<StreamEvent.ToolCallRequest>().single().name,
        )
    }

    @Test
    fun `an unknown tool name is released for the executor to answer`() = runTest {
        // Same contract as native calls: calling a tool that was not offered is the model's mistake
        // on a pairable call, so the call goes through and the tool executor answers it with an
        // error result the model can correct from.
        val unknown = normalize(
            listOf(
                StreamEvent.TextChunk(
                    "<tool_call>{\"name\":\"File_Read\",\"arguments\":{}}</tool_call>"
                )
            ),
            TOOLS,
        )

        assertTrue(unknown.none { it is StreamEvent.Error })
        assertEquals(
            "File_Read",
            unknown.filterIsInstance<StreamEvent.ToolCallRequest>().single().name,
        )
    }

    @Test
    fun `native thought metadata wins without duplicating equivalent inline content`() = runTest {
        val events = normalize(
            listOf(
                StreamEvent.TextChunk("<think>same reasoning</think>"),
                StreamEvent.ThoughtChunk("same reasoning", title = "Thinking", signature = "sig"),
            )
        )
        val thoughts = events.filterIsInstance<StreamEvent.ThoughtChunk>()

        assertEquals("same reasoning", thoughts.joinToString("") { it.thought })
        assertTrue(thoughts.any { it.title == "Thinking" && it.signature == "sig" })
    }

    @Test
    fun `tool call inside unclosed reasoning is executed`() = runTest {
        val events = normalize(
            listOf(
                StreamEvent.ThoughtChunk(
                    "I need to read the file first.\n" +
                        "<tool_call>{\"id\":\"call_1\",\"name\":\"file_read\"," +
                        "\"arguments\":{\"path\":\"a.txt\"}}</tool_call>"
                )
            ),
            TOOLS,
        )

        assertEquals(
            "I need to read the file first.\n",
            events.filterIsInstance<StreamEvent.ThoughtChunk>()
                .joinToString("") { it.thought },
        )
        val call = events.filterIsInstance<StreamEvent.ToolCallRequest>().single()
        assertEquals("call_1", call.id)
        assertEquals("file_read", call.name)
        assertEquals("{\"path\":\"a.txt\"}", call.arguments)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    @Test
    fun `tool call opener ends reasoning at every transport boundary`() = runTest {
        val source = "reason<tool_call>{\"name\":\"file_read\",\"arguments\":{}}</tool_call>"
        for (split in 0..source.length) {
            val events = normalize(
                listOf(
                    StreamEvent.ThoughtChunk(source.substring(0, split)),
                    StreamEvent.ThoughtChunk(source.substring(split)),
                ),
                TOOLS,
            )

            assertEquals(
                "split=$split",
                "reason",
                events.filterIsInstance<StreamEvent.ThoughtChunk>()
                    .joinToString("") { it.thought },
            )
            assertEquals(
                "split=$split",
                "file_read",
                events.filterIsInstance<StreamEvent.ToolCallRequest>().single().name,
            )
        }
    }

    @Test
    fun `tool call markers inside reasoning code stay thought`() = runTest {
        val code = "`<tool_call>`\n```\n<tool_call>{\"name\":\"file_read\"}</tool_call>\n```\n"
        val events = normalize(
            (code + "reason<tool_call>{\"name\":\"file_read\",\"arguments\":{}}</tool_call>")
                .map { character -> StreamEvent.ThoughtChunk(character.toString()) },
            TOOLS,
        )

        assertEquals(
            code + "reason",
            events.filterIsInstance<StreamEvent.ThoughtChunk>()
                .joinToString("") { it.thought },
        )
        assertEquals(
            "file_read",
            events.filterIsInstance<StreamEvent.ToolCallRequest>().single().name,
        )
    }

    @Test
    fun `unterminated tool call inside reasoning fails as a malformed call`() = runTest {
        val events = normalize(
            listOf(StreamEvent.ThoughtChunk("reason<tool_call>{\"name\":\"file_read\"}")),
            TOOLS,
        )

        assertEquals(
            "reason",
            events.filterIsInstance<StreamEvent.ThoughtChunk>()
                .joinToString("") { it.thought },
        )
        assertEquals(
            MALFORMED_TOOL_CALL_NAME,
            events.filterIsInstance<StreamEvent.ToolCallRequest>().single().name,
        )
    }

    private suspend fun normalize(
        events: List<StreamEvent>,
        tools: List<ToolDefinition>? = null,
    ): List<StreamEvent> {
        val normalized = mutableListOf<StreamEvent>()
        val normalizer = ProviderStreamNormalizer(tools)
        events.forEach { event -> normalizer.emit(event, normalized::add) }
        normalizer.finish(normalized::add)
        return normalized
    }

    private companion object {
        val TOOLS = listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "file_read",
                    description = "Read a file",
                    parameters = ToolParameters(properties = emptyMap()),
                )
            )
        )
    }
}
