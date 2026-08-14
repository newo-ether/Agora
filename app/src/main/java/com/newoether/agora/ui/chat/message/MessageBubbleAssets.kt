package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.components.LatexImageTransformer
import com.newoether.agora.ui.chat.caseInsensitiveMatchRanges
import com.newoether.agora.ui.chat.visibleMarkdownMatchRanges
import com.newoether.agora.ui.theme.ChatType
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.elements.LocalTableRowIndex
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The memoized markdown rendering assets shared by a single [MessageItem]: the main
 * chat-body [ChatMarkdownRenderContext] plus the subordinate thought-block typography,
 * colors, padding and components reused by the [SegmentDetailSheet].
 *
 * Extracted from MessageItem so the ~110 lines of typography/color/component wiring
 * live in one place and the message composable reads as layout, not configuration.
 */
@Stable
internal class ChatMarkdownAssets(
    val renderContext: ChatMarkdownRenderContext,
    val thoughtRenderContext: ChatMarkdownRenderContext,
    val colors: MarkdownColors,
    val thoughtTypography: MarkdownTypography,
    val thoughtPadding: MarkdownPadding,
    val components: MarkdownComponents,
    val flavour: MarkdownFlavourDescriptor,
)

@Composable
internal fun rememberChatMarkdownAssets(
    textColor: Color,
    searchHighlight: SearchHighlightSpec? = null,
    parseInlineDollarMath: Boolean = false,
): ChatMarkdownAssets {
    // Chat-specific markdown scale — optimized for immersive reading.
    // Outfit's large x-height means 15sp reads like ~16sp Roboto.
    // Heading steps of 3sp (h1→h2→h3) and 2sp (h3→h4) create
    // a visible but not jarring hierarchy during long-form reading.
    val customTypography = markdownTypography(
        text = ChatType.body,
        paragraph = ChatType.body,
        ordered = ChatType.body,
        bullet = ChatType.body,
        list = ChatType.body,
        h1 = ChatType.mdH1,
        h2 = ChatType.mdH2,
        h3 = ChatType.mdH3,
        h4 = ChatType.mdH4,
        h5 = ChatType.mdH5,
        h6 = ChatType.mdH6,
        code = ChatType.code,
        inlineCode = ChatType.code,
        table = ChatType.body,
    )

    // Compact typography for thought blocks — subordinate to main chat body.
    // One tier below main markdown: body at 13sp (vs 15sp), headings similarly
    // stepped down. Readable for paragraph-level content but clearly secondary.
    val thoughtTypography = markdownTypography(
        text = ChatType.thoughtBody,
        paragraph = ChatType.thoughtBody,
        ordered = ChatType.thoughtBody,
        bullet = ChatType.thoughtBody,
        list = ChatType.thoughtBody,
        h1 = ChatType.thH1,
        h2 = ChatType.thH2,
        h3 = ChatType.thH3,
        h4 = ChatType.thH4,
        h5 = ChatType.thH5,
        h6 = ChatType.thH6,
        code = ChatType.thoughtCode,
        inlineCode = ChatType.thoughtCode,
    )

    val fg = MaterialTheme.colorScheme.onBackground
    val bg = MaterialTheme.colorScheme.surface
    // Composite fg at 0.1 alpha over bg to produce the exact opaque equivalent
    val codeBg = remember(fg, bg) {
        Color(
            red   = fg.red   * 0.1f + bg.red   * 0.9f,
            green = fg.green * 0.1f + bg.green * 0.9f,
            blue  = fg.blue  * 0.1f + bg.blue  * 0.9f,
        )
    }
    val customMarkdownColors = markdownColor(
        codeBackground = codeBg,
        inlineCodeBackground = Color.Transparent,
    )
    val customMarkdownPadding = markdownPadding(block = 8.dp)
    val thoughtMarkdownPadding = markdownPadding(block = 5.dp)
    val searchHighlightColor = SearchHighlightBackground
    val activeSearchHighlightColor = ActiveSearchHighlightBackground

    val customMarkdownComponents = remember(
        searchHighlight,
        searchHighlightColor,
        activeSearchHighlightColor,
    ) {
        lateinit var components: MarkdownComponents
        components = markdownComponents(
            text = { model ->
                SearchHighlightedMarkdownText(
                    model = model,
                    spec = searchHighlight,
                    highlightColor = searchHighlightColor,
                    activeHighlightColor = activeSearchHighlightColor,
                )
            },
            paragraph = { model ->
                SearchHighlightedMarkdownText(
                    model = model,
                    style = model.typography.paragraph,
                    spec = searchHighlight,
                    highlightColor = searchHighlightColor,
                    activeHighlightColor = activeSearchHighlightColor,
                )
            },
            heading1 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h1,
                    MarkdownTokenTypes.ATX_CONTENT,
                    searchHighlight,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            heading2 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h2,
                    MarkdownTokenTypes.ATX_CONTENT,
                    searchHighlight,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            heading3 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h3,
                    MarkdownTokenTypes.ATX_CONTENT,
                    searchHighlight,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            heading4 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h4,
                    MarkdownTokenTypes.ATX_CONTENT,
                    searchHighlight,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            heading5 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h5,
                    MarkdownTokenTypes.ATX_CONTENT,
                    searchHighlight,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            heading6 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h6,
                    MarkdownTokenTypes.ATX_CONTENT,
                    searchHighlight,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            setextHeading1 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h1,
                    MarkdownTokenTypes.SETEXT_CONTENT,
                    searchHighlight,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            setextHeading2 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h2,
                    MarkdownTokenTypes.SETEXT_CONTENT,
                    searchHighlight,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            codeFence = { model ->
                SearchHighlightedMarkdownCode(
                    model = model,
                    fenced = true,
                    spec = searchHighlight,
                    highlightColor = searchHighlightColor,
                    activeHighlightColor = activeSearchHighlightColor,
                )
            },
            codeBlock = { model ->
                SearchHighlightedMarkdownCode(
                    model = model,
                    fenced = false,
                    spec = searchHighlight,
                    highlightColor = searchHighlightColor,
                    activeHighlightColor = activeSearchHighlightColor,
                )
            },
            table = { model ->
                SearchHighlightedMarkdownTable(
                    model = model,
                    spec = searchHighlight,
                    highlightColor = searchHighlightColor,
                    activeHighlightColor = activeSearchHighlightColor,
                )
            },
            custom = { type, model ->
                if (type == MarkdownElementTypes.HTML_BLOCK) {
                    SearchHighlightedMarkdownText(
                        model = model,
                        style = model.typography.paragraph,
                        literalText = requireNotNull(
                            literalHtmlBlockText(model.content, model.node)
                        ),
                        spec = searchHighlight,
                        highlightColor = searchHighlightColor,
                        activeHighlightColor = activeSearchHighlightColor,
                    )
                } else {
                    // Installing a custom component makes the dependency consider every unknown
                    // node handled. Preserve its normal recursive fallback for non-HTML nodes.
                    model.node.children.forEach { child ->
                        MarkdownElement(
                            node = child,
                            components = components,
                            content = model.content,
                        )
                    }
                }
            },
        )
        components
    }
    // Text/code/table components derive typography from each model, so the same component graph
    // serves answer and thought renderers. This keeps glyph-alpha behavior and Markdown spacing
    // identical instead of sending thought/code tails through an unfaded fallback renderer.
    val thoughtMarkdownComponents = customMarkdownComponents

    val latexImageTransformer = remember(textColor) {
        LatexImageTransformer(
            textSize = 56f,
            color = textColor.toArgb(),
        )
    }
    val markdownFlavour = remember { GFMFlavourDescriptor() }
    val markdownRenderContext = remember(
        customMarkdownColors,
        customTypography,
        customMarkdownPadding,
        customMarkdownComponents,
        latexImageTransformer,
        markdownFlavour,
        parseInlineDollarMath,
    ) {
        ChatMarkdownRenderContext(
            colors = customMarkdownColors,
            typography = customTypography,
            padding = customMarkdownPadding,
            components = customMarkdownComponents,
            annotator = literalHtmlMarkdownAnnotator,
            imageTransformer = latexImageTransformer,
            flavour = markdownFlavour,
            plainTextStyle = ChatType.body,
            parseInlineDollarMath = parseInlineDollarMath,
        )
    }
    val thoughtMarkdownRenderContext = remember(
        customMarkdownColors,
        thoughtTypography,
        thoughtMarkdownPadding,
        thoughtMarkdownComponents,
        latexImageTransformer,
        markdownFlavour,
        parseInlineDollarMath,
    ) {
        ChatMarkdownRenderContext(
            colors = customMarkdownColors,
            typography = thoughtTypography,
            padding = thoughtMarkdownPadding,
            components = thoughtMarkdownComponents,
            annotator = literalHtmlMarkdownAnnotator,
            imageTransformer = latexImageTransformer,
            flavour = markdownFlavour,
            plainTextStyle = ChatType.thoughtBody,
            parseInlineDollarMath = parseInlineDollarMath,
        )
    }

    return remember(
        markdownRenderContext,
        thoughtMarkdownRenderContext,
        customMarkdownColors,
        thoughtTypography,
        thoughtMarkdownPadding,
        customMarkdownComponents,
        markdownFlavour,
    ) {
        ChatMarkdownAssets(
            renderContext = markdownRenderContext,
            thoughtRenderContext = thoughtMarkdownRenderContext,
            colors = customMarkdownColors,
            thoughtTypography = thoughtTypography,
            thoughtPadding = thoughtMarkdownPadding,
            components = customMarkdownComponents,
            flavour = markdownFlavour,
        )
    }
}

