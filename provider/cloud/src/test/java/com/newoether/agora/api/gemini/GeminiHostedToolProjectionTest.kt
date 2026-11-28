package com.newoether.agora.api.gemini

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiHostedToolProjectionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun officialGroundingMetadataBecomesGoogleSearchToolResult() {
        val response = json.decodeFromString<ApiStreamResponse>(
            """
            {
              "candidates": [{
                "content": {"parts": [{"text": "Grounded answer"}]},
                "groundingMetadata": {
                  "webSearchQueries": ["Agora Android app"],
                  "searchEntryPoint": {"renderedContent": "<div>Search</div>"},
                  "groundingChunks": [
                    {"web": {"uri": "https://example.com/agora", "title": "Agora"}}
                  ],
                  "groundingSupports": [
                    {"segment": {"startIndex": 0, "endIndex": 8}, "groundingChunkIndices": [0]}
                  ]
                }
              }]
            }
            """.trimIndent(),
        )

        val metadata = response.candidates.orEmpty().single().groundingMetadata
        assertNotNull(metadata)
        val update = metadata!!.toGoogleSearchHostedUpdate("google_1")
        val arguments = json.parseToJsonElement(update.arguments).jsonObject
        val result = json.parseToJsonElement(checkNotNull(update.result)).jsonObject

        assertEquals("google_search", update.name)
        assertEquals("Agora Android app", arguments.getValue("query").jsonPrimitive.content)
        assertEquals(1, result.getValue("results").jsonArray.size)
        assertEquals(
            "https://example.com/agora",
            result.getValue("results").jsonArray.single().jsonObject.getValue("url").jsonPrimitive.content,
        )
        assertTrue(result.containsKey("grounding_metadata"))
        assertFalse(update.isError)
    }

    @Test
    fun officialCodeExecutionPartsBecomeOneHostedLifecycle() {
        val response = json.decodeFromString<ApiStreamResponse>(
            """
            {
              "candidates": [{
                "content": {"parts": [
                  {"executableCode": {"language": "PYTHON", "code": "print(1)"}},
                  {"codeExecutionResult": {"outcome": "OUTCOME_OK", "output": "1\n"}}
                ]}
              }]
            }
            """.trimIndent(),
        )
        val parts = response.candidates.orEmpty().single().content!!.parts
        val active = parts[0].executableCode!!.toCodeExecutionStart("code_1")
        val completed = parts[1].codeExecutionResult!!
            .toCodeExecutionCompletion("code_1", active.arguments)

        assertEquals("code_execution", active.name)
        assertEquals(null, active.result)
        assertEquals("code_1", completed.streamKey)
        assertTrue(completed.result!!.contains("OUTCOME_OK"))
        assertFalse(completed.isError)
    }

    @Test
    fun codeExecutionHistoryReplaysTypedPartsWithoutFlattenedDuplicateText() {
        val message = ChatMessage(
            text = "BeforeAfter",
            participant = Participant.MODEL,
            segments = listOf(
                MessageSegment(type = "answer", content = "Before"),
                MessageSegment(
                    type = "tool",
                    toolName = "code_execution",
                    toolArgs = """{"language":"PYTHON","code":"print(1)"}""",
                    toolResult = """{"outcome":"OUTCOME_OK","output":"1\n"}""",
                ),
                MessageSegment(type = "answer", content = "After"),
            ),
        )

        val parts = geminiModelMessageParts(message)

        assertEquals(4, parts.size)
        assertEquals("Before", parts[0].text)
        assertEquals("print(1)", parts[1].executableCode?.code)
        assertEquals("1\n", parts[2].codeExecutionResult?.output)
        assertEquals("After", parts[3].text)
        assertTrue(parts.none { it.text?.contains("```") == true || it.text?.contains("Output:") == true })
    }

    @Test
    fun multipleCodeExecutionSegmentsReplayInOrderAndPassWireValidation() {
        val message = ChatMessage(
            text = "",
            participant = Participant.MODEL,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "code_execution",
                    toolArgs = """{"language":"PYTHON","code":"print(1)"}""",
                    toolResult = """{"outcome":"OUTCOME_OK","output":"1\n"}""",
                ),
                MessageSegment(
                    type = "tool",
                    toolName = "code_execution",
                    toolArgs = """{"language":"PYTHON","code":"print(2)"}""",
                    toolResult = """{"outcome":"OUTCOME_OK","output":"2\n"}""",
                ),
            ),
        )

        val parts = geminiModelMessageParts(message)

        assertEquals(4, parts.size)
        assertEquals(
            listOf("print(1)", null, "print(2)", null),
            parts.map { it.executableCode?.code },
        )
        assertEquals(
            listOf(null, "1\n", null, "2\n"),
            parts.map { it.codeExecutionResult?.output },
        )
        ApiGenerateContentRequest(
            contents = listOf(
                ApiRequestContent("user", listOf(ApiRequestPart(text = "Run code"))),
                ApiRequestContent("model", parts),
                ApiRequestContent("user", listOf(ApiRequestPart(text = "Continue"))),
            ),
        ).requireValidWireFormat("gemini-2.5-pro")
    }

    @Test
    fun failedCodeExecutionCompletesAsError() {
        val completed = ApiCodeExecutionResult(
            outcome = "OUTCOME_FAILED",
            output = "Traceback",
        ).toCodeExecutionCompletion("code_1", "{}")

        assertEquals("code_execution", completed.name)
        assertTrue(completed.isError)
    }
}
