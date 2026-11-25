package com.newoether.agora.ui.chat.interaction

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.SubagentSummary
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import com.newoether.agora.ui.motion.rememberIdentityClipWidth
import com.newoether.agora.util.contentTextDirection
import kotlin.math.roundToInt

internal fun subagentStatusIcon(status: String): ImageVector = when (status) {
    "failed" -> Icons.Rounded.ErrorOutline
    "stopped" -> Icons.Rounded.PauseCircleOutline
    else -> Icons.Rounded.AccountTree
}

@Composable
internal fun subagentStatusLabel(status: String): String = stringResource(when (status) {
    "running" -> R.string.subagent_running
    "completed" -> R.string.subagent_completed
    "failed" -> R.string.subagent_failed
    "stopped" -> R.string.subagent_stopped
    else -> R.string.subagent_idle
})

/** Navigation only; the shared tools row owns placement, scrolling and outgoing retention. */
@Composable
internal fun ConversationSubagentCapsule(
    agent: SubagentSummary,
    selected: Boolean,
    active: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubagentNavigationCapsule(
        title = agent.title, status = agent.status, selected = selected, active = active,
        identity = agent.id, onClick = { onSelect(agent.id) }, modifier = modifier,
    )
}

/** Both single and aggregate content use the same surface and color/status animation owners. */
@Composable
internal fun SubagentNavigationCapsule(
    title: String,
    status: String,
    selected: Boolean,
    active: Boolean,
    identity: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(250, easing = LinearEasing),
        label = "subagent-container:$identity",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(250, easing = LinearEasing),
        label = "subagent-content:$identity",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(250, easing = LinearEasing),
        label = "subagent-label:$identity",
    )
    BoxWithConstraints {
        val density = LocalDensity.current
        val canvasWidth = minOf(maxWidth, 240.dp)
        val textMaxWidth = (canvasWidth - 58.dp).coerceAtLeast(0.dp)
        val label = stringResource(R.string.message_source_subagent)
        val titleStyle = MaterialTheme.typography.titleSmall.copy(textDirection = contentTextDirection(title))
        val labelStyle = MaterialTheme.typography.labelSmall.copy(textDirection = contentTextDirection(label))
        val textMeasurer = rememberTextMeasurer()
        val targetWidth = with(density) {
            val titleWidth = textMeasurer.measure(
                AnnotatedString(title), titleStyle, maxLines = 1, softWrap = false,
            ).size.width.toDp()
            val labelWidth = textMeasurer.measure(
                AnnotatedString(label), labelStyle, maxLines = 1, softWrap = false,
            ).size.width.toDp()
            minOf(maxOf(titleWidth, labelWidth) + 58.dp, canvasWidth)
        }
        val clipWidth = rememberIdentityClipWidth(
            identity = title, targetWidth = targetWidth,
            allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions,
        )
        val clipWidthPx = with(density) { clipWidth.toPx() }
        val clipShape = GenericShape { size, _ ->
            val visibleWidth = clipWidthPx.coerceIn(0f, size.width)
            addRoundRect(RoundRect(
                left = 0f, top = 0f, right = visibleWidth, bottom = size.height,
                cornerRadius = CornerRadius(size.height / 2f),
            ))
        }
        Surface(
            onClick = { if (active) onClick() }, enabled = active,
            shape = RoundedCornerShape(24.dp), shadowElevation = 0.dp,
            color = containerColor,
            contentColor = contentColor,
            modifier = modifier.graphicsLayer {
                shape = clipShape
                clip = true
                shadowElevation = 4.dp.toPx()
            }.semantics { this.selected = selected },
        ) {
            Row(
                Modifier.layout { measurable, constraints ->
                    // The Surface owns the visible hit area; its content keeps stable constraints.
                    val canvasWidthPx = canvasWidth.roundToPx()
                    val placeable = measurable.measure(constraints.copy(minWidth = canvasWidthPx, maxWidth = canvasWidthPx))
                    layout(clipWidthPx.roundToInt().coerceIn(0, canvasWidthPx), placeable.height) {
                        placeable.placeRelative(0, 0)
                    }
                }.height(48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SubagentStatus(status, "subagent-status:$identity")
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        label,
                        style = labelStyle,
                        color = labelColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Crossfade(
                        targetState = title,
                        animationSpec = tween(250, easing = LinearEasing),
                        label = "subagent-title:$identity",
                    ) { text ->
                        Text(
                            text, style = titleStyle,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(textMaxWidth),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SubagentStatus(status: String, tag: String) {
    Crossfade(
        targetState = status,
        modifier = Modifier.size(18.dp).testTag(tag),
        animationSpec = tween(250, easing = LinearEasing),
        label = tag,
    ) { value ->
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (value == "running") {
                MotionAwareCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(subagentStatusIcon(value), subagentStatusLabel(value), Modifier.size(18.dp))
            }
        }
    }
}
