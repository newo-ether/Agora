package com.newoether.agora.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.ui.theme.CodeEditorFamily
import com.newoether.agora.ui.chat.message.LiteralHtmlMarkdownBlock
import com.newoether.agora.ui.chat.message.SearchHighlightedMarkdownHeading
import com.newoether.agora.ui.chat.message.SearchHighlightedMarkdownTable
import com.newoether.agora.ui.chat.message.SearchHighlightedMarkdownText
import com.newoether.agora.ui.chat.message.chatLinkTextStyles
import com.newoether.agora.ui.chat.message.literalHtmlMarkdownAnnotator
import com.newoether.agora.ui.chat.message.scaledMarkdownTextStyle
import com.newoether.agora.util.NoAutoScrollSelectionContainer
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes

private fun isMarkdownFile(fileName: String): Boolean =
    fileName.endsWith(".md", true) || fileName.endsWith(".markdown", true)

@Composable
fun TextFileViewer(
    content: String,
    fileName: String,
    onClose: () -> Unit,
    autoWrapCodeBlocks: Boolean = true,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = true) { onClose() }

    val isMarkdown = remember(fileName) { isMarkdownFile(fileName) }
    var showOverlay by remember { mutableStateOf(true) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val t = MaterialTheme.typography
        val linkColor = MaterialTheme.colorScheme.primary
        val linkTextStyles = remember(linkColor) { chatLinkTextStyles(linkColor) }
        val viewerBodyStyle = scaledMarkdownTextStyle(t.bodyLarge)
        val viewerTypography = markdownTypography(
            text = viewerBodyStyle,
            paragraph = viewerBodyStyle,
            ordered = viewerBodyStyle,
            bullet = viewerBodyStyle,
            list = viewerBodyStyle,
            h1 = scaledMarkdownTextStyle(t.headlineMedium.copy(fontWeight = FontWeight.Bold)),
            h2 = scaledMarkdownTextStyle(t.headlineSmall.copy(fontWeight = FontWeight.Bold)),
            h3 = scaledMarkdownTextStyle(t.titleLarge.copy(fontWeight = FontWeight.Bold)),
            h4 = scaledMarkdownTextStyle(t.titleMedium.copy(fontWeight = FontWeight.Bold)),
            h5 = scaledMarkdownTextStyle(t.titleSmall.copy(fontWeight = FontWeight.Bold)),
            h6 = scaledMarkdownTextStyle(t.titleSmall.copy(fontWeight = FontWeight.Bold)),
            code = scaledMarkdownTextStyle(t.bodyMedium.copy(fontSize = 13.sp)),
            inlineCode = scaledMarkdownTextStyle(t.bodyMedium.copy(fontSize = 13.sp)),
            textLink = linkTextStyles,
            table = viewerBodyStyle,
        )
        val viewerPadding = markdownPadding(block = 7.dp)
        val literalHtmlComponents = remember {
            lateinit var components: MarkdownComponents
            components = markdownComponents(
                text = { SearchHighlightedMarkdownText(it) },
                paragraph = {
                    SearchHighlightedMarkdownText(it, style = it.typography.paragraph)
                },
                heading1 = {
                    SearchHighlightedMarkdownHeading(
                        it,
                        it.typography.h1,
                        MarkdownTokenTypes.ATX_CONTENT,
                    )
                },
                heading2 = {
                    SearchHighlightedMarkdownHeading(
                        it,
                        it.typography.h2,
                        MarkdownTokenTypes.ATX_CONTENT,
                    )
                },
                heading3 = {
                    SearchHighlightedMarkdownHeading(
                        it,
                        it.typography.h3,
                        MarkdownTokenTypes.ATX_CONTENT,
                    )
                },
                heading4 = {
                    SearchHighlightedMarkdownHeading(
                        it,
                        it.typography.h4,
                        MarkdownTokenTypes.ATX_CONTENT,
                    )
                },
                heading5 = {
                    SearchHighlightedMarkdownHeading(
                        it,
                        it.typography.h5,
                        MarkdownTokenTypes.ATX_CONTENT,
                    )
                },
                heading6 = {
                    SearchHighlightedMarkdownHeading(
                        it,
                        it.typography.h6,
                        MarkdownTokenTypes.ATX_CONTENT,
                    )
                },
                setextHeading1 = {
                    SearchHighlightedMarkdownHeading(
                        it,
                        it.typography.h1,
                        MarkdownTokenTypes.SETEXT_CONTENT,
                    )
                },
                setextHeading2 = {
                    SearchHighlightedMarkdownHeading(
                        it,
                        it.typography.h2,
                        MarkdownTokenTypes.SETEXT_CONTENT,
                    )
                },
                table = { SearchHighlightedMarkdownTable(it) },
                custom = { type, model ->
                    if (type == MarkdownElementTypes.HTML_BLOCK) {
                        LiteralHtmlMarkdownBlock(model)
                    } else {
                        model.node.children.forEach { child ->
                            MarkdownElement(
                                node = child,
                                components = components,
                                content = model.content,
                            )
                        }
                    }
                }
            )
            components
        }

        // Content
        if (isMarkdown) {
            NoAutoScrollSelectionContainer {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {}
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 96.dp, bottom = 56.dp)
                ) {
                    Markdown(
                        content = content,
                        modifier = Modifier.fillMaxWidth(),
                        typography = viewerTypography,
                        padding = viewerPadding,
                        annotator = literalHtmlMarkdownAnnotator,
                        components = literalHtmlComponents,
                    )
                                    }
            }
        } else {
            CodeEditorText(content = content, autoWrap = autoWrapCodeBlocks)
        }

        // Top alpha gradient
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(Brush.verticalGradient(
                        0.0f to MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                        0.6f to MaterialTheme.colorScheme.background.copy(alpha = 0.80f),
                        1.0f to Color.Transparent
                    ))
            )
        }

        // Top overlay — filename + close button, midline aligned
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.shadow(8.dp, RoundedCornerShape(50)).widthIn(max = 320.dp)
                    ) {
                        Text(
                            fileName,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Surface(
                    onClick = onClose,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.size(48.dp).shadow(8.dp, CircleShape)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.provider_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(48.dp).padding(12.dp)
                    )
                }
            }
        }
    }
}

