package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.CitationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class CitationStreamingProjectionTest : CitationContentTestFixture() {
    @Test
    fun originalPositionsSurviveGroupedInsertionsWrappersAndPrivateMarkerCleanup() {
        for (answer in listOf("Alpha beta", "Alpha ([link](https://example.com/a)) beta",
            "Alpha \uE200cite\uE202turn9search9\uE201 beta")) {
            val source = citation(answer, "Example", "https://example.com/a", ranges = arrayOf(0 until 5))
            val projection = requireNotNull(citationMarkdownProjection(answer, listOf(source), false, true))
            assertEquals(projection.markdown.length, projection.sourcePositions.size)
            val index = projection.markdown.indexOf("beta")
            assertEquals(answer.indexOf("beta"), projection.sourcePositions[index])
            for (position in projection.markdown.indices) {
                if (projection.markers.none { it.token == projection.markdown[position] }) {
                    assertEquals(answer[projection.sourcePositions[position]], projection.markdown[position])
                }
            }
        }
    }

    @Test
    fun validClaimsStayUnprojectedWhileStreamingThenReuseOneTerminalCapsule() {
        val answer = "Alpha and beta."
        val source = citation(
            answer = answer,
            title = "Example",
            url = "https://example.com/a",
            ranges = arrayOf(0 until 5, 10 until 14),
        )

        val projection = citationMarkdownProjection(
            answerText = answer,
            citations = listOf(source),
            isStreaming = false,
        )

        assertNotNull(projection)
        val marker = projection!!.markers.single()
        assertEquals(1, marker.number)
        assertEquals("example.com", marker.label)
        assertFalse(marker.label.contains('('))
        assertFalse(marker.label.contains(')'))
        assertEquals(2, projection.markdown.count { it == marker.token })
        val streamingBeforeCitation = citationMarkdownProjection(
            answerText = answer,
            citations = emptyList(),
            isStreaming = true,
        )
        val streamingAfterCitation = citationMarkdownProjection(
            answerText = answer,
            citations = listOf(source),
            isStreaming = true,
        )
        assertEquals(answer, streamingBeforeCitation!!.markdown)
        assertEquals(answer, streamingAfterCitation!!.markdown)
        assertTrue(streamingBeforeCitation.markers.isEmpty())
        assertTrue(streamingAfterCitation.markers.isEmpty())
        assertNotEquals(answer, projection.markdown)
    }

    @Test
    fun streamingWithholdsUnresolvedCitationWrapperAndTerminalRestoresOrdinaryMarkdown() {
        val complete = "Research ([openai.com](https://openai.com/news))"
        val partial = "Research ([openai.com](https://openai.com/news"
        val opening = "Research ("

        val streamingOpening = citationMarkdownProjection(
            answerText = opening,
            citations = emptyList(),
            isStreaming = true,
        )
        val streamingComplete = citationMarkdownProjection(
            answerText = complete,
            citations = emptyList(),
            isStreaming = true,
        )
        val streamingPartial = citationMarkdownProjection(
            answerText = partial,
            citations = emptyList(),
            isStreaming = true,
        )
        val terminal = citationMarkdownProjection(
            answerText = complete,
            citations = emptyList(),
            isStreaming = false,
        )

        assertNotNull(streamingOpening)
        assertNotNull(streamingComplete)
        assertNotNull(streamingPartial)
        assertEquals("Research ", streamingOpening!!.markdown)
        assertEquals("Research ", streamingComplete!!.markdown)
        assertEquals("Research ", streamingPartial!!.markdown)
        assertTrue(streamingComplete.markers.isEmpty())
        assertEquals(complete, terminal!!.markdown)
    }

    @Test
    fun plainProviderArtifactsBecomeOneGroupedNativeCapsule() {
        val answer = "Claim citeturn3search1turn7search1"
        val sources = listOf(
            requireNotNull(
                CitationPolicy.create(
                    provider = "openai",
                    kind = "url",
                    title = "First",
                    url = "https://first.example/source",
                    providerSourceId = "turn3search1",
                ),
            ),
            requireNotNull(
                CitationPolicy.create(
                    provider = "openai",
                    kind = "url",
                    title = "Second",
                    url = "https://second.example/source",
                    providerSourceId = "turn7search1",
                ),
            ),
        )

        val projection = requireNotNull(
            citationMarkdownProjection(answer, sources, isStreaming = false),
        )
        val marker = projection.markers.single()
        val streaming = requireNotNull(
            citationMarkdownProjection(answer, sources, isStreaming = true),
        )

        assertEquals("Claim ${marker.token}", projection.markdown)
        assertEquals(sources.map(CitationRecord::sourceId), marker.sources.map(CitationRecord::sourceId))
        assertEquals(1, marker.additionalCount)
        assertEquals("Claim ", streaming.markdown)
        assertTrue(streaming.markers.isEmpty())
    }

    @Test
    fun plainProviderArtifactsAreWithheldWhilePartialAndStrippedWhenUnmatchedAtTerminal() {
        val streaming = requireNotNull(
            citationMarkdownProjection(
                answerText = "Claim citeturn3sear",
                citations = emptyList(),
                isStreaming = true,
            ),
        )
        val terminalPartial = requireNotNull(
            citationMarkdownProjection(
                answerText = "Claim citeturn3sear",
                citations = emptyList(),
                isStreaming = false,
            ),
        )
        val terminalComplete = requireNotNull(
            citationMarkdownProjection(
                answerText = "Claim citeturn9search9",
                citations = emptyList(),
                isStreaming = false,
            ),
        )

        assertEquals("Claim ", streaming.markdown)
        assertEquals("Claim ", terminalPartial.markdown)
        assertEquals("Claim ", terminalComplete.markdown)
    }
}
