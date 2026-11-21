package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.HistorySearchFilter
import com.newoether.agora.data.local.KeywordSearchRow
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.citationRecords
import com.newoether.agora.model.matchesCitationTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal fun MessageEntity.matchesCitationTitle(query: String): Boolean {
    return citationTitleMatches(toolCallJson, text, query)
}

private fun citationTitleMatches(raw: String?, text: String, query: String): Boolean {
    if (raw == null) return false
    val segments = runCatching { Json.decodeFromString<List<MessageSegment>>(raw) }
        .getOrNull().orEmpty()
    return segments.citationRecords(text).matchesCitationTitle(query)
}
private val citationSearchNewestFirst =
    compareByDescending<MessageEntity>(MessageEntity::timestamp).thenByDescending(MessageEntity::id)

internal suspend fun boundedCitationTitleMatches(
    query: String,
    limit: Int,
    pageSize: Int = 128,
    loadPage: suspend (afterId: String, pageSize: Int) -> List<MessageEntity>,
): List<MessageEntity> {
    if (query.isBlank() || limit <= 0) return emptyList()
    val boundedPageSize = pageSize.coerceIn(1, 256)
    val newestMatches = mutableListOf<MessageEntity>()
    var afterId = ""
    while (true) {
        val page = loadPage(afterId, boundedPageSize)
        if (page.isEmpty()) break
        page.asSequence()
            .filter { it.id > afterId && it.matchesCitationTitle(query) }
            .forEach { candidate ->
                if (newestMatches.none { it.id == candidate.id }) {
                    newestMatches += candidate
                    newestMatches.sortWith(citationSearchNewestFirst)
                    if (newestMatches.size > limit) newestMatches.removeAt(newestMatches.lastIndex)
                }
            }
        val nextAfterId = page.maxOf(MessageEntity::id)
        if (nextAfterId <= afterId) break
        afterId = nextAfterId
        if (page.size < boundedPageSize) break
    }
    return newestMatches
}

internal suspend fun searchConversationMessages(
    chatDao: ChatDao,
    query: String,
    limit: Int,
    filter: HistorySearchFilter = HistorySearchFilter.NONE,
): List<MessageEntity> = withContext(Dispatchers.IO) {
    if (query.isBlank() || limit <= 0) return@withContext emptyList()
    val escapedQuery = escapeLikePattern(query)
    // SQLite LIKE folds only ASCII letters. Other literals can use its faster exact substring scan.
    val literalQuery = query.takeUnless { value -> value.any { it in 'a'..'z' || it in 'A'..'Z' } }
    val ids = boundedKeywordMatches(query, limit) { before, pageSize ->
        if (before == null) chatDao.getKeywordSearchFirstPage(
            escapedQuery, pageSize, literalQuery, filter.conversationId, filter.excludeConversationId,
            filter.participant, filter.afterMs, filter.beforeMs,
        ) else chatDao.getKeywordSearchNextPage(
            escapedQuery, before.timestamp, before.id, pageSize, literalQuery, filter.conversationId,
            filter.excludeConversationId, filter.participant, filter.afterMs, filter.beforeMs,
        )
    }
    if (ids.isEmpty()) return@withContext emptyList()
    // Visibility may change while pages are scanned. Hydrate only the bounded winners.
    chatDao.getSearchableMessagesByIds(ids).filter(filter::matches).sortedWith(citationSearchNewestFirst)
}

internal suspend fun boundedKeywordMatches(
    query: String,
    limit: Int,
    pageSize: Int = 128,
    loadPage: suspend (before: KeywordSearchRow?, pageSize: Int) -> List<KeywordSearchRow>,
): List<String> {
    if (query.isBlank() || limit <= 0) return emptyList()
    val boundedPageSize = pageSize.coerceIn(1, 256)
    val ids = mutableListOf<String>()
    var before: KeywordSearchRow? = null
    while (true) {
        currentCoroutineContext().ensureActive()
        val page = loadPage(before, boundedPageSize)
        if (page.isEmpty()) return ids
        for (row in page) {
            currentCoroutineContext().ensureActive()
            val previous = before
            check(previous == null || row.timestamp < previous.timestamp ||
                row.timestamp == previous.timestamp && row.id < previous.id) {
                "Keyword search page did not advance its cursor"
            }
            before = row
            if (row.directMatch || citationTitleMatches(row.toolCallJson, "", query)) {
                ids += row.id
                if (ids.size == limit) return ids
            }
        }
        if (page.size < boundedPageSize) return ids
        // The DAO suspension ends its read; CPU matching never holds a database connection.
        yield()
    }
}

/** Escapes LIKE wildcards so a literal "%"/"_" in the user's query matches itself
 *  instead of matching everything (paired with ESCAPE '\' in the DAO query). */
private fun escapeLikePattern(query: String): String =
    query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
