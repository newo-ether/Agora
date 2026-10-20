package com.newoether.agora.ui.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.ui.common.AgoraHaptics
import com.newoether.agora.ui.common.NoOpAgoraHaptics
import com.newoether.agora.ui.common.rememberAgoraHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.AnimatedVisibility
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.motion.shadow

private fun hasVideoExtension(url: String): Boolean {
    val normalized = url.substringBefore('?').substringBefore('#').lowercase()
    return normalized.endsWith(".mp4") ||
        normalized.endsWith(".webm") ||
        normalized.endsWith(".mov") ||
        normalized.endsWith(".avi") ||
        normalized.contains("vid_original_")
}

@Composable
private fun rememberIsVideoMedia(url: String): Boolean? {
    if (hasVideoExtension(url)) return true
    if (!url.startsWith("content://", ignoreCase = true)) return false
    val context = LocalContext.current
    val resolved by produceState<Boolean?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.getType(Uri.parse(url))?.startsWith("video/") == true
            } catch (_: Exception) {
                false
            }
        }
    }
    return resolved
}

@Composable
fun FullScreenMediaViewer(
    urls: List<String>,
    initialIndex: Int = 0,
    pdfPages: List<String>,
    pdfSelectedPages: Set<Int>? = null,
    onTogglePdfPage: ((Int) -> Unit)? = null,
    onClose: () -> Unit,
    onNavigate: (Int) -> Unit,
    onMessage: (String) -> Unit = {},
    hapticsEnabled: Boolean = true
) {
    val url = urls.getOrNull(initialIndex) ?: return
    val haptics = rememberAgoraHaptics(hapticsEnabled)
    if (pdfPages.isNotEmpty()) {
        PdfPager(pdfPages, initialIndex, pdfSelectedPages, onTogglePdfPage, onClose, onNavigate)
        return
    }
    if (urls.size > 1) {
        MediaPager(urls, initialIndex, onClose, onNavigate, onMessage, haptics = haptics)
        return
    }
    val isSingleVideo = rememberIsVideoMedia(url)

    if (isSingleVideo == null) {
        BackHandler(onBack = onClose)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = MEDIA_LOADING_INDICATOR_STROKE_WIDTH,
            )
        }
        return
    }

    if (isSingleVideo == true) {
        var showOverlay by remember { mutableStateOf(true) }
        var closing by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxSize()) {
            VideoPlayer(uri = url, onClose = onClose, closing = closing)
            AnimatedVisibility(
                visible = showOverlay || LocalAgoraMotionPolicy.current.eInkMode,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(24.dp)
            ) {
                Surface(
                    onClick = { closing = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.shadow(8.dp, CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.provider_close), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(48.dp).padding(12.dp))
                }
            }
        }
        return
    }

    // Single image — full zoom/pan experience
    SingleImage(url = url, onClose = onClose, onMessage = onMessage, haptics = haptics)
}

// --- PDF pager (existing logic) ---

@Composable
private fun PdfPager(
    pdfPages: List<String>,
    initialIndex: Int,
    pdfSelectedPages: Set<Int>? = null,
    onTogglePdfPage: ((Int) -> Unit)? = null,
    onClose: () -> Unit,
    onNavigate: (Int) -> Unit
) {
    val pdfInitialPage = initialIndex.coerceIn(0, pdfPages.size - 1)
    var currentScale by remember { mutableFloatStateOf(1f) }
    var showOverlay by remember { mutableStateOf(true) }
    val pagerState = rememberPagerState(initialPage = pdfInitialPage) { pdfPages.size }
    LaunchedEffect(pagerState.currentPage) {
        val idx = pagerState.currentPage
        if (idx in pdfPages.indices) onNavigate(idx)
    }
    BackHandler { onClose() }
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = currentScale <= 1.05f
        ) { page ->
            ZoomableImageItem(
                url = pdfPages[page],
                onTap = { showOverlay = !showOverlay },
                onScaleChanged = { if (page == pagerState.currentPage) currentScale = it },
                consumeConditionally = true
            )
        }
        AnimatedVisibility(
            visible = showOverlay || LocalAgoraMotionPolicy.current.eInkMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.shadow(8.dp, RoundedCornerShape(50))
                ) {
                    Text(
                        "${pagerState.currentPage + 1} / ${pdfPages.size}",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = { onClose() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.shadow(8.dp, CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.provider_close), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(48.dp).padding(12.dp))
                }
            }
        }

        // Bottom-left selection capsule
        if (pdfSelectedPages != null && onTogglePdfPage != null) {
            val currentPage = pagerState.currentPage
            AnimatedVisibility(
                visible = showOverlay || LocalAgoraMotionPolicy.current.eInkMode,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = currentPage in pdfSelectedPages,
                        onCheckedChange = { onTogglePdfPage(currentPage) },
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${pdfSelectedPages.size} / ${pdfPages.size} ${stringResource(R.string.pdf_selected)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(end = 10.dp)
                    )
                }
            }
        }
    }
}

