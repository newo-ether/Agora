package com.newoether.agora.ui.chat.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.model.markdownInlineContent
import com.newoether.agora.R
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.ui.chat.caseInsensitiveMatchRanges
import com.newoether.agora.ui.components.SmoothBottomSheet
import com.newoether.agora.ui.components.rememberSmoothBottomSheetState
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton

internal const val CITATION_CAPSULE_TONAL_ELEVATION_DP = 2
internal const val CITATION_CAPSULE_FOREGROUND_ALPHA = 0.7f
internal const val CITATION_CAPSULE_FADE_DURATION_MS = 320
internal const val CITATION_SOURCES_SUMMARY_MIN_HEIGHT_DP = 36
internal const val CITATION_SOURCES_SUMMARY_HORIZONTAL_PADDING_DP = 16
internal const val CITATION_SOURCES_SUMMARY_VERTICAL_PADDING_DP = 8
internal const val CITATION_SOURCES_SUMMARY_ICON_SIZE_DP = 18
internal const val CITATION_SOURCES_SUMMARY_ICON_GAP_DP = 8
internal const val CITATION_INLINE_PRIMARY_MAX_WIDTH_DP = 84
internal const val CITATION_INLINE_HORIZONTAL_PADDING_DP = 14
internal const val CITATION_INLINE_FONT_SIZE_SP = 11
internal const val CITATION_INLINE_LINE_HEIGHT_SP = 12
internal const val CITATION_INLINE_PLACEHOLDER_HEIGHT_SP = 22
internal const val CITATION_INLINE_SUFFIX_GAP_DP = 4
internal const val CITATION_INLINE_OUTER_SPACER_DP = 2
internal const val CITATION_SOURCE_ROW_SHAPE_PERCENT = 50
internal const val CITATION_SOURCE_BADGE_BACKGROUND_ALPHA = 0.12f
internal const val CITATION_SOURCE_BADGE_FOREGROUND_ALPHA = 0.8f

internal fun citationInlineAppearanceKey(marker: CitationInlineMarker): String =
    "citation-inline:${marker.source.sourceId}"

internal data class CitationInlineToken(
    val inlineId: String,
    val alternateText: String,
)

internal val LocalCitationInlineTokens =
    staticCompositionLocalOf<Map<Char, CitationInlineToken>> { emptyMap() }

internal fun citationSummaryVisible(
    showActions: Boolean,
    informationVisible: Boolean,
    sourceCount: Int,
): Boolean = showActions && informationVisible && sourceCount > 0

internal fun citationSourceMatchKeys(
    messageId: String,
    source: CitationRecord,
    titleRanges: List<IntRange>,
): List<String> = titleRanges.map { range ->
    "$messageId:citation:${source.sourceId}:${range.first}:${range.last + 1}"
}

internal fun citationSourcesSheetTitle(
    sourceCount: Int,
    sourcesLabel: String,
): String = "$sourceCount $sourcesLabel"

internal fun citationInlinePlaceholderWidthPx(
    primaryTextWidthPx: Int,
    suffixTextWidthPx: Int?,
    primaryMaxWidthPx: Int,
    horizontalPaddingPx: Int,
    suffixGapPx: Int,
    outerSpacingEachSidePx: Int,
): Int {
    val primaryWidth = primaryTextWidthPx.coerceIn(0, primaryMaxWidthPx.coerceAtLeast(0))
    val baseWidth = primaryWidth + horizontalPaddingPx.coerceAtLeast(0) +
        outerSpacingEachSidePx.coerceAtLeast(0) * 2
    return suffixTextWidthPx?.let { suffixWidth ->
        baseWidth + suffixGapPx.coerceAtLeast(0) + suffixWidth.coerceAtLeast(0)
    } ?: baseWidth
}

@Composable
private fun citationCapsuleBackgroundColor() =
    MaterialTheme.colorScheme.surfaceColorAtElevation(CITATION_CAPSULE_TONAL_ELEVATION_DP.dp)

@Composable
private fun citationCapsuleForegroundColor() =
    MaterialTheme.colorScheme.primary.copy(alpha = CITATION_CAPSULE_FOREGROUND_ALPHA)

