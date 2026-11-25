package com.newoether.agora.ui.chat.bottombar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.newoether.agora.R
import com.newoether.agora.ui.chat.message.COMPOSER_ICON_CROSSFADE_DURATION_MS
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.viewmodel.SpeechInputController
import com.newoether.agora.viewmodel.SpeechInputState

/** The phone's speech controller; absent (null) in previews and tests. */
val LocalSpeechInput = staticCompositionLocalOf<SpeechInputController?> { null }

/** 0 while the composer is idle, 1 while a capture is live; animates between the two. */
internal val LocalComposerCapsuleProgress = compositionLocalOf { 0f }

internal enum class ComposerVoicePhase { IDLE, RECORDING, TRANSCRIBING }

/** One composer's view of the shared microphone session. */
internal class ComposerVoice(
    val controller: SpeechInputController,
    val phase: ComposerVoicePhase,
    val sendPending: Boolean,
    val enabled: Boolean,
    val onMic: () -> Unit,
    val onCancel: () -> Unit,
    val onFinish: () -> Unit,
    /** Stops, transcribes into the draft, then runs [send]. Only valid while recording. */
    val finishAndSend: (send: () -> Unit) -> Unit,
) {
    val active: Boolean get() = phase != ComposerVoicePhase.IDLE
}

/**
 * Binds voice input to [ownerId]. Returns null when recognition is off or has no usable
 * recognizer, so the composer hides its microphone. Leaving the owner (conversation switch,
 * disposal) or the app going to the background cancels capture and keeps the draft untouched.
 */
