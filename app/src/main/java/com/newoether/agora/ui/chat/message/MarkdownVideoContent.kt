package com.newoether.agora.ui.chat.message

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.newoether.agora.R
import com.newoether.agora.ui.chat.VideoActionsSheet
import com.newoether.agora.ui.chat.VideoPlayer
import com.newoether.agora.ui.chat.rememberVideoPoster
import com.newoether.agora.ui.common.DialogSnackbarHost
import com.newoether.agora.ui.common.LocalSnackbarMessage
import com.newoether.agora.ui.common.LocalAgoraHaptics
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where an inline Markdown video is played from. */
internal sealed interface MarkdownVideoSource {
    data class Remote(val url: String) : MarkdownVideoSource
    data class Local(val file: LocalFileTarget) : MarkdownVideoSource
    /** A file on a Conch device; it is copied into the app cache before playback. */
    data class Conch(val server: String, val path: String) : MarkdownVideoSource
}

/**
 * Resolves a video destination. `shell://` videos on the sandbox must still resolve to one of the
 * two readable roots, a `shell://` video on any other device resolves to that Conch file, and
 * HTTP(S) videos stream directly. Every other destination keeps its ordinary text link.
 */
internal fun markdownVideoSource(link: String): MarkdownVideoSource? {
    val raw = markdownVideoLink(link) ?: return null
    parseShellMediaLink(raw)?.let { shell ->
        if (shell.server.equals(LOCAL_SANDBOX_SERVER, ignoreCase = true)) {
            return (resolveMarkdownDestination(raw) as? MarkdownDestination.File)
                ?.let { MarkdownVideoSource.Local(it.target) }
        }
        return MarkdownVideoSource.Conch(shell.server, shell.path)
    }
    return when (val target = resolveMarkdownDestination(raw)) {
        is MarkdownDestination.Web -> MarkdownVideoSource.Remote(target.url)
        is MarkdownDestination.File -> MarkdownVideoSource.Local(target.target)
        is MarkdownDestination.Device -> MarkdownVideoSource.Conch(target.link.server, target.link.path)
        null -> null
    }
}

