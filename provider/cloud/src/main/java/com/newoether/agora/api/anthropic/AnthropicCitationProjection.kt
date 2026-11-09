package com.newoether.agora.api.anthropic

import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.CitationRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val citationJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class AnthropicCitation(
    val type: String? = null,
    @SerialName("cited_text") val citedText: String? = null,
    @SerialName("document_index") val documentIndex: Int? = null,
    @SerialName("document_title") val documentTitle: String? = null,
    @SerialName("start_char_index") val startCharIndex: Int? = null,
    @SerialName("end_char_index") val endCharIndex: Int? = null,
    @SerialName("start_page_number") val startPageNumber: Int? = null,
    @SerialName("end_page_number") val endPageNumber: Int? = null,
    @SerialName("start_block_index") val startBlockIndex: Int? = null,
    @SerialName("end_block_index") val endBlockIndex: Int? = null,
    @SerialName("encrypted_index") val encryptedIndex: String? = null,
    @SerialName("search_result_index") val searchResultIndex: Int? = null,
    val title: String? = null,
    val url: String? = null,
    val source: String? = null,
)

internal fun JsonElement.toAnthropicCitationRecord(
    answerStartIndex: Int,
    answerText: String,
): CitationRecord? {
    val citation = runCatching {
        citationJson.decodeFromJsonElement(AnthropicCitation.serializer(), this)
    }.getOrNull() ?: return null
    val anchors = answerText.takeIf(String::isNotEmpty)?.let { text ->
        listOf(
            CitationAnchor(
                startIndex = answerStartIndex,
                endIndex = answerStartIndex + text.length,
                citedText = text,
            ),
        )
    }.orEmpty()

    return when (citation.type?.lowercase()) {
        "web_search_result_location" -> CitationPolicy.create(
            provider = "anthropic",
            kind = "url",
            title = citation.title,
            url = citation.url,
            providerSourceId = citation.encryptedIndex,
            excerpt = citation.citedText,
            anchors = anchors,
        )
        "search_result_location" -> CitationPolicy.create(
            provider = "anthropic",
            kind = if (CitationPolicy.safeHttpUrl(citation.source) != null) "url" else "document",
            title = citation.title,
            url = citation.source,
            location = rangeLocation("Blocks", citation.startBlockIndex, citation.endBlockIndex),
            providerSourceId = searchResultSourceId(citation),
            excerpt = citation.citedText,
            anchors = anchors,
        )
        "char_location" -> citation.toDocumentCitation(
            location = rangeLocation("Characters", citation.startCharIndex, citation.endCharIndex),
            anchors = anchors,
        )
        "page_location" -> citation.toDocumentCitation(
            location = rangeLocation("Pages", citation.startPageNumber, citation.endPageNumber),
            anchors = anchors,
        )
        "content_block_location" -> citation.toDocumentCitation(
            location = rangeLocation("Blocks", citation.startBlockIndex, citation.endBlockIndex),
            anchors = anchors,
        )
        else -> null
    }
}

private fun AnthropicCitation.toDocumentCitation(
    location: String?,
    anchors: List<CitationAnchor>,
): CitationRecord? = CitationPolicy.create(
    provider = "anthropic",
    kind = "document",
    title = documentTitle,
    location = location,
    providerSourceId = documentIndex?.let { "document:$it" },
    excerpt = citedText,
    anchors = anchors,
)

private fun rangeLocation(label: String, start: Int?, end: Int?): String? = when {
    start != null && end != null -> "$label $start-$end"
    start != null -> "$label $start"
    end != null -> "$label $end"
    else -> null
}

private fun searchResultSourceId(citation: AnthropicCitation): String? {
    val parts = listOfNotNull(
        citation.source?.takeIf(String::isNotBlank),
        citation.searchResultIndex?.let { "result:$it" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString("|")
}
