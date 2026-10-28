package com.newoether.agora.tool

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticSearchBoundedSourceContractTest {
    @Test
    fun semanticSearchHotPathUsesKeysetPagesInsteadOfAFullEmbeddingList() {
        val root = locateMainSourceRoot()
        val dao = File(root, "com/newoether/agora/data/local/ChatSearchDao.kt")
            .readText()
            .replace("\r\n", "\n")
        val daoOwners = dao + File(root, "com/newoether/agora/data/local/ChatDao.kt").readText() +
            File(root, "com/newoether/agora/data/local/ChatMediaReferencesDao.kt").readText()
        val repository = File(
            root,
            "com/newoether/agora/data/repository/ConversationRepository.kt",
        ).readText() + File(
            root,
            "com/newoether/agora/data/repository/ConversationMessageSearch.kt",
        ).readText()
        val provider = File(root, "com/newoether/agora/tool/RagToolProvider.kt").readText()
        val selector = File(
            root,
            "com/newoether/agora/tool/BoundedSemanticEmbeddingSelector.kt",
        ).readText()

        assertFalse(provider.contains("getEmbeddingsByModel("))
        assertFalse(repository.contains("fun getEmbeddingsByModel("))
        assertFalse(daoOwners.contains("fun getEmbeddingsByModel("))
        assertTrue(dao.contains("getEmbeddingSearchIdsPage(modelId, cursor"))
        assertTrue(dao.contains("WHERE e.id IN (:ids) AND e.modelId = :modelId"))
        assertTrue(dao.contains("fun getEmbeddingSearchPage("))
        assertTrue(dao.contains("ORDER BY e.id"))
        assertTrue(dao.contains("LIMIT :limit"))
        assertTrue(
            dao.contains(
                "FROM embeddings e\n        CROSS JOIN messages m ON e.messageId = m.id\n" +
                    "        CROSS JOIN conversations c ON m.conversationId = c.id",
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