/**
 * Poster card for an inline Markdown video. The card shows one display-only frame decoded from the
 * resolved file; tapping it opens the answer's media viewer on this video, or the full-screen
 * player where no answer gallery is provided.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MarkdownVideoCard(link: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val notify = LocalSnackbarMessage.current
    // Remote sessions have no media owner, so a Conch video stays an inert card there.
    val media = LocalShellMarkdownMedia.current
    val haptics = LocalAgoraHaptics.current
    val source = remember(link) { markdownVideoSource(link) }
    val remote = source as? MarkdownVideoSource.Conch
    val download = if (remote != null) media?.videos?.peek(link) else null
    val readyFile = (download as? MarkdownVideoDownload.Ready)?.file
    var playingUri by remember(link) { mutableStateOf<String?>(null) }
    var actionsVisible by remember(link) { mutableStateOf(false) }
    var cardActionsUri by remember(link) { mutableStateOf<String?>(null) }
    var pendingPlay by remember(link) { mutableStateOf(false) }
    var pendingActions by remember(link) { mutableStateOf(false) }
    val label = remember(link, source) {
        when (val value = source) {
            is MarkdownVideoSource.Remote -> value.url.substringAfterLast('/')
            is MarkdownVideoSource.Local -> value.file.relativePath.substringAfterLast('/')
            is MarkdownVideoSource.Conch -> value.path.substringAfterLast('/')
            null -> link
        }.ifBlank { link }
    }
    val posterPath by produceState<String?>(initialValue = null, source, readyFile, context) {
        value = withContext(Dispatchers.IO) {
            when (val resolved = source) {
                is MarkdownVideoSource.Local ->
                    (resolveLocalFileTarget(context, resolved.file) as? LocalFileResolution.Ready)
                        ?.file?.absolutePath
                is MarkdownVideoSource.Conch -> readyFile?.absolutePath
                else -> null
            }
        }
    }
    val poster = rememberVideoPoster(
        posterPath,
        persistTo = remember(readyFile, media) {
            readyFile?.let { file -> media?.videos?.posterFile(file) }
        },
    )
    // The URI the answer's media viewer pages to; a Conch video joins once its copy is cached.
    val viewerUri by produceState<String?>(initialValue = null, source, readyFile, context) {
        value = withContext(Dispatchers.IO) {
            if (source is MarkdownVideoSource.Conch) readyFile?.let { cachedVideoUri(context, it) }
            else resolveVideoUri(context, source)
        }
    }
    val galleryEntry = rememberMarkdownMediaEntry(viewerUri)
    fun unavailable() {
        notify(context.getString(R.string.markdown_link_file_unavailable))
    }
    fun openVideo(uri: String?) {
        when {
            uri == null -> unavailable()
            galleryEntry != null -> galleryEntry.open(uri)
            else -> playingUri = uri
        }
    }
    fun openActions(uri: String?) {
        if (uri == null) unavailable() else {
            haptics.longPress()
            cardActionsUri = uri
        }
    }
    // A copy that finishes after the gesture opens the player or the sheet the tap asked for.
    LaunchedEffect(pendingPlay, pendingActions, readyFile) {
        val file = readyFile ?: return@LaunchedEffect
        if (pendingPlay) {
            pendingPlay = false
            openVideo(cachedVideoUri(context, file))
        } else if (pendingActions) {
            pendingActions = false
            openActions(cachedVideoUri(context, file))
        }
    }
    fun remoteVideoTap(startSheet: Boolean) {
        val start = media ?: return unavailable()
        when {
            readyFile != null -> if (startSheet) openActions(cachedVideoUri(context, readyFile))
                else openVideo(cachedVideoUri(context, readyFile))
            download is MarkdownVideoDownload.Failed -> {
                // A failure is retryable: the cached copy may only need a fresh handshake.
                notify(download.message)
                if (startSheet) {
                    pendingActions = true
                    start.videos.start(link)
                } else {
                    pendingPlay = true
                    start.videos.start(link)
                }
            }
            startSheet -> {
                pendingActions = true
                start.videos.start(link)
            }
            else -> {
                pendingPlay = true
                start.videos.start(link)
            }
        }
    }
    Box(
        modifier = modifier
            .then(galleryEntry?.modifier ?: Modifier)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .combinedClickable(
                onClick = {
                    if (remote != null) remoteVideoTap(startSheet = false)
                    else openVideo(resolveVideoUri(context, source))
                },
                onLongClick = {
                    if (remote != null) remoteVideoTap(startSheet = true)
                    else openActions(resolveVideoUri(context, source))
                },
                hapticFeedbackEnabled = false,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (poster != null) {
            Image(
                bitmap = poster.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.28f)),
            )
        }
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val progress = download as? MarkdownVideoDownload.Downloading
            if (progress != null && progress.total > 0) {
                Text(
                    text = "${progress.loaded * 100 / progress.total}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.play),
                    modifier = Modifier.size(32.dp),
                    tint = Color.White,
                )
            }
        }
        Text(
            text = label,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val cardActions = cardActionsUri
    if (cardActions != null) {
        VideoActionsSheet(
            url = cardActions,
            onMessage = notify,
            onDismiss = { cardActionsUri = null },
        )
    }
    val uri = playingUri
    if (uri != null) {
        Dialog(
            onDismissRequest = { playingUri = null; actionsVisible = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            DialogSnackbarHost {
                val dialogNotify = LocalSnackbarMessage.current
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    VideoPlayer(
                        uri = uri,
                        onClose = { playingUri = null; actionsVisible = false },
                        onLongPress = {
                            haptics.longPress()
                            actionsVisible = true
                        },
                    )
                    if (actionsVisible) {
                        VideoActionsSheet(
                            url = uri,
                            onMessage = dialogNotify,
                            onDismiss = { actionsVisible = false },
                        )
                    }
                }
            }
        }
    }
}

/** A cached Conch copy is served through the same provider as a local file. */
private fun cachedVideoUri(context: Context, file: File): String? = runCatching {
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}.getOrNull()?.toString()

private fun resolveVideoUri(context: Context, source: MarkdownVideoSource?): String? = when (source) {
    null -> null
    is MarkdownVideoSource.Remote -> source.url
    is MarkdownVideoSource.Conch -> null
    is MarkdownVideoSource.Local -> when (val resolved = resolveLocalFileTarget(context, source.file)) {
        is LocalFileResolution.Ready -> cachedVideoUri(context, resolved.file)
        else -> null
    }
}
