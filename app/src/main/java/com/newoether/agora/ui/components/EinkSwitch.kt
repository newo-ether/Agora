package com.newoether.agora.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    if (!LocalAgoraMotionPolicy.current.eInkMode) {
        androidx.compose.material3.Switch(checked, onCheckedChange, modifier, thumbContent,
            enabled, colors, interactionSource)
        return
    }
    val action = if (onCheckedChange != null) Modifier.toggleable(
        value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        else Modifier.semantics {
            role = Role.Switch
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            if (!enabled) disabled()
        }
    Canvas(modifier.then(action).size(52.dp, 32.dp)) {
        drawRoundRect(if (checked) Color.Black else Color.White, cornerRadius = CornerRadius(size.height / 2))
        drawRoundRect(Color.Black, cornerRadius = CornerRadius(size.height / 2), style = Stroke(1.dp.toPx()))
        drawCircle(if (checked) Color.White else Color.Black, 10.dp.toPx(),
            center = Offset(if (checked) size.width - 16.dp.toPx() else 16.dp.toPx(), size.height / 2))
        if (!enabled) drawLine(Color.Black, Offset.Zero, Offset(size.width, size.height), 2.dp.toPx())
    }
}
