package com.newoether.agora.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.newoether.agora.ui.theme.ChatBackground
import com.newoether.agora.ui.theme.parseChatBackground
import java.io.File

private const val MAX_VIDEO_BACKGROUND_FPS = 25f

/**
 * Paints the user's custom chat background (Settings → Appearance) as the bottom layer behind the
 * conversation. No-op for the default. Supports a solid color, an image, or a looping video
 * (fps-capped for battery). [opacity] dims image/video so chat text stays readable.
 */
@Composable
fun ChatBackgroundLayer(
    backgroundSpec: String,
    opacity: Float = 0.3f,
    videoMuted: Boolean = true,
    videoLoop: Boolean = true,
) {
    val bg = remember(backgroundSpec) { parseChatBackground(backgroundSpec) }
    when (bg) {
        is ChatBackground.Default -> Unit
        is ChatBackground.SolidColor ->
            Box(Modifier.fillMaxSize().background(bg.color))
        is ChatBackground.Image -> {
            val file = remember(bg.path) { File(bg.path) }
            if (file.isFile) {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(opacity.coerceIn(0f, 1f)),
                )
            }
        }
        is ChatBackground.Video -> {
            val file = remember(bg.path) { File(bg.path) }
            if (file.isFile) {
                VideoBackground(file.absolutePath, opacity.coerceIn(0f, 1f), videoMuted, videoLoop)
            }
        }
    }
}

@Composable
private fun VideoBackground(path: String, opacity: Float, muted: Boolean, loop: Boolean) {
    val ctx = LocalContext.current
    val player = remember(path, muted, loop) {
        ExoPlayer.Builder(ctx)
            .setRenderersFactory(FpsCappedRenderersFactory(ctx, MAX_VIDEO_BACKGROUND_FPS))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(5000, 10000, 500, 1000)
                    .setTargetBufferBytes(5 * 1024 * 1024)
                    .build(),
            )
            .build().apply {
                repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                volume = if (muted) 0f else 1f
                playWhenReady = true
                setMediaItem(MediaItem.fromUri(File(path).toURI().toString()))
                prepare()
            }
    }
    DisposableEffect(player) {
        onDispose { player.stop(); player.release() }
    }
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                this.player = player
                useController = false
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = opacity },
    )
}
