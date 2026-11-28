package com.newoether.agora.remote

/** A successful catalog page is authoritative for membership, including native archives. */
internal fun List<RemoteSession>.replaceCatalogPage(cursor: String?, page: RemoteSessionPage): List<RemoteSession> {
    val first = indexOfFirst { it.listCursor == cursor }
    if (first < 0) return this
    val before = take(first).filter { row -> page.sessions.none { it.id == row.id } }
    val after = drop(first).filter { it.listCursor != cursor && page.sessions.none { row -> row.id == it.id } }
    return before + page.sessions + after
}

/** Refresh the already admitted catalog range without publishing a shortened first page. */
internal suspend fun FiloClient.refreshSessionCatalog(previous: List<RemoteSession>): RemoteSessionPage {
    val pageCount = previous.map { it.listCursor }.distinct().size.coerceAtLeast(1)
    val rows = mutableListOf<RemoteSession>()
    val statuses = mutableListOf<RemoteSessionStatus>()
    val visited = mutableSetOf<String?>()
    var cursor: String? = null
    repeat(pageCount) {
        check(visited.add(cursor)) { "Filo session cursor did not advance" }
        val page = sessions(cursor)
        require(page.nextCursor == null || page.nextCursor !in visited) { "Filo session cursor did not advance" }
        rows += page.sessions
        statuses += page.statuses
        cursor = page.nextCursor
        if (cursor == null) return RemoteSessionPage(rows.distinctBy { it.id }, null, statuses)
    }
    return RemoteSessionPage(rows.distinctBy { it.id }, cursor, statuses)
}
