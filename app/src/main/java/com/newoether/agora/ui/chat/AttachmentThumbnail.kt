package com.newoether.agora.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.util.AttachmentSourceReader
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

fun resolveAttachmentType(
    path: String,
    metaItem: AttachmentItem?,
): String {
    if (metaItem != null) return metaItem.type
    val normalized = path.substringBefore('?').substringBefore('#').lowercase()
    return when {
        normalized.endsWith(".pdf") -> "pdf"
        normalized.endsWith(".mp4") ||
            normalized.endsWith(".webm") ||
            normalized.endsWith(".mov") ||
            normalized.endsWith(".avi") ||
            normalized.contains("vid_original_") -> "video"
        // This fallback is used only for legacy image-list entries. Generic files are always
        // represented by AttachmentMeta, so MIME-provider IPC is unnecessary during composition.
        else -> "image"
    }
}

fun findMetaForIndex(meta: AttachmentMeta?, index: Int): AttachmentItem? {
    if (meta == null) return null
    meta.items.firstOrNull { it.imageIndex == index }?.let { return it }
    return meta.items.firstOrNull { m ->
        val imageIndex = m.imageIndex
        imageIndex != null && (m.pageCount ?: 1) > 0 &&
        index in imageIndex until imageIndex + (m.pageCount ?: 1)
    }
}

suspend fun readFileContent(
    context: Context,
    uriString: String,
    maxChars: Int = Constants.MAX_FILE_CONTENT_READ_LENGTH,
): String = withContext(Dispatchers.IO) {
    AttachmentSourceReader.readText(context, uriString, maxChars) ?: ""
}