/** Line count above which the gutter is drawn inline instead of one composable per line. */
private const val MAX_CODE_EDITOR_ROWS = 1200

/**
 * Code-editor rendering for plain text: a grey line-number gutter beside JetBrains Mono text with
 * MiOutfit as the glyph fallback. Wrapping follows the chat code-block setting, so lines either
 * fold in place or scroll horizontally under a gutter that stays put.
 */
@Composable
private fun CodeEditorText(content: String, autoWrap: Boolean) {
    val lines = remember(content) { codeEditorLines(content) }
    val style = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = CodeEditorFamily,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    )
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val textColor = MaterialTheme.colorScheme.onSurface
    val digits = remember(lines) { lines.size.toString().length }
    val gutterWidth = (digits * 8).dp
    NoAutoScrollSelectionContainer {
        when {
            autoWrap && lines.size <= MAX_CODE_EDITOR_ROWS -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {}
                    .verticalScroll(rememberScrollState())
                    .padding(top = 96.dp, bottom = 56.dp)
            ) {
                lines.forEachIndexed { index, line ->
                    Row(modifier = Modifier.fillMaxWidth().padding(end = 20.dp)) {
                        Text(
                            text = (index + 1).toString(),
                            style = style,
                            color = gutterColor,
                            textAlign = TextAlign.End,
                            modifier = Modifier.padding(start = 12.dp).widthIn(min = gutterWidth),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            // An empty Text collapses to zero height, which would drop blank lines.
                            text = line.ifEmpty { " " },
                            style = style,
                            color = textColor,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            !autoWrap -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {}
                    .verticalScroll(rememberScrollState())
                    .padding(top = 96.dp, bottom = 56.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = lines.indices.joinToString("\n") { (it + 1).toString() },
                        style = style,
                        color = gutterColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(start = 12.dp).widthIn(min = gutterWidth),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        Text(
                            text = content,
                            style = style,
                            color = textColor,
                            softWrap = false,
                            modifier = Modifier.padding(end = 20.dp),
                        )
                    }
                }
            }
            else -> {
                // A huge file keeps its numbering without one composable pair per line.
                val numbered = remember(lines, gutterColor, digits) {
                    buildAnnotatedString {
                        lines.forEachIndexed { index, line ->
                            if (index > 0) append('\n')
                            withStyle(SpanStyle(color = gutterColor)) {
                                append((index + 1).toString().padStart(digits))
                                append("  ")
                            }
                            append(line)
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {}
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 20.dp, top = 96.dp, bottom = 56.dp)
                ) {
                    Text(text = numbered, style = style, color = textColor)
                }
            }
        }
    }
}

/** Splits viewer text into logical lines; CRLF and a lone CR count as one break. */
internal fun codeEditorLines(content: String): List<String> =
    content.split('\n').map { it.removeSuffix("\r") }
