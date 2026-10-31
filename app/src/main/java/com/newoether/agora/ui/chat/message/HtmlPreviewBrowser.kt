package com.newoether.agora.ui.chat.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.newoether.agora.R
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
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
    val surface = MaterialTheme.colorScheme.surface
    val background = String.format(Locale.ROOT, "#%06X", surface.toArgb() and 0xffffff)
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
    key(document, background, foreground) {
        var ready by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(surface), contentAlignment = Alignment.Center) {
            val loaded = images
            if (unsupported || failed) {
                Text(
                    stringResource(if (unsupported) R.string.html_preview_webview_required else R.string.html_preview_failed),
                    modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (document != null && loaded != null) {
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
                            backgroundColor = surface.toArgb(), onReady = { ready = true },
                        ).also { browser = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.release(); browser = null },
                    update = {},
                )
            }
            HtmlPreviewLoadingOverlay(loading = !ready && !failed && !unsupported)
        }
    }
}

/** The browser stays hidden during entry; this opaque layer leaves only after its first frame is ready. */
@Composable
internal fun HtmlPreviewLoadingOverlay(loading: Boolean) {
    val appearance = remember { MutableTransitionState(false) }
    appearance.targetState = loading
    AnimatedVisibility(
        visibleState = appearance,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(180)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                .testTag("html-preview-loading-overlay")
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        // Hit-test this covering sibling without consuming ancestor scroll gestures.
                        while (true) awaitPointerEvent()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MotionAwareCircularProgressIndicator()
                Text(
                    stringResource(R.string.html_preview_loading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
