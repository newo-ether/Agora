package com.newoether.agora.ui.chat.message

import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.newoether.agora.ui.common.LocalAgoraHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where an inline Markdown video is played from. */
internal sealed interface MarkdownVideoSource {
    data class Remote(val url: String) : MarkdownVideoSource
    data class Local(val file: LocalFileTarget) : MarkdownVideoSource
}

/**
 * Resolves a video destination. `shell://` videos and bare storage paths must resolve to one of
 * the two readable roots; HTTP(S) videos stream directly. Every other device keeps its ordinary
 * text link.
 */
internal fun markdownVideoSource(link: String): MarkdownVideoSource? {
    val raw = markdownVideoLink(link) ?: return null
    return when (val target = classifyMarkdownLink(raw)) {
        is MarkdownLinkTarget.Remote -> MarkdownVideoSource.Remote(target.url)
        is MarkdownLinkTarget.Local -> MarkdownVideoSource.Local(target.file)
        null -> null
    }
}

/**
 * Poster card for an inline Markdown video. The card shows one display-only frame decoded from the
 * resolved file; tapping it opens the full-screen player.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MarkdownVideoCard(link: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val source = remember(link) { markdownVideoSource(link) }
    var playingUri by remember(link) { mutableStateOf<String?>(null) }
    var actionsVisible by remember(link) { mutableStateOf(false) }
    var cardActionsUri by remember(link) { mutableStateOf<String?>(null) }
    val haptics = LocalAgoraHaptics.current
    val label = remember(link, source) {
        when (val value = source) {
            is MarkdownVideoSource.Remote -> value.url.substringAfterLast('/')
            is MarkdownVideoSource.Local -> value.file.relativePath.substringAfterLast('/')
            null -> link
        }.ifBlank { link }
    }
    val posterPath by produceState<String?>(initialValue = null, source, context) {
        value = withContext(Dispatchers.IO) {
            when (val resolved = source) {
                is MarkdownVideoSource.Local ->
                    (resolveLocalFileTarget(context, resolved.file) as? LocalFileResolution.Ready)
                        ?.file?.absolutePath
                else -> null
            }
        }
    }
    val poster = rememberVideoPoster(posterPath)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .combinedClickable(
                onClick = {
                    val uri = resolveVideoUri(context, source)
                    if (uri == null) {
                        Toast.makeText(context, R.string.markdown_link_file_unavailable, Toast.LENGTH_SHORT).show()
                    } else {
                        playingUri = uri
                    }
                },
                onLongClick = {
                    val uri = resolveVideoUri(context, source)
                    if (uri == null) {
                        Toast.makeText(context, R.string.markdown_link_file_unavailable, Toast.LENGTH_SHORT).show()
                    } else {
                        haptics.longPress()
                        cardActionsUri = uri
                    }
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
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.play),
                modifier = Modifier.size(32.dp),
                tint = Color.White,
            )
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
            onMessage = { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() },
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
                        onMessage = { message ->
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        },
                        onDismiss = { actionsVisible = false },
                    )
                }
            }
        }
    }
}

private fun resolveVideoUri(context: Context, source: MarkdownVideoSource?): String? = when (source) {
    null -> null
    is MarkdownVideoSource.Remote -> source.url
    is MarkdownVideoSource.Local -> when (val resolved = resolveLocalFileTarget(context, source.file)) {
        is LocalFileResolution.Ready -> runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", resolved.file)
        }.getOrNull()?.toString()
        else -> null
    }
}
