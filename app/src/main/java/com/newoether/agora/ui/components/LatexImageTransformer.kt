package com.newoether.agora.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.PlaceholderConfig
import com.newoether.agora.model.MarkdownImage
import com.newoether.agora.ui.chat.message.MarkdownImageThumbnail
import com.newoether.agora.ui.chat.message.ShellMarkdownMedia
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

// ── Image transformer ──────────────────────────────────────────────────

internal class LatexImageTransformer(
    private val textSize: Float = 40f,
    private val color: Int = 0xFF000000.toInt(),
    private val inlineImages: Map<String, MarkdownImage> = emptyMap(),
    private val onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    /** Native-chat loader for `shell://` images; Remote passes null and keeps its own images. */
    private val shellMedia: ShellMarkdownMedia? = null,
) : ImageTransformer {
    private fun messageImage(link: String): MarkdownImage? {
        fun decoded(value: String) = android.net.Uri.decode(value.removeSurrounding("<", ">"))
        return inlineImages[link] ?: inlineImages.entries.firstOrNull { decoded(it.key) == decoded(link) }?.value
    }
    private fun inlineImage(link: String): MarkdownImage? = messageImage(link) ?: shellMedia?.peek(link)

    @Composable
    fun renderInlineImage(link: String): Boolean {
        val owned = messageImage(link)
        val image = owned ?: shellMedia?.peek(link) ?: return false
        if (owned == null && shellMedia != null) {
            LaunchedEffect(shellMedia, link) { shellMedia.load(link) }
        }
        val paths = remember(inlineImages) {
            inlineImages.values.mapNotNull { it.attachment?.path }.distinct()
        }
        Box(Modifier.padding(vertical = 8.dp)) {
            MarkdownImageThumbnail(link, image) {
                val path = image.attachment?.path ?: return@MarkdownImageThumbnail
                val index = paths.indexOf(path)
                if (index >= 0) onMediaClick(paths, index) else onMediaClick(listOf(path), 0)
            }
        }
        return true
    }

    /**
     * The formula's size in pixels: the rendered bitmap once it exists, the placeholder estimate
     * before. Reading it in composition recomposes when the bitmap finishes. Null for other links.
     */
    fun formulaSize(link: String): Size? {
        val request = latexImageRequest(link) ?: return null
        val key = LatexRenderKey(request.latex, textSize, color)
        return LatexBitmapCache.observedSize(key)
            ?: estimateLatexPlaceholderSize(request.latex, textSize, request.display)
    }

    @Composable
    override fun transform(link: String): ImageData? {
        // Markdown measures the slot here; both image components render the shared viewport.
        if (inlineImage(link) != null) {
            val side = with(LocalDensity.current) { 300.dp.toPx() }
            val verticalSpace = with(LocalDensity.current) { 16.dp.toPx() }
            val viewport = remember(side, verticalSpace) {
                object : Painter() {
                    override val intrinsicSize = Size(side, side + verticalSpace)
                    override fun DrawScope.onDraw() = Unit
                }
            }
            // A known size promotes the image to a measured block before decoding.
            // An unspecified transparent painter leaves it in the text line, whose
            // height can be smaller than the actual thumbnail on Android.
            return ImageData(painter = viewport)
        }
        val request = latexImageRequest(link) ?: return null
        val key = LatexRenderKey(request.latex, textSize, color)
        var bitmap by remember(key) { mutableStateOf(LatexBitmapCache.get(key)) }
        val fade = remember(key) { Animatable(if (bitmap != null) 1f else 0f) }
        LaunchedEffect(key) {
            val cached = LatexBitmapCache.get(key)
            if (cached != null) {
                bitmap = cached
                fade.snapTo(1f)
                return@LaunchedEffect
            }

            bitmap = null
            fade.snapTo(0f)
            val rendered = try {
                LatexBitmapCache.awaitRendered(key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            if (rendered != null) {
                bitmap = rendered
                fade.snapTo(0f)
                fade.animateTo(1f, tween(durationMillis = 180))
            }
        }
        val bmp: Bitmap? = bitmap
        val density = LocalDensity.current
        val placeholderModifier = if (bmp == null) {
            val estimated = estimateLatexPlaceholderSize(request.latex, textSize, request.display)
            with(density) {
                Modifier.size(estimated.width.toDp(), estimated.height.toDp())
            }
        } else {
            Modifier
        }
        return ImageData(
            painter = bmp?.let { FadingBitmapPainter(it.asImageBitmap(), fade.value) }
                ?: ColorPainter(Color.Transparent),
            contentDescription = request.latex,
            modifier = placeholderModifier,
            alignment = Alignment.CenterStart,
            contentScale = ContentScale.Fit,
        )
    }

    override fun placeholderConfig(
        link: String,
        density: Density,
        containerSize: Size,
        imageWidth: ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((link: String, Size) -> Unit)?,
    ): PlaceholderConfig {
        if (inlineImage(link) != null) {
            val side = with(density) {
                if (containerSize.isUnspecified) 300f else containerSize.width.toDp().value.coerceIn(1f, 300f)
            }
            return PlaceholderConfig(Size(side, side + 16f))
        }
        val request = latexImageRequest(link) ?: return super.placeholderConfig(
            link, density, containerSize, imageWidth, imageSize, imageSizeChanged
        )
        val key = LatexRenderKey(request.latex, textSize, color)
        val resolvedSize = if (imageSize.isUnspecified) {
            LatexBitmapCache.get(key)?.let { bmp ->
                Size(bmp.width.toFloat(), bmp.height.toFloat())
            } ?: estimateLatexPlaceholderSize(request.latex, textSize, request.display)
        } else {
            imageSize
        }
        val sizeDp = with(density) {
            Size(
                width = resolvedSize.width.toDp().value,
                height = resolvedSize.height.toDp().value,
            )
        }
        return PlaceholderConfig(size = sizeDp, verticalAlign = PlaceholderVerticalAlign.TextCenter)
    }
}

private class FadingBitmapPainter(
    private val image: ImageBitmap,
    private val alpha: Float,
) : Painter() {
    override val intrinsicSize: Size
        get() = Size(image.width.toFloat(), image.height.toFloat())

    override fun DrawScope.onDraw() {
        drawImage(
            image = image,
            dstSize = IntSize(
                width = size.width.roundToInt().coerceAtLeast(1),
                height = size.height.roundToInt().coerceAtLeast(1),
            ),
            alpha = alpha.coerceIn(0f, 1f),
        )
    }
}
