package com.newoether.agora.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

/** One-shot fade between the enabled and disabled paints of a sandbox action. */
private const val StateFadeMillis = 200

/**
 * The sandbox call-to-action buttons (Upgrade, and Install while no system is installed) fade
 * between their enabled and disabled paints when their state switches: the fill and label travel
 * to or from `primary` / `onPrimary` instead of snapping to the component's disabled colors.
 *
 * The fade runs once per state switch and never loops, so an idle button is still. The first
 * composition starts settled, so opening the page has no color motion. Color feedback remains
 * under Reduced Motion, as with the other one-shot tints in the app; no second button style,
 * border, or moving gradient appears.
 */
@Composable
internal fun SandboxActionButton(
    onClick: () -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val defaults = ButtonDefaults.buttonColors()
    val fade = tween<Color>(durationMillis = StateFadeMillis, easing = FastOutSlowInEasing)
    val containerColor by animateColorAsState(
        if (enabled) scheme.primary else defaults.disabledContainerColor,
        fade,
        label = "sandboxActionContainer",
    )
    val contentColor by animateColorAsState(
        if (enabled) scheme.onPrimary else defaults.disabledContentColor,
        fade,
        label = "sandboxActionContent",
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            // Both slots read the animated value, so switching `enabled` never swaps palettes
            // mid-fade.
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor,
        ),
    ) {
        content()
    }
}
