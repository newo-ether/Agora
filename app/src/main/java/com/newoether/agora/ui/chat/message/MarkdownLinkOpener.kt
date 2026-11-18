package com.newoether.agora.ui.chat.message

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.newoether.agora.R
import com.newoether.agora.ui.chat.FullScreenMediaViewer
import java.io.File

/**
 * Opens a tapped Markdown destination. Network links leave through the platform handler; sandbox
 * and shared-storage files open in the in-app viewer for images and videos and through the system
 * chooser for everything else.
 */
@Stable
internal class MarkdownLinkOpener(
    private val context: Context,
    private val openRemote: (String) -> Unit,
    private val showMedia: (String) -> Unit,
    private val notify: (Int) -> Unit,
) {
    fun activate(raw: String) {
        when (val target = classifyMarkdownLink(raw)) {
            is MarkdownLinkTarget.Remote -> openRemote(target.url)
            is MarkdownLinkTarget.Local -> when (val resolved = resolveLocalFileTarget(context, target.file)) {
                is LocalFileResolution.Ready -> openLocalFile(resolved.file)
                LocalFileResolution.NotFound -> notify(R.string.markdown_link_file_missing)
                LocalFileResolution.NeedsSharedStorageAccess ->
                    notify(R.string.markdown_link_shared_storage_required)
                LocalFileResolution.Unavailable -> notify(R.string.markdown_link_file_unavailable)
            }
            null -> Unit
        }
    }

    private fun openLocalFile(file: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
        if (uri == null) {
            notify(R.string.markdown_link_file_unavailable)
            return
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime?.startsWith("image/") == true || mime?.startsWith("video/") == true) {
            showMedia(uri.toString())
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime ?: "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (!launched) notify(R.string.markdown_link_no_handler)
    }
}

/**
 * Hosts [MarkdownLinkOpener] plus its in-app media viewer. The dialog lives beside the text that
 * owns the link, so closing the message dismisses its preview with it.
 */
@Composable
internal fun rememberMarkdownLinkOpener(): MarkdownLinkOpener {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var mediaUri by remember { mutableStateOf<String?>(null) }
    val notify: (Int) -> Unit = { resId ->
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }
    val opener = remember(context, uriHandler) {
        MarkdownLinkOpener(
            context = context,
            openRemote = { url ->
                if (runCatching { uriHandler.openUri(url) }.isFailure) {
                    notify(R.string.markdown_link_no_handler)
                }
            },
            showMedia = { uri -> mediaUri = uri },
            notify = notify,
        )
    }
    val openedMedia = mediaUri
    if (openedMedia != null) {
        Dialog(
            onDismissRequest = { mediaUri = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                FullScreenMediaViewer(
                    urls = listOf(openedMedia),
                    initialIndex = 0,
                    pdfPages = emptyList(),
                    onClose = { mediaUri = null },
                    onNavigate = {},
                )
            }
        }
    }
    return opener
}
