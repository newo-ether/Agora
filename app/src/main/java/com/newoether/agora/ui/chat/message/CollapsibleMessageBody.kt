package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R

/** User-capsule disclosure keeps the full text mounted and masks only its collapsed drawing. */
@Composable
internal fun CollapsibleMessageBody(
    messageKey: String,
    searchActive: Boolean = false,
    modifier: Modifier = Modifier,
    onExpansionChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(messageKey) { mutableStateOf(false) }
    var overflows by rememberSaveable(messageKey) { mutableStateOf(false) }
    val collapsed = overflows && !expanded && !searchActive
    Layout(
        modifier = modifier,
        content = {
            Layout(
                content = { Column { content() } },
                modifier = Modifier
                    .graphicsLayer {
                        clip = collapsed
                        compositingStrategy = if (collapsed) CompositingStrategy.Offscreen
                        else CompositingStrategy.Auto
                    }
                    .drawWithContent {
                        drawContent()
                        if (collapsed) {
                            // Clear the footer beneath the opaque button; fade only the body above it.
                            val fadeEnd = (size.height - 48.dp.toPx()).coerceAtLeast(0f)
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Black, Color.Transparent),
                                    startY = (fadeEnd - 64.dp.toPx()).coerceAtLeast(0f),
                                    endY = fadeEnd.coerceAtLeast(1f),
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    },
            ) { measurables, constraints ->
                val body = measurables.single().measure(
                    constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
                )
                val previewHeight = 320.dp.roundToPx()
                overflows = body.height > previewHeight
                val visibleHeight = if (expanded || searchActive) body.height
                else minOf(body.height, previewHeight)
                layout(body.width, constraints.constrainHeight(visibleHeight)) {
                    body.placeRelative(0, 0)
                }
            }
            if (overflows && !searchActive) {
                DisableSelection {
                    TextButton(onClick = {
                        onExpansionChange(!expanded)
                        expanded = !expanded
                    }) {
                        Text(stringResource(if (expanded) R.string.collapse else R.string.expand))
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val body = measurables.first().measure(loose)
        val button = measurables.getOrNull(1)?.measure(loose)
        val width = maxOf(constraints.minWidth, body.width, button?.width ?: 0)
        val height = constraints.constrainHeight(
            body.height + if (collapsed) 0 else button?.height ?: 0,
        )
        layout(width, height) {
            body.placeRelative(0, 0)
            // Disclosure stays on the physical left in both layout directions.
            button?.place(0, height - button.height)
        }
    }
}