/**
 * Standalone code surface using the exact same colors, metrics, padding, corner radius, and
 * JetBrains Mono-backed [ChatType.code] style as code blocks in assistant Markdown.
 *
 * This renders raw code directly rather than synthesizing a Markdown fence, so commands that
 * contain backticks can never terminate or corrupt the surrounding block.
 */
@Composable
internal fun ChatMarkdownCodeBlock(
    code: String,
    modifier: Modifier = Modifier,
) {
    val assets = rememberChatMarkdownAssets(MaterialTheme.colorScheme.onSurface)
    androidx.compose.material3.Surface(
        color = assets.renderContext.colors.codeBackground,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        MarkdownBasicText(
            text = AnnotatedString(code),
            style = assets.renderContext.typography.code.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(assets.renderContext.padding.codeBlock),
        )
    }
}

@Composable
private fun OverflowFriendlyMarkdownTable(model: MarkdownComponentModel) {
    MarkdownTable(
        content = model.content,
        node = model.node,
        style = model.typography.table,
        headerBlock = { content, header, tableWidth, style ->
            MarkdownTableHeader(
                content = content,
                header = header,
                tableWidth = tableWidth,
                style = style,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Clip,
            )
        },
        rowBlock = { content, row, tableWidth, style ->
            MarkdownTableRow(
                content = content,
                header = row,
                tableWidth = tableWidth,
                style = style,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Clip,
            )
        },
    )
}