// --- Multi-image/video pager ---

@Composable
private fun MediaPager(
    urls: List<String>,
    initialIndex: Int,
    onClose: () -> Unit,
    onNavigate: (Int) -> Unit,
    onMessage: (String) -> Unit = {},
    haptics: AgoraHaptics = NoOpAgoraHaptics
) {
    var currentScale by remember { mutableFloatStateOf(1f) }
    var showOverlay by remember { mutableStateOf(true) }
    var closing by remember { mutableStateOf(false) }
    var actionsForUrl by remember { mutableStateOf<String?>(null) }
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, urls.size - 1)) { urls.size }
    val currentPageIsVideo = rememberIsVideoMedia(urls[pagerState.currentPage])
    fun requestClose() {
        if (currentPageIsVideo == true) closing = true
        else onClose()
    }
    val pageScope = rememberCoroutineScope()
    LaunchedEffect(pagerState.currentPage) { onNavigate(pagerState.currentPage) }
    BackHandler { requestClose() }
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = currentScale <= 1.05f
        ) { page ->
            val mediaUrl = urls[page]
            when (rememberIsVideoMedia(mediaUrl)) {
                null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = MEDIA_LOADING_INDICATOR_STROKE_WIDTH,
                    )
                }
                true -> {
                    if (page == pagerState.currentPage) {
                        VideoPlayer(uri = mediaUrl, onClose = onClose, closing = closing)
                    }
                }
                false -> {
                    ZoomableImageItem(
                        url = mediaUrl,
                        onTap = { showOverlay = !showOverlay },
                        onScaleChanged = {
                            if (page == pagerState.currentPage) currentScale = it
                        },
                        onLongPress = {
                            haptics.longPress()
                            actionsForUrl = mediaUrl
                        },
                        consumeConditionally = true,
                    )
                }
            }
        }
        actionsForUrl?.let { ImageActionsSheet(url = it, onMessage = onMessage, onDismiss = { actionsForUrl = null }) }
        AnimatedVisibility(
            visible = showOverlay || LocalAgoraMotionPolicy.current.eInkMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (urls.size > 1) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.shadow(8.dp, RoundedCornerShape(50))
                    ) {
                        Text(
                            "${pagerState.currentPage + 1} / ${urls.size}",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                }
                if (LocalAgoraMotionPolicy.current.eInkMode && urls.size > 1) {
                    com.newoether.agora.ui.components.IconButton(
                        onClick = { pageScope.launch { pagerState.scrollToPage(pagerState.currentPage - 1) } },
                        enabled = pagerState.currentPage > 0,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.e_ink_previous_page)) }
                    com.newoether.agora.ui.components.IconButton(
                        onClick = { pageScope.launch { pagerState.scrollToPage(pagerState.currentPage + 1) } },
                        enabled = pagerState.currentPage < urls.lastIndex,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.e_ink_next_page)) }
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = { requestClose() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.shadow(8.dp, CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.provider_close), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(48.dp).padding(12.dp))
                }
            }
        }
    }
}

// --- Single image (delegates to ZoomableImageItem) ---

@Composable
private fun SingleImage(
    url: String,
    onClose: () -> Unit,
    onMessage: (String) -> Unit = {},
    haptics: AgoraHaptics = NoOpAgoraHaptics
) {
    var showOverlay by remember { mutableStateOf(true) }
    var showActions by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        ZoomableImageItem(
            url = url,
            onTap = { showOverlay = !showOverlay },
            onLongPress = { haptics.longPress(); showActions = true },
            consumeConditionally = true
        )
        if (showActions) ImageActionsSheet(url = url, onMessage = onMessage, onDismiss = { showActions = false })
        AnimatedVisibility(
            visible = showOverlay || LocalAgoraMotionPolicy.current.eInkMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(24.dp)
        ) {
            Surface(
                onClick = onClose,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.shadow(8.dp, CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.provider_close), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(48.dp).padding(12.dp))
            }
        }
    }

    BackHandler { onClose() }
}
