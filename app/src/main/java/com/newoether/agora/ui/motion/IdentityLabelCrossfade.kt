package com.newoether.agora.ui.motion

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Shared label opacity motion used by the composer model selector and Phone Use capsules. */
@Composable
internal fun <T> IdentityLabelCrossfade(
    targetState: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    Crossfade(
        targetState = targetState,
        modifier = modifier,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "identityLabel",
        content = content,
    )
}
