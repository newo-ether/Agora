package com.newoether.agora.ui.chat.message

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.chat.AudioActionsSheet
import com.newoether.agora.ui.chat.MEDIA_LOADING_INDICATOR_STROKE_WIDTH
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.common.LocalSnackbarMessage
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where an inline Markdown audio track is played from. */
internal sealed interface MarkdownAudioSource {
    data class Remote(val url: String) : MarkdownAudioSource
    data class Local(val file: LocalFileTarget) : MarkdownAudioSource
    /** A file on a Conch device or Local Sandbox rootfs; copied into app cache before playback. */
    data class Conch(val server: String, val path: String) : MarkdownAudioSource
}

/**
 * Resolves an audio destination. Readable local roots play in place, other Local Sandbox rootfs
 * paths and Conch devices copy into the app cache before playback, and HTTP(S) audio streams
 * directly.
 */
internal fun markdownAudioSource(link: String): MarkdownAudioSource? {
    val raw = markdownAudioLink(link) ?: return null
    return when (val target = resolveMarkdownDestination(raw)) {
        is MarkdownDestination.Web -> MarkdownAudioSource.Remote(target.url)
        is MarkdownDestination.File -> MarkdownAudioSource.Local(target.target)
        is MarkdownDestination.Device -> MarkdownAudioSource.Conch(target.link.server, target.link.path)
        null -> null
    }
}

internal enum class AudioGlyphKind {
    DOWNLOADING,
    BUSY,
    PLAYING,
    PAUSED,
}

internal val AUDIO_PROGRESS_SPRING = spring<Float>(
    dampingRatio = 0.82f,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = 0.0005f,
)

internal enum class AudioSubtitleMode {
    DOWNLOADING,
    TIMECODE,
    LOADING,
    BADGE,
    EMPTY,
}

