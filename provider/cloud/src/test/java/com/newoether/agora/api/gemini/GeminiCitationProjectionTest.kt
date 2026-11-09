package com.newoether.agora.api.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiCitationProjectionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun utf8ByteOffsetsBecomeUtf16AnchorsAndDuplicateSourcesMerge() {
        val answer = "A\uD83D\uDE00\u732BB"
        val metadata = metadata(
            """
            {
              "groundingChunks": [
                {"web": {"uri": "https://example.com/source", "title": "Example"}}
              ],
              "groundingSupports": [
                {
                  "segment": {"startIndex": 1, "endIndex": 8, "text": "\uD83D\uDE00\u732B"},
                  "groundingChunkIndices": [0]
                },
                {
                  "segment": {"startIndex": 8, "endIndex": 9, "text": "B"},
                  "groundingChunkIndices": [0]
                }
              ]
            }
            """.trimIndent(),
        )

        val citations = metadata.toGeminiCitations(answer)

        assertEquals(1, citations.size)
        assertEquals("gemini", citations.single().provider)
        assertEquals("url", citations.single().kind)
        assertEquals("https://example.com/source", citations.single().url)
        assertEquals(
            listOf(Triple(1, 4, "\uD83D\uDE00\u732B"), Triple(4, 5, "B")),
            citations.single().anchors.map { Triple(it.startIndex, it.endIndex, it.citedText) },
        )
    }

    @Test
    fun omittedZeroStartIndexUsesTheLeadingUtf8Boundary() {
        val metadata = metadata(
            """
            {
              "groundingChunks": [
                {"web": {"uri": "https://example.com/grounded", "title": "Grounded"}}
              ],
              "groundingSupports": [
                {
                  "segment": {"endIndex": 8, "text": "Grounded"},
                  "groundingChunkIndices": [0]
                }
              ]
            }
            """.trimIndent(),
        )

        val citation = metadata.toGeminiCitations("Grounded answer").single()

        assertEquals(0, citation.anchors.single().startIndex)
        assertEquals(8, citation.anchors.single().endIndex)
        assertEquals("Grounded", citation.anchors.single().citedText)
    }

    @Test
    fun invalidUtf8BoundaryKeepsTheSafeSourceWithoutAnInlineAnchor() {
        val metadata = metadata(
            """
            {
              "groundingChunks": [
                {"web": {"uri": "https://example.com/emoji", "title": "Emoji"}}
              ],
              "groundingSupports": [
                {
                  "segment": {"startIndex": 1, "endIndex": 4},
                  "groundingChunkIndices": [0]
                }
              ]
            }
            """.trimIndent(),
        )

        val citation = metadata.toGeminiCitations("\uD83D\uDE00").single()

        assertTrue(citation.anchors.isEmpty())
    }

    @Test
    fun mismatchedTextDropsOnlyTheAnchorAndUnsafeWebChunksRemainDescriptive() {
        val metadata = metadata(
            """
            {
              "groundingChunks": [
                {"web": {"uri": "https://example.com/safe", "title": "Safe"}},
                {"web": {"uri": "javascript:alert(1)", "title": "Unsafe"}}
              ],
              "groundingSupports": [
                {
                  "segment": {"startIndex": 0, "endIndex": 4, "text": "Nope"},
                  "groundingChunkIndices": [0, 1]
                }
              ]
            }
            """.trimIndent(),
        )

        val citations = metadata.toGeminiCitations("Safe answer")

        assertEquals(listOf("Safe", "Unsafe"), citations.map { it.title })
        assertEquals("https://example.com/safe", citations[0].url)
        assertNull(citations[1].url)
        assertTrue(citations.all { it.anchors.isEmpty() })
    }

    private fun metadata(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject
}
