package com.newoether.agora.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The user-docs corpus shares the document_embeddings table with the bundled manual, so every
 * listing/count/delete used by the management UI and search_documents MUST be corpus-scoped. This
 * guards the isolation contract on the DAO directly, without the embedding network path.
 */
@RunWith(RobolectricTestRunner::class)
class UserDocsCorpusIsolationTest {

    private lateinit var db: ChatDatabase
    private lateinit var dao: DocumentEmbeddingDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.documentEmbeddingDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(corpus: String, docId: String, chunk: Int, title: String) =
        DocumentEmbeddingEntity(
            corpus = corpus,
            docId = docId,
            title = title,
            chunkIndex = chunk,
            modelId = "m",
            embedding = ByteArray(4),
            dimension = 1,
            chunkText = "chunk $chunk of $docId",
            sourceFingerprint = "fp-$docId",
        )

    @Test
    fun listCountAndChunksAreScopedToTheCorpus() = runTest {
        dao.insertAll(listOf(
            row("user_docs", "u1", 0, "User Doc One"),
            row("user_docs", "u1", 1, "User Doc One"),
            row("user_docs", "u2", 0, "User Doc Two"),
            row("manual", "scanner", 0, "Network Scanner"),
        ))

        val userDocs = dao.listDocs("user_docs").associateBy { it.docId }
        assertEquals(setOf("u1", "u2"), userDocs.keys)
        assertEquals(2, userDocs.getValue("u1").chunks)
        assertEquals("User Doc One", userDocs.getValue("u1").title)
        assertEquals(2, dao.docCount("user_docs"))

        // The manual row never appears in the user corpus and vice-versa.
        assertEquals(1, dao.docCount("manual"))
        assertTrue(dao.listDocs("manual").none { it.docId.startsWith("u") })

        val chunks = dao.chunksForDoc("user_docs", "u1", 10)
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.contains("u1") })
    }

    @Test
    fun deleteDocRemovesOnlyThatDocWithinTheCorpus() = runTest {
        dao.insertAll(listOf(
            row("user_docs", "u1", 0, "One"),
            row("user_docs", "u2", 0, "Two"),
            row("manual", "u1", 0, "Collision Same Id Other Corpus"),
        ))

        dao.deleteDoc("user_docs", "u1")

        assertEquals(setOf("u2"), dao.listDocs("user_docs").map { it.docId }.toSet())
        // A same-named docId in another corpus is untouched.
        assertEquals(1, dao.docCount("manual"))
        assertFalse(dao.listDocs("manual").isEmpty())
    }

    @Test
    fun chunksByIdsReturnsTheMatchedChunkNotTheDocumentHead() = runTest {
        // Regression: search_documents used to return a document's FIRST chunks regardless of which
        // chunk matched, so a large log only ever surfaced its header. The chunk-level search now
        // fetches the actual matched chunk by row id — this guards that retrieval.
        dao.insertAll(listOf(
            row("user_docs", "log", 0, "App Log"),   // head (lifecycle noise)
            row("user_docs", "log", 1, "App Log"),
            row("user_docs", "log", 2, "App Log"),   // the chunk a network query would match
        ))
        // Row ids are assigned in insert order; chunkIndex 2 is the 3rd row.
        val all = dao.chunksByIds("user_docs", listOf(1L, 2L, 3L)).associateBy { it.chunkIndex }
        assertEquals(3, all.size)
        // Fetching only the deep chunk's id returns that chunk's text, not the head.
        val deepId = all.getValue(2).id
        val fetched = dao.chunksByIds("user_docs", listOf(deepId)).single()
        assertEquals(2, fetched.chunkIndex)
        assertTrue(fetched.chunkText.contains("chunk 2"))
        assertEquals("log", fetched.docId)
    }
}
