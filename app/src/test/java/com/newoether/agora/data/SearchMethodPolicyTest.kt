package com.newoether.agora.data

import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchMethodPolicyTest {
    @Test
    fun `semantic falls back to keyword when no embedding model is configured`() {
        assertEquals(
            Constants.SEARCH_METHOD_KEYWORD,
            settledSearchMethod(Constants.SEARCH_METHOD_RAG, semanticAvailable = false),
        )
    }

    @Test
    fun `semantic stays selected while an embedding model exists`() {
        assertEquals(
            Constants.SEARCH_METHOD_RAG,
            settledSearchMethod(Constants.SEARCH_METHOD_RAG, semanticAvailable = true),
        )
    }

    @Test
    fun `keyword and unknown values are untouched`() {
        assertEquals(
            Constants.SEARCH_METHOD_KEYWORD,
            settledSearchMethod(Constants.SEARCH_METHOD_KEYWORD, semanticAvailable = false),
        )
        assertEquals("custom", settledSearchMethod("custom", semanticAvailable = false))
    }
}