@Composable
fun FileThumbnail(
    fileName: String?,
    isPdf: Boolean,
    modifier: Modifier = Modifier,
    fallbackLabel: String = "TXT",
    shape: Shape = RoundedCornerShape(8.dp),
) {
    if (isPdf) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(Color(0xFFE53935).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text("PDF", style = MaterialTheme.typography.labelMedium, color = Color(0xFFE53935), fontWeight = FontWeight.SemiBold)
        }
    } else {
        val ext = (fileName ?: "").substringAfterLast('.', "").uppercase().take(4)
            .ifEmpty { fallbackLabel }
        Box(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            Text(ext, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Content padding of a user message bubble. */
internal val USER_BUBBLE_CONTENT_PADDING = 15.dp

/**
 * Corner radius of attachments inside a user bubble, kept equal to the bubble's content padding so
 * the two read as one rhythm. (A strictly concentric corner would be 27dp - 15dp = 12dp.)
 */
internal val USER_BUBBLE_ATTACHMENT_CORNER = USER_BUBBLE_CONTENT_PADDING

data class ThumbnailClickHandlers(
    val onMediaClick: ((urls: List<String>, index: Int) -> Unit)? = null,
    val onFileClick: ((fileName: String, content: String) -> Unit)? = null,
    val onPdfClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun AttachmentThumbnailItem(
    type: String,
    imagePath: String,
    fileName: String?,
    originalUri: String? = null,
    textContent: String? = null,
    unavailable: Boolean = false,
    pdfPages: List<String> = emptyList(),
    showFileName: Boolean = true,
    allMediaUrls: List<String> = emptyList(),
    mediaIndex: Int = 0,
    handlers: ThumbnailClickHandlers = ThumbnailClickHandlers(),
    modifier: Modifier = Modifier
) {
    val haptics = LocalAgoraHaptics.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(USER_BUBBLE_ATTACHMENT_CORNER)
    val thumbModifier = modifier
        .size(120.dp, 90.dp)
        .clip(shape)

    if (unavailable && type == "image") {
        Box(
            modifier = thumbModifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.BrokenImage,
                contentDescription = androidx.compose.ui.res.stringResource(
                    com.newoether.agora.R.string.attachment_copy_failed_image,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(32.dp),
            )
        }
        return
    }
    if (unavailable) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(72.dp),
        ) {
            FileThumbnail(
                fileName = fileName,
                isPdf = type == "pdf",
                modifier = Modifier.size(64.dp),
                fallbackLabel = type.uppercase().take(4).ifEmpty { "FILE" },
                shape = shape,
            )
            if (showFileName && fileName != null) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = androidx.compose.ui.res.stringResource(
                    com.newoether.agora.R.string.attachment_unavailable,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                maxLines = 1,
            )
        }
        return
    }

    when (type) {
        "file" -> {
            val canOpen = handlers.onFileClick != null &&
                (textContent != null || originalUri != null)
            val clickMod = if (canOpen) {
                Modifier
                    .clickable {
                        scope.launch {
                            val content = textContent ?: originalUri?.let {
                                readFileContent(
                                    context = context,
                                    uriString = it,
                                    maxChars = Constants.MAX_FILE_CONTENT_READ_LENGTH,
                                )
                            }
                            if (content != null) {
                                handlers.onFileClick?.invoke(fileName ?: "", content)
                            }
                        }
                    }
            } else {
                Modifier
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).then(clickMod)) {
                FileThumbnail(fileName = fileName, isPdf = false, modifier = Modifier.size(64.dp), shape = shape)
                if (showFileName && fileName != null) {
                    Text(fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        "pdf" -> {
            val firstPage = pdfPages.indexOfFirst(String::isNotBlank)
            val clickMod = if (firstPage >= 0 && handlers.onPdfClick != null)
                Modifier.clip(shape).clickable { handlers.onPdfClick(pdfPages, firstPage) } else Modifier
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                FileThumbnail(fileName = null, isPdf = true, modifier = Modifier.size(64.dp).then(clickMod), shape = shape)
                if (showFileName && fileName != null) {
                    Text(fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        "video" -> {
            val actionSource = originalUri?.takeIf(String::isNotBlank)
                ?: imagePath.takeIf(String::isNotBlank)
            var actionsVisible by remember(actionSource) { mutableStateOf(false) }
            Box(
                modifier = Modifier.clip(shape).combinedClickable(
                    onClick = {
                        if (originalUri != null) handlers.onMediaClick?.invoke(allMediaUrls, mediaIndex)
                    },
                    onLongClick = {
                        haptics.longPress()
                        if (actionSource != null) actionsVisible = true
                    },
                    hapticFeedbackEnabled = false,
                ),
            ) {
                // A stored frame artifact (Local Sandbox slicing) already is an image. Plain videos
                // have none, so the poster is decoded from the video bytes at display time.
                if (imagePath.isNotBlank()) {
                    MessageMediaThumbnail(
                        imagePath = imagePath,
                        modifier = thumbModifier,
                        showPlay = true,
                    )
                } else {
                    VideoPosterThumbnail(
                        source = originalUri,
                        contentDescription = null,
                        modifier = thumbModifier,
                        shape = shape,
                    )
                }
            }
            if (actionsVisible && actionSource != null) {
                VideoActionsSheet(
                    url = actionSource,
                    onMessage = { message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    },
                    onDismiss = { actionsVisible = false },
                )
            }
        }
        else -> { // image
            if (imagePath.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(shape)
                        .combinedClickable(
                            onClick = { handlers.onMediaClick?.invoke(allMediaUrls, mediaIndex) },
                            onLongClick = { haptics.longPress() },
                            hapticFeedbackEnabled = false,
                        )
                ) {
                    MessageMediaThumbnail(
                        imagePath = imagePath,
                        modifier = thumbModifier,
                    )
                }
            } else {
                // No image data available (e.g. Claude import), show file-style thumbnail
                val clickMod = if (fileName != null && handlers.onFileClick != null)
                    Modifier.clip(shape).clickable { handlers.onFileClick(fileName, textContent ?: "") } else Modifier
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                    Box(
                        modifier = Modifier.size(64.dp).then(clickMod)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        val ext = (fileName ?: "").substringAfterLast('.', "").uppercase().take(4).ifEmpty { "IMG" }
                        Text(ext, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                    if (showFileName && fileName != null) {
                        Text(fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageMediaThumbnail(
    imagePath: String,
    modifier: Modifier,
    showPlay: Boolean = false,
) {
    var loadState by remember(imagePath) {
        mutableStateOf(MediaLoadPresentation.LOADING)
    }
    val loadingVisible = rememberMediaLoadingVisible(imagePath, loadState == MediaLoadPresentation.LOADING)
    var presentedState by remember(imagePath) {
        mutableStateOf(MediaLoadPresentation.LOADING to false)
    }
    LaunchedEffect(imagePath, loadState, loadingVisible) {
        presentedState = loadState to loadingVisible
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        coil.compose.AsyncImage(
            model = imagePath,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onLoading = { loadState = MediaLoadPresentation.LOADING },
            onSuccess = { loadState = MediaLoadPresentation.LOADED },
            onError = { loadState = MediaLoadPresentation.FAILED },
            modifier = Modifier.fillMaxSize(),
        )
        Crossfade(
            targetState = presentedState,
            animationSpec = tween(MEDIA_STATE_CROSSFADE_MILLIS),
            label = "messageMediaThumbnail",
            modifier = Modifier.fillMaxSize(),
        ) { (state, showLoading) ->
            when (state) {
                MediaLoadPresentation.LOADING -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = MEDIA_LOADING_INDICATOR_STROKE_WIDTH,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                MediaLoadPresentation.LOADED -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showPlay) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .padding(4.dp),
                        )
                    }
                }
                MediaLoadPresentation.FAILED -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.BrokenImage,
                        contentDescription = androidx.compose.ui.res.stringResource(
                            com.newoether.agora.R.string.attachment_copy_failed_image,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}

private const val VIDEO_POSTER_CACHE_ENTRIES = 16
private const val VIDEO_POSTER_MAX_WIDTH_PX = 512

/** Display-only video frames. Nothing is persisted: attachment artifacts stay the only media. */
private val videoPosterCache =
    object : android.util.LruCache<String, Bitmap>(VIDEO_POSTER_CACHE_ENTRIES) {}

private fun loadVideoPoster(context: Context, source: String): Bitmap? {
    videoPosterCache.get(source)?.let { return it }
    val retriever = MediaMetadataRetriever()
    return try {
        when {
            source.startsWith("/") -> retriever.setDataSource(source)
            source.startsWith("file:", ignoreCase = true) -> retriever.setDataSource(URI(source).path)
            else -> retriever.setDataSource(context.applicationContext, Uri.parse(source))
        }
        val durationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION,
        )?.toLongOrNull() ?: 0L
        // Skip a possibly black opening second, but stay near the head of the clip.
        val frameUs = (durationMs * 100L).coerceIn(0L, 2_000_000L)
        retriever.getFrameAtTime(frameUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?.let(::scaledPoster)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun scaledPoster(bitmap: Bitmap): Bitmap {
    if (bitmap.width <= VIDEO_POSTER_MAX_WIDTH_PX) return bitmap
    val height = (bitmap.height.toLong() * VIDEO_POSTER_MAX_WIDTH_PX / bitmap.width)
        .toInt()
        .coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, VIDEO_POSTER_MAX_WIDTH_PX, height, true)
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}

/** Decodes one frame of [source] for a poster; the result never leaves memory. */
@Composable
internal fun rememberVideoPoster(source: String?): Bitmap? {
    val context = LocalContext.current
    var poster by remember(source) {
        mutableStateOf(source?.takeIf(String::isNotBlank)?.let(videoPosterCache::get))
    }
    LaunchedEffect(source) {
        val key = source?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        if (poster != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { loadVideoPoster(context.applicationContext, key) }
        if (loaded != null) {
            videoPosterCache.put(key, loaded)
            poster = loaded
        }
    }
    return poster
}

/** Video cell with a real first frame; the camera glyph stays only while nothing decodes. */
@Composable
internal fun VideoPosterThumbnail(
    source: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    showPlay: Boolean = true,
) {
    val poster = rememberVideoPoster(source)
    Box(
        modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (poster != null) {
            Image(
                bitmap = poster.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (showPlay) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .padding(4.dp),
                )
            }
        } else {
            Icon(
                Icons.Default.Videocam,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
