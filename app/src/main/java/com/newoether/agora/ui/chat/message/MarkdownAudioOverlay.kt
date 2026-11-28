package com.newoether.agora.ui.chat.message

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.newoether.agora.R
import com.newoether.agora.ui.chat.shareAudio
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.common.LocalSnackbarMessage
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.ui.components.DialogWindowNoSystemDim
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareModalBottomSheet as ModalBottomSheet
import com.newoether.agora.ui.theme.chatCompactWidth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class MiniCapsuleDisplay(
    val track: MarkdownAudioTrack,
    val playback: MarkdownAudioPlaybackState,
    val download: MarkdownVideoDownload.Downloading?,
)

private class MiniCapsuleHolder(
    var display: MiniCapsuleDisplay? = null,
    var activeTrackKey: String? = null,
)

private data class MiniSubtitleKey(
    val trackKey: String,
    val mode: AudioSubtitleMode,
)

/**
 * Chat-level host for the floating mini audio capsule (below the top bar) and the expanded
 * playback bottom sheet. Mounted outside [MessageList] so playback controls stay reachable while
 * scrolling through messages or switching conversations.
 */
@Composable
internal fun BoxScope.MarkdownAudioOverlayHost(
    hidden: Boolean = false,
) {
    val media = LocalShellMarkdownMedia.current ?: return
    val controller = media.audioPlayer
    val playback by controller.state.collectAsState()
    val track = playback.track
    val download = if (track?.source is MarkdownAudioSource.Conch && playback.isDownloading) {
        media.audio.peek(track.link) as? MarkdownVideoDownload.Downloading
    } else null
    val motionPolicy = LocalAgoraMotionPolicy.current
    val topCapsuleHeight = if (chatCompactWidth) 48.dp else 52.dp

    // Coalesce brief track-switch loading states (local files ready in ~20ms) and retain the last
    // active snapshot during exit so the mini capsule never flashes Loading/0:00 or strips content.
    val liveMini = if (track != null) MiniCapsuleDisplay(track, playback, download) else null
    val miniHolder = remember { MiniCapsuleHolder() }
    var switchCommitGeneration by remember { mutableIntStateOf(0) }
    val commitGen = switchCommitGeneration
    if (track == null) {
        miniHolder.activeTrackKey = null
    }
    val previousDisplay = miniHolder.display
    val isPendingTrackSwitch = miniHolder.activeTrackKey != null &&
        previousDisplay != null &&
        liveMini != null &&
        liveMini.track.key != previousDisplay.track.key &&
        liveMini.playback.durationMs == 0L &&
        !liveMini.playback.isPlaying &&
        (liveMini.playback.isBuffering || liveMini.playback.isDownloading) &&
        (liveMini.download == null || liveMini.download.total <= 0L)
    if (liveMini != null && !isPendingTrackSwitch) {
        miniHolder.display = liveMini
        miniHolder.activeTrackKey = liveMini.track.key
    }
    LaunchedEffect(liveMini?.track?.key, isPendingTrackSwitch, commitGen) {
        if (isPendingTrackSwitch && liveMini != null) {
            delay(150L)
            miniHolder.display = liveMini
            miniHolder.activeTrackKey = liveMini.track.key
            switchCommitGeneration += 1
        }
    }
    val displayMini = if (liveMini != null && !isPendingTrackSwitch) liveMini else miniHolder.display

    AnimatedVisibility(
        visible = !hidden && track != null,
        enter = if (motionPolicy.allowSpatialTransitions) {
            fadeIn(tween(200)) +
                slideInVertically(tween(240, easing = FastOutSlowInEasing)) { -it / 2 } +
                scaleIn(initialScale = 0.96f, animationSpec = tween(220, easing = FastOutSlowInEasing))
        } else {
            fadeIn(tween(180))
        },
        exit = if (motionPolicy.allowSpatialTransitions) {
            fadeOut(tween(160)) +
                slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { -it / 2 } +
                scaleOut(targetScale = 0.96f, animationSpec = tween(160))
        } else {
            fadeOut(tween(160))
        },
        modifier = Modifier
            .align(Alignment.TopCenter)
            .zIndex(1.6f)
            .statusBarsPadding()
            .padding(top = topCapsuleHeight + 16.dp, start = 16.dp, end = 16.dp),
    ) {
        if (displayMini != null) {
            MarkdownAudioMiniPlayerCapsule(
                track = displayMini.track,
                playback = displayMini.playback,
                download = displayMini.download,
                onTogglePlayPause = { if (track != null) controller.togglePlayPause() },
                onExpand = { if (track != null) controller.openSheet() },
                onClose = { if (track != null) controller.stopAndClose() },
            )
        }
    }

    if (playback.sheetExpanded && track != null) {
        MarkdownAudioPlayerSheet(
            track = track,
            playback = playback,
            download = download,
            onTogglePlayPause = controller::togglePlayPause,
            onPause = controller::pause,
            onSeekTo = controller::seekTo,
            onSeekBy = controller::seekBy,
            onCycleSpeed = controller::cyclePlaybackSpeed,
            onStopAndClose = controller::stopAndClose,
            onDismiss = controller::dismissSheet,
        )
    }
}

