package com.newoether.agora.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy

/** E-ink swaps whole chat trees without retaining an animation/lookahead placement tree. */
@Composable
internal fun ChatContentTransition(
    targetState: Pair<Boolean, Boolean>,
    transitionSpec: AnimatedContentTransitionScope<Pair<Boolean, Boolean>>.() -> ContentTransform,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (Pair<Boolean, Boolean>) -> Unit,
) {
    if (LocalAgoraMotionPolicy.current.eInkMode) {
        Box(modifier) { key(targetState) { content(targetState) } }
    } else {
        AnimatedContent(targetState, modifier, transitionSpec, label = label) { content(it) }
    }
}
