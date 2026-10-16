package com.newoether.agora.tool

import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.data.UserDocSearchHit
import com.newoether.agora.data.UserDocumentManager
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchDocumentsToolProviderTest {

    private val model = EmbeddingModelConfig(
        id = "emb",
        name = "Embeddings",
        type = EmbeddingModelType.REMOTE,
        remoteModelName = "text-embedding-3-small",
        remoteBaseUrl = "",
        remoteApiKey = "key",
        localFilePath = "",
        batchSize = 16,
    )
    private val ctxWithModel = GenerationContext(activeEmbeddingConfig = model, embeddingApiKey = "key")
    private val ctxNoModel = GenerationContext(activeEmbeddingConfig = null, embeddingApiKey = "")

    private fun managerWith(docCount: Int, hits: List<UserDocSearchHit> = emptyList()): UserDocumentManager {
        val m = mockk<UserDocumentManager>()
        coEvery { m.docCount() } returns docCount
        coEvery { m.search(any(), any(), any(), any(), any()) } returns hits
        return m
    }

    @Test
    fun noDefinitionWithoutAnEmbeddingModel() {
        val provider = SearchDocumentsToolProvider(managerWith(docCount = 3))
        assertTrue(provider.definitions(ctxNoModel).isEmpty())
    }

    @Test
    fun noDefinitionWhenNoDocumentsImported() {
        val provider = SearchDocumentsToolProvider(managerWith(docCount = 0))
        assertTrue(provider.definitions(ctxWithModel).isEmpty())
    }

    @Test
    fun exposesSearchDocumentsWhenModelAndDocsPresent() {
        val provider = SearchDocumentsToolProvider(managerWith(docCount = 2))
        val defs = provider.definitions(ctxWithModel)
        assertEquals(1, defs.size)
        assertEquals("search_documents", defs.first().function.name)
        assertEquals(listOf("query"), defs.first().function.parameters.required)
        assertTrue(provider.handles("search_documents"))
        assertFalse(provider.handles("introspect"))
    }

    @Test
    fun executeFormatsTitleAndSnippetHits() = runTest {
        val provider = SearchDocumentsToolProvider(managerWith(
            docCount = 1,
            hits = listOf(
                UserDocSearchHit("d1", "Quarterly Report", "Revenue grew 12% in Q3.", 0.9f),
            ),
        ))
        val result = provider.execute("search_documents", """{"query":"revenue"}""", ctxWithModel)
        assertTrue(result, result.contains("Quarterly Report"))
        assertTrue(result, result.contains("Revenue grew 12% in Q3."))
    }

    @Test
    fun blankQueryIsRejected() = runTest {
        val provider = SearchDocumentsToolProvider(managerWith(docCount = 1))
        val result = provider.execute("search_documents", """{"query":""}""", ctxWithModel)
        assertTrue(result, result.contains("query is required"))
    }

    @Test
    fun noMatchTellsAgentNothingCovered() = runTest {
        val provider = SearchDocumentsToolProvider(managerWith(docCount = 1, hits = emptyList()))
        val result = provider.execute("search_documents", """{"query":"unrelated"}""", ctxWithModel)
        assertTrue(result, result.contains("No imported documents matched"))
    }

    @Test
    fun unknownToolNameIsRejected() = runTest {
        val provider = SearchDocumentsToolProvider(managerWith(docCount = 1))
        val result = provider.execute("introspect", """{"query":"x"}""", ctxWithModel)
        assertTrue(result, result.contains("unknown tool"))
    }
}
