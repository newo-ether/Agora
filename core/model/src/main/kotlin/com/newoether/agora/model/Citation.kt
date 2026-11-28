package com.newoether.agora.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.MessageDigest

@Serializable
data class CitationAnchor(
    val startIndex: Int,
    val endIndex: Int,
    val citedText: String,
)

@Serializable
data class CitationRecord(
    val version: Int = CitationPolicy.VERSION,
    val sourceId: String,
    val provider: String,
    val kind: String,
    val title: String,
    val url: String? = null,
    val fileName: String? = null,
    val location: String? = null,
    val providerSourceId: String? = null,
    val excerpt: String? = null,
    val anchors: List<CitationAnchor> = emptyList(),
)

object CitationPolicy {
    const val VERSION = 1
    const val MAX_SOURCES = 99
    const val MAX_ANCHORS_PER_SOURCE = 32
    const val MAX_METADATA_CHARS = 512
    const val MAX_URL_CHARS = 4096
    const val MAX_CITED_TEXT_CHARS = 4096
    const val MAX_EXCERPT_CHARS = 8192

    private val json = Json { ignoreUnknownKeys = true }
    private val privateEnvelope = Regex(
        "\uE200(?:cite|filecite)\uE202[^\uE200\uE201]+\uE201",
        RegexOption.IGNORE_CASE,
    )
    private val barePrivateEnvelope = Regex(
        "\uE200(?:turn\\d+[a-z]+\\d+)(?:\uE202turn\\d+[a-z]+\\d+)*\uE201",
        RegexOption.IGNORE_CASE,
    )
    private val cjkTurnMarker = Regex("【turn\\d+[a-z]+\\d+】", RegexOption.IGNORE_CASE)
    private val plainPrivateArtifact = Regex(
        "cite(?:turn\\d+[a-z]+\\d+)+",
        RegexOption.IGNORE_CASE,
    )
    private val markerDelimiter = Regex("[\uE200\uE201\uE202]")
    private val trailingPlainPrivateArtifact = Regex(
        "cite(?:turn\\d+[a-z]+\\d+)*(?:turn(?:\\d+(?:[a-z]+\\d*)?)?)$",
        RegexOption.IGNORE_CASE,
    )

    fun create(
        provider: String,
        kind: String,
        title: String? = null,
        url: String? = null,
        fileName: String? = null,
        location: String? = null,
        providerSourceId: String? = null,
        excerpt: String? = null,
        anchors: List<CitationAnchor> = emptyList(),
        answerText: String? = null,
    ): CitationRecord? {
        val normalizedProvider = boundedMetadata(provider)?.lowercase().orEmpty()
        val normalizedKind = boundedMetadata(kind)?.lowercase().orEmpty()
        if (normalizedProvider.isBlank() || normalizedKind.isBlank()) return null

        val safeUrl = safeHttpUrl(url)
        val normalizedFileName = boundedMetadata(fileName)
        val normalizedPrivateId = boundedMetadata(providerSourceId)
        val explicitTitle = boundedMetadata(title)
        if (
            !url.isNullOrBlank() &&
            safeUrl == null &&
            explicitTitle == null &&
            normalizedFileName == null &&
            normalizedPrivateId == null
        ) {
            return null
        }
        val normalizedTitle = explicitTitle
            ?: normalizedFileName
            ?: safeUrl?.let(::urlHost)
            ?: normalizedProvider.replaceFirstChar { it.uppercase() }
        if (
            safeUrl == null &&
            normalizedFileName == null &&
            normalizedPrivateId == null &&
            normalizedTitle.isBlank()
        ) {
            return null
        }

        val normalizedAnchors = anchors.mapNotNull { anchor ->
            normalizeAnchor(anchor, answerText)
        }.distinct().take(MAX_ANCHORS_PER_SOURCE)
        val identity = safeUrl?.let { "url:$it" }
            ?: listOf(
                normalizedProvider,
                normalizedKind,
                normalizedPrivateId.orEmpty(),
                normalizedFileName.orEmpty(),
                normalizedTitle,
            ).joinToString("\u001f")
        return CitationRecord(
            sourceId = "citation_${sha256(identity).take(24)}",
            provider = normalizedProvider,
            kind = normalizedKind,
            title = normalizedTitle,
            url = safeUrl,
            fileName = normalizedFileName,
            location = boundedMetadata(location),
            providerSourceId = normalizedPrivateId,
            excerpt = bounded(excerpt, MAX_EXCERPT_CHARS),
            anchors = normalizedAnchors,
        )
    }