@Composable
private fun SearchHighlightedMarkdownTable(
    model: MarkdownComponentModel,
    spec: SearchHighlightSpec?,
    highlightColor: Color,
    activeHighlightColor: Color,
) {
    MarkdownTable(
        content = model.content,
        node = model.node,
        style = model.typography.table,
        headerBlock = { content, header, tableWidth, style ->
            SearchHighlightedMarkdownTableRow(
                content = content,
                row = header,
                tableWidth = tableWidth,
                style = style,
                typography = model.typography,
                isHeader = true,
                spec = spec,
                highlightColor = highlightColor,
                activeHighlightColor = activeHighlightColor,
            )
        },
        rowBlock = { content, row, tableWidth, style ->
            SearchHighlightedMarkdownTableRow(
                content = content,
                row = row,
                tableWidth = tableWidth,
                style = style,
                typography = model.typography,
                isHeader = false,
                spec = spec,
                highlightColor = highlightColor,
                activeHighlightColor = activeHighlightColor,
            )
        },
    )
}

@Composable
private fun SearchHighlightedMarkdownTableRow(
    content: String,
    row: ASTNode,
    tableWidth: androidx.compose.ui.unit.Dp,
    style: TextStyle,
    typography: MarkdownTypography,
    isHeader: Boolean,
    spec: SearchHighlightSpec?,
    highlightColor: Color,
    activeHighlightColor: Color,
) {
    val rowIndex = if (isHeader) 0 else LocalTableRowIndex.current
    val rowModifier = if (isHeader) {
        Modifier.widthIn(tableWidth).height(IntrinsicSize.Max)
    } else {
        Modifier.widthIn(tableWidth)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = rowModifier,
    ) {
        row.children.filter { it.type == CELL }.forEachIndexed { columnIndex, cell ->
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .weight(1f)
                    .semantics {
                        if (isHeader) heading()
                        collectionItemInfo = CollectionItemInfo(
                            rowIndex = rowIndex,
                            rowSpan = 1,
                            columnIndex = columnIndex,
                            columnSpan = 1,
                        )
                    },
            ) {
                SearchHighlightedMarkdownText(
                    model = MarkdownComponentModel(
                        content = content,
                        node = cell,
                        typography = typography,
                    ),
                    style = if (isHeader) style.copy(fontWeight = FontWeight.Bold) else style,
                    spec = spec,
                    highlightColor = highlightColor,
                    activeHighlightColor = activeHighlightColor,
                )
            }
        }
    }
}

