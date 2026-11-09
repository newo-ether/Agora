package com.newoether.agora.api.anthropic

import com.newoether.agora.api.StreamEvent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicCitationProjectionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun streamedWebCitationAnchorsTheCurrentAnswerTextBlock() {
        val router = AnthropicStreamEventRouter()
        router.route(decode("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""))
        val answer = "The grass is green."
        assertEquals(
            StreamEvent.TextChunk(answer),
            router.route(
                decode(
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$answer"}}""",
                ),
            ).single(),
        )

        val update = router.route(
            decode(
                """
                {
                  "type": "content_block_delta",
                  "index": 0,
                  "delta": {
                    "type": "citations_delta",
                    "citation": {
                      "type": "web_search_result_location",
                      "cited_text": "Grass appears green because it reflects green light.",
                      "encrypted_index": "EAAAA...",
                      "title": "Example source",
                      "url": "https://example.com/grass"
                    }
                  }
                }
                """.trimIndent(),
            ),
        ).single() as StreamEvent.CitationUpdate

        assertEquals("anthropic", update.citation.provider)
        assertEquals("url", update.citation.kind)
        assertEquals("https://example.com/grass", update.citation.url)
        assertEquals("Grass appears green because it reflects green light.", update.citation.excerpt)
        assertEquals(0, update.citation.anchors.single().startIndex)
        assertEquals(answer.length, update.citation.anchors.single().endIndex)
        assertEquals(answer, update.citation.anchors.single().citedText)

        val terminal = router.route(
            decode("""{"type":"content_block_stop","index":0}"""),
        ).single() as StreamEvent.CitationUpdate
        assertEquals(update.citation, terminal.citation)
    }

    @Test
    fun citationBearingTextBlockUsesAnswerRelativeOffsetsAcrossBlocks() {
        val router = AnthropicStreamEventRouter()
        router.route(
            decode(
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Intro "}}""",
            ),
        )
        router.route(decode("""{"type":"content_block_stop","index":0}"""))

        val events = router.route(
            decode(
                """
                {
                  "type": "content_block_start",
                  "index": 1,
                  "content_block": {
                    "type": "text",
                    "text": "Claim",
                    "citations": [{
                      "type": "char_location",
                      "cited_text": "source excerpt",
                      "document_index": 0,
                      "document_title": "Document",
                      "start_char_index": 10,
                      "end_char_index": 24
                    }]
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(StreamEvent.TextChunk("Claim"), events.first())
        val citation = events.last() as StreamEvent.CitationUpdate
        assertEquals("document", citation.citation.kind)
        assertEquals("Document", citation.citation.title)
        assertEquals("Characters 10-24", citation.citation.location)
        assertEquals(6, citation.citation.anchors.single().startIndex)
        assertEquals(11, citation.citation.anchors.single().endIndex)
        assertEquals("Claim", citation.citation.anchors.single().citedText)
    }

    @Test
    fun citationBeforeTextIsReemittedWithTheExpandedAnchor() {
        val router = AnthropicStreamEventRouter()
        router.route(decode("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""))
        val sourceOnly = router.route(
            decode(
                """
                {
                  "type": "content_block_delta",
                  "index": 0,
                  "delta": {
                    "type": "citations_delta",
                    "citation": {
                      "type": "page_location",
                      "cited_text": "source",
                      "document_index": 1,
                      "document_title": "Report",
                      "start_page_number": 2,
                      "end_page_number": 3
                    }
                  }
                }
                """.trimIndent(),
            ),
        ).single() as StreamEvent.CitationUpdate
        assertTrue(sourceOnly.citation.anchors.isEmpty())

        val expanded = router.route(
            decode(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Claim"}}""",
            ),
        ).filterIsInstance<StreamEvent.CitationUpdate>().single()

        assertEquals(0, expanded.citation.anchors.single().startIndex)
        assertEquals(5, expanded.citation.anchors.single().endIndex)
        assertEquals("Claim", expanded.citation.anchors.single().citedText)
    }

    @Test
    fun malformedCitationIsDroppedWhileUnsafeUrlRemainsNonClickableMetadata() {
        val router = AnthropicStreamEventRouter()
        val events = router.route(
            decode(
                """
                {
                  "type": "content_block_start",
                  "index": 0,
                  "content_block": {
                    "type": "text",
                    "text": "Claim",
                    "citations": [
                      {
                        "type": "web_search_result_location",
                        "cited_text": "source",
                        "encrypted_index": "private",
                        "title": "Unsafe source",
                        "url": "javascript:alert(1)"
                      },
                      {"type": 5}
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )

        val citations = events.filterIsInstance<StreamEvent.CitationUpdate>()
        assertEquals(1, citations.size)
        assertEquals("Unsafe source", citations.single().citation.title)
        assertNull(citations.single().citation.url)
        assertTrue(events.none { it is StreamEvent.Error })
    }

    private fun decode(payload: String): AnthropicStreamEvent =
        json.decodeFromString(AnthropicStreamEvent.serializer(), payload)
}
