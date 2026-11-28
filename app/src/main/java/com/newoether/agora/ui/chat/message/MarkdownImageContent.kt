package com.newoether.agora.ui.chat.message

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import java.io.File
import com.newoether.agora.R
import com.newoether.agora.model.MarkdownImage
import com.newoether.agora.ui.chat.MEDIA_LOADING_INDICATOR_STROKE_WIDTH
import com.newoether.agora.ui.chat.MEDIA_STATE_CROSSFADE_MILLIS
import com.newoether.agora.ui.chat.MediaLoadPresentation
import com.newoether.agora.ui.chat.toMediaLoadPresentation
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator

import androidx.compose.foundation.layout.fillMaxWidth
import com.newoether.agora.ui.components.LatexImageTransformer
import com.newoether.agora.ui.components.latexSourceForLink
import com.mikepenz.markdown.compose.LocalImageTransformer
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownImage
import com.mikepenz.markdown.compose.elements.MarkdownInlineImage
import com.mikepenz.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.mikepenz.markdown.model.ReferenceLinkHandler
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType

/** Generated-image geometry and media transitions, shared by Markdown block and inline slots. */
@Composable
internal fun MarkdownImageThumbnail(link: String, image: MarkdownImage, onClick: () -> Unit) {
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { 300.dp.roundToPx().coerceAtLeast(1) }
    val request = remember(context, image.attachment?.path, pixels) {
        image.attachment?.path?.let { ImageRequest.Builder(context).data(File(it)).size(pixels, pixels).build() }
    }
    val painter = rememberAsyncImagePainter(request)
    val target = when {
        image.failed -> MediaLoadPresentation.FAILED
        request == null -> MediaLoadPresentation.LOADING
        else -> painter.state.toMediaLoadPresentation()
    }
    var presented by remember(link) { mutableStateOf<MediaLoadPresentation?>(null) }
    LaunchedEffect(target) { presented = target }
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    Box(
        modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (request != null) Image(
            painter = painter,
            contentDescription = stringResource(R.string.tool_view_image),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clickable(
                enabled = presented == MediaLoadPresentation.LOADED, onClick = onClick,
            ),
        )
        Crossfade(
            targetState = presented,
            animationSpec = tween(MEDIA_STATE_CROSSFADE_MILLIS, easing = LinearEasing),
            label = "markdownImageContent",
            modifier = Modifier.fillMaxSize(),
        ) { state ->
            when (state) {
                MediaLoadPresentation.LOADING -> Box(
                    Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center,
                ) {
                    MotionAwareCircularProgressIndicator(
                        modifier = Modifier.size(28.dp), strokeWidth = MEDIA_LOADING_INDICATOR_STROKE_WIDTH,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                MediaLoadPresentation.FAILED -> Box(
                    Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.BrokenImage, stringResource(R.string.attachment_copy_failed_image),
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                }
                else -> Spacer(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
internal fun ScrollableDisplayLatexImage(model: MarkdownComponentModel) {
    val link = markdownImageLink(model.content, model.node, LocalReferenceLinkHandler.current)
    val alt = remember(model.content, model.node) { markdownImageAltText(model.content, model.node) }
    markdownVideoLink(link ?: "").let { video ->
        if (video != null) {
            MarkdownVideoCard(
                link = video,
                modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(16f / 9f),
            )
            return
        }
    }
    markdownAudioLink(link ?: "").let { audio ->
        if (audio != null) {
            MarkdownAudioCard(
                link = audio,
                title = alt,
                modifier = Modifier.padding(vertical = 4.dp).widthIn(max = 320.dp).fillMaxWidth(),
            )
            return
        }
    }
    markdownHtmlLink(link ?: "").let { htmlFile ->
        if (htmlFile != null) {
            MarkdownHtmlPreviewCard(
                link = htmlFile,
                title = alt,
                modifier = Modifier.fillMaxWidth(),
            )
            return
        }
    }
    val transformer = LocalImageTransformer.current as? LatexImageTransformer
    if (link != null && transformer
            ?.renderInlineImage(link, LocalMarkdownImageOccurrences.current[model.node]) == true) return
    // Any formula on its own line, display or inline promoted out of its paragraph, is one
    // selectable unit that copies its source.
    val latexSource = link?.let(::latexSourceForLink)
    if (link != null && latexSource != null) {
        DisplayLatexBlock(link, latexSource, model.typography.paragraph)
        return
    }
    if (transformer == null || (link != null && transformer.isSupportedImage(link))) {
        MarkdownImage(model.content, model.node)
    } else {
        val fallback = unsupportedMarkdownImageLabel(alt, link)
        if (fallback.isNotEmpty()) {
            Text(
                text = fallback,
                style = model.typography.paragraph,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ChatMarkdownInlineImage(model: MarkdownComponentModel) {
    // Inline image components receive the resolved destination as their content.
    markdownVideoLink(model.content).let { video ->
        if (video != null) {
            MarkdownVideoCard(link = video, modifier = Modifier.size(width = 160.dp, height = 90.dp))
            return
        }
    }
    markdownAudioLink(model.content).let { audio ->
        if (audio != null) {
            MarkdownAudioCard(
                link = audio,
                modifier = Modifier.padding(vertical = 4.dp).widthIn(max = 320.dp).fillMaxWidth(),
            )
            return
        }
    }
    markdownHtmlLink(model.content).let { htmlFile ->
        if (htmlFile != null) {
            MarkdownHtmlPreviewCard(
                link = htmlFile,
                modifier = Modifier.fillMaxWidth(),
            )
            return
        }
    }
    if ((LocalImageTransformer.current as? LatexImageTransformer)
            ?.renderInlineImage(model.content, LocalMarkdownImageOccurrences.current[model.node]) != true) {
        MarkdownInlineImage(model.content, model.node)
    }
}

/** Extracts the unescaped alt text of a Markdown `IMAGE` node, or null when blank. */
internal fun markdownImageAltText(content: String, node: ASTNode): String? {
    fun ASTNode.descendant(type: IElementType): ASTNode? {
        for (child in children) {
            if (child.type == type) return child
            child.descendant(type)?.let { return it }
        }
        return null
    }
    val textNode = node.descendant(MarkdownElementTypes.LINK_TEXT)
        ?: node.descendant(MarkdownElementTypes.LINK_LABEL)
        ?: return null
    return textNode.getUnescapedTextInNode(content)
        .removePrefix("[")
        .removeSuffix("]")
        .trim()
        .takeIf { it.isNotEmpty() }
}

/** Readable plain-text fallback when a Markdown image destination cannot be rendered. */
internal fun unsupportedMarkdownImageLabel(alt: String?, link: String?): String {
    val cleanAlt = alt?.trim().orEmpty()
    val cleanLink = link?.trim()?.removeSurrounding("<", ">").orEmpty()
    return when {
        cleanAlt.isNotEmpty() && cleanLink.isNotEmpty() && cleanAlt != cleanLink ->
            "$cleanAlt ($cleanLink)"
        cleanAlt.isNotEmpty() -> cleanAlt
        cleanLink.isNotEmpty() -> cleanLink
        else -> ""
    }
}

/**
 * Replaces unsupported inline image placeholders with selectable fallback text so destinations
 * the renderer cannot open never collapse into blank 200dp placeholders or drop their alt text.
 */
internal fun AnnotatedString.replaceUnsupportedMarkdownImages(
    sourceContent: String,
    textNode: ASTNode,
    isSupported: (String) -> Boolean,
    fallbackColor: Color,
): AnnotatedString {
    val prefix = "${MARKDOWN_TAG_IMAGE_URL}_"
    val annotations = getStringAnnotations(0, length)
        .filter { it.item.startsWith(prefix) }
        .sortedBy { it.start }
    if (annotations.isEmpty()) return this
    val unsupported = annotations.withIndex().filter { (_, range) ->
        !isSupported(range.item.removePrefix(prefix))
    }
    if (unsupported.isEmpty()) return this
    val imageNodes = mutableListOf<ASTNode>()
    fun visit(node: ASTNode) {
        if (node.type == MarkdownElementTypes.IMAGE) imageNodes += node
        node.children.forEach(::visit)
    }
    visit(textNode)
    val builder = AnnotatedString.Builder()
    var copiedFrom = 0
    for ((index, range) in unsupported) {
        if (range.start < copiedFrom) continue
        if (copiedFrom < range.start) builder.append(this, copiedFrom, range.start)
        val link = range.item.removePrefix(prefix)
        val alt = imageNodes.getOrNull(index)?.let { markdownImageAltText(sourceContent, it) }
        val fallback = unsupportedMarkdownImageLabel(alt, link)
        if (fallback.isNotEmpty()) {
            builder.withStyle(SpanStyle(color = fallbackColor)) {
                append(fallback)
            }
        }
        copiedFrom = range.end
    }
    if (copiedFrom < length) builder.append(this, copiedFrom, length)
    return builder.toAnnotatedString()
}

/** Resolve the existing Markdown AST with the renderer's public unescaping/reference helpers. */
internal fun markdownImageLink(content: String, node: ASTNode, references: ReferenceLinkHandler?): String? {
    fun ASTNode.descendant(type: IElementType): ASTNode? {
        for (child in children) {
            if (child.type == type) return child
            child.descendant(type)?.let { return it }
        }
        return null
    }
    node.descendant(MarkdownElementTypes.LINK_DESTINATION)?.let {
        return it.getUnescapedTextInNode(content)
    }
    // Angle-wrapped inline destinations are parsed as a direct AUTOLINK child.
    node.findChildOfType(MarkdownElementTypes.INLINE_LINK)?.children
        ?.firstOrNull { it.type == MarkdownElementTypes.AUTOLINK }?.let {
            return it.getUnescapedTextInNode(content).removeSurrounding("<", ">")
        }
    val reference = node.descendant(MarkdownElementTypes.FULL_REFERENCE_LINK)
        ?: node.descendant(MarkdownElementTypes.SHORT_REFERENCE_LINK) ?: return null
    val label = reference.findChildOfType(MarkdownElementTypes.LINK_LABEL)?.getUnescapedTextInNode(content)
        ?: return null
    return references?.find(label)?.takeIf { it.isNotEmpty() }
}