@Composable
private fun SearchHighlightedMarkdownText(
    model: MarkdownComponentModel,
    style: TextStyle = model.typography.text,
    textNode: ASTNode = model.node,
    modifier: Modifier = Modifier,
    literalText: String? = null,
    spec: SearchHighlightSpec?,
    highlightColor: Color,
    activeHighlightColor: Color,
) {
    val settings = annotatorSettings()
    val base = remember(model.content, textNode, style, literalText, settings) {
        if (literalText != null) {
            AnnotatedString(literalText)
        } else {
            model.content.buildMarkdownAnnotatedString(
                textNode = textNode,
                style = style,
                annotatorSettings = settings,
            )
        }
    }
    val streamingFadeSpec = LocalStreamingGlyphFadeSpec.current
    val fadeTargetOffset = streamingFadeSpec?.lastVisibleSourceOffset
    val fadeThisNode =
        fadeTargetOffset != null &&
            fadeTargetOffset > textNode.startOffset &&
            fadeTargetOffset <= textNode.endOffset
    val fadeColor = style.color
        .takeUnless { it == Color.Unspecified }
        ?: LocalContentColor.current
    if (spec == null) {
        val renderedText = rememberStreamingGlyphFade(
            content = base,
            color = fadeColor,
            enabled = fadeThisNode,
        )
        MarkdownText(
            content = renderedText,
            node = model.node,
            modifier = modifier,
            style = style,
            sourceContent = model.content,
        )
        return
    }
    val sourceMatches = remember(model.content, textNode, spec.query) {
        sourceMatchesForNode(model.content, textNode, spec.query)
    }
    val displayRanges = remember(base.text, spec.query) {
        caseInsensitiveMatchRanges(base.text, spec.query)
    }
    val displayMatches = remember(displayRanges, sourceMatches, spec.matchKeys) {
        displayRanges.mapIndexedNotNull { index, range ->
            val sourceOccurrence = sourceMatches.getOrNull(index) ?: return@mapIndexedNotNull null
            spec.matchKeys.getOrNull(sourceOccurrence)?.let { key ->
                DisplaySearchMatch(key, range)
            }
        }
    }
    val activeOccurrence = displayMatches.indexOfFirst { it.key == spec.activeKey }
        .takeIf { it >= 0 }
    val highlighted = remember(
        base,
        spec.query,
        activeOccurrence,
        highlightColor,
        activeHighlightColor,
    ) {
        highlightedSearchText(
            text = base,
            query = spec.query,
            activeOccurrence = activeOccurrence,
            highlightColor = highlightColor,
            activeHighlightColor = activeHighlightColor,
        )
    }
    val renderedText = rememberStreamingGlyphFade(
        content = highlighted.first,
        color = fadeColor,
        enabled = fadeThisNode,
    )
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    ReportSearchPositions(
        spec = spec,
        displayMatches = displayMatches,
        layoutResult = layoutResult,
        coordinates = coordinates,
    )
    MarkdownText(
        content = renderedText,
        node = model.node,
        modifier = modifier.onGloballyPositioned { coordinates = it },
        style = style,
        onTextLayout = { result, _ -> layoutResult = result },
        sourceContent = model.content,
    )
}