@Composable
private fun MarkdownAudioMiniPlayerCapsule(
    track: MarkdownAudioTrack,
    playback: MarkdownAudioPlaybackState,
    download: MarkdownVideoDownload.Downloading?,
    onTogglePlayPause: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val progressFraction = if (playback.durationMs > 0L) {
        (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
    } else 0f
    val statusMode = when {
        download != null && download.total > 0 -> AudioSubtitleMode.DOWNLOADING
        playback.durationMs > 0L -> AudioSubtitleMode.TIMECODE
        playback.isDownloading || playback.isBuffering -> AudioSubtitleMode.LOADING
        track.extensionBadge.isNotEmpty() -> AudioSubtitleMode.BADGE
        else -> AudioSubtitleMode.EMPTY
    }
    val subtitleKey = MiniSubtitleKey(track.key, statusMode)
    val liveSubtitleText = when (statusMode) {
        AudioSubtitleMode.DOWNLOADING -> download?.takeIf { it.total > 0 }
            ?.let { "${it.loaded * 100 / it.total}%" }.orEmpty()
        AudioSubtitleMode.TIMECODE -> "${formatAudioTime(playback.positionMs)} / ${formatAudioTime(playback.durationMs)}"
        AudioSubtitleMode.LOADING -> stringResource(R.string.loading_label)
        AudioSubtitleMode.BADGE -> track.extensionBadge
        AudioSubtitleMode.EMPTY -> ""
    }
    val subtitleTextCache = remember { mutableMapOf<MiniSubtitleKey, String>() }
    if (liveSubtitleText.isNotEmpty()) {
        subtitleTextCache[subtitleKey] = liveSubtitleText
        if (subtitleTextCache.size > 6) {
            val staleKey = subtitleTextCache.keys.firstOrNull { it != subtitleKey }
            if (staleKey != null) subtitleTextCache.remove(staleKey)
        }
    }

    Box(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .height(50.dp),
        propagateMinConstraints = true,
    ) {
        Surface(
            modifier = Modifier.matchParentSize(),
            shape = shape,
            color = Color.Transparent,
            shadowElevation = 4.dp,
        ) {}
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 0.dp,
        ) {
            Box(modifier = Modifier.clip(shape)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clickable(onClick = onExpand)
                        .padding(start = 8.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClick = onTogglePlayPause),
                        contentAlignment = Alignment.Center,
                    ) {
                        AudioPlayButtonGlyph(
                            isPlaying = playback.isPlaying,
                            isBusy = playback.isBuffering || playback.isDownloading,
                            download = download,
                            iconSizeDp = 20,
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        AnimatedContent(
                            targetState = track.key to track.title,
                            contentKey = { it.first },
                            transitionSpec = {
                                (fadeIn(tween(180)) togetherWith fadeOut(tween(120))).using(null)
                            },
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.fillMaxWidth(),
                            label = "audioMiniTitle",
                        ) { (_, title) ->
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        AnimatedContent(
                            targetState = subtitleKey,
                            transitionSpec = {
                                (fadeIn(tween(180)) togetherWith fadeOut(tween(120))).using(null)
                            },
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.fillMaxWidth(),
                            label = "audioMiniStatus",
                        ) { key ->
                            val shownText = if (key == subtitleKey) {
                                liveSubtitleText
                            } else {
                                subtitleTextCache[key].orEmpty()
                            }
                            if (key.mode != AudioSubtitleMode.EMPTY && shownText.isNotEmpty()) {
                                Text(
                                    text = shownText,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Spacer(Modifier.height(15.dp))
                            }
                        }
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.provider_close),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AudioRoundedProgressBar(
                    progress = progressFraction,
                    visible = playback.durationMs > 0L,
                    height = 2.5.dp,
                    horizontalInset = 20.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownAudioPlayerSheet(
    track: MarkdownAudioTrack,
    playback: MarkdownAudioPlaybackState,
    download: MarkdownVideoDownload.Downloading?,
    onTogglePlayPause: () -> Unit,
    onPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    onStopAndClose: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val notify = LocalSnackbarMessage.current
    val haptics = LocalAgoraHaptics.current
    val motionPolicy = LocalAgoraMotionPolicy.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var dragFraction by remember(track.key) { mutableStateOf<Float?>(null) }
    var stopping by remember(track.key) { mutableStateOf(false) }

    fun handleStopAndClose() {
        if (stopping) return
        stopping = true
        onPause()
        scope.launch {
            if (motionPolicy.allowSpatialTransitions) {
                runCatching { sheetState.hide() }
            }
            onStopAndClose()
        }
    }

    val durationMs = playback.durationMs
    val rawFraction = if (durationMs > 0L) {
        (playback.positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else 0f
    val sliderInteractionSource = remember(track.key) { MutableInteractionSource() }
    val sliderAnim = remember(track.key) { Animatable(rawFraction) }
    var sawDragInGesture by remember(track.key) { mutableStateOf(false) }
    var gestureValueChangeCount by remember(track.key) { mutableIntStateOf(0) }
    var springSeekActive by remember(track.key) { mutableStateOf(false) }
    var springSeekGeneration by remember(track.key) { mutableIntStateOf(0) }
    var snapAfterDrag by remember(track.key) { mutableStateOf(false) }

    LaunchedEffect(sliderInteractionSource, track.key) {
        sliderInteractionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> sawDragInGesture = true
                is DragInteraction.Cancel -> {
                    sawDragInGesture = false
                    gestureValueChangeCount = 0
                    dragFraction = null
                }
            }
        }
    }

    LaunchedEffect(track.key, rawFraction, dragFraction, springSeekGeneration) {
        val draggingValue = dragFraction
        if (draggingValue != null) {
            springSeekActive = false
            sliderAnim.snapTo(draggingValue)
            return@LaunchedEffect
        }
        if (snapAfterDrag) {
            snapAfterDrag = false
            springSeekActive = false
            sliderAnim.snapTo(rawFraction)
            return@LaunchedEffect
        }
        if (springSeekActive) {
            if (motionPolicy.allowContinuousMotion) {
                sliderAnim.animateTo(rawFraction, AUDIO_PROGRESS_SPRING)
            } else {
                sliderAnim.snapTo(rawFraction)
            }
            springSeekActive = false
            return@LaunchedEffect
        }
        if (motionPolicy.allowContinuousMotion) {
            sliderAnim.animateTo(rawFraction, tween(180, easing = LinearEasing))
        } else {
            sliderAnim.snapTo(rawFraction)
        }
    }

    val sliderFraction = (dragFraction ?: sliderAnim.value).coerceIn(0f, 1f)
    val shownPositionMs = if (dragFraction != null && durationMs > 0L) {
        (sliderFraction * durationMs).toLong()
    } else {
        playback.positionMs
    }
    val sliderColors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
        disabledThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        disabledActiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f),
    )

    ModalBottomSheet(
        onDismissRequest = { if (stopping) onStopAndClose() else onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        DialogWindowEdgeToEdge()
        DialogWindowNoSystemDim()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val metaLabel = when {
                        download != null && download.total > 0 ->
                            "${download.loaded * 100 / download.total}%"
                        playback.isDownloading || (playback.isBuffering && durationMs == 0L) ->
                            stringResource(R.string.loading_label)
                        else -> null
                    }
                    if (track.extensionBadge.isNotEmpty() || metaLabel != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (track.extensionBadge.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                                ) {
                                    Text(
                                        text = track.extensionBadge,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (metaLabel != null) {
                                Text(
                                    text = metaLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                val shareUrl = playback.resolvedFile?.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }
                    ?: (track.source as? MarkdownAudioSource.Remote)?.url
                if (shareUrl != null) {
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            scope.launch {
                                if (!shareAudio(context, shareUrl)) {
                                    notify(context.getString(R.string.markdown_link_file_unavailable))
                                }
                            }
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = stringResource(R.string.img_action_share),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Slider(
                value = sliderFraction,
                onValueChange = { next ->
                    gestureValueChangeCount += 1
                    dragFraction = next.coerceIn(0f, 1f)
                },
                onValueChangeFinished = {
                    val target = dragFraction
                    val wasTap = !sawDragInGesture && gestureValueChangeCount == 1
                    sawDragInGesture = false
                    gestureValueChangeCount = 0
                    dragFraction = null
                    if (target != null && durationMs > 0L) {
                        val clampedTarget = target.coerceIn(0f, 1f)
                        if (wasTap) {
                            snapAfterDrag = false
                            springSeekActive = true
                            springSeekGeneration += 1
                        } else {
                            springSeekActive = false
                            snapAfterDrag = true
                        }
                        onSeekTo((clampedTarget * durationMs).toLong())
                    }
                },
                enabled = durationMs > 0L,
                interactionSource = sliderInteractionSource,
                colors = sliderColors,
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = sliderInteractionSource,
                        colors = sliderColors,
                        enabled = durationMs > 0L,
                        thumbSize = DpSize(4.dp, 26.dp),
                    )
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(6.dp),
                        enabled = durationMs > 0L,
                        colors = sliderColors,
                        drawStopIndicator = null,
                        thumbTrackGapSize = 6.dp,
                        trackInsideCornerSize = 2.dp,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatAudioTime(shownPositionMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (durationMs > 0L) formatAudioTime(durationMs) else "--:--",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            haptics.selection()
                            onCycleSpeed()
                        },
                ) {
                    AnimatedContent(
                        targetState = formatPlaybackSpeed(playback.playbackSpeed),
                        transitionSpec = {
                            if (motionPolicy.allowSpatialTransitions) {
                                (fadeIn(tween(150)) + slideInVertically(tween(170, easing = FastOutSlowInEasing)) { it / 3 }) togetherWith
                                    (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 3 })
                            } else {
                                fadeIn(tween(140)) togetherWith fadeOut(tween(110))
                            }
                        },
                        label = "audioSpeedLabel",
                    ) { speedLabel ->
                        Text(
                            text = speedLabel,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontFeatureSettings = "tnum",
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }

                IconButton(
                    onClick = {
                        if (!stopping) {
                            snapAfterDrag = false
                            springSeekActive = true
                            springSeekGeneration += 1
                            onSeekBy(-10_000L)
                        }
                    },
                    enabled = durationMs > 0L,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Replay10,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { if (!stopping) onTogglePlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    AudioPlayButtonGlyph(
                        isPlaying = playback.isPlaying,
                        isBusy = playback.isBuffering || playback.isDownloading,
                        download = download,
                        iconSizeDp = 30,
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }

                IconButton(
                    onClick = {
                        if (!stopping) {
                            snapAfterDrag = false
                            springSeekActive = true
                            springSeekGeneration += 1
                            onSeekBy(10_000L)
                        }
                    },
                    enabled = durationMs > 0L,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Forward10,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                    )
                }

                IconButton(
                    onClick = ::handleStopAndClose,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = stringResource(R.string.provider_close),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