    fun normalize(record: CitationRecord, answerText: String? = null): CitationRecord? {
        if (record.version != VERSION) return null
        return create(
            provider = record.provider,
            kind = record.kind,
            title = record.title,
            url = record.url,
            fileName = record.fileName,
            location = record.location,
            providerSourceId = record.providerSourceId,
            excerpt = record.excerpt,
            anchors = record.anchors,
            answerText = answerText,
        )
    }

    fun merge(first: CitationRecord, incoming: CitationRecord): CitationRecord {
        require(first.sourceId == incoming.sourceId)
        val anchors = first.anchors.toMutableList()
        incoming.anchors.forEach { anchor ->
            val sameStart = anchors.indexOfFirst { it.startIndex == anchor.startIndex }
            when {
                sameStart >= 0 && anchor.endIndex >= anchors[sameStart].endIndex ->
                    anchors[sameStart] = anchor
                sameStart < 0 && anchor !in anchors && anchors.size < MAX_ANCHORS_PER_SOURCE ->
                    anchors += anchor
            }
        }
        return first.copy(
            title = first.title.ifBlank { incoming.title },
            url = first.url ?: incoming.url,
            fileName = first.fileName ?: incoming.fileName,
            location = first.location ?: incoming.location,
            providerSourceId = first.providerSourceId ?: incoming.providerSourceId,
            excerpt = first.excerpt ?: incoming.excerpt,
            anchors = anchors,
        )
    }

    fun encode(record: CitationRecord): String = json.encodeToString(record)

    fun decode(raw: String, answerText: String? = null): CitationRecord? = runCatching {
        json.decodeFromString<CitationRecord>(raw)
    }.getOrNull()?.let { normalize(it, answerText) }

