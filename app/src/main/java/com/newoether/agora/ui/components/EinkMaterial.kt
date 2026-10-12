package com.newoether.agora.ui.components

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.motion.EinkPressIndication
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy

/** Multiple Material consumers share these e-ink drawing overrides; action semantics stay Material-owned. */
@Composable
internal fun Modifier.einkFeedback(source: MutableInteractionSource, enabled: Boolean): Modifier {
    if (!LocalAgoraMotionPolicy.current.eInkMode) return this
    val unavailable = stringResource(R.string.e_ink_unavailable)
    return indication(source, EinkPressIndication).semantics {
        if (!enabled) stateDescription = unavailable
    }.drawWithContent {
        drawContent()
        if (!enabled) {
            // A small no-entry mark replaces grey disabled opacity without obscuring the label.
            val radius = 4.dp.toPx()
            val center = Offset(size.width - 8.dp.toPx(), 8.dp.toPx())
            drawCircle(Color.White, radius, center)
            drawCircle(Color.Black, radius, center, style = Stroke(1.dp.toPx()))
            val diagonal = radius * 0.65f
            drawLine(Color.Black, center + Offset(-diagonal, diagonal),
                center + Offset(diagonal, -diagonal), 1.dp.toPx())
        }
    }
}

@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    androidx.compose.material3.Surface(modifier, shape, color, contentColor,
        if (eInk) 0.dp else tonalElevation, if (eInk) 0.dp else shadowElevation,
        if (eInk && shape != RectangleShape) border ?: BorderStroke(1.dp, Color.Black) else border,
        content)
}

@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    val source = interactionSource ?: remember { MutableInteractionSource() }
    androidx.compose.material3.Surface(onClick, modifier.einkFeedback(source, enabled), enabled,
        shape, color, contentColor, if (eInk) 0.dp else tonalElevation,
        if (eInk) 0.dp else shadowElevation,
        if (eInk) border ?: BorderStroke(1.dp, Color.Black) else border, source, content)
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    val source = interactionSource ?: remember { MutableInteractionSource() }
    androidx.compose.material3.Button(onClick, modifier.einkFeedback(source, enabled), enabled,
        shape, if (eInk) colors.copy(disabledContainerColor = Color.White,
            disabledContentColor = Color.Black) else colors,
        if (eInk) null else elevation,
        if (eInk) border ?: BorderStroke(1.dp, Color.Black) else border,
        contentPadding, source, content)
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    val source = interactionSource ?: remember { MutableInteractionSource() }
    androidx.compose.material3.TextButton(onClick, modifier.einkFeedback(source, enabled), enabled,
        shape, if (eInk) colors.copy(disabledContainerColor = Color.White,
            disabledContentColor = Color.Black) else colors,
        if (eInk) null else elevation,
        if (eInk) border ?: BorderStroke(1.dp, Color.Black) else border,
        contentPadding, source, content)
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    val source = interactionSource ?: remember { MutableInteractionSource() }
    androidx.compose.material3.OutlinedButton(onClick, modifier.einkFeedback(source, enabled), enabled,
        shape, if (eInk) colors.copy(disabledContainerColor = Color.White,
            disabledContentColor = Color.Black) else colors,
        if (eInk) null else elevation,
        if (eInk) border ?: BorderStroke(1.dp, Color.Black) else border,
        contentPadding, source, content)
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    val source = interactionSource ?: remember { MutableInteractionSource() }
    androidx.compose.material3.IconButton(onClick = onClick, modifier = modifier.einkFeedback(source, enabled),
        enabled = enabled, colors = if (eInk) colors.copy(disabledContentColor = Color.Black) else colors,
        interactionSource = source, content = content)
}

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(),
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismissRequest,
        confirmButton = {
            DialogWindowEinkPresentation(eInk)
            confirmButton()
        }, modifier = modifier.then(if (eInk) Modifier.border(1.dp, Color.Black, shape) else Modifier),
        dismissButton = dismissButton, icon = icon, title = title, text = text,
        shape = shape, containerColor = containerColor, iconContentColor = iconContentColor,
        titleContentColor = titleContentColor, textContentColor = textContentColor,
        tonalElevation = if (eInk) 0.dp else tonalElevation, properties = properties)
}

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    val source = interactionSource ?: remember { MutableInteractionSource() }
    androidx.compose.material3.FilledTonalButton(onClick, modifier.einkFeedback(source, enabled), enabled,
        shape, if (eInk) colors.copy(disabledContainerColor = Color.White,
            disabledContentColor = Color.Black) else colors,
        if (eInk) null else elevation,
        if (eInk) border ?: BorderStroke(1.dp, Color.Black) else border,
        contentPadding, source, content)
}

@Composable
fun ElevatedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.elevatedButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.elevatedButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    val source = interactionSource ?: remember { MutableInteractionSource() }
    androidx.compose.material3.ElevatedButton(onClick, modifier.einkFeedback(source, enabled), enabled,
        shape, if (eInk) colors.copy(disabledContainerColor = Color.White,
            disabledContentColor = Color.Black) else colors,
        if (eInk) null else elevation,
        if (eInk) border ?: BorderStroke(1.dp, Color.Black) else border,
        contentPadding, source, content)
}
