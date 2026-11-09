package com.newoether.agora.model

import com.newoether.agora.data.local.DatabaseConstants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePersistenceGuardTest {
    @Test
    fun underBudgetEncodingMatchesCanonicalJsonByteForByte() {
        val segments = listOf(
            MessageSegment(
                type = "answer",
                content = "quoted \" text \\ newline\n control\u0001 😀",
                signature = "sig",
                signatureProvider = "provider",
            ),
            MessageSegment(
                type = "tool",
                content = "tool content",
                toolName = "lookup",
                toolArgs = "{\"query\":\"value\"}",
                toolResult = "result",
                toolResultText = "display",
                toolStructuredResult = "{\"ok\":true}",
                toolProgress = "complete",
            ),
        )
        val expected = Json.encodeToString(segments)

        val actual = MessagePersistenceGuard.encodeSegmentsBounded(
            segments = segments,
            maxBytes = expected.toByteArray(Charsets.UTF_8).size,
        )

        assertEquals(expected, actual)
    }

    @Test
    fun oversizedPayloadIsBudgetedBeforeAggregateEncoding() {
        val original = MessageSegment(
            type = "tool",
            content = "tool content must remain untouched",
            toolName = "lookup",
            toolArgs = "{\"query\":\"unchanged\"}",
            toolResult = ("plain\"\\\n\t\u0001😀").repeat(30_000),
            toolCallId = "call_1",
            signature = "signature",
            signatureProvider = "provider",
            durationMs = 123L,
            toolState = ToolExecutionStates.RUNNING,
            toolProgress = "p".repeat(60_000),
            toolTarget = "target",
            toolDisplayName = "Lookup",
            toolResultText = "t".repeat(180_000),
            toolStructuredResult = "s".repeat(120_000),
            toolTranscription = "transcription metadata",
            responseOutputItemProvider = "OpenAI",
        )
        val encodedCandidates = mutableListOf<List<MessageSegment>>()
        val maxBytes = 20_000

        val encoded = checkNotNull(
            MessagePersistenceGuard.encodeSegmentsBounded(
                segments = listOf(original),
                maxBytes = maxBytes,
                encode = { candidate ->
                    encodedCandidates += candidate
                    Json.encodeToString(candidate)
                },
            ),
        )

        assertEquals(2, encodedCandidates.size)
        val placeholder = encodedCandidates.first().single()
        assertEquals("x", placeholder.toolResult)
        assertEquals("x", placeholder.toolResultText)
        assertEquals("x", placeholder.toolStructuredResult)
        assertEquals("x", placeholder.toolProgress)
        assertEquals(original.content, placeholder.content)
        assertFalse(encodedCandidates.any { it.single().toolResult == original.toolResult })
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= maxBytes)

        val persisted = Json.decodeFromString<List<MessageSegment>>(encoded).single()
        assertEquals(original.type, persisted.type)
        assertEquals(original.content, persisted.content)
        assertEquals(original.toolName, persisted.toolName)
        assertEquals(original.toolArgs, persisted.toolArgs)
        assertEquals(original.toolCallId, persisted.toolCallId)
        assertEquals(original.signature, persisted.signature)
        assertEquals(original.signatureProvider, persisted.signatureProvider)
        assertEquals(original.durationMs, persisted.durationMs)
        assertEquals(original.toolState, persisted.toolState)
        assertEquals(original.toolTarget, persisted.toolTarget)
        assertEquals(original.toolDisplayName, persisted.toolDisplayName)
        assertEquals(original.toolTranscription, persisted.toolTranscription)
        assertEquals(original.responseOutputItemProvider, persisted.responseOutputItemProvider)
        assertTrue(persisted.toolResult.orEmpty().length < original.toolResult.orEmpty().length)
        assertTrue(
            persisted.toolResultText.orEmpty().length < original.toolResultText.orEmpty().length,
        )
        assertTrue(
            persisted.toolStructuredResult.orEmpty().length <
                original.toolStructuredResult.orEmpty().length,
        )
        assertTrue(persisted.toolProgress.orEmpty().length < original.toolProgress.orEmpty().length)
    }

    @Test
    fun independentlyPersistedToolResultFieldsAreIncludedInRowBudget() {
        val originalLength = 6_000
        val encoded = checkNotNull(
            MessagePersistenceGuard.encodeSegmentsBounded(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolResult = "r".repeat(originalLength),
                        toolResultText = "t".repeat(originalLength),
                        toolStructuredResult = "s".repeat(originalLength),
                    ),
                ),
                maxBytes = 12_000,
            ),
        )

        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= 12_000)
        val segment = Json.decodeFromString<List<MessageSegment>>(encoded).single()
        assertTrue(
            listOf(
                segment.toolResult,
                segment.toolResultText,
                segment.toolStructuredResult,
            ).any { it.orEmpty().length < originalLength },
        )
    }

    @Test
    fun trimStrictlyReducesAFieldJustAboveTheFloor() {
        val original = "x".repeat(2_001)
        val segments = listOf(MessageSegment(type = "tool", toolResult = original))
        val canonicalBytes = Json.encodeToString(segments).toByteArray(Charsets.UTF_8).size

        val encoded = checkNotNull(
            MessagePersistenceGuard.encodeSegmentsBounded(
                segments = segments,
                maxBytes = canonicalBytes - 1,
            ),
        )

        val persisted = Json.decodeFromString<List<MessageSegment>>(encoded).single().toolResult!!
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size < canonicalBytes)
        assertTrue(persisted.length < original.length)
        assertTrue(persisted.endsWith(MessagePersistenceGuard.TRUNCATION_MARKER))
    }

    @Test
    fun trimmedPayloadDoesNotSplitASurrogatePair() {
        val original = "a".repeat(2_500) + "😀" + "b".repeat(2_500)
        val encoded = checkNotNull(
            MessagePersistenceGuard.encodeSegmentsBounded(
                segments = listOf(MessageSegment(type = "tool", toolResult = original)),
                maxBytes = 3_000,
            ),
        )

        val persisted = Json.decodeFromString<List<MessageSegment>>(encoded).single().toolResult!!
        val retained = persisted.removeSuffix(MessagePersistenceGuard.TRUNCATION_MARKER)
        assertTrue(persisted.endsWith(MessagePersistenceGuard.TRUNCATION_MARKER))
        assertFalse(retained.last().isHighSurrogate())
    }

    @Test
    fun unshrinkableAggregateFailsClosedInsteadOfPersistingAnOversizedRow() {
        val encoded = MessagePersistenceGuard.encodeSegmentsBounded(
            segments = List(40) {
                MessageSegment(
                    type = "tool",
                    toolName = "tool_$it",
                    toolArgs = """{"payload":"${"x".repeat(2_000)}"}""",
                )
            },
            maxBytes = 8_000,
        )

        assertNull(encoded)
    }

    @Test
    fun responsesContinuationStateFailsExplicitlyInsteadOfBecomingSqlNull() {
        val error = runCatching {
            MessagePersistenceGuard.encodeSegmentsBounded(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = "lookup",
                        responseOutputItems = listOf(
                            buildJsonObject {
                                put("id", "rs_1")
                                put("type", "reasoning")
                                put("encrypted_content", "x".repeat(4_000))
                            },
                        ),
                        responseOutputItemProvider = "OpenAI",
                    ),
                ),
                maxBytes = 512,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("continuation state"))
    }

    @Test
    fun citationSegmentRemainsDecodableWithinPersistenceBudget() {
        val answer = "Claim"
        val citation = requireNotNull(
            CitationPolicy.create(
                provider = "test",
                kind = "web",
                title = "Source",
                url = "https://example.com/source",
                anchors = listOf(CitationAnchor(0, answer.length, answer)),
                answerText = answer,
            ),
        )
        val encoded = requireNotNull(
            MessagePersistenceGuard.encodeSegmentsBounded(
                listOf(citation.toMessageSegment()),
            ),
        )

        assertEquals(
            listOf(citation),
            Json.decodeFromString<List<MessageSegment>>(encoded).citationRecords(answer),
        )
    }

    @Test
    fun clippedTextDoesNotSplitASurrogatePair() {
        val prefix = "a".repeat(DatabaseConstants.MAX_PERSISTED_TEXT_CHARS - 1)
        val clipped = MessagePersistenceGuard.clipText(prefix + "😀" + "tail")
        val retained = clipped.removeSuffix(MessagePersistenceGuard.TRUNCATION_MARKER)

        assertTrue(clipped.endsWith(MessagePersistenceGuard.TRUNCATION_MARKER))
        assertFalse(retained.last().isHighSurrogate())
    }
}
