package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiError
import com.newoether.agora.api.OpenAiResponseAnnotation
import com.newoether.agora.api.OpenAiResponseEnvelope
import com.newoether.agora.api.OpenAiResponseIncompleteDetails
import com.newoether.agora.api.OpenAiResponseOutputItem
import com.newoether.agora.api.OpenAiResponseStreamEvent
import com.newoether.agora.api.OpenAiResponseUsage
import com.newoether.agora.api.StreamEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ToolCallTextParserTest : ResponsesEventFixture() {
    @Test
    fun parsesBareAnthropicXmlInvoke() {
        val content = """
            I will inspect it.
            <invoke name="file_grep">
              <parameter name="pattern">notifySendAccepted|acquireForSend()</parameter>
              <parameter name="path">F:\workspace\repo\Agora\MessageGenerationController.kt</parameter>
              <parameter name="server">Quantum</parameter>
            </invoke>
        """.trimIndent()

        val parsed = ToolCallTextParser.parse(content)

        assertEquals(1, parsed.size)
        assertEquals("file_grep", parsed.single().name)
        assertTrue(parsed.single().arguments.contains("notifySendAccepted|acquireForSend()"))
        assertTrue(parsed.single().arguments.contains("MessageGenerationController.kt"))
        assertTrue(parsed.single().arguments.contains("Quantum"))
    }

    @Test
    fun parsesNamespacedAnthropicXmlAndDecodesEntities() {
        val parsed = ToolCallTextParser.parse(
            """
            <antml:invoke name='execute_shell_command'>
              <antml:parameter name='command'>echo &quot;a&amp;b&quot;</antml:parameter>
              <antml:parameter name='timeout_ms'>60000</antml:parameter>
            </antml:invoke>
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        assertEquals("execute_shell_command", parsed.single().name)
        assertTrue(parsed.single().arguments.contains("echo \\\"a&b\\\""))
        assertTrue(parsed.single().arguments.contains("60000"))
    }

    @Test
    fun parsesMultipleXmlInvokes() {
        val parsed = ToolCallTextParser.parse(
            """
            <invoke name="file_read"><parameter name="path">a.txt</parameter></invoke>
            <invoke name="file_read"><parameter name="path">b.txt</parameter></invoke>
            """.trimIndent()
        )

        assertEquals(listOf("file_read", "file_read"), parsed.map { it.name })
        assertTrue(parsed[0].arguments.contains("a.txt"))
        assertTrue(parsed[1].arguments.contains("b.txt"))
    }

    @Test
    fun proseMentionOfInvokeIsNotParsed() {
        val parsed = ToolCallTextParser.parse(
            "Use an invoke tag with a parameter tag, but do not execute anything."
        )

        assertTrue(parsed.isEmpty())
    }

    @Test
    fun rejectsPrimitiveArgumentsAndUnsafeNames() {
        assertTrue(
            ToolCallTextParser.parse(
                """{"name":"file_read","arguments":"not-an-object"}"""
            ).isEmpty()
        )
        assertTrue(
            ToolCallTextParser.parse(
                """{"name":"bad name","arguments":{}}"""
            ).isEmpty()
        )
    }

    @Test
    fun parsesIdAliasesAndRejectsConflicts() {
        val id = ToolCallTextParser.parse(
            """{"id":"call_1","name":"file_read","arguments":{}}"""
        ).single()
        val callId = ToolCallTextParser.parse(
            """{"call_id":"call_2","name":"file_read","arguments":{}}"""
        ).single()
        val matchingAliases = ToolCallTextParser.parse(
            """{"id":"call_3","call_id":"call_3","name":"file_read","arguments":{}}"""
        ).single()

        assertEquals("call_1", id.id)
        assertEquals("call_2", callId.id)
        assertEquals("call_3", matchingAliases.id)
        assertTrue(
            ToolCallTextParser.parse(
                """{"id":"call_1","call_id":"call_2","name":"file_read","arguments":{}}"""
            ).isEmpty()
        )
    }

    @Test
    fun rejectsNonStringAndUnsafeIds() {
        listOf(
            """{"id":7,"name":"file_read","arguments":{}}""",
            """{"call_id":false,"name":"file_read","arguments":{}}""",
            """{"id":"bad id","name":"file_read","arguments":{}}""",
        ).forEach { content ->
            assertTrue(ToolCallTextParser.parse(content).isEmpty())
        }
    }

    @Test
    fun parsesNestedFunctionArgumentsAndParameters() {
        val arguments = ToolCallTextParser.parse(
            """{"id":"call_1","function":{"name":"file_read","arguments":{"path":"a.txt"}}}"""
        ).single()
        val parameters = ToolCallTextParser.parse(
            """{"call_id":"call_2","function":{"name":"file_write","parameters":"{\"path\":\"b.txt\",\"content\":\"x\"}"}}"""
        ).single()

        assertEquals("file_read", arguments.name)
        assertEquals("a.txt", Json.parseToJsonElement(arguments.arguments).jsonObject["path"]?.jsonPrimitive?.content)
        assertEquals("file_write", parameters.name)
        assertEquals("b.txt", Json.parseToJsonElement(parameters.arguments).jsonObject["path"]?.jsonPrimitive?.content)
    }

    @Test
    fun malformedMemberRejectsWholeJsonBatch() {
        val parsed = ToolCallTextParser.parse(
            """[{"name":"file_read","arguments":{"path":"a"}},{"name":"file_write","arguments":"broken"}]"""
        )

        assertTrue(parsed.isEmpty())
    }

    @Test
    fun streamingParserWithholdsSplitXmlAndCompletesWithoutLeakingMarkup() = runTest {
        val parser = StreamingTextToolCallParser()
        val text = StringBuilder()
        val updates = mutableListOf<StreamingTextToolCallParser.Snapshot>()
        val completed = mutableListOf<StreamingTextToolCallParser.CompletedCall>()
        val malformed = mutableListOf<String>()

        suspend fun feed(chunk: String) {
            parser.feed(
                content = chunk,
                onText = { text.append(it) },
                onUpdate = { updates += it },
                onComplete = { completed += it },
                onMalformed = { malformed += it },
            )
        }

        feed("Checking. <inv")
        feed("oke name=\"file_grep\"><parameter name=\"pattern\">needle")
        feed("</parameter></invoke>")
        parser.flush(
            onText = { text.append(it) },
            onUpdate = { updates += it },
            onComplete = { completed += it },
            onMalformed = { malformed += it },
        )

        assertEquals("Checking. ", text.toString())
        assertEquals(1, completed.size)
        assertEquals("file_grep", completed.single().name)
        assertTrue(completed.single().arguments.contains("needle"))
        assertTrue(malformed.isEmpty())
        assertFalse(text.contains("invoke"))
        assertTrue(updates.isNotEmpty())
    }

    @Test
    fun responsesCompletedRoutesTextReasoningAndUsage() {
        val router = responsesRouter()

        assertEquals(
            "answer",
            router.route(responseEvent("response.output_text.delta", 1, delta = "answer"))
                .filterIsInstance<StreamEvent.TextChunk>().single().text,
        )
        assertEquals(
            "reason",
            router.route(responseEvent("response.reasoning_text.delta", 2, delta = "reason"))
                .filterIsInstance<StreamEvent.ThoughtChunk>().single().thought,
        )
        val terminal = router.route(
            responseEvent(
                "response.completed",
                3,
                response = OpenAiResponseEnvelope(
                    status = "completed",
                    usage = OpenAiResponseUsage(inputTokens = 3, outputTokens = 4, totalTokens = 7),
                ),
            )
        )

        assertTrue(router.sawTerminalMarker)
        assertEquals("completed", router.stopReason)
        assertEquals(7, terminal.filterIsInstance<StreamEvent.UsageUpdate>().single().usage.totalTokenCount)
        assertTrue(terminal.none { it is StreamEvent.Error })
    }

    @Test
    fun responsesSummaryPartsAreSeparatedWithoutSplittingSamePartDeltas() {
        val router = responsesRouter()

        val first = router.route(
            responseEvent(
                "response.reasoning_summary_text.delta",
                1,
                delta = "**Analyzing sources**",
                outputIndex = 0,
                summaryIndex = 0,
            ),
        ).filterIsInstance<StreamEvent.ThoughtChunk>().single()
        val continuation = router.route(
            responseEvent(
                "response.reasoning_summary_text.delta",
                2,
                delta = " and constraints",
                outputIndex = 0,
                summaryIndex = 0,
            ),
        ).filterIsInstance<StreamEvent.ThoughtChunk>().single()
        val next = router.route(
            responseEvent(
                "response.reasoning_summary_text.delta",
                3,
                delta = "**Planning fix**",
                outputIndex = 0,
                summaryIndex = 1,
            ),
        ).filterIsInstance<StreamEvent.ThoughtChunk>().single()

        assertEquals("Analyzing sources", first.title)
        assertEquals(null, continuation.title)
        assertEquals("Planning fix", next.title)
        assertEquals(
            "**Analyzing sources** and constraints\n\n**Planning fix**",
            listOf(first, continuation, next).joinToString("") { it.thought },
        )
    }

    @Test
    fun responsesUrlCitationsEmitStructuredAnchoredMetadataOnce() {
        val router = responsesRouter()
        router.route(
            responseEvent("response.output_text.delta", 1, delta = "Grounded claim"),
        )
        val citation = OpenAiResponseAnnotation(
            type = "url_citation",
            title = "Source one",
            url = "https://Example.com:443/a)b",
            startIndex = 0,
            endIndex = 8,
        )
        val first = router.route(
            responseEvent("response.output_text.annotation.added", 2).copy(annotation = citation),
        ).filterIsInstance<StreamEvent.CitationUpdate>().single().citation
        assertEquals("Source one", first.title)
        assertEquals("https://example.com/a)b", first.url)
        assertEquals("Grounded", first.anchors.single().citedText)
        assertTrue(
            router.route(
                responseEvent("response.output_text.annotation.added", 3).copy(annotation = citation),
            ).isEmpty(),
        )
    }

    @Test
    fun responsesCitationOffsetsAreScopedToTheirOutputTextItem() {
        val router = responsesRouter()
        router.route(
            responseEvent(
                "response.output_text.delta",
                1,
                delta = "Earlier output. ",
                itemId = "message-1",
                outputIndex = 0,
                contentIndex = 0,
            ),
        )
        val cited = "([openai.com](https://openai.com/research))"
        val secondOutput = "Later output $cited"
        router.route(
            responseEvent(
                "response.output_text.delta",
                2,
                delta = secondOutput,
                itemId = "message-1",
                outputIndex = 0,
                contentIndex = 1,
            ),
        )
        val localStart = secondOutput.indexOf(cited)

        val citation = router.route(
            responseEvent(
                "response.output_text.annotation.added",
                3,
                itemId = "message-1",
                outputIndex = 0,
                contentIndex = 1,
            ).copy(
                annotation = OpenAiResponseAnnotation(
                    type = "url_citation",
                    title = "OpenAI Research",
                    url = "https://openai.com/research",
                    startIndex = localStart,
                    endIndex = localStart + cited.length,
                ),
            ),
        ).filterIsInstance<StreamEvent.CitationUpdate>().single().citation

        assertEquals("Earlier output. ".length + localStart, citation.anchors.single().startIndex)
        assertEquals(cited, citation.anchors.single().citedText)
    }

    @Test
    fun responsesInvalidUrlCitationIsDroppedWithoutFailingAnswer() {
        val router = responsesRouter()
        val events = router.route(
            responseEvent("response.output_text.annotation.added", 1).copy(
                annotation = OpenAiResponseAnnotation(type = "url_citation", url = "javascript:alert(1)"),
            ),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun responsesFileCitationEmitsNonUrlSourceMetadata() {
        val router = responsesRouter()
        val citation = router.route(
            responseEvent("response.output_text.annotation.added", 1).copy(
                annotation = OpenAiResponseAnnotation(
                    type = "file_citation",
                    fileId = "file-123",
                    filename = "report.pdf",
                ),
            ),
        ).filterIsInstance<StreamEvent.CitationUpdate>().single().citation
        assertEquals("file", citation.kind)
        assertEquals("report.pdf", citation.title)
        assertEquals("file-123", citation.providerSourceId)
        assertTrue(citation.anchors.isEmpty())
    }

    @Test
    fun responsesMissingCitationAnnotationIsIgnored() {
        val events = responsesRouter().route(
            responseEvent("response.output_text.annotation.added", 1),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun responsesReasoningIsAlwaysSurfaced() {
        val router = responsesRouter()

        val thoughts = listOf(
            router.route(responseEvent("response.reasoning_text.delta", 1, delta = "reason")),
            router.route(
                responseEvent(
                    "response.reasoning_summary_text.delta",
                    2,
                    delta = "summary",
                    outputIndex = 0,
                    summaryIndex = 0,
                )
            ),
        ).flatten().filterIsInstance<StreamEvent.ThoughtChunk>()

        assertEquals(listOf("reason", "summary"), thoughts.map { it.thought })
    }

    @Test
    fun responsesBlankHostedCompletionIdRetainsEffectiveIdentity() {
        val router = responsesRouter()
        val addedItem = OpenAiResponseOutputItem(id = "ws_1", type = "web_search_call")
        val started = router.route(
            responseEvent(
                "response.output_item.added",
                1,
                outputIndex = 0,
                item = addedItem,
            )
        ).filterIsInstance<StreamEvent.HostedToolCallUpdate>().single()
        val completed = router.route(
            responseEvent(
                "response.output_item.done",
                2,
                outputIndex = 0,
                item = addedItem.copy(id = " ", type = " "),
            )
        ).filterIsInstance<StreamEvent.HostedToolCallUpdate>().single()

        assertEquals("ws_1", started.streamKey)
        assertEquals(started.streamKey, completed.streamKey)
        assertTrue(completed.result?.contains("\"id\":\"ws_1\"") == true)
    }

    @Test
    fun responsesFailedOrIncompleteNeverReleasesExecutableCall() {
        listOf("response.failed", "response.incomplete").forEach { terminalType ->
            val router = responsesRouter()
            val item = responseCallItem("item_1", "call_1", "lookup", "{}")
            router.route(responseEvent("response.output_item.added", 1, outputIndex = 0, item = item))
            assertTrue(
                router.route(
                    responseEvent("response.output_item.done", 2, outputIndex = 0, item = item)
                ).isEmpty()
            )
            val terminal = router.route(
                responseEvent(
                    terminalType,
                    3,
                    response = OpenAiResponseEnvelope(
                        status = terminalType.removePrefix("response."),
                        error = OpenAiError("terminal failure", type = "provider_error"),
                        incompleteDetails = OpenAiResponseIncompleteDetails("max_output_tokens"),
                    ),
                )
            )

            assertTrue(terminal.none { it is StreamEvent.ToolCallRequest || it is StreamEvent.ToolCallsRequest })
            assertTrue(router.sawTerminalMarker)
            assertTrue(router.streamError != null)
        }
    }

    @Test
    fun responsesRejectsMalformedCallsDuplicateIdentityAndOpenCompletion() {
        val malformed = responsesRouter()
        val unsafe = responseCallItem("item_1", "bad id", "bad name", "[]")
        malformed.route(responseEvent("response.output_item.added", 1, outputIndex = 0, item = unsafe))
        assertEquals(
            1,
            malformed.route(
                responseEvent("response.output_item.done", 2, outputIndex = 0, item = unsafe)
            ).filterIsInstance<StreamEvent.Error>().size,
        )

        val duplicate = responsesRouter()
        val first = responseCallItem("same_item", "call_1", "lookup")
        duplicate.route(responseEvent("response.output_item.added", 1, outputIndex = 0, item = first))
        assertEquals(
            1,
            duplicate.route(
                responseEvent("response.output_item.added", 2, outputIndex = 1, item = first)
            ).filterIsInstance<StreamEvent.Error>().size,
        )

        val open = responsesRouter()
        open.route(responseEvent("response.output_item.added", 1, outputIndex = 0, item = first))
        assertEquals(
            1,
            open.route(
                responseEvent(
                    "response.completed",
                    2,
                    response = OpenAiResponseEnvelope(status = "completed"),
                )
            ).filterIsInstance<StreamEvent.Error>().size,
        )
    }

    @Test
    fun responsesRequiresIncreasingSequenceAndRejectsTerminalMismatchOrLateEvent() {
        val missing = responsesRouter()
        assertEquals(
            1,
            missing.route(OpenAiResponseStreamEvent(type = "response.created"))
                .filterIsInstance<StreamEvent.Error>().size,
        )

        val repeated = responsesRouter()
        repeated.route(responseEvent("response.created", 1))
        assertEquals(
            1,
            repeated.route(responseEvent("response.in_progress", 1))
                .filterIsInstance<StreamEvent.Error>().size,
        )

        val mismatch = responsesRouter()
        assertEquals(
            1,
            mismatch.route(
                responseEvent(
                    "response.completed",
                    1,
                    response = OpenAiResponseEnvelope(status = "failed"),
                )
            ).filterIsInstance<StreamEvent.Error>().size,
        )

        val missingStatus = responsesRouter()
        assertEquals(
            1,
            missingStatus.route(
                responseEvent(
                    "response.completed",
                    1,
                    response = OpenAiResponseEnvelope(),
                )
            ).filterIsInstance<StreamEvent.Error>().size,
        )
        val late = responsesRouter()
        late.route(
            responseEvent(
                "response.completed",
                1,
                response = OpenAiResponseEnvelope(status = "completed"),
            )
        )
        assertEquals(
            1,
            late.route(responseEvent("response.output_text.delta", 2, delta = "late"))
                .filterIsInstance<StreamEvent.Error>().size,
        )
    }

    @Test
    fun responsesTopLevelErrorIsTerminalAndExplicit() {
        val router = responsesRouter()

        val output = router.route(
            responseEvent(
                "error",
                1,
                error = OpenAiError("relay rejected request", type = "relay_error", code = "bad_tool"),
            )
        )

        assertTrue(output.isEmpty())
        assertTrue(router.sawTerminalMarker)
        assertEquals(
            "relay rejected request",
            (router.streamError as com.newoether.agora.api.GenerationError.Api).message,
        )
    }
}
