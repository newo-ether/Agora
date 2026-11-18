package com.newoether.agora.ui.chat.message

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.newoether.agora.R
import com.newoether.agora.ui.chat.VideoPlayer

/** Where an inline Markdown video is played from. */
internal sealed interface MarkdownVideoSource {
    data class Remote(val url: String) : MarkdownVideoSource
    data class Local(val file: LocalFileTarget) : MarkdownVideoSource
}

/**
 * Resolves a video destination. `shell://` videos must live on `Local Sandbox` under the sandbox
 * home; HTTP(S) videos stream directly. Every other device keeps its ordinary text link.
 */
internal fun markdownVideoSource(link: String): MarkdownVideoSource? {
    val raw = markdownVideoLink(link) ?: return null
    parseShellMediaLink(raw)?.let { shell ->
        if (!shell.server.equals(LOCAL_SANDBOX_SERVER, ignoreCase = true)) return null
        val local = classifyMarkdownLink(raw) as? MarkdownLinkTarget.Local ?: return null
        return MarkdownVideoSource.Local(local.file)
    }
    return MarkdownVideoSource.Remote(raw)
}

/**
 * Poster card for an inline Markdown video. The tap resolves the source and opens the full-screen
 * player; nothing decodes until the user asks for playback.
 */
@Composable
internal fun MarkdownVideoCard(link: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val source = remember(link) { markdownVideoSource(link) }
    var playingUri by remember(link) { mutableStateOf<String?>(null) }
    val label = remember(link, source) {
        when (val value = source) {
            is MarkdownVideoSource.Remote -> value.url.substringAfterLast('/')
            is MarkdownVideoSource.Local -> value.file.relativePath.substringAfterLast('/')
            null -> link
        }.ifBlank { link }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable {
                val uri = resolveVideoUri(context, source)
                if (uri == null) {
                    Toast.makeText(context, R.string.markdown_link_file_unavailable, Toast.LENGTH_SHORT).show()
                } else {
                    playingUri = uri
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = stringResource(R.string.play),
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val uri = playingUri
    if (uri != null) {
        Dialog(
            onDismissRequest = { playingUri = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                VideoPlayer(uri = uri, onClose = { playingUri = null })
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
