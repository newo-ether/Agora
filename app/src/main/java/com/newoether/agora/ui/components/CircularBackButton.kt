package com.newoether.agora.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Surface

/**
 * A back-navigation icon button wrapped in a circular [Surface]. Shared across the app so every
 * top bar gets the same tappable circular affordance instead of a bare [androidx.compose.material3.IconButton].
 *
 * @param onClick invoked when tapped (typically navigate up / pop back stack).
 * @param containerColor the circle's fill; defaults to a subtle neutral tone.
 * @param size the diameter of the circle.
 */
@Composable
fun CircularBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.back),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = 6.dp,
    size: Dp = 40.dp,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = contentDescription,
            )
        }
    }
}
