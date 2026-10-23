package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentRagEngineTest {

    @Test
    fun shortBodyIsOneChunk() {
        val chunks = DocumentRagEngine.chunk("A short manual page about scanning.")
        assertEquals(1, chunks.size)
        assertEquals("A short manual page about scanning.", chunks.first())
    }

    @Test
    fun emptyOrBlankBodyYieldsNoChunks() {
        assertTrue(DocumentRagEngine.chunk("").isEmpty())
        assertTrue(DocumentRagEngine.chunk("   \n  ").isEmpty())
    }

    @Test
    fun longBodySplitsOnSentenceBoundariesWithinBudget() {
        val sentence = "This is a sentence that is reasonably long and descriptive. "
        val body = sentence.repeat(60) // well over the default 1200-char budget
        val chunks = DocumentRagEngine.chunk(body, maxChars = 300)
        assertTrue("expected multiple chunks", chunks.size > 1)
        assertTrue("each chunk within budget", chunks.all { it.length <= 300 })
        // Reassembled text preserves all sentence content (ignoring whitespace).
        val joined = chunks.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        assertTrue(joined.contains("reasonably long and descriptive"))
    }

    @Test
    fun oversizedSingleSentenceIsHardSplit() {
        val giant = "x".repeat(1000)
        val chunks = DocumentRagEngine.chunk(giant, maxChars = 200)
        assertTrue(chunks.size >= 5)
        assertTrue(chunks.all { it.length <= 200 })
    }

    @Test
    fun fingerprintIsStableAndOrderIndependent() {
        val a = CorpusDocument("scanner", "Network Scanner", "scan the lan")
        val b = CorpusDocument("fileserver", "File Server", "share files")
        val fp1 = DocumentRagEngine.corpusFingerprint(listOf(a, b))
        val fp2 = DocumentRagEngine.corpusFingerprint(listOf(b, a)) // reversed order
        assertEquals("order must not change the fingerprint", fp1, fp2)
    }

    @Test
    fun fingerprintChangesWhenContentChanges() {
        val base = listOf(CorpusDocument("scanner", "Network Scanner", "scan the lan"))
        val edited = listOf(CorpusDocument("scanner", "Network Scanner", "scan the lan now with WoL"))
        assertNotEquals(
            DocumentRagEngine.corpusFingerprint(base),
            DocumentRagEngine.corpusFingerprint(edited),
        )
    }
}
