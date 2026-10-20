package com.newoether.agora.ui.chat.message

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.mikepenz.markdown.model.ReferenceLinkHandler
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import com.newoether.agora.ui.motion.Crossfade
import com.newoether.agora.ui.components.Icon

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
            .clip(RoundedCornerShape(8.dp)).background(background),
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
                        Icons.Default.BrokenImage, stringResource(R.string.attachment_copy_failed_image),
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
    if (link != null && (LocalImageTransformer.current as? LatexImageTransformer)
            ?.renderInlineImage(link) == true) return
    // Any formula on its own line, display or inline promoted out of its paragraph, is one
    // selectable unit that copies its source.
    val latexSource = link?.let(::latexSourceForLink)
    if (link != null && latexSource != null) {
        DisplayLatexBlock(link, latexSource, model.typography.paragraph)
        return
    }
    MarkdownImage(model.content, model.node)
}

@Composable
internal fun ChatMarkdownInlineImage(model: MarkdownComponentModel) {
    if ((LocalImageTransformer.current as? LatexImageTransformer)?.renderInlineImage(model.content) != true) {
        MarkdownInlineImage(model.content, model.node)
    }
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
    node.descendant(MarkdownElementTypes.LINK_DESTINATION)?.let { return it.getUnescapedTextInNode(content) }
    val reference = node.descendant(MarkdownElementTypes.FULL_REFERENCE_LINK)
        ?: node.descendant(MarkdownElementTypes.SHORT_REFERENCE_LINK) ?: return null
    val label = reference.findChildOfType(MarkdownElementTypes.LINK_LABEL)?.getUnescapedTextInNode(content)
        ?: return null
    return references?.find(label)?.takeIf { it.isNotEmpty() }
}
