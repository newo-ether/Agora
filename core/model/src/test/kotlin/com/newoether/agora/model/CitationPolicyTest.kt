package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationPolicyTest {
    @Test
    fun `safe url and utf16 anchor normalize into one durable record`() {
        val answer = "A \uD83D\uDE00 grounded claim"
        val citation = CitationPolicy.create(
            provider = "OpenAI",
            kind = "URL",
            title = "Example",
            url = "HTTPS://Example.COM:443/a/../source",
            anchors = listOf(
                CitationAnchor(
                    startIndex = 5,
                    endIndex = answer.length,
                    citedText = "grounded claim",
                ),
            ),
            answerText = answer,
        )

        assertEquals("https://example.com/source", citation?.url)
        assertEquals("grounded claim", citation?.anchors?.single()?.citedText)
        val roundTrip = citation?.toMessageSegment()?.toCitationRecord(answer)
        assertEquals(citation, roundTrip)
    }

    @Test
    fun `item relative anchor relocates to its unique exact final answer occurrence`() {
        val cited = "([openai.com](https://openai.com/research/index/?utm_source=openai))"
        val finalAnswer = "Earlier tool-facing answer.\n\nLater answer $cited"
        val itemRelativeStart = "Later answer ".length
        val persisted = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = "OpenAI Research",
                url = "https://openai.com/research/index/?utm_source=openai",
                anchors = listOf(
                    CitationAnchor(
                        startIndex = itemRelativeStart,
                        endIndex = itemRelativeStart + cited.length,
                        citedText = cited,
                    ),
                ),
            ),
        )

        val recovered = requireNotNull(CitationPolicy.normalize(persisted, finalAnswer))
        val expectedStart = finalAnswer.indexOf(cited)
        assertEquals(
            CitationAnchor(expectedStart, expectedStart + cited.length, cited),
            recovered.anchors.single(),
        )
    }

    @Test
    fun `ambiguous exact cited text remains sources only`() {
        val cited = "([openai.com](https://openai.com/research))"
        val finalAnswer = "$cited and $cited"
        val persisted = requireNotNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = "OpenAI Research",
                url = "https://openai.com/research",
                anchors = listOf(CitationAnchor(4, 4 + cited.length, cited)),
            ),
        )

        val normalized = requireNotNull(CitationPolicy.normalize(persisted, finalAnswer))

        assertTrue(normalized.anchors.isEmpty())
    }

    @Test
    fun `invalid anchor becomes sources only and unsafe url without title is dropped`() {
        val citation = CitationPolicy.create(
            provider = "gemini",
            kind = "url",
            title = "Grounding",
            url = "https://example.com",
            anchors = listOf(CitationAnchor(0, 4, "wrong")),
            answerText = "text",
        )

        assertTrue(citation?.anchors?.isEmpty() == true)
        assertNull(
            CitationPolicy.create(
                provider = "openai",
                kind = "url",
                url = "javascript:alert(1)",
            ),
        )
    }

    @Test
    fun `deduplication preserves source order and merges anchors`() {
        val answer = "first second"
        val first = CitationPolicy.create(
            provider = "openai",
            kind = "url",
            title = "One",
            url = "https://example.com/source",
            anchors = listOf(CitationAnchor(0, 5, "first")),
            answerText = answer,
        )!!
        val repeated = CitationPolicy.create(
            provider = "gemini",
            kind = "url",
            title = "Duplicate provider title",
            url = "https://EXAMPLE.com:443/source",
            anchors = listOf(CitationAnchor(6, 12, "second")),
            answerText = answer,
        )!!

        val result = CitationPolicy.deduplicate(listOf(first, repeated), answer)

        assertEquals(1, result.size)
        assertEquals("One", result.single().title)
        assertEquals(listOf("first", "second"), result.single().anchors.map { it.citedText })
    }

    @Test
    fun `private markers are removed without changing surrounding text`() {
        val raw = "Before\uE200cite\uE202turn0search2\uE202turn0search4\uE201, after " +
            "\u3010turn0search8\u3011 and [ordinary](https://example.com)."

        val cleaned = CitationPolicy.stripPrivateMarkers(raw)

        assertEquals("Before, after  and [ordinary](https://example.com).", cleaned)
    }

    @Test
    fun `plain provider markers are removed from copyable text`() {
        val raw = "Before citeturn3search1turn7search1, after citeturn9search"

        val cleaned = CitationPolicy.stripPrivateMarkers(raw)

        assertEquals("Before , after ", cleaned)
    }

    @Test
    fun `copy emits portable sources without private ids or unsafe urls`() {
        val linked = CitationPolicy.create(
            provider = "openai",
            kind = "url",
            title = "Source [one]",
            url = "https://example.com/a)b",
            providerSourceId = "turn0search2",
        )!!
        val file = CitationPolicy.create(
            provider = "anthropic",
            kind = "file",
            title = "Report",
            fileName = "report.pdf",
            providerSourceId = "file-secret",
            location = "Page 3",
        )!!

        val copied = CitationPolicy.copyText("Answer", listOf(linked, file))

        assertTrue(copied.contains("## Sources"))
        assertTrue(copied.contains("[Source \\[one\\]](https://example.com/a%29b)"))
        assertTrue(copied.contains("2. Report - Page 3"))
        assertFalse(copied.contains("turn0search2"))
        assertFalse(copied.contains("file-secret"))
    }

    @Test
    fun `source and anchor counts are bounded deterministically`() {
        val sources = (0 until 120).map { index ->
            CitationPolicy.create(
                provider = "test",
                kind = "url",
                title = "Source $index",
                url = "https://example.com/$index",
            )!!
        }
        val answer = "x".repeat(40)
        val anchored = CitationPolicy.create(
            provider = "test",
            kind = "document",
            title = "Bounded anchors",
            providerSourceId = "bounded",
            anchors = answer.indices.map { index ->
                CitationAnchor(index, index + 1, "x")
            },
            answerText = answer,
        )!!

        assertEquals(CitationPolicy.MAX_SOURCES, CitationPolicy.deduplicate(sources).size)
        assertEquals(CitationPolicy.MAX_ANCHORS_PER_SOURCE, anchored.anchors.size)
        assertEquals(31, anchored.anchors.last().startIndex)
    }
}
