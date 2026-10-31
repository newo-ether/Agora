package com.newoether.agora.webui

import android.graphics.BitmapFactory
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.util.AttachmentFiles
import com.newoether.agora.util.ImageArtifactProcessor
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reads current owned bytes without comparing them with historical metadata or re-encoding. */
internal suspend fun inspectWebUiImageArtifact(
    file: File,
    artifact: ImageArtifact?,
    maxBytes: Long = AttachmentFiles.MAX_ATTACHMENT_BYTES,
): ImageArtifact? {
    if (artifact?.unavailable == true || !file.isFile || !file.canRead() || file.length() !in 1..maxBytes) return null
    val actionContext = currentCoroutineContext()
    actionContext.ensureActive()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
    val mime = bounds.outMimeType?.lowercase()?.takeIf { it in WEB_UI_RASTER_TYPES } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    actionContext.ensureActive()
    val options = BitmapFactory.Options().apply {
        inSampleSize = ImageArtifactProcessor.sampleSizeForBounds(
            bounds.outWidth, bounds.outHeight, ImageArtifactProcessor.DEFAULT_LONG_EDGE,
        )
    }
    val decoded = file.inputStream().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    decoded.recycle()
    actionContext.ensureActive()
    val size = file.length().takeIf { it in 1..maxBytes } ?: return null
    return ImageArtifact(file.path, mime, size, bounds.outWidth, bounds.outHeight,
        policyVersion = artifact?.policyVersion ?: 0)
}

internal suspend fun verifyWebUiImageArtifact(file: File, artifact: ImageArtifact): Boolean =
    inspectWebUiImageArtifact(file, artifact) != null

/** Selects completed occurrences; current-byte inspection supplies the response MIME. */
internal fun attachmentPreviewArtifact(
    attachment: SelectedAttachment,
    kind: String,
    index: Int,
): Pair<String, String>? {
    if (index < 0 || (attachment.unavailable && kind == "source")) return null
    val path = when (kind) {
        "page" -> attachment.takeIf { it.type == "pdf" }?.preRenderedPaths?.getOrNull(index)
        "frame" -> attachment.takeIf { it.type == "video" }?.processedFrames?.getOrNull(index)
        "source" -> if (index != 0) null else when (attachment.type) {
            "image" -> attachment.localPath.takeIf { attachment.importState == AttachmentImportState.READY }
            "video" -> return attachment.localPath?.let { source ->
                attachment.mimeType?.takeIf { VIDEO_MIME.matches(it) }?.let { source to it }
            }
            else -> null
        }
        else -> null
    }?.takeIf(String::isNotBlank) ?: return null
    val artifact = if (kind == "page" || kind == "frame") attachment.imageArtifacts.getOrNull(index)
        else attachment.imageArtifacts.firstOrNull { it.path == path }
    if (artifact?.unavailable == true || (artifact != null && artifact.path != path)) return null
    return path to (artifact?.mimeType?.takeIf { it.startsWith("image/") } ?: "image/*")
}

private val VIDEO_MIME = Regex("video/[A-Za-z0-9.+-]+")
private val WEB_UI_RASTER_TYPES = setOf(
    "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif",
    "image/heic", "image/heif", "image/avif",
)
