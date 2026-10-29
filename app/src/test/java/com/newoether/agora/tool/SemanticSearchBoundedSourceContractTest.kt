package com.newoether.agora.tool

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticSearchBoundedSourceContractTest {
    @Test
    fun semanticSearchSeeksEmbeddingIdentitiesBeforeReadingBoundedVectors() {
        val root = locateMainSourceRoot()
        val dao = source(root, "data/local/ChatSearchDao.kt")
        val daoOwners = dao + source(root, "data/local/ChatDao.kt") +
            source(root, "data/local/ChatMediaReferencesDao.kt")
        val repository = source(root, "data/repository/ConversationRepository.kt") +
            source(root, "data/repository/ConversationMessageSearch.kt")
        val provider = source(root, "tool/RagToolProvider.kt")
        val selector = source(root, "tool/BoundedSemanticEmbeddingSelector.kt")

        assertFalse(provider.contains("getEmbeddingsByModel("))
        assertFalse(repository.contains("fun getEmbeddingsByModel("))
        assertFalse(daoOwners.contains("fun getEmbeddingsByModel("))
        val paging = dao.section("suspend fun getEmbeddingSearchPage(",
            "@Query(\"SELECT id FROM embeddings")
        assertTrue(paging.contains("val legacyPending = !ensureMetadataState().complete"))
        assertTrue(paging.contains("val physicalPageSize = minOf(limit, 256)"))
        assertTrue(paging.contains("getEmbeddingSearchIdsPage(modelId, cursor, physicalPageSize)"))
        assertTrue(paging.indexOf("getEmbeddingSearchIdsPage(") < paging.indexOf("getEmbeddingSearchRowsForIds("))
        assertTrue(paging.indexOf("getEmbeddingSearchIdsPage(") < paging.indexOf("getLegacyEmbeddingSearchRowsForIds("))
        val legacyBranch = paging.section("val page = if (legacyPending) {", "} else {")
        assertTrue(legacyBranch.contains("getLegacyEmbeddingSearchRowsForIds(modelId, ids, minimumTextLength)"))
        val metadataBranch = paging.section("} else {", "result += page.take")
        assertTrue(metadataBranch.contains("getEmbeddingSearchRowsForIds(modelId, ids, minimumTextLength)"))
        assertFalse(metadataBranch.contains("getLegacyEmbeddingSearchRowsForIds("))
        assertTrue(paging.contains("cursor = ids.last()"))
        assertTrue(paging.contains("ensureActive()"))
        assertTrue(paging.contains("yield()"))
        assertFalse(paging.contains("@Transaction"))
        val ids = dao.queryFor("getEmbeddingSearchIdsPage")
        assertTrue(ids.contains("WHERE modelId = :modelId AND id > :afterId ORDER BY id LIMIT :limit"))
        assertFalse(ids.contains("JOIN"))
        val rows = dao.queryFor("getEmbeddingSearchRowsForIds")
        assertTrue(rows.contains("SELECT e.id, e.messageId, e.embedding, e.dimension"))
        assertTrue(rows.contains("WHERE e.id IN (:ids) AND e.modelId = :modelId"))
        assertTrue(rows.contains("CROSS JOIN semantic_sources s ON e.messageId = s.messageId"))
        assertTrue(rows.contains("c.taskId IS NULL AND c.parentConversationId IS NULL"))
        assertTrue(rows.contains("s.historyEligible = 1 AND s.searchLength >= :minimumTextLength"))
        assertTrue(rows.contains("ORDER BY e.id"))
        assertFalse(rows.contains("FROM messages"))
        assertFalse(rows.contains("JOIN messages"))
        assertFalse(rows.contains("CAST("))
        assertFalse(rows.contains("sourcePrefix"))
        val legacyRows = dao.queryFor("getLegacyEmbeddingSearchRowsForIds")
        assertTrue(legacyRows.contains("SELECT e.id, e.messageId, e.embedding, e.dimension"))
        assertTrue(legacyRows.contains("WHERE e.id IN (:ids) AND e.modelId = :modelId"))
        assertTrue(legacyRows.contains("LEFT JOIN semantic_sources s ON s.messageId = e.messageId"))
        assertTrue(legacyRows.contains("LEFT JOIN messages m ON s.messageId IS NULL AND m.id = e.messageId"))
        assertTrue(legacyRows.contains("c.taskId IS NULL AND c.parentConversationId IS NULL"))
        assertTrue(legacyRows.contains("CASE WHEN s.messageId IS NOT NULL"))
        assertTrue(legacyRows.contains("THEN s.historyEligible = 1 AND s.searchLength >= :minimumTextLength"))
        assertTrue(legacyRows.contains("ELSE (\$HISTORY_MESSAGE_ELIGIBILITY) AND LENGTH(m.text) >= :minimumTextLength END"))
        assertTrue(legacyRows.contains("ORDER BY e.id"))
        listOf("SELECT m.*", "SELECT m.text", "sourcePrefix", "CAST(").forEach {
            assertFalse("Legacy ranking must not project source bodies or prefixes: $it", legacyRows.contains(it))
        }
        assertTrue(provider.contains("BoundedSemanticEmbeddingSelector"))
        assertFalse(selector.contains("EmbeddingIndexer.bytesToFloats(row.embedding)"))
        val hydration = dao.queryFor("getSearchableMessagesByIds")
        assertTrue(hydration.contains("m.id IN (:ids) AND \$HISTORY_MESSAGE_ELIGIBILITY"))
    }

    @Test
    fun countRefreshReadsOnlyDurableConversationSummaries() {
        val root = locateMainSourceRoot()
        val dao = source(root, "data/local/ChatSearchDao.kt")
        val counts = dao.section("suspend fun getEmbeddingCacheCounts(", "\n    }")
        assertTrue(dao.contains("@Transaction\n    suspend fun getEmbeddingCacheCounts("))
        assertTrue(counts.contains("complete = false"))
        assertTrue(counts.contains("getSemanticCachedTotals(requestedIds)"))
        assertTrue(counts.contains("getSemanticEligibleTotal()"))
        assertTrue(counts.contains("cached[it] ?: 0"))
        val summaries = dao.queryFor("getSemanticEligibleTotal") + dao.queryFor("getSemanticCachedTotals")
        assertTrue(summaries.contains("SUM(eligibleCount)"))
        assertTrue(summaries.contains("SUM(m.cachedCount)"))
        assertTrue(summaries.contains("cacheEligible = 1"))
        listOf("FROM messages", "JOIN messages", "FROM semantic_sources", "JOIN semantic_sources",
            "FROM embeddings", "JOIN embeddings", "CAST(", "LENGTH(").forEach {
            assertFalse("Count summary must not use $it", summaries.contains(it))
        }
        listOf("getFirstEmbeddingCacheCountPage(", "getEmbeddingCacheCountPage(", "getLegacySource(",
            "getLegacyEmbeddingSearchRowsForIds(", "getConversationSourceIds(", "while (", "yield()").forEach {
            assertFalse("Count refresh must not use $it", counts.contains(it))
        }
    }

    private fun source(root: File, path: String): String =
        File(root, "com/newoether/agora/$path").readText().replace("\r\n", "\n")

    private fun String.section(start: String, end: String): String {
        val startIndex = indexOf(start)
        assertTrue("Missing source boundary: $start", startIndex >= 0)
        val endIndex = indexOf(end, startIndex + start.length)
        assertTrue("Missing source boundary after $start: $end", endIndex > startIndex)
        return substring(startIndex + start.length, endIndex)
    }

    private fun String.queryFor(method: String): String {
        val methodIndex = indexOf("suspend fun $method(")
        assertTrue("Missing DAO operation: $method", methodIndex >= 0)
        val queryIndex = lastIndexOf("@Query(", methodIndex)
        assertTrue("Missing query for $method", queryIndex >= 0)
        return substring(queryIndex, methodIndex)
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
