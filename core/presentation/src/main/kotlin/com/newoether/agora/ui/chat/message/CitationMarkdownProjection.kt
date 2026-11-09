package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.CitationRecord
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.net.URI

private const val CitationTokenStart = 0xE300
private const val CitationTokenEnd = 0xF8FF
private val PlainCitationArtifact = Regex("cite(?:turn\\d+[a-z]+\\d+)+", RegexOption.IGNORE_CASE)
private val PlainCitationSourceId = Regex("turn\\d+[a-z]+\\d+", RegexOption.IGNORE_CASE)
private val TrailingPlainCitationArtifact = Regex("cite(?:turn\\d+[a-z]+\\d+)*(?:turn(?:\\d+(?:[a-z]+\\d*)?)?)$", RegexOption.IGNORE_CASE)
data class CitationInlineMarker(
    val token: Char,
    val inlineId: String,
    val number: Int,
    val label: String,
    val sources: List<CitationRecord>,
) {
    val source: CitationRecord
        get() = sources.first()
    val additionalCount: Int
        get() = (sources.size - 1).coerceAtLeast(0)
    val displayLabel: String
        get() = if (additionalCount > 0) "$label +$additionalCount" else label
}

data class CitationMarkdownProjection(
    val markdown: String,
    val markers: List<CitationInlineMarker>,
    val sourcePositions: List<Int> = emptyList(),
)

private data class CitationProjectionCandidate(
    val sourceIndex: Int,
    val source: CitationRecord,
    val startIndex: Int,
    val endIndex: Int,
    val replacesPresentation: Boolean,
)

private data class CitationProjectionEdit(
    val startIndex: Int,
    val endIndex: Int,
    val replacement: String,
)

