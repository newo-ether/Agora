package com.newoether.agora.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelSearchTest {
    @Test
    fun requestUsesSearpContractAndClampsLimit() {
        val body = Json.parseToJsonElement(
            parallelSearchRequestBody(query = "nix flake inputs", numResults = 42)
        ).jsonObject

        assertEquals("nix flake inputs", body.getValue("objective").jsonPrimitive.content)
        assertEquals(
            listOf("nix flake inputs"),
            body.getValue("search_queries").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("fast", body.getValue("mode").jsonPrimitive.content)
        val advanced = body.getValue("advanced_settings").jsonObject
        assertEquals("10", advanced.getValue("max_results").jsonPrimitive.content)
        assertEquals(
            PARALLEL_EXCERPT_CHARS.toString(),
            advanced.getValue("excerpt_settings").jsonObject
                .getValue("max_chars_per_result").jsonPrimitive.content,
        )
    }

    @Test
    fun parallelRequestRoutesKeyedCallsToRestAndKeylessCallsToMcp() {
        val keyed = parallelSearchRequest("q", 5, "parallel-key")
        assertEquals(PARALLEL_REST_URL, keyed.url)
        assertEquals("parallel-key", keyed.headers["x-api-key"])

        val keyless = parallelSearchRequest("q", 5, "")
        assertEquals(PARALLEL_MCP_URL, keyless.url)
        assertEquals("application/json, text/event-stream", keyless.headers["Accept"])
        assertNull(keyless.headers["x-api-key"])
    }

    @Test
    fun requestHonoursSmallerResultCounts() {
        val body = Json.parseToJsonElement(parallelSearchRequestBody("kagi", 3)).jsonObject

        assertEquals(
            "3",
            body.getValue("advanced_settings").jsonObject.getValue("max_results").jsonPrimitive.content,
        )
    }

    @Test
    fun responseMapsExcerptsToDescriptionsAndSkipsDuplicates() {
        val response = """
            {
              "search_id": "search_1",
              "results": [
                {
                  "url": "https://example.com/one",
                  "title": "One",
                  "publish_date": null,
                  "excerpts": ["First excerpt.", "Second excerpt."]
                },
                {
                  "url": "https://example.com/one",
                  "title": "Duplicate",
                  "excerpts": ["dup"]
                },
                {
                  "url": "https://example.com/two",
                  "title": "Two",
                  "excerpts": []
                }
              ]
            }
        """.trimIndent()

        val normalized = Json.parseToJsonElement(
            normalizeParallelSearchResponse(response, query = "example", numResults = 5)
        ).jsonObject
        val results = normalized.getValue("results").jsonArray

        assertEquals("web_search", normalized.getValue("type").jsonPrimitive.content)
        assertEquals(2, results.size)
        val first = results[0].jsonObject
        assertEquals("One", first.getValue("title").jsonPrimitive.content)
        assertEquals("https://example.com/one", first.getValue("url").jsonPrimitive.content)
        assertEquals(
            "First excerpt.\n\nSecond excerpt.",
            first.getValue("description").jsonPrimitive.content,
        )
        assertEquals("", results[1].jsonObject.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun responseStopsAtRequestedResultCount() {
        val results = (1..5).joinToString(",") { index ->
            """{"url":"https://example.com/$index","title":"T$index","excerpts":["e"]}"""
        }
        val normalized = Json.parseToJsonElement(
            normalizeParallelSearchResponse("""{"results":[$results]}""", "q", numResults = 2)
        ).jsonObject

        assertEquals(2, normalized.getValue("results").jsonArray.size)
    }

    @Test
    fun responseWithoutResultsReportsNoResults() {
        val normalized = Json.parseToJsonElement(
            normalizeParallelSearchResponse("""{"search_id":"x","results":[]}""", "q", 5)
        ).jsonObject

        assertEquals("no_results", normalized.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun errorEnvelopeSurfacesTypeAndMessage() {
        val normalized = Json.parseToJsonElement(
            normalizeParallelSearchResponse(
                """{"type":"invalid_request","message":"search_queries must not be empty"}""",
                "q",
                5,
            )
        ).jsonObject

        assertEquals("invalid_request", normalized.getValue("error").jsonPrimitive.content)
        assertEquals(
            "search_queries must not be empty",
            normalized.getValue("message").jsonPrimitive.content,
        )
    }

    @Test
    fun normalizerTrimsWhitespaceOnlyUrls() {
        val normalized = Json.parseToJsonElement(
            normalizeParallelSearchResponse(
                """{"results":[{"url":"   ","title":"Blank","excerpts":["x"]}]}""",
                "q",
                5,
            )
        ).jsonObject

        assertTrue(normalized.containsKey("error"))
        assertEquals("no_results", normalized.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun keylessRequestWrapsTheSameArgumentsInJsonRpc() {
        val body = Json.parseToJsonElement(parallelMcpRequestBody("nix flakes")).jsonObject

        assertEquals("2.0", body.getValue("jsonrpc").jsonPrimitive.content)
        assertEquals("tools/call", body.getValue("method").jsonPrimitive.content)
        val params = body.getValue("params").jsonObject
        assertEquals("web_search", params.getValue("name").jsonPrimitive.content)
        val arguments = params.getValue("arguments").jsonObject
        assertEquals("nix flakes", arguments.getValue("objective").jsonPrimitive.content)
        assertEquals(
            listOf("nix flakes"),
            arguments.getValue("search_queries").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun keylessResponseUnwrapsContentTextIntoResults() {
        val payload = """{"search_id":"s1","results":[{"url":"https://example.com/a","title":"A","excerpts":["one","two"]}]}"""
        val envelope = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("result", buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", payload)
                    })
                })
            })
        }.toString()

        val normalized = Json.parseToJsonElement(
            normalizeParallelSearchResponse(envelope, query = "a", numResults = 3)
        ).jsonObject
        val results = normalized.getValue("results").jsonArray

        assertEquals(1, results.size)
        assertEquals("A", results[0].jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(
            "one\n\ntwo",
            results[0].jsonObject.getValue("description").jsonPrimitive.content,
        )
    }

    @Test
    fun keylessToolErrorSurfacesItsGuidanceMessage() {
        val envelope = """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Missing required argument: objective."}],"isError":true}}"""

        val normalized = Json.parseToJsonElement(
            normalizeParallelSearchResponse(envelope, "q", 5)
        ).jsonObject

        assertEquals("search_error", normalized.getValue("error").jsonPrimitive.content)
        assertEquals(
            "Missing required argument: objective.",
            normalized.getValue("message").jsonPrimitive.content,
        )
    }

    @Test
    fun keylessJsonRpcErrorSurfacesItsMessage() {
        val envelope = """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Invalid params"}}"""

        val normalized = Json.parseToJsonElement(
            normalizeParallelSearchResponse(envelope, "q", 5)
        ).jsonObject

        assertEquals("search_error", normalized.getValue("error").jsonPrimitive.content)
        assertEquals("Invalid params", normalized.getValue("message").jsonPrimitive.content)
    }
}
