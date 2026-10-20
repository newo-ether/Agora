package com.newoether.agora.ui.chat

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
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
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.motion.AnimatedVisibility
import com.newoether.agora.ui.motion.shadow

private fun isMarkdownFile(fileName: String): Boolean =
    fileName.endsWith(".md", true) || fileName.endsWith(".markdown", true)

@Composable
fun TextFileViewer(
    content: String,
    fileName: String,
    onClose: () -> Unit,
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
            val scrollState = rememberScrollState()
            NoAutoScrollSelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {}
                        .verticalScroll(scrollState)
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 96.dp, bottom = 56.dp)
                ) {
                    Text(
                        content,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = t.bodyMedium.copy(
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        ),
                    )
                                    }
            }
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
