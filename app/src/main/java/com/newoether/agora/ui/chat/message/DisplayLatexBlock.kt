package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.newoether.agora.ui.components.formulaAlternateText
import com.newoether.agora.ui.components.isDisplayLatexLink
import com.newoether.agora.ui.components.latexSourceForLink
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import com.newoether.agora.ui.components.Text

private const val DISPLAY_LATEX_INLINE_ID = "display-latex"

/**
 * A formula laid out on its own line as one selectable unit: a single-line Text whose only
 * character is an inline placeholder holding the rendered formula. The placeholder's alternate
 * text is the formula's original source, so a selection covering it highlights the whole formula
 * and copies that source. The line scrolls horizontally when the formula is wider than the message.
 */
@Composable
internal fun DisplayLatexBlock(
    link: String,
    source: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val transformer = LocalImageTransformer.current
    val density = LocalDensity.current
    val imageData = transformer.transform(link)
    val imageSize = imageData?.let { transformer.intrinsicSize(it.painter) } ?: Size.Unspecified
    val config = transformer.placeholderConfig(
        link = link,
        density = density,
        containerSize = Size.Unspecified,
        imageWidth = ImageWidth.IMAGE_WIDTH,
        imageSize = imageSize,
    )
    val text = remember(source) {
        buildAnnotatedString { appendInlineContent(DISPLAY_LATEX_INLINE_ID, formulaAlternateText(source)) }
    }
    val inlineContent = mapOf(
        DISPLAY_LATEX_INLINE_ID to InlineTextContent(
            Placeholder(
                width = with(density) { config.size.width.dp.toSp() },
                height = with(density) { config.size.height.dp.toSp() },
                placeholderVerticalAlign = config.verticalAlign,
            ),
        ) {
            if (imageData != null) {
                Image(
                    painter = imageData.painter,
                    contentDescription = imageData.contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    alignment = imageData.alignment,
                    contentScale = imageData.contentScale,
                    alpha = imageData.alpha,
                    colorFilter = imageData.colorFilter,
                )
            }
        },
    )
    val scrollState = rememberScrollState()
    TrackStreamingHorizontalScroll(scrollState)
    // A formula that fits has nothing to scroll; leaving the gesture unclaimed lets a horizontal
    // swipe reach the drawer instead of being consumed here.
    Row(modifier = modifier.fillMaxWidth().horizontalScroll(scrollState, enabled = scrollState.maxValue > 0)) {
        Text(
            text = text,
            style = style,
            inlineContent = inlineContent,
            softWrap = false,
            maxLines = 1,
        )
    }
}

/**
 * The link of a paragraph whose only content is a display formula. Such a paragraph renders as a
 * [DisplayLatexBlock] whatever the formula's height, so every display formula is one selectable,
 * horizontally scrollable unit.
 */
internal fun displayLatexParagraphLink(content: String, paragraph: ASTNode): String? {
    val children = paragraph.children.filterNot {
        it.type == MarkdownTokenTypes.WHITE_SPACE || it.type == MarkdownTokenTypes.EOL
    }
    val image = children.singleOrNull()?.takeIf { it.type == MarkdownElementTypes.IMAGE } ?: return null
    return markdownImageLink(content, image, null)?.takeIf(::isDisplayLatexLink)
}
