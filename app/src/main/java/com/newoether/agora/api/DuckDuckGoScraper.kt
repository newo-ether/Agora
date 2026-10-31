package com.newoether.agora.api

import com.newoether.agora.util.AppUserAgent
import com.newoether.agora.util.executeWithNetworkRetry

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Lightweight DuckDuckGo Lite search scraper with auto-pagination.
 *
 * Scrapes [https://lite.duckduckgo.com/lite/](https://lite.duckduckgo.com/lite/) — DDG's
 * deliberately-simple HTML interface — and returns structured [SearchResult]s.
 * When more results are requested than a single page provides, subsequent pages
 * are fetched automatically using DDG's native pagination (the `vqd` token + `s` offset).
 * Results are deduplicated by URL across pages.
 *
 * **Design**
 * - Zero coupling to ToolProvider, GenerationContext, or UI layers.
 * - OkHttpClient is injected; defaults to the shared [HttpClient.client] singleton.
 * - Regex-only HTML parsing — no Jsoup or other third-party dependency.
 * - DDG redirect URLs (`//duckduckgo.com/l/?uddg=...`) are transparently decoded.
 * - Errors are reported via [SearchResponse] with machine-readable [SearchErrorType] codes,
 *   so callers (e.g. [com.newoether.agora.tool.WebSearchToolProvider]) can produce
 *   meaningful LLM-facing messages.
 *
 * **Usage**
 * ```kotlin
 * val scraper = DuckDuckGoScraper()
 * when (val r = scraper.search("hello world", maxResults = 15)) {
 *     is SearchResponse.Success -> println("Got ${r.results.size} results")
 *     is SearchResponse.Error -> println("Failed: ${r.type} — ${r.message}")
 * }
 * ```
 *
 * **Stability note**
 * DuckDuckGo may rate-limit or CAPTCHA aggressive clients. This provider is best used
 * as a free / zero-config fallback, not the primary search backend. Normal human-paced
 * usage (a few queries per minute) rarely triggers protections.
 */
class DuckDuckGoScraper(
    private val client: OkHttpClient = HttpClient.client,
) {
    /** A single web search result. */
    data class WebResult(
        val title: String,
        val url: String,
        val snippet: String,
    )

    /** Machine-readable error category for [SearchResponse.Error]. */
    enum class SearchErrorType {
        /** DDG returned a CAPTCHA / bot-detection challenge. Temporary; retry later. */
        CAPTCHA,
        /** HTTP-level failure (network error, timeout, non-2xx status). */
        NETWORK_ERROR,
        /** DDG returned a valid response but contained zero search-result rows. */
        NO_RESULTS,
    }

    /** Result of a [search] call. */
    sealed class SearchResponse {
        data class Success(val results: List<WebResult>) : SearchResponse()
        data class Error(val type: SearchErrorType, val message: String) : SearchResponse()
    }

    companion object {
        private const val BASE_URL = "https://lite.duckduckgo.com/lite/"
        // Browser-compatible prefix is required (DDG lite serves bot traffic differently), but
        // the Agora token must stay the shared identity rather than a frozen version string.
        private val USER_AGENT: String get() = "Mozilla/5.0 (compatible; ${AppUserAgent.token})"
        private const val DEFAULT_MAX_RESULTS = 5
        private const val PAGE_SIZE = 10
        private const val MAX_PAGES = 5

        // -- parsing -----------------------------------------------------------------

        private val LINK_TAG_REGEX = Regex("""<a\s[^>]*class=['"]result-link['"][^>]*>""")
        private val LINK_TEXT_REGEX = Regex("""<a\s[^>]*class=['"]result-link['"][^>]*>([\s\S]*?)</a>""")
        private val HREF_REGEX = Regex("""href=['"]([^'"]*?)['"]""")
        private val SNIPPET_REGEX = Regex("""<td[^>]+class=['"]result-snippet['"][^>]*>([\s\S]*?)</td>""")
        private val UDDG_REGEX = Regex("""uddg=([^&]+)""")
        private val HTML_TAG_REGEX = Regex("<[^>]*>")
        private val CAPTCHA_REGEX = Regex("""anomaly-modal|challenge-form|Unfortunately.*bots""")

        /** Extracts the vqd session token from the Next Page form. */
        private val VQD_REGEX = Regex("""name="vqd"\s+value="([^"]*)"""")

        /** Offset value from the Next Page form (e.g. `name="s" value="10"`). */
        private val OFFSET_REGEX = Regex("""name="s"\s+value="(\d+)"""")
    }

    // -- public API ----------------------------------------------------------------

    /**
     * Execute a web search and return up to [maxResults] unique results.
     *
     * Automatically fetches additional pages when [maxResults] exceeds the
     * per-page count (~10). Results are deduplicated by URL. Pagination stops
     * when the target count is reached, no further pages are available, or a
     * page yields zero new unique results.
     *
     * Returns [SearchResponse.Error] with a specific [SearchErrorType] when
     * the search cannot be completed, allowing callers to provide meaningful
     * feedback to the LLM or user.
     */
    fun search(query: String, maxResults: Int = DEFAULT_MAX_RESULTS): SearchResponse {
        val allResults = mutableListOf<WebResult>()
        val seenUrls = mutableSetOf<String>()
        var offset = 0
        var vqd = ""

        for (page in 0 until MAX_PAGES) {
            if (allResults.size >= maxResults) break

            val pageResult = fetchPage(query, offset, vqd)
            when (pageResult) {
                is PageResult.Success -> {
                    val html = pageResult.html

                    if (CAPTCHA_REGEX.containsMatchIn(html)) {
                        return if (allResults.isEmpty()) {
                            SearchResponse.Error(
                                SearchErrorType.CAPTCHA,
                                "DuckDuckGo bot detection triggered. Try again later or use a different search provider."
                            )
                        } else {
                            // Partial results are better than nothing — return what we have.
                            break
                        }
                    }

                    // Update vqd for subsequent pages (first page provides the token).
                    if (page == 0) {
                        vqd = VQD_REGEX.find(html)?.groupValues?.get(1).orEmpty()
                    }

                    val pageResults = parseResults(html, maxResults = PAGE_SIZE)
                    var newInThisPage = 0
                    for (r in pageResults) {
                        if (allResults.size >= maxResults) break
                        if (seenUrls.add(r.url)) {
                            allResults.add(r)
                            newInThisPage++
                        }
                    }

                    if (newInThisPage == 0) break      // No new unique results.
                    if (vqd.isEmpty()) break            // No Next Page button.

                    val nextOffset = OFFSET_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull()
                    if (nextOffset == null) break       // Malformed pagination form.
                    offset = nextOffset
                }

                is PageResult.Error -> {
                    return if (allResults.isEmpty()) {
                        SearchResponse.Error(
                            SearchErrorType.NETWORK_ERROR,
                            pageResult.message
                        )
                    } else {
                        // Partial results — return what we have rather than failing.
                        break
                    }
                }
            }
        }

        return if (allResults.isEmpty()) {
            SearchResponse.Error(
                SearchErrorType.NO_RESULTS,
                "DuckDuckGo returned no results for this query."
            )
        } else {
            SearchResponse.Success(allResults)
        }
    }

    // -- internals ----------------------------------------------------------------

    /** Outcome of a single page fetch. */
    private sealed class PageResult {
        data class Success(val html: String) : PageResult()
        data class Error(val message: String) : PageResult()
    }

    /** Fetch a single page of search results. */
    private fun fetchPage(query: String, offset: Int, vqd: String): PageResult {
        val formBuilder = FormBody.Builder().add("q", query)
        if (offset > 0 && vqd.isNotEmpty()) {
            formBuilder.add("s", offset.toString())
            formBuilder.add("vqd", vqd)
        }

        val request = Request.Builder()
            .url(BASE_URL)
            .header("User-Agent", USER_AGENT)
            .post(formBuilder.build())
            .build()

        return try {
            client.executeWithNetworkRetry(request) { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (body != null) PageResult.Success(body)
                    else PageResult.Error("Empty response body from DuckDuckGo.")
                } else {
                    PageResult.Error("DuckDuckGo returned HTTP ${resp.code}.")
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: IOException) {
            PageResult.Error("Network error: ${e.message}")
        } catch (e: Exception) {
            PageResult.Error("Unexpected error: ${e.message}")
        }
    }

    internal fun parseResults(html: String, maxResults: Int): List<WebResult> {
        val results = mutableListOf<WebResult>()

        val linkTags = LINK_TAG_REGEX.findAll(html).toList()
        val linkTexts = LINK_TEXT_REGEX.findAll(html).toList()
        val snippets = SNIPPET_REGEX.findAll(html).toList()

        for (i in linkTexts.indices) {
            if (results.size >= maxResults) break

            val linkTag = linkTags.getOrNull(i)?.value ?: continue
            val href = HREF_REGEX.find(linkTag)?.groupValues?.get(1) ?: continue
            val title = linkTexts[i].groupValues[1].stripHtml().trim()
            val snippet = snippets.getOrNull(i)?.groupValues?.get(1)?.stripHtml()?.trim().orEmpty()

            val url = extractUrl(href)

            if (url.isNotBlank() && title.isNotBlank()) {
                results.add(WebResult(title, url, snippet))
            }
        }

        return results
    }

    /**
     * Resolve the destination URL from an `<a href>` value.
     *
     * DDG wraps results in its own redirect:
     *   `//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com&rut=...`
     *
     * Some results (especially POST searches) link directly to the destination.
     */
    private fun extractUrl(href: String): String {
        val uddgParam = UDDG_REGEX.find(href)?.groupValues?.get(1)
        if (uddgParam != null) {
            return URLDecoder.decode(uddgParam, "UTF-8")
        }
        return if (href.startsWith("//")) "https:$href" else href
    }

    /** Strip HTML tags and decode common entities. */
    private fun String.stripHtml(): String = replace(HTML_TAG_REGEX, "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
}