@Composable
private fun citationCapsuleFadeModifier(
    animationKey: String,
    visible: Boolean = true,
    sharedDrawAlpha: Animatable<Float, AnimationVector1D>? = null,
): Modifier {
    val ownedDrawAlpha = remember(animationKey) { Animatable(0f) }
    val drawAlpha = sharedDrawAlpha ?: ownedDrawAlpha
    LaunchedEffect(animationKey, visible, drawAlpha) {
        if (visible) {
            drawAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = CITATION_CAPSULE_FADE_DURATION_MS,
                    easing = LinearEasing,
                ),
            )
        } else {
            drawAlpha.snapTo(0f)
        }
    }
    return Modifier.graphicsLayer { alpha = drawAlpha.value }
}

internal fun AnnotatedString.replaceCitationInlineTokens(
    tokens: Map<Char, CitationInlineToken>,
): AnnotatedString {
    if (tokens.isEmpty() || text.none(tokens::containsKey)) return this
    val builder = AnnotatedString.Builder()
    var copiedFrom = 0
    text.forEachIndexed { index, character ->
        val inline = tokens[character] ?: return@forEachIndexed
        if (copiedFrom < index) builder.append(this, copiedFrom, index)
        builder.appendInlineContent(inline.inlineId, inline.alternateText)
        copiedFrom = index + 1
    }
    if (copiedFrom < length) builder.append(this, copiedFrom, length)
    return builder.toAnnotatedString()
}

