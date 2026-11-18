package com.newoether.agora.tool

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
