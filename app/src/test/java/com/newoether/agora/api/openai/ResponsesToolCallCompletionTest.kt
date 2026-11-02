package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiResponseEnvelope
import com.newoether.agora.api.OpenAiResponseOutputItem
import com.newoether.agora.api.StreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ResponsesToolCallCompletionTest : ResponsesEventFixture() {
    @Test
    fun responsesReasoningOutputItemIsRetainedUntilCompletedCallRelease() {
        val router = responsesRouter()
        val reasoning = OpenAiResponseOutputItem(
            id = "rs_1",
            type = "reasoning",
            summary = Json.parseToJsonElement("""[{"type":"summary_text","text":"summary"}]"""),
            encryptedContent = "opaque-reasoning-state",
        )
        assertTrue(
            router.route(
                responseEvent(
                    "response.output_item.added",
                    1,
                    outputIndex = 0,
                    item = reasoning,
                ),
            ).isEmpty(),
        )
        assertTrue(
            router.route(
                responseEvent(
                    "response.output_item.done",
                    2,
                    outputIndex = 0,
                    item = OpenAiResponseOutputItem(
                        id = " ",
                        type = " ",
                        summary = JsonArray(emptyList()),
                        encryptedContent = " ",
                    ),
                ),
            ).isEmpty(),
        )
        val callItem = responseCallItem("item_1", "call_1", "lookup", "{}")
        router.route(
            responseEvent(
                "response.output_item.added",
                3,
                outputIndex = 1,
                item = callItem,
            ),
        )
        router.route(
            responseEvent(
                "response.output_item.done",
                4,
                outputIndex = 1,
                item = callItem,
            ),
        )

        val call = router.route(
            responseEvent(
                "response.completed",
                5,
                response = OpenAiResponseEnvelope(status = "completed"),
            ),
        ).filterIsInstance<StreamEvent.ToolCallRequest>().single()

        assertEquals(
            listOf(responseItem(reasoning.copy(summary = JsonArray(emptyList()))), responseItem(callItem)),
            call.responseOutputItems,
        )
        assertTrue(router.route(responseEvent("response.created", 6)).single() is StreamEvent.Error)
    }

    @Test
    fun responsesEmptyReasoningSummarySurvivesEmptyOrMissingDoneSummary() {
        for (completedSummary in listOf(JsonArray(emptyList()), null, JsonNull)) {
            val router = responsesRouter()
            val reasoning = OpenAiResponseOutputItem(
                id = "rs_1",
                type = "reasoning",
                summary = JsonArray(emptyList()),
                encryptedContent = "opaque-reasoning-state",
            )
            val callItem = responseCallItem("item_1", "call_1", "lookup", "{}")
            router.route(responseEvent("response.output_item.added", 1, outputIndex = 0, item = reasoning))
            router.route(responseEvent("response.output_item.done", 2, outputIndex = 0,
                item = reasoning.copy(summary = completedSummary)))
            router.route(responseEvent("response.output_item.added", 3, outputIndex = 1, item = callItem))
            router.route(responseEvent("response.output_item.done", 4, outputIndex = 1, item = callItem))

            val call = router.route(responseEvent("response.completed", 5,
                response = OpenAiResponseEnvelope(status = "completed")))
                .filterIsInstance<StreamEvent.ToolCallRequest>().single()

            assertEquals(listOf(responseItem(reasoning), responseItem(callItem)), call.responseOutputItems)
        }
    }

    @Test
    fun responsesFinalArgumentsSnapshotOverridesEquivalentStreamFormatting() {
        val router = responsesRouter()
        val item = responseCallItem("item_1", "call_1", "lookup")
        router.route(responseEvent("response.output_item.added", 1, outputIndex = 0, item = item))
        router.route(
            responseEvent(
                "response.function_call_arguments.delta",
                2,
                delta = """{"q":"x"}""",
                itemId = "item_1",
                outputIndex = 0,
            ),
        )
        val finalArguments = """{ "q": "x" }"""
        val done = router.route(
            responseEvent(
                "response.function_call_arguments.done",
                3,
                arguments = finalArguments,
                name = "lookup",
                itemId = "item_1",
                outputIndex = 0,
            ),
        )
        val itemDone = router.route(
            responseEvent(
                "response.output_item.done",
                4,
                outputIndex = 0,
                item = item.copy(arguments = finalArguments),
            ),
        )
        val call = router.route(
            responseEvent(
                "response.completed",
                5,
                response = OpenAiResponseEnvelope(status = "completed"),
            ),
        ).filterIsInstance<StreamEvent.ToolCallRequest>().single()

        assertTrue((done + itemDone).none { it is StreamEvent.Error })
        assertEquals(finalArguments, call.arguments)
    }

    @Test
    fun responsesBlankCompletionMetadataCannotEraseEffectiveCallState() {
        val router = responsesRouter()
        val item = responseCallItem("item_1", "call_1", "lookup")
        router.route(responseEvent("response.output_item.added", 1, outputIndex = 0, item = item))
        router.route(
            responseEvent(
                "response.function_call_arguments.delta",
                2,
                delta = "{}",
                itemId = "item_1",
                outputIndex = 0,
            )
        )
        val argumentsDone = router.route(
            responseEvent(
                "response.function_call_arguments.done",
                3,
                arguments = " ",
                name = " ",
                itemId = " ",
                outputIndex = 0,
            )
        )
        val itemDone = router.route(
            responseEvent(
                "response.output_item.done",
                4,
                outputIndex = 0,
                item = responseCallItem(" ", " ", " ", " ").copy(type = " "),
            )
        )
        val call = router.route(
            responseEvent(
                "response.completed",
                5,
                response = OpenAiResponseEnvelope(status = "completed"),
            )
        ).filterIsInstance<StreamEvent.ToolCallRequest>().single()

        assertTrue((argumentsDone + itemDone).none { it is StreamEvent.Error })
        assertEquals("call_1", call.id)
        assertEquals("lookup", call.name)
        assertEquals("{}", call.arguments)
        val retainedItem = call.responseOutputItems.orEmpty().single()
        assertEquals("item_1", retainedItem["id"]?.jsonPrimitive?.content)
        assertEquals("call_1", retainedItem["call_id"]?.jsonPrimitive?.content)
        assertEquals("lookup", retainedItem["name"]?.jsonPrimitive?.content)
        assertEquals("{}", retainedItem["arguments"]?.jsonPrimitive?.content)
    }

    @Test
    fun responsesFunctionCallIsExecutableOnlyAfterCompleted() {
        val router = responsesRouter()
        val item = responseCallItem("item_1", "call_1", "lookup")

        val added = router.route(
            responseEvent("response.output_item.added", 1, outputIndex = 0, item = item)
        )
        val delta = router.route(
            responseEvent(
                "response.function_call_arguments.delta",
                2,
                delta = """{"q":"x"}""",
                itemId = "item_1",
                outputIndex = 0,
            )
        )
        val argumentsDone = router.route(
            responseEvent(
                "response.function_call_arguments.done",
                3,
                arguments = """{"q":"x"}""",
                name = "lookup",
                itemId = "item_1",
                outputIndex = 0,
            )
        )
        val itemDone = router.route(
            responseEvent(
                "response.output_item.done",
                4,
                outputIndex = 0,
                item = item.copy(arguments = """{"q":"x"}"""),
            )
        )

        assertTrue((added + delta + argumentsDone + itemDone).none { it is StreamEvent.ToolCallRequest })
        val call = router.route(
            responseEvent(
                "response.completed",
                5,
                response = OpenAiResponseEnvelope(status = "completed"),
            )
        ).filterIsInstance<StreamEvent.ToolCallRequest>().single()
        assertEquals("call_1", call.id)
        assertEquals("lookup", call.name)
        assertEquals("""{"q":"x"}""", call.arguments)
    }

    @Test
    fun responsesMultipleCallsReleaseAtomicallyOnCompleted() {
        val router = responsesRouter()
        repeat(2) { index ->
            val item = responseCallItem("item_$index", "call_$index", "tool_$index", "{}")
            assertTrue(
                router.route(
                    responseEvent(
                        "response.output_item.added",
                        index * 2 + 1,
                        outputIndex = index,
                        item = item,
                    )
                ).none { it is StreamEvent.ToolCallRequest || it is StreamEvent.ToolCallsRequest }
            )
            assertTrue(
                router.route(
                    responseEvent(
                        "response.output_item.done",
                        index * 2 + 2,
                        outputIndex = index,
                        item = item,
                    )
                ).isEmpty()
            )
        }

        val batch = router.route(
            responseEvent(
                "response.completed",
                5,
                response = OpenAiResponseEnvelope(status = "completed"),
            )
        ).filterIsInstance<StreamEvent.ToolCallsRequest>().single()
        assertEquals(listOf("call_0", "call_1"), batch.calls.map { it.id })
        assertEquals(2, batch.calls.first().responseOutputItems.size)
        assertTrue(batch.calls.drop(1).all { it.responseOutputItems.isEmpty() })
    }

    @Test
    fun responsesOutOfOrderCompletionStillReplaysOutputIndexOrder() {
        val router = responsesRouter()
        val first = responseCallItem("item_0", "call_0", "tool_0", "{}")
        val second = responseCallItem("item_1", "call_1", "tool_1", "{}")
        router.route(
            responseEvent(
                "response.output_item.added",
                1,
                outputIndex = 1,
                item = second,
            ),
        )
        router.route(
            responseEvent(
                "response.output_item.added",
                2,
                outputIndex = 0,
                item = first,
            ),
        )
        router.route(
            responseEvent(
                "response.output_item.done",
                3,
                outputIndex = 1,
                item = second,
            ),
        )
        router.route(
            responseEvent(
                "response.output_item.done",
                4,
                outputIndex = 0,
                item = first,
            ),
        )

        val batch = router.route(
            responseEvent(
                "response.completed",
                5,
                response = OpenAiResponseEnvelope(status = "completed"),
            ),
        ).filterIsInstance<StreamEvent.ToolCallsRequest>().single()

        assertEquals(listOf("call_0", "call_1"), batch.calls.map { it.id })
        assertEquals(
            listOf("item_0", "item_1"),
            batch.calls.first().responseOutputItems.map {
                it["id"]?.jsonPrimitive?.content
            },
        )
    }
}
