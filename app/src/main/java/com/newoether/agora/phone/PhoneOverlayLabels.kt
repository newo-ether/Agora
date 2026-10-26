package com.newoether.agora.phone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
internal fun PhoneDurationCapsule(seconds: Long, modifier: Modifier = Modifier) {
    val text = phoneDuration(seconds)
    val style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val template = phoneDurationTemplate(text, measurer, style)
    PhoneLabelCapsule(text, template, template, style, duration = true, modifier = modifier)
}

@Composable
internal fun PhoneStatusCapsule(text: String, modifier: Modifier = Modifier) {
    PhoneLabelCapsule(text, text, text, MaterialTheme.typography.labelLarge, duration = false, modifier = modifier)
}

/** Same final-layout canvas, clip owner and label transition as ComposerModelSelector. */
@Composable
private fun PhoneLabelCapsule(
    text: String,
    measurementText: String,
    widthIdentity: String,
    style: TextStyle,
    duration: Boolean,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val measured = measurer.measure(AnnotatedString(measurementText), style, maxLines = 1, softWrap = false)
    val targetWidth = with(density) { measured.size.width.toDp() } + 24.dp
    val height = with(density) { measured.size.height.toDp() } + 16.dp
    val clipWidth = rememberIdentityClipWidth(
        identity = widthIdentity,
        targetWidth = targetWidth,
        allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions,
    )
    val shape = remember(duration) {
        if (duration) RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 5.dp, bottomEnd = 5.dp)
        else RoundedCornerShape(5.dp)
    }
    Box(modifier.height(height).width(clipWidth).clip(shape)) {
        Surface(
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).size(targetWidth, height),
            shape = shape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (duration) {
                    Text(text, Modifier.wrapContentWidth(Alignment.Start, unbounded = true), style = style,
                        maxLines = 1, softWrap = false)
                } else {
                    IdentityLabelCrossfade(targetState = text) { label ->
                        Text(label, Modifier.wrapContentWidth(Alignment.Start, unbounded = true), style = style,
                            maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