/**
 * Lightweight clickable audio card rendered inline inside Markdown messages. Playback is owned by
 * [MarkdownAudioPlayerController] on [ShellMarkdownMedia], so scrolling this card off screen never
 * interrupts playback. Tapping the play button quick-toggles playback; tapping the card body opens
 * the full player sheet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MarkdownAudioCard(
    link: String,
    title: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val notify = LocalSnackbarMessage.current
    val media = LocalShellMarkdownMedia.current
    val controller = media?.audioPlayer
    val haptics = LocalAgoraHaptics.current
    val motionPolicy = LocalAgoraMotionPolicy.current
    val scope = rememberCoroutineScope()
    val track = remember(link, title) { buildMarkdownAudioTrack(link, title) }
    val playbackState = if (controller != null) controller.state.collectAsState().value else null
    val activePlayback = playbackState?.takeIf { track != null && it.track?.key == track.key }
    val isActive = activePlayback != null
    val download = if (track?.source is MarkdownAudioSource.Conch && media != null) {
        media.audio.peek(link)
    } else null
    val downloading = download as? MarkdownVideoDownload.Downloading
    val readyFile = (download as? MarkdownVideoDownload.Ready)?.file
    var cardActionsUrl by remember(link) { mutableStateOf<String?>(null) }
    var pendingActions by remember(link) { mutableStateOf(false) }

    LaunchedEffect(pendingActions, download) {
        if (!pendingActions) return@LaunchedEffect
        when (download) {
            is MarkdownVideoDownload.Ready -> {
                pendingActions = false
                haptics.longPress()
                cardActionsUrl = Uri.fromFile(download.file).toString()
            }
            is MarkdownVideoDownload.Failed -> {
                pendingActions = false
                notify(download.message)
            }
            else -> Unit
        }
    }

    fun openActionsMenu() {
        val currentTrack = track ?: return notify(context.getString(R.string.markdown_link_file_unavailable))
        when (val source = currentTrack.source) {
            is MarkdownAudioSource.Remote -> {
                haptics.longPress()
                cardActionsUrl = source.url
            }
            is MarkdownAudioSource.Local -> {
                scope.launch {
                    val resolution = withContext(Dispatchers.IO) {
                        resolveLocalFileTarget(context, source.file)
                    }
                    when (resolution) {
                        is LocalFileResolution.Ready -> {
                            haptics.longPress()
                            cardActionsUrl = Uri.fromFile(resolution.file).toString()
                        }
                        LocalFileResolution.NotFound ->
                            notify(context.getString(R.string.markdown_link_file_missing))
                        LocalFileResolution.NeedsSharedStorageAccess ->
                            notify(context.getString(R.string.markdown_link_shared_storage_required))
                        LocalFileResolution.Unavailable ->
                            notify(context.getString(R.string.markdown_link_file_unavailable))
                    }
                }
            }
            is MarkdownAudioSource.Conch -> {
                val activeMedia = media ?: return notify(context.getString(R.string.markdown_link_file_unavailable))
                when {
                    readyFile != null -> {
                        haptics.longPress()
                        cardActionsUrl = Uri.fromFile(readyFile).toString()
                    }
                    download is MarkdownVideoDownload.Failed -> {
                        notify(download.message)
                        pendingActions = true
                        activeMedia.audio.start(link)
                    }
                    else -> {
                        pendingActions = true
                        activeMedia.audio.start(link)
                    }
                }
            }
        }
    }

    val isPlaying = activePlayback?.isPlaying == true
    val isBusy = activePlayback != null && (activePlayback.isBuffering || activePlayback.isDownloading)
    val durationMs = activePlayback?.durationMs ?: 0L
    val positionMs = activePlayback?.positionMs ?: 0L
    var lastProgressFraction by remember(track?.key) { mutableFloatStateOf(0f) }
    var lastTimecodeText by remember(track?.key) { mutableStateOf("") }
    if (isActive && durationMs > 0L) {
        lastProgressFraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        lastTimecodeText = "${formatAudioTime(positionMs)} / ${formatAudioTime(durationMs)}"
    } else if (isActive) {
        lastProgressFraction = 0f
    }

    val subtitleMode = when {
        downloading != null && downloading.total > 0 -> AudioSubtitleMode.DOWNLOADING
        isActive && durationMs > 0L -> AudioSubtitleMode.TIMECODE
        activePlayback?.isDownloading == true -> AudioSubtitleMode.LOADING
        track != null && track.extensionBadge.isNotEmpty() -> AudioSubtitleMode.BADGE
        else -> AudioSubtitleMode.EMPTY
    }

    val cardBackground by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        animationSpec = if (motionPolicy.reduceMotion) snap() else tween(220, easing = FastOutSlowInEasing),
        label = "audioCardBackground",
    )
    val subtitleColor by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = if (motionPolicy.reduceMotion) snap() else tween(200, easing = FastOutSlowInEasing),
        label = "audioCardSubtitleColor",
    )

    fun invokePlayer(openSheet: Boolean) {
        if (controller == null || track == null) {
            notify(context.getString(R.string.markdown_link_file_unavailable))
            return
        }
        controller.playOrToggle(track, openSheet = openSheet, onError = notify)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(cardBackground)
            .combinedClickable(
                onClick = { invokePlayer(openSheet = true) },
                onLongClick = ::openActionsMenu,
                hapticFeedbackEnabled = false,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .combinedClickable(
                        onClick = { invokePlayer(openSheet = false) },
                        onLongClick = ::openActionsMenu,
                        hapticFeedbackEnabled = false,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AudioPlayButtonGlyph(
                    isPlaying = isPlaying,
                    isBusy = isBusy,
                    download = downloading,
                    iconSizeDp = 22,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = track?.title ?: title?.trim()?.ifEmpty { link } ?: link,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedContent(
                    targetState = subtitleMode,
                    transitionSpec = {
                        val contentTransform = if (motionPolicy.allowSpatialTransitions) {
                            when {
                                targetState == AudioSubtitleMode.TIMECODE ->
                                    EnterTransition.None togetherWith fadeOut(tween(140))
                                initialState == AudioSubtitleMode.TIMECODE ->
                                    fadeIn(tween(180)) togetherWith ExitTransition.None
                                else ->
                                    fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                            }
                        } else {
                            fadeIn(tween(140)) togetherWith fadeOut(tween(110))
                        }
                        // SizeTransform on a fillMaxWidth AnimatedContent lags behind the sibling
                        // GraphicEq expand/shrink in the Row, so Compose centers the temporarily
                        // oversized measure box inside the narrower Column and shifts the text left.
                        contentTransform.using(null)
                    },
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    label = "audioCardSubtitle",
                ) { mode ->
                    val modeText = when (mode) {
                        AudioSubtitleMode.DOWNLOADING -> downloading?.takeIf { it.total > 0 }
                            ?.let { "${it.loaded * 100 / it.total}%" }.orEmpty()
                        AudioSubtitleMode.TIMECODE -> lastTimecodeText
                        AudioSubtitleMode.LOADING -> stringResource(R.string.loading_label)
                        AudioSubtitleMode.BADGE -> track?.extensionBadge.orEmpty()
                        AudioSubtitleMode.EMPTY -> ""
                    }
                    val timecodeModifier = if (mode == AudioSubtitleMode.TIMECODE && motionPolicy.allowSpatialTransitions) {
                        val reveal by transition.animateFloat(
                            transitionSpec = {
                                if (EnterExitState.PreEnter isTransitioningTo EnterExitState.Visible) {
                                    tween(340, easing = FastOutSlowInEasing)
                                } else {
                                    tween(200, easing = FastOutSlowInEasing)
                                }
                            },
                            label = "audioTimecodeMaskReveal",
                        ) { state ->
                            if (state == EnterExitState.Visible) 1f else 0f
                        }
                        val maskAlpha by transition.animateFloat(
                            transitionSpec = {
                                if (EnterExitState.PreEnter isTransitioningTo EnterExitState.Visible) {
                                    tween(260, easing = FastOutSlowInEasing)
                                } else {
                                    tween(160, easing = FastOutSlowInEasing)
                                }
                            },
                            label = "audioTimecodeMaskAlpha",
                        ) { state ->
                            if (state == EnterExitState.Visible) 1f else 0f
                        }
                        Modifier.horizontalGradientMask(reveal = { reveal }, alpha = { maskAlpha })
                    } else {
                        Modifier
                    }
                    if (mode != AudioSubtitleMode.EMPTY && modeText.isNotEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = modeText,
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = subtitleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = timecodeModifier,
                            )
                        }
                    } else {
                        Spacer(Modifier.height(0.dp))
                    }
                }
            }
            AnimatedVisibility(
                visible = isPlaying,
                enter = if (motionPolicy.allowSpatialTransitions) {
                    fadeIn(tween(200)) +
                        scaleIn(initialScale = 0.8f, animationSpec = tween(200, easing = FastOutSlowInEasing)) +
                        expandHorizontally(tween(200, easing = FastOutSlowInEasing))
                } else {
                    fadeIn(tween(160))
                },
                exit = if (motionPolicy.allowSpatialTransitions) {
                    fadeOut(tween(150)) +
                        scaleOut(targetScale = 0.8f, animationSpec = tween(150)) +
                        shrinkHorizontally(tween(180, easing = FastOutSlowInEasing))
                } else {
                    fadeOut(tween(140))
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        AudioRoundedProgressBar(
            progress = lastProgressFraction,
            visible = isActive && durationMs > 0L,
            height = 3.dp,
            horizontalInset = 12.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 3.dp),
        )
    }
    val actionsUrl = cardActionsUrl
    if (actionsUrl != null) {
        AudioActionsSheet(
            url = actionsUrl,
            onMessage = notify,
            onDismiss = { cardActionsUrl = null },
        )
    }
}

@Composable
internal fun AudioPlayButtonGlyph(
    isPlaying: Boolean,
    isBusy: Boolean,
    download: MarkdownVideoDownload.Downloading?,
    iconSizeDp: Int,
    tint: Color,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val kind = when {
        download != null && download.total > 0 -> AudioGlyphKind.DOWNLOADING
        isBusy -> AudioGlyphKind.BUSY
        isPlaying -> AudioGlyphKind.PLAYING
        else -> AudioGlyphKind.PAUSED
    }

    AnimatedContent(
        targetState = kind,
        transitionSpec = {
            if (motionPolicy.allowSpatialTransitions) {
                (fadeIn(tween(150)) + scaleIn(initialScale = 0.78f, animationSpec = tween(180, easing = FastOutSlowInEasing))) togetherWith
                    (fadeOut(tween(120)) + scaleOut(targetScale = 0.78f, animationSpec = tween(120)))
            } else {
                fadeIn(tween(130)) togetherWith fadeOut(tween(110))
            }
        },
        contentAlignment = Alignment.Center,
        label = "audioPlayGlyph",
    ) { state ->
        when (state) {
            AudioGlyphKind.DOWNLOADING -> {
                val percent = if (download != null && download.total > 0) {
                    (download.loaded * 100 / download.total).coerceIn(0, 100)
                } else 0
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                )
            }
            AudioGlyphKind.BUSY -> {
                MotionAwareCircularProgressIndicator(
                    modifier = Modifier.size((iconSizeDp - 2).coerceAtLeast(16).dp),
                    strokeWidth = MEDIA_LOADING_INDICATOR_STROKE_WIDTH,
                    color = tint,
                )
            }
            AudioGlyphKind.PLAYING -> {
                Icon(
                    imageVector = Icons.Rounded.Pause,
                    contentDescription = stringResource(R.string.pause),
                    modifier = Modifier.size(iconSizeDp.dp),
                    tint = tint,
                )
            }
            AudioGlyphKind.PAUSED -> {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.play),
                    modifier = Modifier.size(iconSizeDp.dp),
                    tint = tint,
                )
            }
        }
    }
}

@Composable
internal fun AudioRoundedProgressBar(
    progress: Float,
    visible: Boolean,
    height: Dp,
    horizontalInset: Dp,
    modifier: Modifier = Modifier,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (motionPolicy.allowContinuousMotion) AUDIO_PROGRESS_SPRING else snap(),
        label = "audioBarProgress",
    )
    val trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val activeColor = MaterialTheme.colorScheme.primary

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(160)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalInset)
                .height(height),
        ) {
            val stroke = size.height
            val cap = stroke / 2f
            val y = cap
            val startX = cap
            val endX = (size.width - cap).coerceAtLeast(startX)
            drawLine(
                color = trackColor,
                start = Offset(startX, y),
                end = Offset(endX, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            val clampedProgress = animatedProgress.coerceIn(0f, 1f)
            if (clampedProgress > 0.001f) {
                val activeEndX = startX + (endX - startX) * clampedProgress
                drawLine(
                    color = activeColor,
                    start = Offset(startX, y),
                    end = Offset(activeEndX, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private fun Modifier.horizontalGradientMask(
    reveal: () -> Float,
    alpha: () -> Float,
): Modifier = this
    .graphicsLayer {
        val r = reveal().coerceIn(0f, 1f)
        val a = alpha().coerceIn(0f, 1f)
        compositingStrategy = if (r < 0.999f || a < 0.999f) {
            CompositingStrategy.Offscreen
        } else {
            CompositingStrategy.Auto
        }
    }
    .drawWithContent {
        val r = reveal().coerceIn(0f, 1f)
        val a = alpha().coerceIn(0f, 1f)
        if (r >= 0.999f && a >= 0.999f) {
            drawContent()
            return@drawWithContent
        }
        if (r <= 0.001f || a <= 0.001f || size.width <= 0f) {
            return@drawWithContent
        }
        drawContent()
        val feather = (size.width * 0.4f).coerceIn(16.dp.toPx(), 40.dp.toPx())
        val fadeStart = r * (size.width + feather) - feather
        val fadeEnd = fadeStart + feather
        val solid = Color.Black.copy(alpha = a)
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(solid, Color.Transparent),
                startX = fadeStart,
                endX = fadeEnd,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

internal fun formatAudioTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
