package com.newoether.agora.ui.chat

import android.net.Uri
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.newoether.agora.R
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.motion.AnimatedVisibility
import com.newoether.agora.ui.motion.animateFloatAsState

@Composable
fun VideoPlayer(
    uri: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    closing: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var contentReady by remember { mutableStateOf(false) }
    var internalClosing by remember { mutableStateOf(false) }
    val effectiveClosing = closing || internalClosing
    val alpha by animateFloatAsState(
        targetValue = if (contentReady && !effectiveClosing) 1f else 0f,
        animationSpec = tween(400)
    )

    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    var autoHideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun scheduleAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = scope.launch {
            delay(4000)
            if (isPlaying) controlsVisible = false
        }
    }

    fun toggleControls() {
        if (controlsVisible) {
            controlsVisible = false
            autoHideJob?.cancel()
        } else {
            controlsVisible = true
            scheduleAutoHide()
        }
    }

    BackHandler(enabled = contentReady && !effectiveClosing) {
        internalClosing = true
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            prepare()
            playWhenReady = false
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    contentReady = true
                    durationMs = player.duration.coerceAtLeast(0)
                }
                isPlaying = player.playWhenReady && state == Player.STATE_READY
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                currentPositionMs = new.positionMs.coerceAtLeast(0)
            }
        }
        player.addListener(listener)

        // Poll position while playing
        val job = scope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    currentPositionMs = player.currentPosition.coerceAtLeast(0)
                }
                delay(200)
            }
        }

        onDispose {
            job.cancel()
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(effectiveClosing) {
        if (effectiveClosing) {
            player.pause()
            delay(400)
            onClose()
        }
    }

    LaunchedEffect(contentReady) {
        if (contentReady) {
            controlsVisible = true
            scheduleAutoHide()
        }
    }

    val progress = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f

    fun formatTime(ms: Long): String {
        val s = (ms / 1000)
        val m = s / 60
        val sec = s % 60
        return "${m}:${sec.toString().padStart(2, '0')}"
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Video surface (no built-in controls)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .alpha(alpha)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { toggleControls() }
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(
                        R.layout.view_texture_video_player,
                        null,
                        false,
                    ) as androidx.media3.ui.PlayerView).apply {
                        this.player = player
                    }
                },
                update = { view ->
                    if (view.player !== player) view.player = player
                },
                modifier = Modifier.fillMaxSize()
            )

            // Central play/pause button (visible when paused or controls shown)
            AnimatedVisibility(
                visible = !isPlaying || controlsVisible,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                IconButton(
                    onClick = {
                        if (isPlaying) {
                            player.pause()
                        } else {
                            if (currentPositionMs >= durationMs - 500) {
                                player.seekTo(0)
                            }
                            player.play()
                        }
                        controlsVisible = true
                        scheduleAutoHide()
                    },
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        // Bottom controls bar
        AnimatedVisibility(
            visible = controlsVisible && !effectiveClosing,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
            ) {
                // Time label
                Text(
                    "${formatTime(currentPositionMs)} / ${formatTime(durationMs)}",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                // Seekbar
                Slider(
                    value = progress,
                    onValueChange = { player.seekTo((it * durationMs).roundToLong()); controlsVisible = true },
                    onValueChangeFinished = { scheduleAutoHide() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
            }
        }
    }
}
