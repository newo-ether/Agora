package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.ui.components.LatexImageTransformer
import com.newoether.agora.ui.components.latexSourceForLink
import com.newoether.agora.ui.chat.caseInsensitiveMatchRanges
import com.newoether.agora.ui.chat.visibleMarkdownMatchRanges
import com.newoether.agora.ui.theme.ChatType
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
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
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.LocalTableRowIndex
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy

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

internal fun chatLinkTextStyles(color: Color): TextLinkStyles {
    val style = SpanStyle(
        color = color,
        textDecoration = TextDecoration.None,
    )
    return TextLinkStyles(
        style = style,
        focusedStyle = style,
        hoveredStyle = style,
        pressedStyle = style,
    )
}

internal fun buildCitationAwareMarkdownAnnotatedString(
    content: String,
    textNode: ASTNode,
    style: TextStyle,
    annotatorSettings: AnnotatorSettings,
    citationTokens: Map<Char, CitationInlineToken>,
    literalText: String? = null,
): AnnotatedString {
    val annotated = if (literalText != null) {
        AnnotatedString(literalText)
    } else {
        content.buildMarkdownAnnotatedString(
            textNode = textNode,
            style = style,
            annotatorSettings = annotatorSettings,
        )
    }
    return annotated.replaceCitationInlineTokens(citationTokens)
}

private const val MARKDOWN_LINE_HEIGHT_MULTIPLIER = 1.1f

