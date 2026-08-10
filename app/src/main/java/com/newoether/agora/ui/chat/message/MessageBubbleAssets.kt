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
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownDimens
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
        )
    }
    val thoughtMarkdownRenderContext = remember(
        customMarkdownColors,
        thoughtTypography,
        thoughtMarkdownPadding,
        thoughtMarkdownComponents,
        latexImageTransformer,
        markdownFlavour,
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
    CompositionLocalProvider(
        LocalMarkdownDimens provides markdownDimens(),
        LocalMarkdownColors provides assets.renderContext.colors,
        LocalMarkdownPadding provides assets.renderContext.padding,
    ) {
        MarkdownCodeBackground(
            color = assets.renderContext.colors.codeBackground,
            shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            showHeader = true,
            language = null,
            code = code,
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
  
