package com.newoether.agora.ui.chat.interaction

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.newoether.agora.R
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import kotlin.math.roundToInt
import com.newoether.agora.ui.motion.animateFloatAsState
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.motion.shadow

private val CardCorner = 24.dp
private val CapsuleHeight = 48.dp
private const val MorphDurationMs = 320

/** The icon and title that name one kind of request, both on its card and on its capsule. */
internal enum class InteractionKind(val icon: ImageVector, val titleRes: Int) {
    Question(Icons.AutoMirrored.Outlined.HelpOutline, R.string.interaction_ask_user),
    Approval(Icons.Default.Terminal, R.string.interaction_approval),
}

internal val UserInteraction.kind: InteractionKind
    get() = when (this) {
        is UserInteraction.Question -> InteractionKind.Question
        is UserInteraction.ShellCommand -> InteractionKind.Approval
    }

/**
 * The card for one request, which can fold into a left-aligned capsule above the composer.
 *
 * Folding only changes the container: the card's content keeps the size it was laid out at and is
 * clipped by the shrinking outline, so nothing inside reflows. The content fades out during the
 * first half and the capsule's icon and title fade in during the second, in the spot where the
 * card's own header sits, so the header appears to stay while the card closes around it.
 */
@Composable
internal fun MorphingInteractionCard(
    kind: InteractionKind,
    minimized: Boolean,
    onMinimizedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val motion = LocalAgoraMotionPolicy.current
    val spec = if (motion.allowContinuousMotion) {
        tween<Float>(MorphDurationMs, easing = FastOutSlowInEasing)
    } else {
        snap()
    }
    val progress by animateFloatAsState(if (minimized) 1f else 0f, spec, label = "capsule")
    val density = LocalDensity.current
    val capsuleHeightPx = with(density) { CapsuleHeight.roundToPx() }
    var capsuleWidthPx by remember { mutableIntStateOf(0) }
    val shape = RoundedCornerShape(lerp(CardCorner, CapsuleHeight / 2, progress))
    val contentAlpha = (1f - progress * 2f).coerceIn(0f, 1f)
    val capsuleAlpha = (progress * 2f - 1f).coerceIn(0f, 1f)
    val expandLabel = stringResource(R.string.interaction_expand)

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
        Box(
            modifier = Modifier
                .shadow(4.dp, shape, clip = false)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(
                    if (minimized) {
                        Modifier
                            .clickable { onMinimizedChange(false) }
                            .semantics {
                                role = Role.Button
                                contentDescription = expandLabel
                            }
                    } else {
                        Modifier
                    },
                )
                .capsuleMorph(progress, capsuleWidthPx, capsuleHeightPx),
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer { alpha = contentAlpha }
                    .then(if (minimized) Modifier.clearAndSetSemantics {} else Modifier),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    content()
                }
                IconButton(
                    onClick = { onMinimizedChange(true) },
                    enabled = !minimized,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp),
                ) {
                    Icon(
                        CollapseContentIcon,
                        contentDescription = stringResource(R.string.interaction_minimize),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            CapsuleLabel(
                kind = kind,
                modifier = Modifier
                    .graphicsLayer { alpha = capsuleAlpha }
                    .clearAndSetSemantics {}
                    .onSizeChanged { capsuleWidthPx = it.width },
            )
        }
    }
}

@Composable
private fun CapsuleLabel(kind: InteractionKind, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(CapsuleHeight)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            kind.icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(kind.titleRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            ExpandContentIcon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Lays the content out once at the full width it is offered and reports a size that moves from
 * that full size ([progress] 0) to the capsule size ([progress] 1). Content is placed at the top
 * start and never re-measured, so the outline clips it instead of making it reflow.
 */
private fun Modifier.capsuleMorph(progress: Float, capsuleWidth: Int, capsuleHeight: Int) =
    layout { measurable, constraints ->
        val fullWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val placeable = measurable.measure(
            Constraints(minWidth = fullWidth, maxWidth = fullWidth, maxHeight = constraints.maxHeight),
        )
        val targetWidth = capsuleWidth.coerceIn(capsuleHeight, placeable.width.coerceAtLeast(capsuleHeight))
        val width = (placeable.width + (targetWidth - placeable.width) * progress).roundToInt()
        val height = (placeable.height + (capsuleHeight - placeable.height) * progress).roundToInt()
        layout(width, height) { placeable.placeRelative(0, 0) }
    }
