package com.newoether.agora.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SearxngSearchUrlTest {
    @Test
    fun searxngUsesInstanceDefaultsAndCanonicalizesTrailingSlash() {
        val url = searxngSearchUrl(
            configuredBaseUrl = "https://search.example.test/",
            query = "hello world/中文",
        )

        assertEquals(
            "https://search.example.test/search?q=hello+world%2F%E4%B8%AD%E6%96%87&format=json",
            url,
        )
        assertFalse(url.contains("engines="))
        assertFalse(url.contains(".test//search"))
    }

    @Test
    fun configuredLimitIsThePerCallCeiling() {
        assertEquals(5, webSearchResultLimit(5))
        assertEquals(1, webSearchResultLimit(0))
        assertEquals(1, webSearchResultLimit(-3))
        assertEquals(10, webSearchResultLimit(50))
    }

}

class WebSearchResponseLimitTest {
    @Test
    fun searxngResponseKeepsOnlyTheRequestedNumberOfResults() {
        val response = """
            {"results":[
              {"title":"One","url":"https://example.test/1","content":"First"},
              {"title":"Two","url":"https://example.test/2","content":"Second"},
              {"title":"Three","url":"https://example.test/3","content":"Third"},
              {"title":"Four","url":"https://example.test/4","content":"Fourth"},
              {"title":"Five","url":"https://example.test/5","content":"Fifth"}
            ]}
        """.trimIndent()

        val normalized = Json.parseToJsonElement(
            normalizeStandardSearchResponse(response, query = "example", numResults = 2)
        ).jsonObject
        val results = normalized.getValue("results").jsonArray

        assertEquals(2, results.size)
        assertEquals("One", results[0].jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals("https://example.test/1", results[0].jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals("First", results[0].jsonObject.getValue("description").jsonPrimitive.content)
        assertEquals("Two", results[1].jsonObject.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun serperOrganicResultsUseTheSnippetAndRespectTheLimit() {
        val response = """
            {"organic":[
              {"title":"One","link":"https://example.test/1","snippet":"First"},
              {"title":"Two","link":"https://example.test/2","snippet":"Second"}
            ]}
        """.trimIndent()

        val normalized = Json.parseToJsonElement(
            normalizeStandardSearchResponse(response, query = "example", numResults = 1)
        ).jsonObject
        val results = normalized.getValue("results").jsonArray

        assertEquals(1, results.size)
        assertEquals("https://example.test/1", results[0].jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals("First", results[0].jsonObject.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun braveWebResultsStillNormalizeAfterTheExtraction() {
        val response = """
            {"web":{"results":[
              {"title":"One","url":"https://example.test/1","description":"First"},
              {"title":"Two","url":"https://example.test/2","description":"Second"}
            ]}}
        """.trimIndent()

        val normalized = Json.parseToJsonElement(
            normalizeStandardSearchResponse(response, query = "example", numResults = 1)
        ).jsonObject
        val results = normalized.getValue("results").jsonArray

        assertEquals(1, results.size)
        assertEquals("One", results[0].jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals("First", results[0].jsonObject.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun tavilyResponseKeepsTheAnswerAndLimitsResults() {
        val response = """
            {"answer":"Short answer","results":[
              {"title":"One","url":"https://example.test/1","content":"First","score":0.9},
              {"title":"Two","url":"https://example.test/2","content":"Second","score":0.5},
              {"title":"Three","url":"https://example.test/3","content":"Third"}
            ]}
        """.trimIndent()

        val normalized = Json.parseToJsonElement(
            normalizeTavilySearchResponse(response, query = "example", numResults = 2)
        ).jsonObject
        val results = normalized.getValue("results").jsonArray

        assertEquals("Short answer", normalized.getValue("answer").jsonPrimitive.content)
        assertEquals(2, results.size)
        assertEquals("First", results[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(0.9f, results[0].jsonObject.getValue("score").jsonPrimitive.content.toFloat())
        assertEquals("Two", results[1].jsonObject.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun tavilyResponseWithoutResultsReportsNoResults() {
        val normalized = Json.parseToJsonElement(
            normalizeTavilySearchResponse("""{"answer":"","results":[]}""", query = "example", numResults = 3)
        ).jsonObject

        assertEquals("no_results", normalized.getValue("error").jsonPrimitive.content)
    }
}

class WebSearchProviderEnvelopeTest {
    @Test
    fun attachesTheProviderWithoutTouchingTheResults() {
        val result = withSearchProvider(
            """{"type":"web_search","query":"q","results":[{"title":"One"}]}""",
            "SearXNG",
        )
        val parsed = Json.parseToJsonElement(result).jsonObject

        assertEquals("SearXNG", parsed.getValue("provider").jsonPrimitive.content)
        assertEquals("q", parsed.getValue("query").jsonPrimitive.content)
        assertEquals(1, parsed.getValue("results").jsonArray.size)
        assertEquals(
            "One",
            parsed.getValue("results").jsonArray[0].jsonObject.getValue("title").jsonPrimitive.content,
        )
    }

    @Test
    fun keepsAProviderTheSearchAlreadyReported() {
        val result = withSearchProvider(
            """{"type":"web_search","error":"no_api_key","provider":"Exa"}""",
            "Tavily",
        )

        assertEquals("Exa", Json.parseToJsonElement(result).jsonObject.getValue("provider").jsonPrimitive.content)
    }

    @Test
    fun leavesPayloadsThatAreNotObjectsAlone() {
        assertEquals("not json", withSearchProvider("not json", "Exa"))
        assertEquals("[1,2]", withSearchProvider("[1,2]", "Exa"))
    }
}
