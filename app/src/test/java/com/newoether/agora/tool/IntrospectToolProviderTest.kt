package com.newoether.agora.tool

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.CorpusDocument
import com.newoether.agora.data.ManualIndexer
import com.newoether.agora.data.ManualKeywordScorer
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ManualKeywordScorerTest {

    private val docs = listOf(
        CorpusDocument(
            "scanner",
            "Network Scanner",
            "# Network Scanner\nkeywords: lan scan, wake-on-lan, ports\nDiscover hosts and open ports on your LAN.",
        ),
        CorpusDocument(
            "fileserver",
            "File Server",
            "# File Server\nkeywords: http share upload\nShare a folder over your local network.",
        ),
        CorpusDocument(
            "memory",
            "Memory",
            "# Memory\nkeywords: remember facts\nStore facts across conversations.",
        ),
    )

    @Test
    fun ranksTitleMatchHighest() {
        val ranked = ManualKeywordScorer.rank("network scanner", docs, 3)
        assertTrue(ranked.isNotEmpty())
        assertEquals("scanner", ranked.first().doc.docId)
    }

    @Test
    fun keywordsLineBoostsRelevance() {
        // "wake-on-lan" only appears in the scanner keywords line.
        val ranked = ManualKeywordScorer.rank("wake on lan", docs, 3)
        assertEquals("scanner", ranked.first().doc.docId)
    }

    @Test
    fun unrelatedQueryReturnsNothing() {
        assertTrue(ManualKeywordScorer.rank("bitcoin mining rig", docs, 3).isEmpty())
    }

    @Test
    fun emptyQueryOrCorpusReturnsNothing() {
        assertTrue(ManualKeywordScorer.rank("", docs, 3).isEmpty())
        assertTrue(ManualKeywordScorer.rank("scanner", emptyList(), 3).isEmpty())
    }

    @Test
    fun respectsLimit() {
        val ranked = ManualKeywordScorer.rank("share local network over your", docs, 1)
        assertTrue(ranked.size <= 1)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IntrospectToolProviderTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun indexerWith(vararg docs: CorpusDocument): ManualIndexer {
        // Real ManualIndexer needs a DAO; here we only exercise keyword fallback (config = null),
        // so a relaxed mock DAO is never hit. We stub the corpus by mocking the indexer surface.
        val indexer = mockk<ManualIndexer>()
        coEvery { indexer.pageCount } returns docs.size
        coEvery {
            indexer.search(any(), any(), any(), isNull(), any())
        } answers {
            ManualKeywordScorer.rank(firstArg(), docs.toList(), 3).map {
                com.newoether.agora.data.ManualSearchResult(
                    it.doc.docId, it.doc.title, it.doc.body, it.score.toFloat(), semantic = false,
                )
            }
        }
        return indexer
    }

    private val ctx = GenerationContext(activeEmbeddingConfig = null, embeddingApiKey = "")

    @Test
    fun definitionExposesIntrospectWhenManualPresent() {
        val provider = IntrospectToolProvider(indexerWith(
            CorpusDocument("scanner", "Network Scanner", "# Network Scanner\nscan the lan"),
        ))
        val defs = provider.definitions(ctx)
        assertEquals(1, defs.size)
        assertEquals("introspect", defs.first().function.name)
        assertEquals(listOf("query"), defs.first().function.parameters.required)
        assertTrue(provider.handles("introspect"))
        assertFalse(provider.handles("web_search"))
    }

    @Test
    fun noDefinitionWhenManualEmpty() {
        val empty = mockk<ManualIndexer>()
        coEvery { empty.pageCount } returns 0
        assertTrue(IntrospectToolProvider(empty).definitions(ctx).isEmpty())
    }

    @Test
    fun returnsFullPageWithAnswerOnlyInstruction() = runTest {
        val provider = IntrospectToolProvider(indexerWith(
            CorpusDocument(
                "scanner",
                "Network Scanner",
                "# Network Scanner\nkeywords: lan scan\nDiscover hosts and open ports on your LAN.",
            ),
        ))
        val result = provider.execute("introspect", """{"query":"network scanner"}""", ctx)
        assertTrue(result, result.contains("ONLY from the Agora manual"))
        assertTrue(result, result.contains("Manual page: Network Scanner"))
        assertTrue(result, result.contains("Discover hosts and open ports"))
    }

    @Test
    fun blankQueryIsRejected() = runTest {
        val provider = IntrospectToolProvider(indexerWith(
            CorpusDocument("x", "X", "# X\nbody"),
        ))
        val result = provider.execute("introspect", """{"query":""}""", ctx)
        assertTrue(result, result.contains("query is required"))
    }

    @Test
    fun noMatchTellsAgentManualDoesNotCoverIt() = runTest {
        val provider = IntrospectToolProvider(indexerWith(
            CorpusDocument("scanner", "Network Scanner", "# Network Scanner\nscan the lan"),
        ))
        val result = provider.execute("introspect", """{"query":"quantum teleportation"}""", ctx)
        assertTrue(result, result.contains("no page matching"))
    }
}