@Composable
private fun SearchHighlightedMarkdownHeading(
    model: MarkdownComponentModel,
    style: TextStyle,
    contentType: org.intellij.markdown.IElementType,
    spec: SearchHighlightSpec?,
    highlightColor: Color,
    activeHighlightColor: Color,
) {
    SearchHighlightedMarkdownText(
        model = model,
        style = style,
        textNode = model.node.findChildOfType(contentType) ?: model.node,
        modifier = Modifier.semantics { heading() },
        spec = spec,
        highlightColor = highlightColor,
        activeHighlightColor = activeHighlightColor,
    )
}

@Composable
private fun SearchHighlightedMarkdownCode(
    model: MarkdownComponentModel,
    fenced: Boolean,
    spec: SearchHighlightSpec?,
    highlightColor: Color,
    activeHighlightColor: Color,
) {
    val streamingFadeSpec = LocalStreamingGlyphFadeSpec.current
    val fadeTargetOffset = streamingFadeSpec?.lastVisibleSourceOffset
    val fadeThisNode =
        fadeTargetOffset != null &&
            fadeTargetOffset > model.node.startOffset &&
            fadeTargetOffset <= model.node.endOffset
    // Every state — idle, streaming (fade), and search — renders through the header-bearing
    // [SearchHighlightedMarkdownCodeText] path so the copy button is always present. Falling back
    // to the library's plain fence at terminal is what made the button vanish once the streaming
    // fade stopped. With spec == null and no fade the highlight is a no-op, so the idle block is
    // just plain code text under the same copy header.

    val sourceRange = if (spec == null) {
        null
    } else {
        remember(model.content, model.node, fenced) {
            markdownCodeSourceRange(model.content, model.node, fenced)
        }
    }
    val sourceMatches = if (spec == null) {
        emptyList()
    } else {
        remember(model.content, sourceRange, spec.query) {
            sourceRange?.let { range ->
                val all = visibleMarkdownMatchRanges(model.content, spec.query)
                all.indices.filter { index ->
                    val match = all[index]
                    match.first >= range.first && match.last <= range.last
                }
            }.orEmpty()
        }
    }
    val block: @Composable (String, String?, TextStyle) -> Unit = { code, language, style ->
        SearchHighlightedMarkdownCodeText(
            code = code,
            language = language,
            style = style,
            spec = spec,
            sourceMatches = sourceMatches,
            highlightColor = highlightColor,
            activeHighlightColor = activeHighlightColor,
            fadeEnabled = fadeThisNode,
        )
    }
    if (fenced) {
        MarkdownCodeFence(model.content, model.node, model.typography.code, block)
    } else {
        MarkdownCodeBlock(model.content, model.node, model.typography.code, block)
    }
}