@Composable
internal fun CitationInlineContentHost(
    projection: CitationMarkdownProjection?,
    onActivate: (List<CitationRecord>) -> Unit,
    content: @Composable () -> Unit,
) {
    val inlineFadeStateByPrimarySource = remember {
        mutableMapOf<String, Animatable<Float, AnimationVector1D>>()
    }
    val markers = projection?.markers.orEmpty()
    val activeFadeKeys = markers
        .map(::citationInlineAppearanceKey)
        .toSet()
    LaunchedEffect(activeFadeKeys) {
        inlineFadeStateByPrimarySource.keys.retainAll(activeFadeKeys)
    }
    val currentOnActivate by rememberUpdatedState(onActivate)
    val existingInlineContent = LocalMarkdownInlineContent.current
    val tokenMap = markers.associate { marker ->
        marker.token to CitationInlineToken(
            inlineId = marker.inlineId,
            alternateText = "[${marker.displayLabel}]",
        )
    }
    val inlineTextStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = CITATION_INLINE_FONT_SIZE_SP.sp,
        lineHeight = CITATION_INLINE_LINE_HEIGHT_SP.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val primaryMaxWidthPx = with(density) {
        CITATION_INLINE_PRIMARY_MAX_WIDTH_DP.dp.roundToPx()
    }
    val horizontalPaddingPx = with(density) {
        CITATION_INLINE_HORIZONTAL_PADDING_DP.dp.roundToPx()
    }
    val suffixGapPx = with(density) {
        CITATION_INLINE_SUFFIX_GAP_DP.dp.roundToPx()
    }
    val outerSpacingEachSidePx = with(density) {
        CITATION_INLINE_OUTER_SPACER_DP.dp.roundToPx()
    }
    val inlineContent = markers.associate { marker ->
        val suffixText = marker.additionalCount
            .takeIf { it > 0 }
            ?.let { "+$it" }
        val primaryTextWidthPx = textMeasurer.measure(
            text = marker.label,
            style = inlineTextStyle,
            maxLines = 1,
        ).size.width
        val suffixTextWidthPx = suffixText?.let { suffix ->
            textMeasurer.measure(
                text = suffix,
                style = inlineTextStyle,
                maxLines = 1,
            ).size.width
        }
        val widthPx = citationInlinePlaceholderWidthPx(
            primaryTextWidthPx = primaryTextWidthPx,
            suffixTextWidthPx = suffixTextWidthPx,
            primaryMaxWidthPx = primaryMaxWidthPx,
            horizontalPaddingPx = horizontalPaddingPx,
            suffixGapPx = suffixGapPx,
            outerSpacingEachSidePx = outerSpacingEachSidePx,
        )
        val width = (widthPx / (density.density * density.fontScale)).sp
        marker.inlineId to InlineTextContent(
            placeholder = Placeholder(
                width = width,
                height = CITATION_INLINE_PLACEHOLDER_HEIGHT_SP.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            val appearanceKey = citationInlineAppearanceKey(marker)
            // The fade Animatable is retained per appearance key inside the map,
            // which is itself remembered, so no remember() wrapper applies here.
            @Suppress("RememberInComposition")
            val sharedDrawAlpha = inlineFadeStateByPrimarySource.getOrPut(appearanceKey) {
                Animatable(0f)
            }
            val accessibilityLabel = if (marker.additionalCount > 0) {
                citationSourcesSheetTitle(
                    sourceCount = marker.sources.size,
                    sourcesLabel = stringResource(R.string.citation_sources),
                )
            } else {
                stringResource(
                    R.string.citation_source_accessibility,
                    marker.number,
                    marker.source.title,
                )
            }
            CitationInlineCapsule(
                primaryText = marker.label,
                additionalCount = marker.additionalCount,
                textStyle = inlineTextStyle,
                animationKey = appearanceKey,
                sharedDrawAlpha = sharedDrawAlpha,
                accessibilityLabel = accessibilityLabel,
                onClick = { currentOnActivate(marker.sources) },
            )
        }
    }
    CompositionLocalProvider(
        LocalCitationInlineTokens provides tokenMap,
        LocalMarkdownInlineContent provides markdownInlineContent(
            existingInlineContent.inlineContent + inlineContent,
        ),
        content = content,
    )
}

@Composable
private fun CitationInlineCapsule(
    primaryText: String,
    additionalCount: Int,
    textStyle: TextStyle,
    animationKey: String,
    sharedDrawAlpha: Animatable<Float, AnimationVector1D>,
    accessibilityLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CITATION_INLINE_OUTER_SPACER_DP.dp)
            .then(
                citationCapsuleFadeModifier(
                    animationKey = animationKey,
                    sharedDrawAlpha = sharedDrawAlpha,
                ),
            )
            .clip(RoundedCornerShape(50))
            .background(citationCapsuleBackgroundColor())
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel }
            .padding(horizontal = (CITATION_INLINE_HORIZONTAL_PADDING_DP / 2).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = primaryText,
            color = citationCapsuleForegroundColor(),
            style = textStyle,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (additionalCount > 0) Modifier.weight(1f, fill = false) else Modifier,
        )
        if (additionalCount > 0) {
            Spacer(Modifier.width(CITATION_INLINE_SUFFIX_GAP_DP.dp))
            Text(
                text = "+$additionalCount",
                color = citationCapsuleForegroundColor(),
                style = textStyle,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun CitationSourcesSummaryCapsule(
    messageId: String,
    citations: List<CitationRecord>,
    searchSpec: SearchHighlightSpec?,
    visible: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (citations.isEmpty()) return
    val label = "${citations.size} ${stringResource(R.string.citation_sources)}"
    val matchKeys = remember(messageId, citations, searchSpec?.query) {
        citations.flatMap { source ->
            citationSourceMatchKeys(
                messageId = messageId,
                source = source,
                titleRanges = caseInsensitiveMatchRanges(
                    source.title,
                    searchSpec?.query.orEmpty(),
                ),
            )
        }
    }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(visible, searchSpec?.query, matchKeys, coordinates) {
        val spec = searchSpec ?: return@LaunchedEffect
        val coords = coordinates?.takeIf { it.isAttached } ?: return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        val centerY = coords.positionInRoot().y + coords.size.height / 2f
        matchKeys.forEach { key ->
            spec.onMatchPosition(key, spec.measurementEpoch, centerY)
        }
    }
    Row(
        modifier = modifier
            .onGloballyPositioned { coordinates = it }
            .heightIn(min = CITATION_SOURCES_SUMMARY_MIN_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(50))
            .background(citationCapsuleBackgroundColor())
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .then(
                if (visible) {
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = label
                    }
                } else {
                    Modifier.clearAndSetSemantics { }
                }
            )
            .padding(
                horizontal = CITATION_SOURCES_SUMMARY_HORIZONTAL_PADDING_DP.dp,
                vertical = CITATION_SOURCES_SUMMARY_VERTICAL_PADDING_DP.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.link_24),
            contentDescription = null,
            modifier = Modifier.size(CITATION_SOURCES_SUMMARY_ICON_SIZE_DP.dp),
            tint = citationCapsuleForegroundColor(),
        )
        Spacer(modifier = Modifier.width(CITATION_SOURCES_SUMMARY_ICON_GAP_DP.dp))
        Text(
            text = label,
            color = citationCapsuleForegroundColor(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun CitationSourcesBottomSheet(
    messageId: String,
    citations: List<CitationRecord>,
    searchSpec: SearchHighlightSpec?,
    onActivate: (CitationRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberSmoothBottomSheetState()
    val listState = rememberLazyListState()
    var pendingActivation by remember { mutableStateOf<CitationRecord?>(null) }
    val sheetSearchSpec = searchSpec?.copy(onMatchPosition = { _, _, _ -> })
    fun collapseThenActivate(source: CitationRecord) {
        pendingActivation = source
        sheetState.requestDismiss()
    }

    SmoothBottomSheet(
        state = sheetState,
        onDismissRequest = {
            val source = pendingActivation
            pendingActivation = null
            onDismiss()
            source?.let(onActivate)
        },
        contentAtTop = {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
        },
        header = {
            Text(
                text = citationSourcesSheetTitle(
                    sourceCount = citations.size,
                    sourcesLabel = stringResource(R.string.citation_sources),
                ),
                style = ChatType.detailTitle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                items = citations,
                key = { _, source -> source.sourceId },
            ) { index, source ->
                CitationSourceRow(
                    messageId = messageId,
                    number = index + 1,
                    source = source,
                    searchSpec = sheetSearchSpec,
                    onActivate = { collapseThenActivate(source) },
                )
            }
        }
    }
}

@Composable
private fun CitationSourceRow(
    messageId: String,
    number: Int,
    source: CitationRecord,
    searchSpec: SearchHighlightSpec?,
    onActivate: () -> Unit,
) {
    val sourceLabel = stringResource(
        R.string.citation_source_accessibility,
        number,
        source.title,
    )
    val titleRanges = caseInsensitiveMatchRanges(
        source.title,
        searchSpec?.query.orEmpty(),
    )
    val titleKeys = citationSourceMatchKeys(messageId, source, titleRanges)
    val activeTitleRange = titleKeys.indexOf(searchSpec?.activeKey)
        .takeIf { it >= 0 }
        ?.let(titleRanges::getOrNull)
    val sourceSearchSpec = searchSpec
        ?.takeIf { titleRanges.isNotEmpty() }
        ?.copy(
            activeRange = activeTitleRange,
            matchKeys = titleKeys,
        )
    val titleColor = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                citationCapsuleFadeModifier(
                    animationKey = "citation-source-row:$messageId:${source.sourceId}",
                ),
            )
            .clip(RoundedCornerShape(CITATION_SOURCE_ROW_SHAPE_PERCENT))
            .clickable(role = Role.Button, onClick = onActivate)
            .semantics(mergeDescendants = true) { contentDescription = sourceLabel }
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CitationBadgeVisual(number)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            SearchHighlightedPlainText(
                text = source.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = TextDecoration.None,
                ),
                color = titleColor,
                spec = sourceSearchSpec,
                modifier = Modifier.fillMaxWidth(),
            )
            val metadata = listOfNotNull(source.fileName, source.location)
                .distinct()
                .joinToString(" - ")
            if (metadata.isNotBlank()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CitationBadgeVisual(number: Int) {
    val width = if (number < 10) 20.dp else 26.dp
    Box(
        modifier = Modifier
            .size(width = width, height = 20.dp)
            .clip(CircleShape)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = CITATION_SOURCE_BADGE_BACKGROUND_ALPHA,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = CITATION_SOURCE_BADGE_FOREGROUND_ALPHA,
            ),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun CitationSourceDetailDialog(
    source: CitationRecord,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(source.title) },
        text = {
            Column {
                source.fileName?.let {
                    Text("${stringResource(R.string.citation_source_file)}: $it")
                }
                source.location?.let {
                    Text("${stringResource(R.string.citation_source_location)}: $it")
                }
                source.excerpt?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        },
    )
}
