package com.newoether.agora.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.motion.AnimatedVisibility
import com.newoether.agora.ui.motion.animateDpAsState

@Composable
internal fun BoxScope.ChatBottomScrollButton(
    showButton: Boolean,
    bottomBarHeight: Dp,
    onClick: () -> Unit,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val fabElevation by animateDpAsState(
        targetValue = if (showButton) 4.dp else 0.dp,
        animationSpec = if (motionPolicy.allowSpatialTransitions) {
            tween(400)
        } else {
            snap()
        }
    )
    AnimatedVisibility(
        visible = showButton,
        enter = if (motionPolicy.allowSpatialTransitions) {
            fadeIn(tween(400)) +
                scaleIn(initialScale = 0.6f, animationSpec = tween(400))
        } else {
            fadeIn(tween(400))
        },
        exit = if (motionPolicy.allowSpatialTransitions) {
            fadeOut(tween(400)) +
                scaleOut(targetScale = 0.6f, animationSpec = tween(400))
        } else {
            fadeOut(tween(400))
        },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBarHeight + 8.dp)
    ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            FloatingActionButton(onClick = onClick, containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp), contentColor = MaterialTheme.colorScheme.onSurface, shape = CircleShape, elevation = FloatingActionButtonDefaults.elevation(fabElevation), modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.scroll_to_bottom), modifier = Modifier.size(24.dp))
            }
        }
    }
}
