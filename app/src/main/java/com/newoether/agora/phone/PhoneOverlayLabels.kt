package com.newoether.agora.phone

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.IdentityLabelCrossfade
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.rememberIdentityClipWidth

internal fun phoneDuration(seconds: Long): String {
    val value = seconds.coerceAtLeast(0)
    val minutes = value / 60
    val remainder = (value % 60).toString().padStart(2, '0')
    return if (minutes < 60) "$minutes:$remainder"
        else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$remainder"
}

/** Measure every digit in the resolved font; no assumption about which glyph is widest. */
internal fun phoneDurationTemplate(text: String, measurer: TextMeasurer, style: TextStyle): String {
    val widest = ('0'..'9').maxBy { digit ->
        measurer.measure(AnnotatedString(digit.toString()), style, maxLines = 1, softWrap = false).size.width
    }
    return text.map { if (it.isDigit()) widest else it }.joinToString("")
}

@Composable
internal fun PhoneDurationCapsule(
    seconds: Long,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    val text = phoneDuration(seconds)
    val style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val template = phoneDurationTemplate(text, measurer, style)
    PhoneLabelCapsule(text, template, template, style, duration = true, modifier = modifier,
        containerColor = containerColor)
}

@Composable
internal fun PhoneStatusCapsule(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    thinking: Boolean = false,
    active: Boolean = true,
) {
    var retainedAlpha by remember { mutableFloatStateOf(if (thinking) 0.8f else 1f) }
    val alpha = when {
        !active -> retainedAlpha
        !thinking -> 1f
        !LocalAgoraMotionPolicy.current.allowContinuousMotion -> 0.8f
        else -> {
            val transition = rememberInfiniteTransition(label = "phoneThinking")
            val value by transition.animateFloat(
                initialValue = 0.8f, targetValue = 0.2f,
                animationSpec = infiniteRepeatable(
                    tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse,
                ), label = "phoneThinkingAlpha",
            )
            value
        }
    }
    if (active) SideEffect { retainedAlpha = alpha }
    PhoneLabelCapsule(text, text, text, MaterialTheme.typography.labelLarge, duration = false,
        modifier = modifier, containerColor = containerColor, textAlpha = alpha)
}

/** Final-width label canvas inside a painted clip whose slot also positions the next capsule. */
@Composable
private fun PhoneLabelCapsule(
    text: String,
    measurementText: String,
    widthIdentity: String,
    style: TextStyle,
    duration: Boolean,
    modifier: Modifier,
    containerColor: Color,
    textAlpha: Float = 1f,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val measured = measurer.measure(AnnotatedString(measurementText), style, maxLines = 1, softWrap = false)
    val targetWidth = with(density) { measured.size.width.toDp() } + 24.dp
    val height = (with(density) { measured.size.height.toDp() } + 16.dp).coerceAtLeast(48.dp)
    val clipWidth = rememberIdentityClipWidth(
        identity = widthIdentity,
        targetWidth = targetWidth,
        allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions,
    )
    val shape = remember(duration) {
        if (duration) RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 5.dp, bottomEnd = 5.dp)
        else RoundedCornerShape(5.dp)
    }
    Surface(
        modifier = modifier.height(height).width(clipWidth),
        shape = shape,
        color = containerColor.copy(alpha = 1f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 1f),
    ) {
        // Paint the whole visible width even while shrinking past the incoming label's final width.
        // Only the label canvas uses targetWidth; its text never receives animated constraints.
        Box(
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).size(targetWidth, height)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (duration) {
                Text(text, Modifier.wrapContentWidth(Alignment.Start, unbounded = true), style = style,
                    maxLines = 1, softWrap = false)
            } else {
                IdentityLabelCrossfade(targetState = text,
                    modifier = Modifier.graphicsLayer { alpha = textAlpha }) { label ->
                    Text(label, Modifier.wrapContentWidth(Alignment.Start, unbounded = true), style = style,
                        maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