@Composable
internal fun rememberComposerVoice(
    ownerId: String,
    enabled: Boolean,
    onTranscript: (String) -> Unit,
): ComposerVoice? {
    val controller = LocalSpeechInput.current ?: return null
    val settings by controller.settings.collectAsState()
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val haptics = LocalAgoraHaptics.current
    val insert by rememberUpdatedState(onTranscript)
    var sendPending by remember(ownerId) { mutableStateOf(false) }
    val phase = when (val current = state) {
        is SpeechInputState.Recording ->
            if (current.ownerId == ownerId) ComposerVoicePhase.RECORDING else ComposerVoicePhase.IDLE
        is SpeechInputState.Transcribing ->
            if (current.ownerId == ownerId) ComposerVoicePhase.TRANSCRIBING else ComposerVoicePhase.IDLE
        SpeechInputState.Idle -> ComposerVoicePhase.IDLE
    }
    LaunchedEffect(phase) { if (phase == ComposerVoicePhase.IDLE) sendPending = false }
    DisposableEffect(controller, ownerId) { onDispose { controller.cancel(ownerId) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, ownerId, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.cancel(ownerId)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val start = {
        if (controller.start(ownerId) { _, text -> insert(text) }) haptics.selection()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else controller.reportPermissionDenied()
    }
    // Keep a running capture reachable even if settings change underneath it.
    if (!settings.usable && phase == ComposerVoicePhase.IDLE) return null
    return ComposerVoice(
        controller = controller,
        phase = phase,
        sendPending = sendPending,
        enabled = enabled,
        onMic = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                start()
            } else {
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onCancel = { controller.cancel(ownerId) },
        onFinish = { controller.finish(ownerId) { _, text -> insert(text) } },
        finishAndSend = { send ->
            sendPending = true
            controller.finish(ownerId) { _, text ->
                insert(text)
                send()
            }
        },
    )
}

/** Inserts [text] at the cursor, adding a space only between two Latin words. */
internal fun TextFieldState.insertTranscript(text: String) {
    val transcript = text.trim()
    if (transcript.isEmpty()) return
    edit {
        val start = selection.min
        val end = selection.max
        val before = if (start > 0) charAt(start - 1) else null
        val needsSpace = before != null && before.isLetterOrDigit() && before.code < 0x2E80 &&
            transcript.first().isLetterOrDigit() && transcript.first().code < 0x2E80
        val insertion = if (needsSpace) " $transcript" else transcript
        replace(start, end, insertion)
        selection = TextRange(start + insertion.length)
    }
}

/**
 * Composer controls with voice input. Idle: the controls capsule, then a thin microphone next to
 * the send button. Recording: the capsule stretches over the freed space and its contents cross
 * into a cancel cross, a scrolling loudness history and the timer, while the microphone becomes a
 * stop square in place. The send button never moves.
 */
@Composable
internal fun RowScope.ComposerVoiceControls(
    voice: ComposerVoice?,
    controls: @Composable RowScope.() -> Unit,
    send: @Composable () -> Unit,
) {
    if (voice == null) {
        controls()
        send()
        return
    }
    val progress by animateFloatAsState(
        targetValue = if (voice.active) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "composerVoiceCapsule",
    )
    CompositionLocalProvider(LocalComposerCapsuleProgress provides progress) {
        controls()
    }
    send()
}

/**
 * The microphone that starts a capture. It fades out and stops accepting taps while a capture owns
 * the composer, because the capsule then carries the stop square itself.
 */
@Composable
internal fun ComposerVoiceButton(voice: ComposerVoice) {
    IconButton(
        onClick = voice.onMic,
        enabled = voice.enabled && !voice.active,
        modifier = Modifier.size(COMPOSER_CONTROL_HEIGHT),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        ComposerMicGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The stop square's own circular button at the capsule's right end. It no longer borrows the
 * microphone's 48 dp slot, so it can sit nearer the send button while the mic fades out above it.
 */
private val COMPOSER_STOP_SIZE = 32.dp

/**
 * The stop square inside the capsule's right end. It belongs to [ComposerRecordingOverlay], so the
 * capsule's own rounded clip trims it during the collapse instead of the mic slot carrying it.
 * Colour and glyph both fade. The two glyphs share one fixed 18 dp box and swap by alpha only, so
 * their different intrinsic sizes never animate against each other.
 */
@Composable
private fun ComposerStopButton(
    color: Color,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.speech_input_stop)
    val glyphColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(durationMillis = 400),
        label = "composerStopColor",
    )
    val squareAlpha by animateFloatAsState(
        targetValue = if (loading) 0f else 1f,
        animationSpec = tween(durationMillis = COMPOSER_ICON_CROSSFADE_DURATION_MS),
        label = "composerStopSquareAlpha",
    )
    val spinnerAlpha by animateFloatAsState(
        targetValue = if (loading) 1f else 0f,
        animationSpec = tween(durationMillis = COMPOSER_ICON_CROSSFADE_DURATION_MS),
        label = "composerStopSpinnerAlpha",
    )
    Box(
        modifier = modifier
            .size(COMPOSER_STOP_SIZE)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (squareAlpha > 0.001f) {
                Canvas(Modifier.size(14.dp).graphicsLayer { alpha = squareAlpha }) {
                    drawRoundRect(
                        color = glyphColor,
                        size = size,
                        cornerRadius = CornerRadius(3.5.dp.toPx()),
                    )
                }
            }
            if (spinnerAlpha > 0.001f) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp).graphicsLayer { alpha = spinnerAlpha },
                    strokeWidth = 2.dp,
                    color = glyphColor,
                )
            }
        }
    }
}

/** How long the microphone takes to fade out of and back into its slot. */
internal const val COMPOSER_MIC_FADE_MS = 160

/** Side of the mic glyph's box. Geometry is authored in a 24 unit box and scales to this. */
private val MIC_GLYPH_SIZE = 26.dp
private const val MIC_GLYPH_UNITS = 24f

/** Stroke width in glyph units; lighter than 1.7 so the larger glyph stays visually light. */
private const val MIC_GLYPH_STROKE = 1.45f

/**
 * Hand-drawn microphone: a hollow body ringed by a cradle arc concentric to the body's bottom cap,
 * so the gap between them stays even all the way round instead of widening at the corners.
 */