fun citationInlineLabel(source: CitationRecord): String =
    CitationPolicy.safeHttpUrl(source.url)?.let { safeUrl ->
        runCatching { URI(safeUrl).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf(String::isNotBlank)
    } ?: source.fileName?.takeIf(String::isNotBlank) ?: source.title

fun projectCitationMarkdown(
    answerText: String,
    citations: List<CitationRecord>,
    includeSourcePositions: Boolean = false,
): CitationMarkdownProjection {
    val normalized = CitationPolicy.deduplicate(citations, answerText)
    val originalPositions = if (includeSourcePositions) answerText.indices.toList() else emptyList()
    if (answerText.isEmpty()) {
        return CitationMarkdownProjection(answerText, emptyList(), originalPositions)
    }
    val unsupported by lazy(LazyThreadSafetyMode.NONE) {
        unsupportedMarkdownRanges(answerText)
    }
    val citationWrappers = parenthesizedCitationLinkWrappers(answerText)
    val candidates = normalized.flatMapIndexed { sourceIndex, source ->
        val matchingWrappers = source.url?.let(CitationPolicy::safeHttpUrl)?.let { safeSourceUrl ->
            citationWrappers.filter { wrapper -> wrapper.safeUrl == safeSourceUrl }
        }.orEmpty()
        val anchoredWrappers = matchingWrappers.filter { wrapper ->
            source.anchors.any { anchor ->
                anchor.startIndex < wrapper.endIndex && anchor.endIndex > wrapper.startIndex
            }
        }
        val wrapperCandidates = anchoredWrappers.ifEmpty {
            matchingWrappers.takeIf { it.size == 1 }.orEmpty()
        }.map { wrapper ->
            CitationProjectionCandidate(
                sourceIndex = sourceIndex,
                source = source,
                startIndex = wrapper.startIndex,
                endIndex = wrapper.endIndex,
                replacesPresentation = true,
            )
        }
        val anchored = source.anchors.mapNotNull { anchor ->
            val exact = anchor.startIndex >= 0 &&
                anchor.endIndex <= answerText.length &&
                anchor.endIndex > anchor.startIndex &&
                answerText.substring(anchor.startIndex, anchor.endIndex) == anchor.citedText
            if (!exact) return@mapNotNull null
            if (matchingWrappers.any { wrapper ->
                    anchor.startIndex < wrapper.endIndex && anchor.endIndex > wrapper.startIndex
                }
            ) return@mapNotNull null
            val overlapsUnsupported = unsupported.any { range ->
                anchor.startIndex < range.endExclusive && anchor.endIndex > range.start
            }
            if (overlapsUnsupported) return@mapNotNull null
            CitationProjectionCandidate(
                sourceIndex = sourceIndex,
                source = source,
                startIndex = anchor.startIndex,
                endIndex = anchor.endIndex,
                replacesPresentation = false,
            )
        }
        val structuredCandidates = wrapperCandidates + anchored
        val providerSourceId = source.providerSourceId ?: return@flatMapIndexed structuredCandidates
        structuredCandidates + PlainCitationArtifact.findAll(answerText).mapNotNull { artifact ->
            if (PlainCitationSourceId.findAll(artifact.value).none {
                it.value.equals(providerSourceId, ignoreCase = true)
            }) return@mapNotNull null
            CitationProjectionCandidate(
                sourceIndex = sourceIndex,
                source = source,
                startIndex = artifact.range.first,
                endIndex = artifact.range.last + 1,
                replacesPresentation = true,
            )
        }
    }.distinctBy { candidate ->
        listOf(
            candidate.source.sourceId,
            candidate.startIndex,
            candidate.endIndex,
            candidate.replacesPresentation,
        )
    }
    val replacementCandidates = candidates.filter(CitationProjectionCandidate::replacesPresentation)
    val acceptedReplacements = replacementCandidates.filter { candidate ->
        replacementCandidates.none { other ->
            other !== candidate &&
                (other.startIndex != candidate.startIndex || other.endIndex != candidate.endIndex) &&
                candidate.startIndex < other.endIndex &&
                candidate.endIndex > other.startIndex
        }
    }
    val acceptedInsertions = candidates
        .filterNot(CitationProjectionCandidate::replacesPresentation)
        .filter { candidate ->
            acceptedReplacements.none { replacement ->
                candidate.endIndex in replacement.startIndex until replacement.endIndex
            }
        }
    val placements = (acceptedReplacements + acceptedInsertions)
        .sortedWith(
            compareBy(CitationProjectionCandidate::sourceIndex)
                .thenBy(CitationProjectionCandidate::startIndex),
        )
    if (placements.isEmpty()) return CitationMarkdownProjection(answerText, emptyList(), originalPositions)

    val usedTokens = answerText.toSet().toMutableSet()
    var nextToken = CitationTokenStart
    fun allocateToken(): Char? {
        while (nextToken <= CitationTokenEnd) {
            val candidate = nextToken++.toChar()
            if (usedTokens.add(candidate)) return candidate
        }
        return null
    }

    val markerBySource = linkedMapOf<String, CitationInlineMarker>()
    placements.forEach { placement ->
        if (placement.source.sourceId in markerBySource) return@forEach
        val token = allocateToken() ?: return@forEach
        markerBySource[placement.source.sourceId] = CitationInlineMarker(
            token = token,
            inlineId = "citation-inline:${placement.source.sourceId}",
            number = placement.sourceIndex + 1,
            label = citationInlineLabel(placement.source),
            sources = listOf(placement.source),
        )
    }
    val usablePlacements = placements.filter { it.source.sourceId in markerBySource }
    if (usablePlacements.isEmpty()) {
        return CitationMarkdownProjection(answerText, emptyList(), originalPositions)
    }

    val edits = usablePlacements
        .groupBy { placement ->
            if (placement.replacesPresentation) {
                placement.startIndex to placement.endIndex
            } else {
                placement.endIndex to placement.endIndex
            }
        }
        .map { (range, grouped) ->
            CitationProjectionEdit(
                startIndex = range.first,
                endIndex = range.second,
                replacement = grouped.joinToString(separator = "") { placement ->
                    markerBySource.getValue(placement.source.sourceId).token.toString()
                },
            )
        }
        .sortedWith(
            compareByDescending(CitationProjectionEdit::startIndex)
                .thenByDescending(CitationProjectionEdit::endIndex),
        )
    val projected = StringBuilder(answerText)
    val projectedPositions = if (includeSourcePositions) originalPositions.toMutableList() else null
    edits.forEach { edit ->
        if (edit.startIndex == edit.endIndex) {
            projected.insert(edit.startIndex, edit.replacement)
        } else {
            projected.replace(edit.startIndex, edit.endIndex, edit.replacement)
        }
        projectedPositions?.apply {
            subList(edit.startIndex, edit.endIndex).clear()
            addAll(edit.startIndex, List(edit.replacement.length) { edit.startIndex })
        }
    }

    val markerByToken = markerBySource.values.associateBy(CitationInlineMarker::token)
    val visibleMarkerBySources = linkedMapOf<List<String>, CitationInlineMarker>()
    val collapsed = StringBuilder(projected.length)
    val collapsedPositions = if (includeSourcePositions) mutableListOf<Int>() else null
    var projectedIndex = 0
    while (projectedIndex < projected.length) {
        val markerPosition = projectedPositions?.get(projectedIndex)
        val firstMarker = markerByToken[projected[projectedIndex]]
        if (firstMarker == null) {
            collapsed.append(projected[projectedIndex])
            collapsedPositions?.add(requireNotNull(markerPosition))
            projectedIndex += 1
            continue
        }
        val adjacentMarkers = buildList {
            while (projectedIndex < projected.length) {
                val marker = markerByToken[projected[projectedIndex]] ?: break
                add(marker)
                projectedIndex += 1
            }
        }
        val groupedSources = adjacentMarkers
            .flatMap(CitationInlineMarker::sources)
            .distinctBy(CitationRecord::sourceId)
        val sourceIds = groupedSources.map(CitationRecord::sourceId)
        val marker = visibleMarkerBySources.getOrPut(sourceIds) {
            if (groupedSources.size == 1) {
                markerBySource.getValue(groupedSources.single().sourceId)
            } else {
                val primary = adjacentMarkers.first()
                CitationInlineMarker(
                    token = allocateToken() ?: primary.token,
                    inlineId = "citation-inline-group:${sourceIds.joinToString(separator = "|")}",
                    number = primary.number,
                    label = primary.label,
                    sources = groupedSources,
                )
            }
        }
        collapsed.append(marker.token)
        collapsedPositions?.add(requireNotNull(markerPosition))
    }
    return CitationMarkdownProjection(
        markdown = collapsed.toString(),
        markers = visibleMarkerBySources.values.toList(),
        sourcePositions = collapsedPositions.orEmpty(),
    )
}

private const val StreamingCitationWrapperMaxLength = 4_096
private const val StreamingCitationLabelMaxLength = 256

private fun boundedTrailingCitationWrapperStart(answerText: String): Int {
    val lowerBound = (answerText.length - StreamingCitationWrapperMaxLength).coerceAtLeast(0)
    var index = answerText.length - 2
    while (index >= lowerBound) {
        if (answerText[index] == '(' && answerText[index + 1] == '[') return index
        index -= 1
    }
    return -1
}

internal fun withholdTrailingCitationWrapper(answerText: String): String {
    if (answerText.endsWith('(')) return answerText.dropLast(1)
    val start = boundedTrailingCitationWrapperStart(answerText)
    if (start < 0 || answerText.length - start > StreamingCitationWrapperMaxLength) return answerText
    val suffix = answerText.substring(start)
    if ('\n' in suffix || '\r' in suffix) return answerText

    val targetStart = suffix.indexOf("](", startIndex = 2)
    if (targetStart < 0) {
        val partialLabel = suffix.drop(2)
        return if (
            partialLabel.length <= StreamingCitationLabelMaxLength &&
            partialLabel.none { it == ']' || it == ')' }
        ) {
            answerText.substring(0, start)
        } else {
            answerText
        }
    }
    val label = suffix.substring(2, targetStart)
    if (label.isBlank() || label.length > StreamingCitationLabelMaxLength) return answerText

    val targetAndClose = suffix.substring(targetStart + 2)
    val closeStart = targetAndClose.indexOf("))")
    if (closeStart >= 0) {
        if (closeStart != targetAndClose.length - 2) return answerText
        val target = targetAndClose.substring(0, closeStart)
        return if (
            target.isNotBlank() &&
            target.none(Char::isWhitespace) &&
            CitationPolicy.safeHttpUrl(target) != null
        ) {
            answerText.substring(0, start)
        } else {
            answerText
        }
    }

    if (
        targetAndClose.length > StreamingCitationWrapperMaxLength ||
        targetAndClose.any(Char::isWhitespace)
    ) {
        return answerText
    }
    val lowerTarget = targetAndClose.lowercase()
    val possibleHttpTarget =
        "https://".startsWith(lowerTarget) ||
            "http://".startsWith(lowerTarget) ||
            lowerTarget.startsWith("https://") ||
            lowerTarget.startsWith("http://")
    return if (possibleHttpTarget) answerText.substring(0, start) else answerText
}

fun citationMarkdownProjection(
    answerText: String,
    citations: List<CitationRecord>,
    isStreaming: Boolean,
    includeSourcePositions: Boolean = false,
): CitationMarkdownProjection? {
    if (answerText.isEmpty()) return null
    val projection = if (isStreaming) {
        val markdown = withholdTrailingCitationWrapper(answerText)
        val partialStart = TrailingPlainCitationArtifact.find(markdown)?.range?.first
        val visible = partialStart?.let { markdown.substring(0, it) } ?: markdown
        CitationMarkdownProjection(
            markdown = visible,
            markers = emptyList(),
            sourcePositions = if (includeSourcePositions) visible.indices.toList() else emptyList(),
        )
    } else {
        projectCitationMarkdown(answerText, citations, includeSourcePositions)
    }
    val positions = if (includeSourcePositions) projection.sourcePositions.toMutableList() else null
    return projection.copy(
        markdown = CitationPolicy.stripPrivateMarkers(projection.markdown, positions),
        sourcePositions = positions.orEmpty(),
    )
}

fun citationRecordsForAnswerSlice(
    citations: List<CitationRecord>,
    sliceStart: Int,
    sliceText: String,
): List<CitationRecord> {
    val sliceEnd = sliceStart + sliceText.length
    return citations.map { source ->
        val anchors = source.anchors.mapNotNull { anchor ->
            if (anchor.startIndex < sliceStart || anchor.endIndex > sliceEnd) return@mapNotNull null
            anchor.copy(
                startIndex = anchor.startIndex - sliceStart,
                endIndex = anchor.endIndex - sliceStart,
            )
        }
        source.copy(anchors = anchors)
    }
}

private data class SourceRange(val start: Int, val endExclusive: Int)

private fun unsupportedMarkdownRanges(content: String): List<SourceRange> = runCatching {
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
    buildList { root.collectUnsupportedCitationRanges(this) }
}.getOrElse {
    listOf(SourceRange(0, content.length))
}

private fun ASTNode.collectUnsupportedCitationRanges(target: MutableList<SourceRange>) {
    val typeName = type.toString().uppercase()
    val unsupported = typeName.contains("CODE") ||
        typeName.contains("LINK") ||
        typeName.contains("IMAGE") ||
        typeName.contains("HTML")
    if (unsupported) {
        if (endOffset > startOffset) target += SourceRange(startOffset, endOffset)
        return
    }
    children.forEach { it.collectUnsupportedCitationRanges(target) }
}
