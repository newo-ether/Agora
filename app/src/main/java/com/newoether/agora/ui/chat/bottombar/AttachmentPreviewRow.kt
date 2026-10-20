package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.MaterialTheme
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.newoether.agora.R
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.ui.chat.FileThumbnail
import com.newoether.agora.ui.chat.MEDIA_LOADING_INDICATOR_STROKE_WIDTH
import com.newoether.agora.ui.chat.MediaLoadPresentation
import com.newoether.agora.ui.chat.rememberMediaLoadingVisible
import com.newoether.agora.ui.chat.toMediaLoadPresentation
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.motion.Crossfade
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Text

private const val ATTACHMENT_STATUS_CROSSFADE_MS = 200

internal enum class AttachmentPreviewPresentation {
    INITIAL,
    UNAVAILABLE,
    IMPORT_LOADING,
    IMPORT_FAILED,
    READY_FILE,
    READY_NOTE,
    READY_PDF,
    READY_VIDEO_PLACEHOLDER,
    MEDIA_LOADING,
    MEDIA_SUCCESS,
    MEDIA_ERROR,
}

internal fun attachmentPreviewPresentation(
    unavailable: Boolean,
    importState: AttachmentImportState,
    type: String,
    hasVideoFrame: Boolean,
    mediaLoadState: MediaLoadPresentation,
    isNotes: Boolean = false,
    hasNotePreview: Boolean = false,
): AttachmentPreviewPresentation = when {
    unavailable -> AttachmentPreviewPresentation.UNAVAILABLE
    importState == AttachmentImportState.PROCESSING ->
        AttachmentPreviewPresentation.IMPORT_LOADING
    importState == AttachmentImportState.FAILED ->
        AttachmentPreviewPresentation.IMPORT_FAILED
    isNotes && (!hasNotePreview || mediaLoadState == MediaLoadPresentation.FAILED) ->
        AttachmentPreviewPresentation.READY_NOTE
    type == "file" && !isNotes -> AttachmentPreviewPresentation.READY_FILE
    type == "pdf" -> AttachmentPreviewPresentation.READY_PDF
    type == "video" && !hasVideoFrame ->
        AttachmentPreviewPresentation.READY_VIDEO_PLACEHOLDER
    mediaLoadState == MediaLoadPresentation.LOADED ->
        AttachmentPreviewPresentation.MEDIA_SUCCESS
    mediaLoadState == MediaLoadPresentation.FAILED ->
        AttachmentPreviewPresentation.MEDIA_ERROR
    else -> AttachmentPreviewPresentation.MEDIA_LOADING
}

/** Notes previews are display assets; their presence does not change AI image selection. */
internal fun notesAttachmentPreview(attachment: SelectedAttachment): String? =
    attachment.sourceThumbnailPath ?: if (attachment.notesDocumentId != null && attachment.notesRevisionId != null)
        attachment.preRenderedPaths?.firstOrNull() ?: attachment.notesThumbnailPath
    else null

