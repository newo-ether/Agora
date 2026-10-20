package com.newoether.agora.ui.settings

import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.animateDpAsState
import com.newoether.agora.ui.motion.animateFloatAsState
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Text

/** Shared pill-shaped FAB with the press animation used by settings documentation actions. */
@Composable
fun AnimatedActionFab(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animatePress =
        isPressed &&
            enabled &&
            LocalAgoraMotionPolicy.current.allowSpatialTransitions

    val targetWidth = if (animatePress) 240.dp else 200.dp
    val targetHeight = if (animatePress) 56.dp else 48.dp
    val width by animateDpAsState(
        targetWidth,
        spring(stiffness = 400f, dampingRatio = 0.25f),
        label = "actionFabWidth",
    )
    val height by animateDpAsState(
        targetHeight,
        spring(stiffness = 400f, dampingRatio = 0.25f),
        label = "actionFabHeight",
    )
    val contentScale by animateFloatAsState(
        if (animatePress) 1.1f else 1f,
        spring(stiffness = 400f, dampingRatio = 0.25f),
        label = "actionFabContentScale",
    )
    val spacerWidth by animateDpAsState(
        if (animatePress) 16.dp else 10.dp,
        spring(stiffness = 400f, dampingRatio = 0.25f),
        label = "actionFabSpacerWidth",
    )

    Box(
        modifier = modifier.navigationBarsPadding().height(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        FloatingActionButton(
            onClick = { if (enabled) onClick() },
            shape = RoundedCornerShape(50),
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (enabled) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            elevation = FloatingActionButtonDefaults.elevation(4.dp, 4.dp),
            interactionSource = interactionSource,
            modifier = Modifier
                .width(width)
                .height(height)
                .then(if (enabled) Modifier else Modifier.semantics { disabled() }),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(22.dp).height(22.dp).scale(contentScale),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                } else {
                    Icon(icon, contentDescription = null, modifier = Modifier.scale(contentScale))
                }
                Spacer(Modifier.width(spacerWidth))
                Text(label, maxLines = 1, modifier = Modifier.scale(contentScale))
            }
        }
    }
}