// Center extra leading so bold-only lines do not crowd adjacent regular lines.
internal fun scaledMarkdownTextStyle(style: TextStyle): TextStyle = style.copy(
    lineHeight = style.lineHeight * MARKDOWN_LINE_HEIGHT_MULTIPLIER,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

internal fun ASTNode.needsListParagraphSpacer(): Boolean {
    if (type != MarkdownElementTypes.PARAGRAPH) return false
    val listItem = parent?.takeIf { it.type == MarkdownElementTypes.LIST_ITEM } ?: return false
    return listItem.children
        .takeWhile { it !== this }
        .any { it.type == MarkdownElementTypes.PARAGRAPH }
}

@Composable
internal fun rememberChatMarkdownAssets(
    textColor: Color,
    parseInlineDollarMath: Boolean = false,
    inlineImages: Map<String, com.newoether.agora.model.MarkdownImage> = emptyMap(),
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    preparedMarkdown: Map<String, com.mikepenz.markdown.model.State.Success> = emptyMap(),
    autoWrapCodeBlocks: Boolean = true,
): ChatMarkdownAssets {
    val linkColor = MaterialTheme.colorScheme.primary
    val linkTextStyles = remember(linkColor) { chatLinkTextStyles(linkColor) }
    // Chat-specific markdown scale — optimized for immersive reading.
    // Outfit's large x-height means 15sp reads like ~16sp Roboto.
    // Heading steps of 3sp (h1→h2→h3) and 2sp (h3→h4) create
    // a visible but not jarring hierarchy during long-form reading.
    val markdownBodyStyle = scaledMarkdownTextStyle(ChatType.body)
    val thoughtMarkdownBodyStyle = scaledMarkdownTextStyle(ChatType.thoughtBody)
    val customTypography = markdownTypography(
        text = markdownBodyStyle,
        paragraph = markdownBodyStyle,
        ordered = markdownBodyStyle,
        bullet = markdownBodyStyle,
        list = markdownBodyStyle,
        h1 = scaledMarkdownTextStyle(ChatType.mdH1),
        h2 = scaledMarkdownTextStyle(ChatType.mdH2),
        h3 = scaledMarkdownTextStyle(ChatType.mdH3),
        h4 = scaledMarkdownTextStyle(ChatType.mdH4),
        h5 = scaledMarkdownTextStyle(ChatType.mdH5),
        h6 = scaledMarkdownTextStyle(ChatType.mdH6),
        code = scaledMarkdownTextStyle(ChatType.code),
        inlineCode = scaledMarkdownTextStyle(ChatType.code),
        textLink = linkTextStyles,
        table = markdownBodyStyle,
    )

    // Thought markdown keeps its subordinate font sizes while sharing the 1.1x rhythm.
    val thoughtTypography = markdownTypography(
        text = thoughtMarkdownBodyStyle,
        paragraph = thoughtMarkdownBodyStyle,
        ordered = thoughtMarkdownBodyStyle,
        bullet = thoughtMarkdownBodyStyle,
        list = thoughtMarkdownBodyStyle,
        h1 = scaledMarkdownTextStyle(ChatType.thH1),
        h2 = scaledMarkdownTextStyle(ChatType.thH2),
        h3 = scaledMarkdownTextStyle(ChatType.thH3),
        h4 = scaledMarkdownTextStyle(ChatType.thH4),
        h5 = scaledMarkdownTextStyle(ChatType.thH5),
        h6 = scaledMarkdownTextStyle(ChatType.thH6),
        code = scaledMarkdownTextStyle(ChatType.thoughtCode),
        inlineCode = scaledMarkdownTextStyle(ChatType.thoughtCode),
        textLink = linkTextStyles,
        table = thoughtMarkdownBodyStyle,
    )
    val fg = MaterialTheme.colorScheme.onBackground
    val bg = MaterialTheme.colorScheme.surface
    // Composite fg at 0.1 alpha over bg to produce the exact opaque equivalent
    val eInkMode = LocalAgoraMotionPolicy.current.eInkMode
    val codeBg = remember(fg, bg, eInkMode) {
        if (eInkMode) Color.White else Color(
            red   = fg.red   * 0.1f + bg.red   * 0.9f,
            green = fg.green * 0.1f + bg.green * 0.9f,
            blue  = fg.blue  * 0.1f + bg.blue  * 0.9f,
        )
    }
    val customMarkdownColors = markdownColor(
        codeBackground = codeBg,
        inlineCodeBackground = Color.Transparent,
        dividerColor = MaterialTheme.colorScheme.outlineVariant,
        tableBackground = if (eInkMode) Color.White else fg.copy(alpha = 0.02f),
    )
    val customMarkdownPadding = markdownPadding(block = 8.dp)
    val thoughtMarkdownPadding = markdownPadding(block = 5.dp)
    val searchHighlightColor = SearchHighlightBackground
    val activeSearchHighlightColor = ActiveSearchHighlightBackground

    val customMarkdownComponents = remember(
        searchHighlightColor,
        activeSearchHighlightColor,
        autoWrapCodeBlocks,
    ) {
        lateinit var components: MarkdownComponents
        components = markdownComponents(
            text = { model ->
                SearchHighlightedMarkdownText(
                    model = model,
                    spec = LocalSearchHighlightSpec.current,
                    highlightColor = searchHighlightColor,
                    activeHighlightColor = activeSearchHighlightColor,
                )
            },
            image = { model ->
                ScrollableDisplayLatexImage(model)
            },
            inlineImage = { model -> ChatMarkdownInlineImage(model) },
            paragraph = { model ->
                val displayLatexLink = displayLatexParagraphLink(model.content, model.node)
                val displayLatexSource = displayLatexLink?.let(::latexSourceForLink)
                if (displayLatexLink != null && displayLatexSource != null) {
                    DisplayLatexBlock(
                        link = displayLatexLink,
                        source = displayLatexSource,
                        style = model.typography.paragraph,
                        modifier = if (model.node.needsListParagraphSpacer()) {
                            Modifier.padding(top = LocalMarkdownPadding.current.block)
                        } else {
                            Modifier
                        },
                    )
                    return@markdownComponents
                }
                SearchHighlightedMarkdownText(
                    model = model,
                    style = model.typography.paragraph,
                    modifier = if (model.node.needsListParagraphSpacer()) {
                        Modifier.padding(top = LocalMarkdownPadding.current.block)
                    } else {
                        Modifier
                    },
                    spec = LocalSearchHighlightSpec.current,
                    highlightColor = searchHighlightColor,
                    activeHighlightColor = activeSearchHighlightColor,
                    promoteWideLatex = true,
                )
            },
            heading1 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h1,
                    MarkdownTokenTypes.ATX_CONTENT,
                    LocalSearchHighlightSpec.current,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            heading2 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h2,
                    MarkdownTokenTypes.ATX_CONTENT,
                    LocalSearchHighlightSpec.current,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            heading3 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h3,
                    MarkdownTokenTypes.ATX_CONTENT,
                    LocalSearchHighlightSpec.current,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            heading4 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h4,
                    MarkdownTokenTypes.ATX_CONTENT,
                    LocalSearchHighlightSpec.current,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            heading5 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h5,
                    MarkdownTokenTypes.ATX_CONTENT,
                    LocalSearchHighlightSpec.current,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            heading6 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h6,
                    MarkdownTokenTypes.ATX_CONTENT,
                    LocalSearchHighlightSpec.current,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            setextHeading1 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h1,
                    MarkdownTokenTypes.SETEXT_CONTENT,
                    LocalSearchHighlightSpec.current,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            setextHeading2 = { model ->
                SearchHighlightedMarkdownHeading(
                    model,
                    model.typography.h2,
                    MarkdownTokenTypes.SETEXT_CONTENT,
                    LocalSearchHighlightSpec.current,
                    searchHighlightColor,
                    activeSearchHighlightColor,
                )
            },
            codeFence = { model ->
                SearchHighlightedMarkdownCode(
                    model = model,
                    fenced = true,
                    spec = LocalSearchHighlightSpec.current,
                    highlightColor = searchHighlightColor,
                    activeHighlightColor = activeSearchHighlightColor,
                    autoWrap = autoWrapCodeBlocks,
                )
            },
            codeBlock = { model ->
                SearchHighlightedMarkdownCode(
                    model = model,
                    fenced = false,
                    spec = LocalSearchHighlightSpec.current,
                    highlightColor = searchHighlightColor,
                    activeHighlightColor = activeSearchHighlightColor,
                    autoWrap = autoWrapCodeBlocks,
                )
            },
            table = { model ->
                SearchHighlightedMarkdownTable(
                    model = model,
                    spec = LocalSearchHighlightSpec.current,
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
                        spec = LocalSearchHighlightSpec.current,
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

    val latexTextSize = with(LocalDensity.current) { 20.sp.toPx() }
    val latexImageTransformer = remember(textColor, inlineImages, onMediaClick, latexTextSize) {
        LatexImageTransformer(
            textSize = latexTextSize,
            color = textColor.toArgb(),
            inlineImages = inlineImages,
            onMediaClick = onMediaClick,
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
        preparedMarkdown,
    ) {
        ChatMarkdownRenderContext(
            colors = customMarkdownColors,
            typography = customTypography,
            padding = customMarkdownPadding,
            components = customMarkdownComponents,
            annotator = chatMarkdownAnnotator,
            imageTransformer = latexImageTransformer,
            flavour = markdownFlavour,
            plainTextStyle = markdownBodyStyle,
            parseInlineDollarMath = parseInlineDollarMath,
            preparedMarkdown = preparedMarkdown,
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
        preparedMarkdown,
    ) {
        ChatMarkdownRenderContext(
            colors = customMarkdownColors,
            typography = thoughtTypography,
            padding = thoughtMarkdownPadding,
            components = thoughtMarkdownComponents,
            annotator = chatMarkdownAnnotator,
            imageTransformer = latexImageTransformer,
            flavour = markdownFlavour,
            plainTextStyle = thoughtMarkdownBodyStyle,
            parseInlineDollarMath = parseInlineDollarMath,
            preparedMarkdown = preparedMarkdown,
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
/**
 * MarkdownTable draws the GFM delimiter row as a MarkdownDivider using dividerColor. A 0dp
 * thickness would still render as a hairline, so the divider is made transparent instead.
 * Table cells only hold inline content, so no other divider lives inside this scope.
 */
@Composable
private fun WithoutTableHeaderDivider(content: @Composable () -> Unit) {
    val base = LocalMarkdownColors.current
    val colors = remember(base) {
        object : MarkdownColors by base {
            override val dividerColor: Color = Color.Transparent
        }
    }
    CompositionLocalProvider(LocalMarkdownColors provides colors, content = content)
}

@Composable
internal fun SearchHighlightedMarkdownTable(
    model: MarkdownComponentModel,
    spec: SearchHighlightSpec? = null,
    highlightColor: Color = SearchHighlightBackground,
    activeHighlightColor: Color = ActiveSearchHighlightBackground,
) = WithoutTableHeaderDivider {
    Box(if (LocalAgoraMotionPolicy.current.eInkMode) Modifier.border(1.dp, Color.Black) else Modifier) {
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
                    .padding(LocalMarkdownDimens.current.tableCellPadding)
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
internal fun SearchHighlightedMarkdownText(
    model: MarkdownComponentModel,
    style: TextStyle = model.typography.text,
    textNode: ASTNode = model.node,
    modifier: Modifier = Modifier,
    literalText: String? = null,
    spec: SearchHighlightSpec? = null,
    highlightColor: Color = SearchHighlightBackground,
    activeHighlightColor: Color = ActiveSearchHighlightBackground,
    promoteWideLatex: Boolean = false,
) {
    val settings = annotatorSettings()
    val citationTokens = LocalCitationInlineTokens.current
    val base = remember(model.content, textNode, style, literalText, settings, citationTokens) {
        buildCitationAwareMarkdownAnnotatedString(
            content = model.content,
            textNode = textNode,
            style = style,
            annotatorSettings = settings,
            citationTokens = citationTokens,
            literalText = literalText,
        )
    }
    val streamingFadeSpec = LocalStreamingGlyphFadeSpec.current
    val nodeFade = remember(streamingFadeSpec, model.content, textNode) {
        streamingFadeSpec.nodeFade(
            blockContent = model.content,
            nodeStart = textNode.startOffset,
            nodeEnd = textNode.endOffset,
        )
    }
    val fadeColor = style.color
        .takeUnless { it == Color.Unspecified }
        ?: LocalContentColor.current
    if (spec == null) {
        val renderedText = rememberStreamingGlyphFade(
            content = base,
            color = fadeColor,
            fade = nodeFade,
        )
        LatexAwareMarkdownText(
            content = renderedText,
            node = model.node,
            modifier = modifier,
            style = style,
            sourceContent = model.content,
            promoteWideLatex = promoteWideLatex,
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
        fade = nodeFade,
    )
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    ReportSearchPositions(
        spec = spec,
        displayMatches = displayMatches,
        layoutResult = layoutResult,
        coordinates = coordinates,
    )
    LatexAwareMarkdownText(
        content = renderedText,
        node = model.node,
        modifier = modifier
            .onGloballyPositioned { coordinates = it },
        style = style,
        onTextLayout = { layoutResult = it },
        sourceContent = model.content,
        promoteWideLatex = promoteWideLatex,
    )
}

@Composable
internal fun SearchHighlightedMarkdownHeading(
    model: MarkdownComponentModel,
    style: TextStyle,
    contentType: org.intellij.markdown.IElementType,
    spec: SearchHighlightSpec? = null,
    highlightColor: Color = SearchHighlightBackground,
    activeHighlightColor: Color = ActiveSearchHighlightBackground,
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
