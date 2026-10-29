package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.newoether.agora.R
import com.newoether.agora.model.ToolImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Visibility and lifecycle bound both device image reads and the browser instance. */
@Composable
internal fun HtmlPreviewBrowser(code: String, modifier: Modifier = Modifier, fullScreen: Boolean = false) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var visible by remember { mutableStateOf(false) }
    Box(
        modifier.then(if (fullScreen) Modifier else Modifier.height(360.dp))
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                visible = bounds.width > 0 && bounds.height > 0
            },
        contentAlignment = Alignment.Center,
    ) {
        if (visible && foreground) {
            HtmlPreviewActiveBrowser(code)
        }
    }
}

@Composable
private fun HtmlPreviewActiveBrowser(code: String) {
    val context = LocalContext.current
    val media = LocalShellMarkdownMedia.current
    val document = remember(code) { runCatching { HtmlPreviewDocument(code) }.getOrNull() }
    var images by remember(document) { mutableStateOf<Map<String, ToolImageAttachment>?>(null) }
    var failed by remember(document) { mutableStateOf(document == null) }
    var unsupported by remember(document) { mutableStateOf(false) }
    val background = String.format(Locale.ROOT, "#%06X", MaterialTheme.colorScheme.surface.toArgb() and 0xffffff)
    val foreground = String.format(Locale.ROOT, "#%06X", MaterialTheme.colorScheme.onSurface.toArgb() and 0xffffff)
    LaunchedEffect(document, media) {
        if (document == null) return@LaunchedEffect
        val loaded = linkedMapOf<String, ToolImageAttachment>()
        var total = 0L
        for ((url, link) in document.images) {
            val image = media?.previewImage(link) ?: continue
            val size = withContext(Dispatchers.IO) { File(image.path).length() }
            if (image.mimeType !in HtmlPreviewResources.RASTER_TYPES || size <= 0 ||
                total + size > HtmlPreviewDocument.MAX_TOTAL_BYTES) continue
            total += size
            loaded[url] = image
        }
        images = loaded
    }
    val loaded = images
    if (unsupported || failed || loaded == null) {
        Text(
            stringResource(when {
                unsupported -> R.string.html_preview_webview_required
                failed -> R.string.html_preview_failed
                else -> R.string.html_preview_loading
            }),
            modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (document != null) {
        key(document, background, foreground) {
            val resources = remember { HtmlPreviewResources(document, loaded, background, foreground) }
            var browser by remember { mutableStateOf<HtmlPreviewWebView?>(null) }
            DisposableEffect(resources) {
                onDispose {
                    browser?.release()
                    resources.close()
                }
            }
            AndroidView(
                factory = {
                    HtmlPreviewWebView(context, document, resources,
                        onFailure = { failed = true }, onUnsupported = { unsupported = true },
                    ).also { browser = it }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.release(); browser = null },
                update = {},
            )
        }
    }
}
