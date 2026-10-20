package com.newoether.agora.ui.chat

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.newoether.agora.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton
import com.newoether.agora.ui.components.Button
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.motion.animateColorAsState

data class VideoSliceResult(
    val uri: String,
    val frameCount: Int,
    val intervalMs: Long
)

object VideoSliceDefaults {
    fun defaultFrameCount(durationMs: Long): Int {
        val seconds = durationMs / 1000
        return when {
            seconds < 10 -> 3
            seconds < 30 -> 5
            seconds < 60 -> 8
            else -> maxOf(
                2,
                (seconds / 5).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
    }
}

@Composable
fun VideoSliceDialog(
    videoUri: String,
    durationMs: Long,
    onConfirm: (VideoSliceResult) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val seconds = durationMs / 1000
    val defaultFrames = remember(durationMs) { VideoSliceDefaults.defaultFrameCount(durationMs) }

    var useFrameCountMode by remember { mutableStateOf(true) }
    var frameCountInput by remember(defaultFrames) {
        mutableStateOf(defaultFrames.toString())
    }
    val frameCount = frameCountInput.toIntOrNull()?.takeIf { it >= 2 }
    var intervalSec by remember(durationMs) {
        mutableIntStateOf(maxOf(1, (seconds / defaultFrames).toInt()))
    }

    val effectiveFrameCount = if (useFrameCountMode) {
        frameCount ?: 2
    } else {
        maxOf(
            2,
            (seconds / intervalSec).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }
    val effectiveIntervalMs = if (useFrameCountMode) {
        if (effectiveFrameCount > 1) durationMs / effectiveFrameCount else 0L
    } else {
        intervalSec * 1000L
    }

    var thumbnail by remember(videoUri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(videoUri) {
        thumbnail = withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, android.net.Uri.parse(videoUri))
                    val bitmap = retriever.frameAtTime
                    bitmap?.let {
                        Bitmap.createScaledBitmap(
                            it,
                            512,
                            (512f * it.height / it.width).roundToInt(),
                            true,
                        ).also { scaled ->
                            if (scaled !== bitmap) bitmap.recycle()
                        }
                    }
                } finally {
                    retriever.release()
                }
            } catch (_: Exception) {
                null
            }
        }
    }
    DisposableEffect(videoUri) {
        onDispose {
            thumbnail?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    val durationFormatted = remember(durationMs) {
        val m = seconds / 60
        val s = seconds % 60
        "${m}:${s.toString().padStart(2, '0')}"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.video_slice_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.video_duration, durationFormatted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                val previewBitmap = thumbnail
                if (previewBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    ) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.video_preview),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    val shape = RoundedCornerShape(50)
                    ModeChip(selected = useFrameCountMode, onClick = { useFrameCountMode = true }, label = stringResource(R.string.by_frame_count), modifier = Modifier.weight(1f))
                    ModeChip(selected = !useFrameCountMode, onClick = { useFrameCountMode = false }, label = stringResource(R.string.by_interval), modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.height(12.dp))

                if (useFrameCountMode) {
                    val betweenLabel = (effectiveIntervalMs / 1000f).let {
                        if (it < 1) "${(it * 1000).roundToInt()}ms" else "${it.roundToInt()}s"
                    }
                    OutlinedTextField(
                        value = frameCountInput,
                        onValueChange = { input ->
                            if (input.isEmpty() || input.all(Char::isDigit)) {
                                frameCountInput = input.trimStart('0').ifEmpty {
                                    if (input.isEmpty()) "" else "0"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        label = {
                            Text(stringResource(R.string.frames_count, effectiveFrameCount))
                        },
                        supportingText = {
                            Text(stringResource(R.string.video_between_frames, betweenLabel))
                        },
                        singleLine = true,
                        isError = frameCount == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                } else {
                    val maxIntervalSec = seconds.coerceIn(1L, 30L).toInt()
                    Text(
                        stringResource(R.string.interval_seconds, effectiveIntervalMs / 1000),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = intervalSec.toFloat(),
                        onValueChange = {
                            intervalSec = it.roundToInt().coerceIn(1, maxIntervalSec)
                        },
                        valueRange = 1f..maxOf(2f, maxIntervalSec.toFloat()),
                        steps = (maxIntervalSec - 1).coerceIn(0, 29),
                    )
                    Text(
                        stringResource(R.string.frames_count, effectiveFrameCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, shape = RoundedCornerShape(50)) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(
                                VideoSliceResult(
                                    videoUri,
                                    effectiveFrameCount,
                                    effectiveIntervalMs,
                                ),
                            )
                        },
                        enabled = !useFrameCountMode || frameCount != null,
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(stringResource(R.string.extract_frames, effectiveFrameCount))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    val bgColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(300)
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(300)
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor, shape = shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor
        )
    }
}