    fun safeHttpUrl(raw: String?): String? {
        val value = bounded(raw?.trim(), MAX_URL_CHARS) ?: return null
        if (value.any(Char::isWhitespace)) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.userInfo != null) return null
        val host = uri.host?.lowercase()?.takeIf(String::isNotBlank) ?: return null
        val normalizedPort = when {
            scheme == "http" && uri.port == 80 -> -1
            scheme == "https" && uri.port == 443 -> -1
            else -> uri.port
        }
        return runCatching {
            URI(
                scheme,
                null,
                host,
                normalizedPort,
                uri.path,
                uri.query,
                uri.fragment,
            ).normalize().toASCIIString()
        }.getOrNull()?.takeIf { it.length <= MAX_URL_CHARS }
    }

    fun stripPrivateMarkers(text: String, sourcePositions: MutableList<Int>? = null): String {
        var cleaned = text
        for (pattern in listOf(privateEnvelope, barePrivateEnvelope, cjkTurnMarker, plainPrivateArtifact,
            trailingPlainPrivateArtifact, markerDelimiter)) {
            if (sourcePositions != null) {
                pattern.findAll(cleaned).toList().asReversed().forEach { match ->
                    sourcePositions.subList(match.range.first, match.range.last + 1).clear()
                }
            }
            cleaned = cleaned.replace(pattern, "")
        }
        return cleaned
    }

    fun copyText(answer: String, citations: List<CitationRecord>): String {
        val cleaned = stripPrivateMarkers(answer)
        val normalized = deduplicate(citations, cleaned)
        if (normalized.isEmpty()) return cleaned
        val sources = normalized.mapIndexed { index, source ->
            val title = escapeMarkdownLabel(source.title)
            val locationSuffix = source.location?.let { " - $it" }.orEmpty()
            val rendered = source.url?.let { url ->
                "[$title](${url.replace(")", "%29")})"
            } ?: title
            "${index + 1}. $rendered$locationSuffix"
        }
        return cleaned.trimEnd() + "\n\n## Sources\n" + sources.joinToString("\n")
    }

    fun deduplicate(
        citations: Iterable<CitationRecord>,
        answerText: String? = null,
    ): List<CitationRecord> {
        val result = linkedMapOf<String, CitationRecord>()
        citations.forEach { raw ->
            val citation = normalize(raw, answerText) ?: return@forEach
            val existing = result[citation.sourceId]
            if (existing == null) {
                if (result.size < MAX_SOURCES) result[citation.sourceId] = citation
            } else {
                result[citation.sourceId] = merge(existing, citation)
            }
        }
        return result.values.toList()
    }

    private fun normalizeAnchor(
        anchor: CitationAnchor,
        answerText: String?,
    ): CitationAnchor? {
        if (anchor.startIndex < 0 || anchor.endIndex <= anchor.startIndex) return null
        val supplied = bounded(anchor.citedText, MAX_CITED_TEXT_CHARS) ?: return null
        if (answerText == null) {
            if (anchor.endIndex - anchor.startIndex != supplied.length) return null
            return anchor.copy(citedText = supplied)
        }
        if (anchor.endIndex > answerText.length) return null
        val actual = answerText.substring(anchor.startIndex, anchor.endIndex)
        if (actual == supplied) return anchor.copy(citedText = actual)

        val recoveredStart = answerText.indexOf(supplied)
        if (recoveredStart < 0) return null
        if (answerText.indexOf(supplied, recoveredStart + 1) >= 0) return null
        return CitationAnchor(
            startIndex = recoveredStart,
            endIndex = recoveredStart + supplied.length,
            citedText = supplied,
        )
    }

    private fun urlHost(url: String): String = runCatching { URI(url).host }.getOrNull()
        ?.removePrefix("www.")
        ?.takeIf(String::isNotBlank)
        ?: "Source"

    private fun boundedMetadata(value: String?): String? = bounded(value?.trim(), MAX_METADATA_CHARS)

    private fun bounded(value: String?, maxChars: Int): String? {
        val raw = value?.takeIf(String::isNotEmpty) ?: return null
        if (raw.length <= maxChars) return raw
        var end = maxChars
        if (
            end in 1 until raw.length &&
            Character.isHighSurrogate(raw[end - 1]) &&
            Character.isLowSurrogate(raw[end])
        ) {
            end--
        }
        return raw.substring(0, end)
    }

    private fun escapeMarkdownLabel(value: String): String = value
        .replace("\\", "\\\\")
        .replace("[", "\\[")
        .replace("]", "\\]")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

fun CitationRecord.toMessageSegment(): MessageSegment = MessageSegment(
    type = "citation",
    content = CitationPolicy.encode(this),
)

fun MessageSegment.toCitationRecord(answerText: String? = null): CitationRecord? =
    takeIf { it.type == "citation" }
        ?.let { CitationPolicy.decode(it.content, answerText) }

fun List<MessageSegment>.citationRecords(answerText: String? = null): List<CitationRecord> =
    CitationPolicy.deduplicate(mapNotNull { it.toCitationRecord(answerText) }, answerText)

fun ChatMessage.citationRecords(): List<CitationRecord> =
    segments.orEmpty().citationRecords(text)

fun ChatMessage.copyTextWithCitations(): String =
    CitationPolicy.copyText(text, citationRecords())

fun Iterable<CitationRecord>.matchesCitationTitle(query: String): Boolean =
    query.isNotBlank() && any { citation ->
        citation.title.contains(query, ignoreCase = true)
    }
