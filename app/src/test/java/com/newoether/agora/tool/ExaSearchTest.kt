package com.newoether.agora.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaSearchTest {
    private fun textBlock(text: String): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("result", buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                })
            })
        }.toString()

    private fun sse(vararg payloads: String): String =
        payloads.joinToString("") { payload -> "event: message\ndata: $payload\n\n" }

    @Test
    fun keylessRequestCallsWebSearchExaWithQueryAndObjective() {
        val body = Json.parseToJsonElement(exaMcpRequestBody("nix flake inputs", 42)).jsonObject

        assertEquals("2.0", body.getValue("jsonrpc").jsonPrimitive.content)
        assertEquals("tools/call", body.getValue("method").jsonPrimitive.content)
        val params = body.getValue("params").jsonObject
        assertEquals("web_search_exa", params.getValue("name").jsonPrimitive.content)
        val arguments = params.getValue("arguments").jsonObject
        assertEquals("nix flake inputs", arguments.getValue("query").jsonPrimitive.content)
        assertEquals("nix flake inputs", arguments.getValue("objective").jsonPrimitive.content)
        assertEquals("10", arguments.getValue("numResults").jsonPrimitive.content)
    }

    @Test
    fun keyedRequestAsksForHighlightsAndClampsLimit() {
        val body = Json.parseToJsonElement(exaSearchRequestBody("kagi", 3)).jsonObject

        assertEquals("kagi", body.getValue("query").jsonPrimitive.content)
        assertEquals("auto", body.getValue("type").jsonPrimitive.content)
        assertEquals("3", body.getValue("numResults").jsonPrimitive.content)
        assertEquals(
            "true",
            body.getValue("contents").jsonObject.getValue("highlights").jsonPrimitive.content,
        )
    }

    @Test
    fun keyedRequestNeverSendsFewerThanOneOrMoreThanTenResults() {
        assertEquals(
            "1",
            Json.parseToJsonElement(exaSearchRequestBody("q", 0)).jsonObject
                .getValue("numResults").jsonPrimitive.content,
        )
        assertEquals(
            "10",
            Json.parseToJsonElement(exaSearchRequestBody("q", 99)).jsonObject
                .getValue("numResults").jsonPrimitive.content,
        )
    }

    @Test
    fun exaRequestRoutesKeyedCallsToRestAndKeylessCallsToMcp() {
        val keyed = exaSearchRequest("q", 5, "exa-key")
        assertEquals(EXA_REST_URL, keyed.url)
        assertEquals("exa-key", keyed.headers["x-api-key"])

        val keyless = exaSearchRequest("q", 5, "")
        assertEquals(EXA_MCP_URL, keyless.url)
        assertEquals("application/json, text/event-stream", keyless.headers["Accept"])
        assertNull(keyless.headers["x-api-key"])
    }

    @Test
    fun eventStreamTextBlocksBecomeHits() {
        val text = """
            Title: Flakes — nix.dev documentation
            URL: https://nix.dev/concepts/flakes.html
            Published: 2025-01-02T00:00:00.000Z
            Author: the Nix documentation team
            Highlights:
            ## What are flakes?#

            Flakes offer an entrypoint file.

            ---

            Title: Second page
            URL: https://example.com/two
            Published: N/A
            Author: N/A
            Highlights:
            Highlight one.
            Highlight two.
        """.trimIndent()

        val outcome = normalizeExaSearchResponse(sse(textBlock(text)), numResults = 5)
            as ExaSearchOutcome.Hits
        val results = outcome.results

        assertEquals(2, results.size)
        val first = results[0].jsonObject
        assertEquals("Flakes — nix.dev documentation", first.getValue("title").jsonPrimitive.content)
        assertEquals("https://nix.dev/concepts/flakes.html", first.getValue("url").jsonPrimitive.content)
        assertEquals(
            "## What are flakes?#\n\nFlakes offer an entrypoint file.",
            first.getValue("description").jsonPrimitive.content,
        )
        assertEquals(
            "Highlight one.\nHighlight two.",
            results[1].jsonObject.getValue("description").jsonPrimitive.content,
        )
    }

    @Test
    fun noticeLinesAreDroppedBeforeParsing() {
        val text = "Note: A query restriction was relaxed.\n\nTitle: One\nURL: https://example.com/1\nPublished: N/A\nAuthor: N/A\nHighlights:\nBody"

        val outcome = normalizeExaSearchResponse(sse(textBlock(text)), numResults = 5)
            as ExaSearchOutcome.Hits

        assertEquals(1, outcome.results.size)
        assertEquals(
            "https://example.com/1",
            outcome.results[0].jsonObject.getValue("url").jsonPrimitive.content,
        )
    }

    @Test
    fun textWithoutHighlightsUsesTheTextFieldAsDescription() {
        val text = "Title: One\nURL: https://example.com/1\nPublished: N/A\nAuthor: N/A\nText: Full page text."

        val outcome = normalizeExaSearchResponse(sse(textBlock(text)), numResults = 5)
            as ExaSearchOutcome.Hits

        assertEquals(
            "Full page text.",
            outcome.results[0].jsonObject.getValue("description").jsonPrimitive.content,
        )
    }

    @Test
    fun emptySearchNoticeIsAnEmptyResult() {
        assertEquals(
            ExaSearchOutcome.EmptyResults,
            normalizeExaSearchResponse(
                sse(textBlock("No search results found. Please try a different query.")),
                5,
            ),
        )
    }

    @Test
    fun resultsStopAtTheRequestedCountAndSkipDuplicateUrls() {
        val blocks = (1..4).joinToString("\n\n---\n\n") { index ->
            "Title: T$index\nURL: https://example.com/$index\nPublished: N/A\nAuthor: N/A\nHighlights:\ne$index"
        }

        val outcome = normalizeExaSearchResponse(sse(textBlock(blocks)), numResults = 2)
            as ExaSearchOutcome.Hits

        assertEquals(2, outcome.results.size)

        val duplicated = "Title: A\nURL: https://example.com/a\nPublished: N/A\nAuthor: N/A\nHighlights:\nx" +
            "\n\n---\n\n" +
            "Title: A again\nURL: https://example.com/a\nPublished: N/A\nAuthor: N/A\nHighlights:\ny"
        val deduped = normalizeExaSearchResponse(sse(textBlock(duplicated)), numResults = 5)
            as ExaSearchOutcome.Hits

        assertEquals(1, deduped.results.size)
    }

    @Test
    fun toolErrorTextIsAFailure() {
        val envelope = """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"web_search_exa error (400): Missing required argument: query."}],"isError":true}}"""

        assertEquals(
            ExaSearchOutcome.Failure("search_error", "web_search_exa error (400): Missing required argument: query."),
            normalizeExaSearchResponse(sse(envelope), 5),
        )
    }

    @Test
    fun jsonRpcErrorSurfacesItsMessage() {
        val envelope = """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Invalid params"}}"""

        assertEquals(
            ExaSearchOutcome.Failure("search_error", "Invalid params"),
            normalizeExaSearchResponse(sse(envelope), 5),
        )
    }

    @Test
    fun plainJsonBodyIsTreatedAsTheRestApi() {
        val body = """
            {
              "requestId": "req_1",
              "results": [
                {
                  "title": "One",
                  "url": "https://example.com/one",
                  "publishedDate": "2025-01-02T00:00:00.000Z",
                  "author": "Someone",
                  "highlights": ["First highlight.", "Second highlight."],
                  "text": "Full text"
                },
                {
                  "title": "Two",
                  "url": "https://example.com/two",
                  "highlights": [],
                  "text": "Only text."
                }
              ]
            }
        """.trimIndent()

        val outcome = normalizeExaSearchResponse(body, numResults = 5) as ExaSearchOutcome.Hits
        val results = outcome.results

        assertEquals(2, results.size)
        assertEquals(
            "First highlight.\n\nSecond highlight.",
            results[0].jsonObject.getValue("description").jsonPrimitive.content,
        )
        assertEquals(
            "Only text.",
            results[1].jsonObject.getValue("description").jsonPrimitive.content,
        )
    }

    @Test
    fun restPayloadWithoutResultsIsAnEmptyResult() {
        assertEquals(
            ExaSearchOutcome.EmptyResults,
            normalizeExaSearchResponse("""{"requestId":"r","results":[]}""", 5),
        )
    }

    @Test
    fun restApiErrorPayloadSurfacesItsMessage() {
        assertEquals(
            ExaSearchOutcome.Failure("search_error", "Invalid API key"),
            normalizeExaSearchResponse("""{"requestId":"r","error":"Invalid API key"}""", 5),
        )
    }

    @Test
    fun blankEventStreamIsAFailure() {
        val outcome = normalizeExaSearchResponse("event: message\ndata: \n\n", 5)

        assertTrue(outcome is ExaSearchOutcome.Failure)
        assertEquals("search_error", (outcome as ExaSearchOutcome.Failure).error)
    }
}