/** Projects the exact conversation-owned attachment snapshot and emits identity commands. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AttachmentPreviewRow(
    attachments: List<SelectedAttachment>,
    editable: Boolean,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
    onAllMediaClick: ((urls: List<String>, index: Int) -> Unit)?,
    onFileContentClick: ((fileName: String, content: String) -> Unit)?,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)?,
) {
    val haptics = LocalAgoraHaptics.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val keyboardHeight = with(density) { WindowInsets.ime.getBottom(this).toDp().value }
    val availableHeight = configuration.screenHeightDp - keyboardHeight
    val context = LocalContext.current
    val mediaAttachments = remember(attachments) {
        attachments.filter {
            !it.unavailable && it.importState == AttachmentImportState.READY &&
                (it.type == "image" || it.type == "video")
        }
    }
    val allMediaUrls = remember(mediaAttachments) {
        mediaAttachments.map { it.localPath ?: it.uri }
    }
    val mediaIndexById = remember(mediaAttachments) {
        mediaAttachments.mapIndexed { index, attachment -> attachment.localId to index }.toMap()
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val previewWidth = ((maxWidth.value - 16) * .46f).coerceIn(144f, 220f).dp
        val previewHeight = (availableHeight * .40f).coerceIn(144f, previewWidth.value).dp
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(attachments, key = SelectedAttachment::localId) { attachment ->
                val uriString = attachment.uri
                val isVideo = attachment.type == "video"
                val isPdf = attachment.type == "pdf"
                val isFile = attachment.type == "file"
                val isNotes = attachment.notesDocumentId != null && attachment.notesRevisionId != null
                val notePreview = notesAttachmentPreview(attachment)
                val isReady = attachment.importState == AttachmentImportState.READY
                val mediaIndex = mediaIndexById[attachment.localId]
                val mediaModel = when {
                    attachment.unavailable || !isReady -> null
                    notePreview != null -> notePreview.let { java.io.File(it) }
                    isVideo -> attachment.processedFrames?.firstOrNull()
                    isFile || isPdf -> null
                    else -> attachment.localPath ?: uriString
                }
                val request = remember(mediaModel, previewWidth, previewHeight, density.density) {
                    ImageRequest.Builder(context).data(mediaModel)
                        .size(with(density) { previewWidth.roundToPx() }, with(density) { previewHeight.roundToPx() })
                        .crossfade(false).build()
                }
                val mediaPainter = rememberAsyncImagePainter(model = request)
                val drawable = (mediaPainter.state as? AsyncImagePainter.State.Success)?.result?.drawable
                val aspect = drawable?.takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }
                    ?.let { it.intrinsicWidth.toFloat() / it.intrinsicHeight }
                    ?: if (isNotes) 86f / 131f else 1f
                val followsImage = notePreview != null || isNotes || attachment.type == "image" || isVideo
                val tileWidth = if (followsImage) minOf(previewWidth.value, previewHeight.value * aspect).dp else previewWidth
                val tileHeight = if (followsImage) (tileWidth.value / aspect).dp else previewHeight
                val mediaLoadState = mediaPainter.state.toMediaLoadPresentation()
                LaunchedEffect(isNotes, mediaPainter.state) {
                    if (isNotes && mediaPainter.state is AsyncImagePainter.State.Error) {
                        val error=(mediaPainter.state as AsyncImagePainter.State.Error).result.throwable
                        com.newoether.agora.util.DebugLog.w("NotesThumbnail", "Notebook image load failed", error)
                    }
                }
                val targetPresentation = attachmentPreviewPresentation(
                    unavailable = attachment.unavailable,
                    importState = attachment.importState,
                    type = attachment.type,
                    hasVideoFrame = attachment.processedFrames?.isNotEmpty() == true,
                    mediaLoadState = mediaLoadState,
                    isNotes = isNotes || notePreview != null,
                    hasNotePreview = notePreview != null,
                )
                val isLoading = targetPresentation == AttachmentPreviewPresentation.IMPORT_LOADING ||
                    targetPresentation == AttachmentPreviewPresentation.MEDIA_LOADING
                val delayedLoadingVisible = rememberMediaLoadingVisible(
                    loadingKey = attachment.localId,
                    isLoading = isLoading && attachment.type == "image",
                )
                val loadingVisible = isLoading && (attachment.type != "image" || delayedLoadingVisible)
                var presentedState by remember(attachment.localId) {
                    mutableStateOf(AttachmentPreviewPresentation.INITIAL to false)
                }
                LaunchedEffect(attachment.localId, targetPresentation, loadingVisible) {
                    presentedState = targetPresentation to loadingVisible
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(tileWidth)
                        .padding(top = 5.dp),
                ) {
                    Box {
                        val clickableModifier = when {
                            attachment.unavailable || !isReady -> Modifier
                            isFile -> {
                                if (!attachment.storage.canPreview || attachment.preparedText == null) {
                                    Modifier
                                } else if (onFileContentClick != null) {
                                    Modifier.clickable {
                                        onFileContentClick(
                                            attachment.fileName ?: uriString,
                                            attachment.preparedText,
                                        )
                                    }
                                } else {
                                    Modifier
                                }
                            }
                            isPdf -> {
                                if (onPdfPagesClick != null) {
                                    Modifier.clickable {
                                        onPdfPagesClick(attachment.preRenderedPaths.orEmpty(), 0)
                                    }
                                } else {
                                    Modifier
                                }
                            }
                            mediaIndex != null -> Modifier.combinedClickable(
                                onClick = {
                                    onAllMediaClick?.invoke(allMediaUrls, mediaIndex)
                                },
                                onLongClick = { haptics.longPress() },
                                hapticFeedbackEnabled = false,
                            )
                            else -> Modifier
                        }
                        Crossfade(
                            targetState = presentedState,
                            animationSpec = tween(ATTACHMENT_STATUS_CROSSFADE_MS),
                            label = "attachmentPresentation",
                            modifier = Modifier
                                .size(tileWidth, tileHeight)
                                .clip(RoundedCornerShape(8.dp))
                                .then(clickableModifier),
                        ) { (presentation, showLoading) ->
                            AttachmentPresentationContent(
                                presentation = presentation,
                                showLoading = showLoading,
                                attachment = attachment,
                                mediaPainter = mediaPainter,
                                editable = editable,
                                onRetry = {
                                    haptics.selection()
                                    onRetry(attachment.localId)
                                },
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 5.dp, y = (-5).dp)
                                .size(32.dp)
                                .background(Color.Black.copy(alpha = 0.8f), CircleShape)
                                .clip(CircleShape)
                                .clickable(enabled = editable) {
                                    haptics.selection()
                                    onRemove(attachment.localId)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.remove),
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Crossfade(
                        targetState = presentedState.first,
                        animationSpec = tween(ATTACHMENT_STATUS_CROSSFADE_MS),
                        label = "attachmentCaption",
                    ) { presentation ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (
                                presentation == AttachmentPreviewPresentation.UNAVAILABLE ||
                                isFile ||
                                isPdf
                            ) {
                                attachment.fileName?.let { fileName ->
                                    Text(
                                        text = attachment.sourceTitle ?: if (isNotes) fileName.removeSuffix(".notes.zip") else fileName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                            attachment.sourceBookTitle?.let { book ->
                                Text(text = book, style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (presentation == AttachmentPreviewPresentation.UNAVAILABLE) {
                                Text(
                                    text = stringResource(R.string.attachment_unavailable),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentPresentationContent(
    presentation: AttachmentPreviewPresentation,
    showLoading: Boolean,
    attachment: SelectedAttachment,
    mediaPainter: AsyncImagePainter,
    editable: Boolean,
    onRetry: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (presentation) {
            AttachmentPreviewPresentation.INITIAL -> Unit
            AttachmentPreviewPresentation.READY_NOTE -> NoteAttachmentPlaceholder()
            AttachmentPreviewPresentation.UNAVAILABLE,
            AttachmentPreviewPresentation.READY_FILE,
            AttachmentPreviewPresentation.READY_PDF -> FileThumbnail(
                fileName = attachment.fileName ?: attachment.uri,
                isPdf = attachment.type == "pdf",
                modifier = Modifier.fillMaxSize(),
                fallbackLabel = attachment.type.uppercase().take(4).ifEmpty { "FILE" },
            )
            AttachmentPreviewPresentation.READY_VIDEO_PLACEHOLDER -> Icon(
                Icons.Default.Videocam,
                stringResource(R.string.video),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            AttachmentPreviewPresentation.IMPORT_LOADING -> {
                AttachmentTypePlaceholder(attachment)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(Color.Black.copy(alpha = 0.4f)),
                )
                if (showLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = MEDIA_LOADING_INDICATOR_STROKE_WIDTH,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            AttachmentPreviewPresentation.IMPORT_FAILED,
            AttachmentPreviewPresentation.MEDIA_ERROR -> {
                AttachmentTypePlaceholder(attachment)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(Color.Black.copy(alpha = 0.25f))
                        .clickable(enabled = editable, onClick = onRetry),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (attachment.type == "image") Icons.Default.BrokenImage
                        else Icons.Default.ErrorOutline,
                        contentDescription = stringResource(R.string.retry),
                        tint = Color(0xFFB0B0B0),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            AttachmentPreviewPresentation.MEDIA_LOADING -> {
                // AsyncImagePainter must stay attached to an Image while its request is running.
                // Rendering only the spinner can leave size-sensitive requests permanently Loading.
                Image(
                    painter = mediaPainter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                if (showLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = MEDIA_LOADING_INDICATOR_STROKE_WIDTH,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            AttachmentPreviewPresentation.MEDIA_SUCCESS -> {
                Image(
                    painter = mediaPainter,
                    contentDescription = if (attachment.type == "video") {
                        stringResource(R.string.video_thumbnail)
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                if (attachment.type == "video") {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.play),
                        tint = Color.White,
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .padding(4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentTypePlaceholder(attachment: SelectedAttachment) {
    if (attachment.notesDocumentId != null && attachment.notesRevisionId != null) {
        NoteAttachmentPlaceholder()
    } else if (attachment.type == "video") {
        Icon(
            Icons.Default.Videocam,
            stringResource(R.string.video),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    } else {
        FileThumbnail(
            fileName = attachment.fileName ?: attachment.uri,
            isPdf = attachment.type == "pdf",
            modifier = Modifier.fillMaxSize(),
            fallbackLabel = attachment.type.uppercase().take(4).ifEmpty { "FILE" },
        )
    }
}

@Composable
private fun NoteAttachmentPlaceholder() {
    Text("Preview unavailable", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
}
