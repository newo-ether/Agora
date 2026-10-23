package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.util.DebugLog

/**
 * Loads the bundled user manual (markdown files under assets/manual) into [CorpusDocument]s for
 * semantic indexing and keyword fallback. The `docId` is the file slug (e.g. "scanner"); the title
 * is the first `#` heading; the body keeps the full markdown so `introspect` can return a complete
 * page.
 */
object ManualCorpus {

    const val ASSET_DIR = "manual"
    const val CORPUS = "manual"

    fun load(context: Context): List<CorpusDocument> {
        val assets = context.assets
        val files = runCatching { assets.list(ASSET_DIR)?.toList().orEmpty() }
            .getOrElse { emptyList() }
            .filter { it.endsWith(".md") }
            .sorted()
        if (files.isEmpty()) {
            DebugLog.w(TAG, "no bundled manual pages found under assets/$ASSET_DIR")
            return emptyList()
        }
        return files.mapNotNull { file ->
            runCatching {
                val raw = assets.open("$ASSET_DIR/$file").bufferedReader().use { it.readText() }
                val docId = file.removeSuffix(".md")
                CorpusDocument(docId = docId, title = titleOf(raw, docId), body = raw.trim())
            }.getOrNull()
        }
    }

    /** First markdown H1 (`# Title`) or the slug as a fallback. */
    fun titleOf(markdown: String, fallbackSlug: String): String {
        val heading = markdown.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()
        return heading?.takeIf { it.isNotBlank() }
            ?: fallbackSlug.replace('-', ' ').replaceFirstChar { it.uppercase() }
    }

    /**
     * The searchable text for keyword fallback: title + any `keywords:` line + body, lower-cased.
     * Semantic indexing uses the raw body chunks instead.
     */
    fun searchableText(doc: CorpusDocument): String = buildString {
        append(doc.title).append('\n')
        doc.body.lineSequence()
            .firstOrNull { it.trimStart().startsWith("keywords:", ignoreCase = true) }
            ?.let { append(it).append('\n') }
        append(doc.body)
    }.lowercase()

    private const val TAG = "ManualCorpus"
}
