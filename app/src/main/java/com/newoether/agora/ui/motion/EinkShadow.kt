package com.newoether.agora.ui.motion

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow as materialShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Normal geometry/shadow arguments pass through; e-ink surfaces use their existing outline. */
fun Modifier.shadow(
    elevation: Dp,
    shape: Shape = RectangleShape,
    clip: Boolean = elevation > 0.dp,
    ambientColor: Color = Color.Black,
    spotColor: Color = Color.Black,
): Modifier = composed {
    if (LocalAgoraMotionPolicy.current.eInkMode) Modifier
    else Modifier.materialShadow(elevation, shape, clip, ambientColor, spotColor)
}