@Composable
private fun SearchHighlightedMarkdownCodeText(
    code: String,
    language: String?,
    style: TextStyle,
    spec: SearchHighlightSpec?,
    sourceMatches: List<Int>,
    highlightColor: Color,
    activeHighlightColor: Color,
    fadeEnabled: Boolean,
) {
    val displayMatches = if (spec == null) {
        emptyList()
    } else {
        val displayRanges = remember(code, spec.query) {
            caseInsensitiveMatchRanges(code, spec.query)
        }
        remember(displayRanges, sourceMatches, spec.matchKeys) {
            displayRanges.mapIndexedNotNull { index, range ->
                val sourceOccurrence =
                    sourceMatches.getOrNull(index) ?: return@mapIndexedNotNull null
                spec.matchKeys.getOrNull(sourceOccurrence)?.let { key ->
                    DisplaySearchMatch(key, range)
                }
            }
        }
    }
    val highlighted = if (spec == null) {
        AnnotatedString(code)
    } else {
        val activeOccurrence = displayMatches.indexOfFirst { it.key == spec.activeKey }
            .takeIf { it >= 0 }
        remember(
            code,
            spec.query,
            activeOccurrence,
            highlightColor,
            activeHighlightColor,
        ) {
            highlightedSearchText(
                text = AnnotatedString(code),
                query = spec.query,
                activeOccurrence = activeOccurrence,
                highlightColor = highlightColor,
                activeHighlightColor = activeHighlightColor,
            ).first
        }
    }
    val fadeColor = style.color
        .takeUnless { it == Color.Unspecified }
        ?: LocalContentColor.current
    val renderedText = rememberStreamingGlyphFade(
        content = highlighted,
        color = fadeColor,
        enabled = fadeEnabled,
    )
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    if (spec != null) {
        ReportSearchPositions(
            spec = spec,
            displayMatches = displayMatches,
            layoutResult = layoutResult,
            coordinates = coordinates,
        )
    }

    val horizontalScrollState = rememberScrollState()
    val interactionController = LocalStreamingMarkdownInteractionController.current
    val interactionOwner = remember { Any() }
    if (interactionController != null) {
        LaunchedEffect(interactionController, interactionOwner, horizontalScrollState) {
            snapshotFlow { horizontalScrollState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { active ->
                    interactionController.setCodeBlockScrolling(interactionOwner, active)
                }
        }
        DisposableEffect(interactionController, interactionOwner) {
            onDispose {
                interactionController.setCodeBlockScrolling(interactionOwner, active = false)
            }
        }
    }

    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        MarkdownBasicText(
            text = renderedText,
            style = style,
            modifier = Modifier
                .horizontalScroll(horizontalScrollState)
                .padding(8.dp)
                .onGloballyPositioned { coordinates = it },
            onTextLayout = { layoutResult = it },
        )
    }
}

private fun sourceMatchesForNode(
    content: String,
    node: ASTNode,
    query: String,
): List<Int> {
    val start = node.startOffset.coerceIn(0, content.length)
    val end = node.endOffset.coerceIn(start, content.length)
    val matches = visibleMarkdownMatchRanges(content, query)
    return matches.indices.filter { index ->
        val match = matches[index]
        match.first >= start && match.last < end
    }
}

private fun markdownCodeSourceRange(
    content: String,
    node: ASTNode,
    fenced: Boolean,
): IntRange? {
    if (node.children.isEmpty()) return null
    val start: Int
    val endExclusive: Int
    if (fenced) {
        if (node.children.size < 3) return null
        val language = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)
        start = node.children[2].startOffset
        val minimumEndIndex = if (language != null && node.children.size > 3) 3 else 2
        endExclusive = node.children[
            (node.children.size - 2).coerceAtLeast(minimumEndIndex)
        ].endOffset
    } else {
        start = node.children.first().startOffset
        endExclusive = node.children.last().endOffset
    }
    val safeStart = start.coerceIn(0, content.length)
    val safeEnd = endExclusive.coerceIn(safeStart, content.length)
    return safeStart until safeEnd
}
