package com.newoether.agora.data

/**
 * Pure, dependency-free keyword ranking over manual pages. Used as the `introspect` fallback when
 * no embedding model is configured (so self-help always works), and as a deterministic baseline.
 *
 * Scoring is simple TF with a title/keywords boost: each query term contributes its occurrence
 * count in the page's searchable text, with extra weight when it appears in the title or the
 * `keywords:` line. No corpus-wide IDF is needed for a ~30-page manual.
 */
object ManualKeywordScorer {

    data class Scored(val doc: CorpusDocument, val score: Double)

    private val TOKEN = Regex("[a-z0-9]+")

    fun rank(query: String, documents: List<CorpusDocument>, limit: Int): List<Scored> {
        val terms = tokenize(query).distinct()
        if (terms.isEmpty() || documents.isEmpty()) return emptyList()
        return documents
            .map { doc -> Scored(doc, score(terms, doc)) }
            .filter { it.score > 0.0 }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.doc.docId })
            .take(limit)
    }

    private fun score(terms: List<String>, doc: CorpusDocument): Double {
        val title = doc.title.lowercase()
        val keywordsLine = doc.body.lineSequence()
            .firstOrNull { it.trimStart().startsWith("keywords:", ignoreCase = true) }
            ?.lowercase()
            .orEmpty()
        val body = ManualCorpus.searchableText(doc)
        var total = 0.0
        for (term in terms) {
            val bodyHits = countOccurrences(body, term)
            if (bodyHits == 0) continue
            total += bodyHits.toDouble()
            if (title.contains(term)) total += TITLE_BOOST
            if (keywordsLine.contains(term)) total += KEYWORDS_BOOST
        }
        return total
    }

    fun tokenize(text: String): List<String> =
        TOKEN.findAll(text.lowercase()).map { it.value }.filter { it.length >= 2 }.toList()

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    private const val TITLE_BOOST = 5.0
    private const val KEYWORDS_BOOST = 3.0
}
