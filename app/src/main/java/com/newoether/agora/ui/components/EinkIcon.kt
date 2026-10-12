package com.newoether.agora.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy

/** Untinted artwork stays original; semantic icon tints use opaque black or white. */
@Composable
private fun einkIconTint(tint: Color): Color =
    if (tint == Color.Unspecified || !LocalAgoraMotionPolicy.current.eInkMode) tint else einkForeground(tint)

@Composable
fun Icon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current) {
    androidx.compose.material3.Icon(imageVector, contentDescription, modifier, einkIconTint(tint))
}

@Composable
fun Icon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current) {
    androidx.compose.material3.Icon(painter, contentDescription, modifier, einkIconTint(tint))
}

@Composable
fun Icon(bitmap: ImageBitmap, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current) {
    androidx.compose.material3.Icon(bitmap, contentDescription, modifier, einkIconTint(tint))
}
