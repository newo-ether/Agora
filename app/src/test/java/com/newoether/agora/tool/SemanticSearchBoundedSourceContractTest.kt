package com.newoether.agora.tool

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticSearchBoundedSourceContractTest {
    @Test
    fun semanticSearchHotPathUsesKeysetPagesInsteadOfAFullEmbeddingList() {
        val root = locateMainSourceRoot()
        val dao = File(
            root,
            "com/newoether/agora/data/local/ChatEmbeddingDao.kt",
        ).readText()
            .replace("\r\n", "\n")
        val repository = File(
            root,
            "com/newoether/agora/data/repository/ConversationRepository.kt",
        ).readText()
        val provider = File(root, "com/newoether/agora/tool/RagToolProvider.kt").readText()
        val selector = File(
            root,
            "com/newoether/agora/tool/BoundedSemanticEmbeddingSelector.kt",
        ).readText()

        assertFalse(provider.contains("getEmbeddingsByModel("))
        assertFalse(repository.contains("fun getEmbeddingsByModel("))
        assertFalse(dao.contains("fun getEmbeddingsByModel("))
        assertTrue(dao.contains("fun getEmbeddingSearchPage("))
        assertTrue(dao.contains("ORDER BY e.id"))
        assertTrue(dao.contains("LIMIT :limit"))
        assertTrue(
            dao.contains(
                "FROM embeddings e\n        CROSS JOIN messages m\n" +
                    "        CROSS JOIN conversations c",
            ),
        )
        assertTrue(provider.contains("BoundedSemanticEmbeddingSelector"))
        assertFalse(selector.contains("EmbeddingIndexer.bytesToFloats(row.embedding)"))
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