@Composable
private fun ComposerMicGlyph(color: Color) {
    Canvas(Modifier.size(MIC_GLYPH_SIZE)) {
        // Geometry below is authored in the original 24 unit box; d converts a unit to pixels
        // so the whole glyph (including stroke weight) scales with MIC_GLYPH_SIZE.
        val d = size.width / MIC_GLYPH_UNITS
        val stroke = MIC_GLYPH_STROKE * d
        val centerX = 12f * d
        val bodyTop = 4.25f * d
        val bodyWidth = 6.4f * d
        val bodyHeight = 10.6f * d
        drawRoundRect(
            color = color,
            topLeft = Offset(centerX - bodyWidth / 2f, bodyTop),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(bodyWidth / 2f),
            style = Stroke(width = stroke),
        )
        val cradleRadius = 6.3f * d
        val cradleCenterY = bodyTop + bodyHeight - bodyWidth / 2f
        drawArc(
            color = color,
            startAngle = 15f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(centerX - cradleRadius, cradleCenterY - cradleRadius),
            size = Size(cradleRadius * 2f, cradleRadius * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val stemTop = cradleCenterY + cradleRadius
        drawLine(
            color = color,
            start = Offset(centerX, stemTop),
            end = Offset(centerX, stemTop + 1.8f * d),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * The capsule's recording contents. Drawn over the idle controls (which fade out underneath) and
 * sized to the whole capsule, so the bars are clipped by its rounded edge. The cross starts as a
 * plus and rotates 45 degrees into place, matching the attachment button it replaces.
 */
@Composable
internal fun ComposerRecordingOverlay(voice: ComposerVoice) {
    val progress = LocalComposerCapsuleProgress.current
    if (progress <= 0.001f) return
    val levels by voice.controller.levels.collectAsState()
    val elapsed by voice.controller.elapsedMs.collectAsState()
    val transcribing = voice.phase == ComposerVoicePhase.TRANSCRIBING
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = if (transcribing) 0.4f else 0.9f)
    // Stop spins while the transcript is being made, dims while a send from the composer is in
    // flight (the send slot owns that spinner), and stays solid while merely recording.
    val stopLoading = transcribing && !voice.sendPending
    val stopColor = when {
        stopLoading -> MaterialTheme.colorScheme.onSurfaceVariant
        transcribing -> contentColor.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = COMPOSER_CONTROL_HEIGHT / 2 - 16.dp,
                    end = COMPOSER_CONTROL_HEIGHT + 4.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = voice.onCancel,
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer {
                        alpha = progress
                        rotationZ = 45f * (1f - progress)
                    },
            ) {
                Icon(
                    Icons.Rounded.Close,
                    stringResource(R.string.speech_input_cancel),
                    modifier = Modifier.size(18.dp),
                    tint = contentColor,
                )
            }
            Spacer(Modifier.width(8.dp))
            Canvas(
                Modifier
                    .weight(1f)
                    .height(24.dp)
                    .graphicsLayer { alpha = progress },
            ) {
                val barWidth = 3.dp.toPx()
                val step = barWidth + 2.dp.toPx()
                val visible = levels.takeLast((size.width / step).toInt().coerceAtLeast(0))
                val startX = size.width - visible.size * step
                visible.forEachIndexed { index, level ->
                    val height = maxOf(barWidth, level * size.height)
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(startX + index * step, (size.height - height) / 2f),
                        size = Size(barWidth, height),
                        cornerRadius = CornerRadius(barWidth / 2f),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                formatSpeechElapsed(elapsed),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                modifier = Modifier.graphicsLayer { alpha = progress },
            )
            Spacer(Modifier.width(4.dp))
        }
        ComposerStopButton(
            color = stopColor,
            loading = stopLoading,
            enabled = !transcribing,
            onClick = voice.onFinish,
            // Pull the 32 dp touch target inward so the square lands on the capsule's end
            // cap centre rather than hugging the rim.
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = -(COMPOSER_CONTROL_HEIGHT - COMPOSER_STOP_SIZE) / 2),
        )
    }
}

internal fun formatSpeechElapsed(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

/**
 * Gap from the microphone to the send button. Fixed, so stretching the capsule for a capture
 * never shifts the microphone.
 */
internal val COMPOSER_MIC_SEND_GAP = 2.dp

/** Gap from the stretched capsule's right edge to the send button. */
internal val COMPOSER_CAPSULE_SEND_GAP = 14.dp
